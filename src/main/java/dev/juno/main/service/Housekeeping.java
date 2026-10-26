package dev.juno.main.service;

import dev.juno.main.git.GitWorkspace;
import dev.juno.main.model.ActiveRun;
import dev.juno.main.model.Ticket;
import dev.juno.main.store.JunoStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Keeps Juno's folders tidy: frees branches after delivery, removes everything a deleted ticket left behind
 * (worktree, logs, run records, terminal scripts and Claude Code's session history of its agents; never the
 * branch), keeps only recent run records, and clears files of tickets that no longer exist.
 */
final class Housekeeping {

    /** Run records (journal + prompt) kept per ticket; older ones are deleted. */
    static final int RUNS_KEPT = 10;
    private static final int RELEASE_RETRIES = 20;
    private static final long RELEASE_RETRY_MS = 30_000;

    private final BoardState state;

    Housekeeping(BoardState state) {
        this.state = state;
    }

    /**
     * After a run is delivered (pushed, PR opened, review or merge pushed), removes the ticket's worktree so its
     * branch can be checked out in your own folder. The next run recreates it from the branch. A worktree with
     * uncommitted changes is kept. If Windows reports the files as in use, this keeps retrying in the background.
     */
    void releaseBranch(String ticketId, Path repo, Path worktree, String branch) {
        GitWorkspace.Release result = state.git(ticketId).release(repo, worktree, branch);
        if (result == GitWorkspace.Release.RELEASED) {
            state.log(ticketId, "[juno] released branch " + branch + ": you can check it out in your own folder now");
        } else if (result == GitWorkspace.Release.LOCKED) {
            state.log(ticketId, "[juno] couldn't free branch " + branch + " yet: Windows reports files under "
                    + repo.resolve(".git").resolve("worktrees") + " as in use. Common causes: OneDrive syncing the "
                    + "project folder, or an editor/IDE with the project open. Juno keeps retrying for 10 minutes; "
                    + "you can also run 'git worktree prune' in the project once they're closed.");
            retryInBackground(ticketId, () -> {
                if (state.busy(ticketId)) {
                    return true; // another run started: its worktree is in use again
                }
                boolean done = new GitWorkspace("git", line -> { }).release(repo, worktree, branch)
                        == GitWorkspace.Release.RELEASED;
                if (done) {
                    state.log(ticketId, "[juno] released branch " + branch + ": you can check it out in your own "
                            + "folder now");
                }
                return done;
            });
        }
    }

    /**
     * Removes what a deleted ticket left behind: its worktree (uncommitted changes included; the branch and its
     * commits stay), its logs, run records, terminal scripts and Claude Code's session history of its agents.
     * Runs in the background.
     */
    void deleteTicketFiles(Ticket ticket, Path repo) {
        String id = ticket.getId();
        Path defaultWorktree = worktreesDir().resolve(BoardState.safeName(id));
        Thread.ofVirtual().name("cleanup-" + id).start(() -> {
            deleteClaudeSessions(defaultWorktree);
            if (ticket.getWorktreePath() != null) {
                deleteClaudeSessions(Path.of(ticket.getWorktreePath()));
            }
            if (ticket.getWorktreePath() != null && repo != null) {
                Path worktree = Path.of(ticket.getWorktreePath());
                GitWorkspace git = new GitWorkspace("git", state::addTrackerLog);
                if (git.discard(repo, worktree, ticket.getBranchName()) == GitWorkspace.Release.LOCKED) {
                    retryInBackground(id, () -> new GitWorkspace("git", line -> { })
                            .discard(repo, worktree, ticket.getBranchName()) == GitWorkspace.Release.RELEASED);
                }
            }
            deleteTicketRecords(id);
        });
    }

    private void deleteTicketRecords(String ticketId) {
        JunoStore store = state.store;
        store.deleteTree(store.runsDir(ticketId));
        store.deleteLogs(ticketId);
        Path consoles = store.home().resolve("consoles");
        String prefix = BoardState.safeName(ticketId);
        try (Stream<Path> files = Files.exists(consoles) ? Files.list(consoles) : Stream.empty()) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix)).forEach(store::deleteTree);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Keeps the {@link #RUNS_KEPT} most recent run records of a ticket, never deleting one that the ticket still
     * needs (the current run, the last run, or a run being recovered from).
     */
    void pruneRuns(String ticketId) {
        Set<String> keep = new HashSet<>();
        synchronized (state) {
            state.find(ticketId).ifPresent(t -> {
                ActiveRun run = t.getActiveRun();
                if (run != null) {
                    add(keep, run.journal());
                    add(keep, run.recoveredFrom());
                }
                add(keep, t.getLastJournal());
            });
        }
        Path dir = state.store.runsDir(ticketId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> journals;
        try (Stream<Path> files = Files.list(dir)) {
            journals = files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return;
        }
        for (int i = RUNS_KEPT; i < journals.size(); i++) {
            Path journal = journals.get(i);
            if (keep.contains(journal.toAbsolutePath().normalize().toString())) {
                continue;
            }
            String base = journal.getFileName().toString().replaceFirst("\\.jsonl$", "");
            state.store.deleteTree(journal);
            state.store.deleteTree(journal.resolveSibling(base + ".prompt.md"));
        }
    }

    /**
     * On start-up: removes logs, run records, terminal scripts and Claude Code session history of tickets that no
     * longer exist, and worktrees of deleted tickets that have no uncommitted changes (they would block checking
     * out their branch).
     */
    void cleanUpOrphans() {
        Set<String> live = new HashSet<>();
        synchronized (state) {
            for (Ticket t : state.board.getTickets()) {
                live.add(BoardState.safeName(t.getId()));
            }
        }
        JunoStore store = state.store;
        try (Stream<Path> runs = list(store.home().resolve("runs"))) {
            runs.filter(p -> !live.contains(p.getFileName().toString())).forEach(store::deleteTree);
        }
        try (Stream<Path> logs = list(store.home().resolve("logs"))) {
            logs.filter(p -> !live.contains(p.getFileName().toString().replaceFirst("(\\.\\d+)?\\.log$", "")))
                    .forEach(store::deleteTree);
        }
        try (Stream<Path> worktrees = list(worktreesDir())) {
            worktrees.filter(p -> !live.contains(p.getFileName().toString())).forEach(this::removeOrphanWorktree);
        }
        // Claude Code's own history of agents that worked in a deleted ticket's worktree.
        Set<String> liveSessions = new HashSet<>();
        live.forEach(name -> liveSessions.add(claudeProjectName(worktreesDir().resolve(name))));
        String prefix = claudeProjectName(worktreesDir()) + "-";
        try (Stream<Path> sessions = list(claudeProjectsDir())) {
            sessions.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.length() > prefix.length() && !liveSessions.contains(name);
            }).forEach(Housekeeping::deleteSessionFolder);
        }
        // Terminal scripts of deleted tickets ("<id>[-variant].cmd", "open-<id>[-variant].cmd", ...).
        try (Stream<Path> scripts = list(store.home().resolve("consoles"))) {
            scripts.filter(p -> {
                String name = p.getFileName().toString().replaceFirst("^open-", "");
                return live.stream().noneMatch(name::startsWith);
            }).forEach(store::deleteTree);
        }
    }

    private Path worktreesDir() {
        return state.store.home().resolve("worktrees");
    }

    // ---- Claude Code's session history ---------------------------------------------------------

    /**
     * Deletes the history Claude Code keeps for agents that ran in this worktree (their full transcripts, in
     * {@code ~/.claude/projects/<worktree path>}). Only ever a folder belonging to one of Juno's worktrees.
     */
    void deleteClaudeSessions(Path worktree) {
        Path absolute = worktree.toAbsolutePath().normalize();
        if (!absolute.startsWith(worktreesDir().toAbsolutePath().normalize()) || absolute.equals(
                worktreesDir().toAbsolutePath().normalize())) {
            return;
        }
        deleteSessionFolder(claudeProjectsDir().resolve(claudeProjectName(absolute)));
    }

    /** Deletes one folder directly inside Claude Code's projects folder (and nothing else). */
    private static void deleteSessionFolder(Path folder) {
        Path projects = claudeProjectsDir().toAbsolutePath().normalize();
        Path target = folder.toAbsolutePath().normalize();
        if (!projects.equals(target.getParent()) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // best effort
        }
    }

    /** Where Claude Code keeps per-folder session history: {@code $CLAUDE_CONFIG_DIR/projects} or ~/.claude/projects. */
    static Path claudeProjectsDir() {
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        Path config = configured != null && !configured.isBlank() ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".claude");
        return config.resolve("projects");
    }

    /** Claude Code's folder name for a working directory: every character other than a letter or digit becomes '-'. */
    static String claudeProjectName(Path dir) {
        return dir.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
    }

    /** Removes a worktree no ticket owns, through its own repository, unless it has uncommitted changes. */
    private void removeOrphanWorktree(Path worktree) {
        GitWorkspace git = new GitWorkspace("git", line -> { });
        Path repo = git.mainRepository(worktree);
        if (repo == null) {
            return; // not a git worktree (or git can't read it): leave it alone
        }
        String branch = git.currentBranch(worktree);
        if (git.release(repo, worktree, branch == null ? "" : branch) == GitWorkspace.Release.RELEASED) {
            state.addTrackerLog("[juno] removed the worktree of a deleted ticket: " + worktree);
        }
    }

    private static Stream<Path> list(Path dir) {
        try {
            return Files.isDirectory(dir) ? Files.list(dir) : Stream.empty();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static void add(Set<String> set, String path) {
        if (path != null) {
            set.add(Path.of(path).toAbsolutePath().normalize().toString());
        }
    }

    /** Retries {@code attempt} every 30 seconds for 10 minutes, until it returns true. */
    private static void retryInBackground(String ticketId, BooleanSupplier attempt) {
        Thread.ofVirtual().name("retry-" + ticketId).start(() -> {
            for (int i = 0; i < RELEASE_RETRIES; i++) {
                try {
                    Thread.sleep(RELEASE_RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (attempt.getAsBoolean()) {
                    return;
                }
            }
        });
    }
}
