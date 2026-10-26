package dev.juno.main.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The git operations Juno performs around an agent run, using the {@code git} command line (so the user's
 * own git configuration and credentials apply):
 * <ol>
 *   <li>before: create the ticket's branch from the base branch in a dedicated worktree</li>
 *   <li>after: commit anything left uncommitted, check there is work, push the branch</li>
 * </ol>
 * Each ticket gets its own worktree, so several agents can work on the same repository at once without
 * touching each other or the user's own checkout.
 */
public final class GitWorkspace {

    /** Result of preparing a worktree. */
    public record Prepared(Path worktree, String baseRef, boolean reused) {
    }

    private static final Duration LOCAL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(3);

    private final String git;
    private final Consumer<String> log;

    /**
     * @param git executable ({@code git} on PATH by default)
     * @param log receives a line per command and its output, for the ticket's activity log
     */
    public GitWorkspace(String git, Consumer<String> log) {
        this.git = git == null || git.isBlank() ? "git" : git;
        this.log = log;
    }

    /** A branch name made from the ticket's title, such as {@code juno/add-login-page}. */
    public static String suggestBranchName(String title) {
        String slug = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 55) {
            slug = slug.substring(0, 55).replaceAll("-+$", "");
        }
        return "juno/" + (slug.isEmpty() ? "ticket" : slug);
    }

    /**
     * Ensures a worktree for the ticket exists on {@code branch}. A new branch is created from
     * {@code origin/<base>} (after fetching it) or, failing that, the local {@code <base>}. An existing branch is
     * checked out as-is. An existing worktree already on the branch is reused.
     *
     * @param repo         the project's repository (its main checkout)
     * @param worktree     where the ticket's worktree should live
     * @param baseBranch   branch to start from
     * @param branch       branch to work on
     */
    public Prepared prepare(Path repo, Path worktree, String baseBranch, String branch) throws GitException {
        if (!Files.isDirectory(repo)) {
            throw new GitException("Project directory does not exist: " + repo);
        }
        if (!run(repo, LOCAL_TIMEOUT, false, "rev-parse", "--is-inside-work-tree").ok()) {
            throw new GitException(repo + " is not a git repository");
        }
        if (!run(repo, LOCAL_TIMEOUT, false, "check-ref-format", "--branch", branch).ok()) {
            throw new GitException("'" + branch + "' is not a valid branch name");
        }
        if (!run(repo, LOCAL_TIMEOUT, false, "check-ref-format", "--branch", baseBranch).ok()) {
            throw new GitException("'" + baseBranch + "' is not a valid base branch name");
        }

        if (Files.isDirectory(worktree)) {
            Result head = run(worktree, LOCAL_TIMEOUT, false, "rev-parse", "--abbrev-ref", "HEAD");
            if (head.ok() && head.output().strip().equals(branch)) {
                log.accept("[git] reusing worktree " + worktree + " on " + branch);
                return new Prepared(worktree, null, true);
            }
            throw new GitException("Worktree folder " + worktree + " already exists but is not on branch '"
                    + branch + "'. Remove it (and run 'git worktree prune' in the project) and try again.");
        }

        // Stale registrations (folders deleted by hand) would block 'worktree add'.
        run(repo, LOCAL_TIMEOUT, false, "worktree", "prune");

        Result fetch = run(repo, NETWORK_TIMEOUT, false, "fetch", "origin", baseBranch);
        if (!fetch.ok()) {
            log.accept("[git] could not fetch origin/" + baseBranch + "; using local refs");
        }
        String baseRef;
        if (verify(repo, "refs/remotes/origin/" + baseBranch)) {
            baseRef = "origin/" + baseBranch;
        } else if (verify(repo, "refs/heads/" + baseBranch)) {
            baseRef = baseBranch;
        } else {
            throw new GitException("Base branch '" + baseBranch + "' was not found locally or on origin");
        }

        try {
            Files.createDirectories(worktree.getParent());
        } catch (IOException e) {
            throw new GitException("Could not create " + worktree.getParent() + ": " + e.getMessage());
        }
        Result add;
        boolean existing = verify(repo, "refs/heads/" + branch);
        if (existing) {
            String elsewhere = checkedOutAt(repo, branch);
            if (elsewhere != null) {
                throw new GitException("Branch '" + branch + "' is checked out in " + elsewhere + ". Git allows a "
                        + "branch in one place at a time: switch that folder to another branch (for example "
                        + "'git checkout " + baseBranch + "'), then try again.");
            }
            log.accept("[git] branch " + branch + " already exists; checking it out");
            add = run(repo, LOCAL_TIMEOUT, true, "worktree", "add", worktree.toString(), branch);
        } else {
            add = run(repo, LOCAL_TIMEOUT, true, "worktree", "add", "--no-track", "-b", branch,
                    worktree.toString(), baseRef);
        }
        if (!add.ok()) {
            throw new GitException("Could not create a worktree for '" + branch + "': " + add.output().strip());
        }
        // An existing branch keeps the base commit recorded when it was first created.
        return new Prepared(worktree, existing ? null : baseRef, false);
    }

    /** The folder where {@code branch} is checked out (main checkout or another worktree), or null. */
    String checkedOutAt(Path repo, String branch) {
        Result list = run(repo, LOCAL_TIMEOUT, false, "worktree", "list", "--porcelain");
        if (!list.ok()) {
            return null;
        }
        String path = null;
        for (String line : list.output().lines().toList()) {
            if (line.startsWith("worktree ")) {
                path = line.substring("worktree ".length()).strip();
            } else if (line.strip().equals("branch refs/heads/" + branch)) {
                return path;
            }
        }
        return null;
    }

    /**
     * The main repository a linked worktree belongs to, or null if the folder isn't a linked worktree (a main
     * checkout, or not a git folder at all).
     */
    public Path mainRepository(Path worktree) {
        Result common = run(worktree, LOCAL_TIMEOUT, false, "rev-parse", "--path-format=absolute", "--git-common-dir");
        Result own = run(worktree, LOCAL_TIMEOUT, false, "rev-parse", "--path-format=absolute", "--git-dir");
        if (!common.ok() || !own.ok()) {
            return null;
        }
        Path commonDir = Path.of(common.output().strip());
        if (commonDir.equals(Path.of(own.output().strip())) || commonDir.getParent() == null) {
            return null;
        }
        return commonDir.getParent();
    }

    /** The branch checked out in a folder, or null (detached or not a repository). */
    public String currentBranch(Path dir) {
        Result head = run(dir, LOCAL_TIMEOUT, false, "symbolic-ref", "--quiet", "--short", "HEAD");
        return head.ok() ? head.output().strip() : null;
    }

    /** Outcome of {@link #release}. */
    public enum Release { RELEASED, HAS_CHANGES, LOCKED }

    /**
     * Removes the ticket's worktree once its work is committed and pushed, so its branch is free to check out
     * anywhere else (the branch itself is kept; the next run recreates the worktree). Keeps a worktree with
     * uncommitted changes. On Windows, files can be briefly locked by other programs (OneDrive syncing the repo,
     * an editor or IDE watching {@code .git}, antivirus), so removal is retried a few times before giving up.
     */
    public Release release(Path repo, Path worktree, String branch) {
        return release(repo, worktree, branch, true);
    }

    /**
     * Removes the worktree even if it has uncommitted changes (used when its ticket is deleted). The branch and
     * its commits are kept.
     */
    public Release discard(Path repo, Path worktree, String branch) {
        return release(repo, worktree, branch, false);
    }

    private Release release(Path repo, Path worktree, String branch, boolean keepChanges) {
        if (Files.isDirectory(worktree)) {
            Result status = run(worktree, LOCAL_TIMEOUT, false, "status", "--porcelain");
            if (keepChanges && (!status.ok() || !status.output().isBlank())) {
                log.accept("[git] kept the worktree " + worktree + ": it has uncommitted changes");
                return Release.HAS_CHANGES;
            }
            for (int attempt = 1; attempt <= 5 && Files.isDirectory(worktree); attempt++) {
                // --force: files git ignores (e.g. Juno's skills folder), or the changes of a deleted ticket.
                if (run(repo, LOCAL_TIMEOUT, false, "worktree", "remove", "--force", worktree.toString()).ok()) {
                    break;
                }
                pause(attempt * 1000L);
            }
        }
        // The worktree folder may be gone while git's record of it (.git/worktrees/<name>) is still locked.
        for (int attempt = 1; attempt <= 5; attempt++) {
            run(repo, LOCAL_TIMEOUT, false, "worktree", "prune");
            if (!Files.isDirectory(worktree) && checkedOutAt(repo, branch) == null) {
                return Release.RELEASED;
            }
            pause(attempt * 1000L);
        }
        return Release.LOCKED;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Commits any uncommitted changes in the worktree. Returns true if a commit was made. */
    public boolean commitLeftovers(Path worktree, String message) throws GitException {
        Result status = run(worktree, LOCAL_TIMEOUT, false, "status", "--porcelain");
        if (!status.ok()) {
            throw new GitException("git status failed: " + status.output().strip());
        }
        if (status.output().isBlank()) {
            return false;
        }
        require(run(worktree, LOCAL_TIMEOUT, true, "add", "-A"), "git add");
        require(run(worktree, LOCAL_TIMEOUT, true, "commit", "-m", message), "git commit");
        return true;
    }

    /** Number of commits on HEAD that are not on {@code baseRef}. */
    public int commitsAhead(Path worktree, String baseRef) throws GitException {
        Result count = run(worktree, LOCAL_TIMEOUT, false, "rev-list", "--count", baseRef + "..HEAD");
        require(count, "git rev-list");
        try {
            return Integer.parseInt(count.output().strip());
        } catch (NumberFormatException e) {
            throw new GitException("Unexpected output from git rev-list: " + count.output().strip());
        }
    }

    /** Outcome of merging the updated base branch into the ticket's branch. */
    public record MergeResult(boolean conflicts, boolean upToDate, String baseRef, String forkPoint,
                              List<String> conflictedFiles) {
    }

    /**
     * Merges the latest {@code origin/<base>} into the worktree's branch without committing. With conflicts, the
     * conflicted files are left with git's conflict markers for an agent to resolve, and the merge stays in
     * progress so committing it records both parents. Without conflicts the merge is abandoned: nothing changes.
     */
    public MergeResult mergeBase(Path worktree, String baseBranch) throws GitException {
        abortMerge(worktree);
        Result fetch = run(worktree, NETWORK_TIMEOUT, false, "fetch", "origin", baseBranch);
        String baseRef = "origin/" + baseBranch;
        if (!fetch.ok() || !verify(worktree, "refs/remotes/" + baseRef)) {
            throw new GitException("Could not fetch " + baseRef + ": " + fetch.output().strip());
        }
        Result forkPoint = run(worktree, LOCAL_TIMEOUT, false, "merge-base", "HEAD", baseRef);
        require(forkPoint, "git merge-base");
        if (run(worktree, LOCAL_TIMEOUT, false, "merge-base", "--is-ancestor", baseRef, "HEAD").ok()) {
            return new MergeResult(false, true, baseRef, forkPoint.output().strip(), List.of());
        }
        Result merge = run(worktree, LOCAL_TIMEOUT, true, "merge", "--no-ff",
                "--no-commit", baseRef);
        Result unmerged = run(worktree, LOCAL_TIMEOUT, false, "diff", "--name-only", "--diff-filter=U");
        List<String> files = unmerged.ok() ? unmerged.output().lines().map(String::strip)
                .filter(l -> !l.isEmpty()).toList() : List.of();
        if (merge.ok() || files.isEmpty()) {
            abortMerge(worktree);
            if (!merge.ok()) {
                throw new GitException("Merging " + baseRef + " failed: " + merge.output().strip());
            }
            return new MergeResult(false, false, baseRef, forkPoint.output().strip(), List.of());
        }
        return new MergeResult(true, false, baseRef, forkPoint.output().strip(), files);
    }

    /** Whether a merge is in progress in the worktree. */
    public boolean mergeInProgress(Path worktree) {
        return verify(worktree, "MERGE_HEAD");
    }

    /** Abandons an unfinished merge, if any, restoring the branch as it was. */
    public void abortMerge(Path worktree) {
        if (mergeInProgress(worktree)) {
            run(worktree, LOCAL_TIMEOUT, true, "merge", "--abort");
        }
    }

    /** Files (of those given) that still contain conflict markers. */
    public List<String> filesWithConflictMarkers(Path worktree, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            Path file = worktree.resolve(f);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (var lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
                if (lines.anyMatch(l -> l.startsWith("<<<<<<< ") || l.startsWith(">>>>>>> ") || l.equals("<<<<<<<")
                        || l.equals(">>>>>>>"))) {
                    out.add(f);
                }
            } catch (IOException | UncheckedIOException e) {
                // unreadable: let git decide
            }
        }
        return out;
    }

    /**
     * Commits on {@code baseRef} since {@code forkPoint} that touched the files, each as "hash subject" plus its
     * body (e.g. "Merge pull request #12 from acme/juno/x").
     */
    public List<String> commitsTouching(Path worktree, String forkPoint, String baseRef, List<String> files) {
        // --first-parent: the base branch's own history, so a merged pull request shows up as its merge commit
        // (whose message names the PR and branch), or as its squash commit.
        List<String> args = new ArrayList<>(List.of("log", "--first-parent", "--format=%h %s%n%b%x1e",
                forkPoint + ".." + baseRef, "--"));
        args.addAll(files);
        Result log = run(worktree, LOCAL_TIMEOUT, false, args.toArray(String[]::new));
        if (!log.ok()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String entry : log.output().split("\u001e")) {
            String e = entry.strip();
            if (!e.isEmpty()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Files changed in the worktree compared with HEAD (staged, unstaged or still unmerged). */
    public List<String> changedFiles(Path worktree) {
        Result diff = run(worktree, LOCAL_TIMEOUT, false, "diff", "--name-only", "HEAD");
        Result unmerged = run(worktree, LOCAL_TIMEOUT, false, "diff", "--name-only", "--diff-filter=U");
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Result r : List.of(diff, unmerged)) {
            if (r.ok()) {
                r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(files::add);
            }
        }
        return List.copyOf(files);
    }

    /** Files the merge commit at HEAD changed relative to its first parent (empty if HEAD isn't a merge). */
    public List<String> mergeCommitFiles(Path worktree) {
        if (!verify(worktree, "HEAD^2")) {
            return List.of();
        }
        Result diff = run(worktree, LOCAL_TIMEOUT, false, "diff", "--name-only", "HEAD^1", "HEAD");
        return diff.ok() ? diff.output().lines().map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of();
    }

    /** Whether {@code ref} is contained in the worktree's HEAD. */
    public boolean contains(Path worktree, String ref) {
        return run(worktree, LOCAL_TIMEOUT, false, "merge-base", "--is-ancestor", ref, "HEAD").ok();
    }

    /**
     * Brings the worktree up to date with {@code origin/<branch>} (e.g. commits a reviewer pushed), fast-forward
     * only. Does nothing if the branch is not on origin yet or the local branch is already ahead.
     */
    public void syncWithRemote(Path worktree, String branch) throws GitException {
        Result fetch = run(worktree, NETWORK_TIMEOUT, false, "fetch", "origin", branch);
        if (!fetch.ok() || !verify(worktree, "refs/remotes/origin/" + branch)) {
            log.accept("[git] origin/" + branch + " not available; using the local branch as-is");
            return;
        }
        String remote = "origin/" + branch;
        if (run(worktree, LOCAL_TIMEOUT, false, "merge-base", "--is-ancestor", remote, "HEAD").ok()) {
            return; // remote is already contained in HEAD
        }
        if (run(worktree, LOCAL_TIMEOUT, false, "merge-base", "--is-ancestor", "HEAD", remote).ok()) {
            require(run(worktree, LOCAL_TIMEOUT, true, "merge", "--ff-only", remote), "git merge --ff-only");
            return;
        }
        throw new GitException("Branch '" + branch + "' has diverged from " + remote
                + "; reconcile it in " + worktree + " first");
    }

    /**
     * Adds a pattern to the repository's {@code info/exclude} (shared by its worktrees) so files Juno places in
     * the worktree, such as skill copies, are never committed. Never touches the tracked {@code .gitignore}.
     */
    public void exclude(Path worktree, String pattern) throws GitException {
        Result where = run(worktree, LOCAL_TIMEOUT, false, "rev-parse", "--git-path", "info/exclude");
        require(where, "git rev-parse --git-path");
        Path file = Path.of(where.output().strip());
        if (!file.isAbsolute()) {
            file = worktree.resolve(file);
        }
        try {
            String current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (current.lines().noneMatch(l -> l.strip().equals(pattern))) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, (current.isEmpty() || current.endsWith("\n") ? current : current + "\n")
                        + "# added by Juno\n" + pattern + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GitException("Could not update " + file + ": " + e.getMessage());
        }
    }

    /** A short description of the branch's state for an agent resuming work: commits and uncommitted files. */
    public String describe(Path worktree, String baseRef) {
        StringBuilder sb = new StringBuilder();
        Result log = run(worktree, LOCAL_TIMEOUT, false, "log", "--oneline", "-n", "30", baseRef + "..HEAD");
        sb.append("Commits on this branch so far:\n")
                .append(log.ok() && !log.output().isBlank() ? indent(log.output()) : "  (none)\n");
        Result status = run(worktree, LOCAL_TIMEOUT, false, "status", "--short");
        sb.append("Uncommitted changes in the worktree:\n")
                .append(status.ok() && !status.output().isBlank() ? indent(status.output()) : "  (none)\n");
        return sb.toString();
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        text.lines().limit(60).forEach(l -> sb.append("  ").append(l).append('\n'));
        return sb.toString();
    }

    /** Pushes the branch to origin and sets its upstream. */
    public void push(Path worktree, String branch) throws GitException {
        require(run(worktree, NETWORK_TIMEOUT, true, "push", "-u", "origin", branch), "git push");
    }

    // ---- process plumbing -------------------------------------------------------------------

    private record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private boolean verify(Path repo, String ref) {
        return run(repo, LOCAL_TIMEOUT, false, "rev-parse", "--verify", "--quiet", ref + "^{commit}").ok();
    }

    private static void require(Result result, String what) throws GitException {
        if (!result.ok()) {
            throw new GitException(what + " failed: " + result.output().strip());
        }
    }

    private Result run(Path dir, Duration timeout, boolean logOutput, String... args) {
        List<String> command = new ArrayList<>();
        command.add(git);
        command.addAll(List.of(args));
        log.accept("[git] " + String.join(" ", args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        // Never block on a credential prompt; fail fast and report instead.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GCM_INTERACTIVE", "never");
        try {
            Process process = pb.start();
            process.getOutputStream().close();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = Thread.ofPlatform().daemon().start(() -> copy(process.getInputStream(), buffer));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "timed out after " + timeout.toSeconds() + "s");
            }
            reader.join(2000);
            String output = buffer.toString(StandardCharsets.UTF_8);
            if (logOutput || process.exitValue() != 0) {
                output.lines().filter(l -> !l.isBlank()).forEach(l -> log.accept("[git]   " + l));
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            return new Result(-1, "could not run git (" + e.getMessage() + "). Is git installed and on PATH?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // process ended
        }
    }
}
