package dev.juno.main.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

    /** A branch name suggestion such as {@code juno/juno-12-add-login-page}. */
    public static String suggestBranchName(String ticketId, String title) {
        String slug = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        String base = "juno/" + ticketId.toLowerCase(Locale.ROOT);
        if (slug.isEmpty()) {
            return base;
        }
        String name = base + "-" + slug;
        if (name.length() > 60) {
            name = name.substring(0, 60).replaceAll("-+$", "");
        }
        return name;
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
        if (verify(repo, "refs/heads/" + branch)) {
            log.accept("[git] branch " + branch + " already exists; checking it out");
            add = run(repo, LOCAL_TIMEOUT, true, "worktree", "add", worktree.toString(), branch);
        } else {
            add = run(repo, LOCAL_TIMEOUT, true, "worktree", "add", "--no-track", "-b", branch,
                    worktree.toString(), baseRef);
        }
        if (!add.ok()) {
            throw new GitException("Could not create a worktree for '" + branch + "': " + add.output().strip());
        }
        return new Prepared(worktree, baseRef, false);
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
