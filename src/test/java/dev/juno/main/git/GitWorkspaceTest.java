package dev.juno.main.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorkspaceTest {

    @TempDir
    Path tmp;

    @Test
    void suggestsBranchNames() {
        assertEquals("juno/add-login-page", GitWorkspace.suggestBranchName("Add login page!"));
        assertEquals("juno/ticket", GitWorkspace.suggestBranchName("  "));
        assertTrue(GitWorkspace.suggestBranchName("x".repeat(200)).length() <= 60);
    }

    @Test
    void preparesWorktreeCommitsAndPushes() throws Exception {
        Path origin = tmp.resolve("origin.git");
        Path repo = tmp.resolve("repo");
        git(tmp, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(tmp, "clone", "-q", origin.toString(), repo.toString());
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "a");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "init");
        git(repo, "push", "-q", "origin", "main");

        List<String> log = new ArrayList<>();
        GitWorkspace ws = new GitWorkspace("git", log::add);
        Path worktree = tmp.resolve("wt").resolve("JUNO-1");

        GitWorkspace.Prepared prepared = ws.prepare(repo, worktree, "main", "juno/juno-1-test");
        assertEquals("origin/main", prepared.baseRef());
        assertFalse(prepared.reused());
        assertTrue(Files.isDirectory(worktree));

        // Second call reuses the worktree.
        assertTrue(ws.prepare(repo, worktree, "main", "juno/juno-1-test").reused());

        assertEquals(0, ws.commitsAhead(worktree, "origin/main"));
        assertFalse(ws.commitLeftovers(worktree, "nothing"));
        Files.writeString(worktree.resolve("b.txt"), "b");
        assertTrue(ws.commitLeftovers(worktree, "JUNO-1: add b"));
        assertEquals(1, ws.commitsAhead(worktree, "origin/main"));

        ws.push(worktree, "juno/juno-1-test");
        assertEquals("JUNO-1: add b", git(tmp, "--git-dir", origin.toString(), "log", "-1", "--format=%s",
                "juno/juno-1-test").strip());

        // The user's own checkout stays on main and clean.
        assertEquals("main", git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertTrue(git(repo, "status", "--porcelain").isBlank());

        // Uncommitted changes keep the worktree.
        Files.writeString(worktree.resolve("c.txt"), "c");
        assertFalse(ws.release(repo, worktree));
        Files.delete(worktree.resolve("c.txt"));

        // Once delivered, the worktree is released and the branch can be checked out in the user's folder.
        Files.createDirectories(worktree.resolve(".claude/skills/juno-x"));
        ws.exclude(worktree, "/.claude/skills/juno-*/");
        Files.writeString(worktree.resolve(".claude/skills/juno-x/SKILL.md"), "skill");
        assertTrue(ws.release(repo, worktree));
        assertFalse(Files.exists(worktree));
        git(repo, "checkout", "-q", "juno/juno-1-test");
        assertTrue(Files.exists(repo.resolve("b.txt")));

        // While the user has it checked out, the next run explains instead of failing obscurely.
        try {
            ws.prepare(repo, worktree, "main", "juno/juno-1-test");
            throw new AssertionError("expected a clear error");
        } catch (GitException e) {
            assertTrue(e.getMessage().contains("is checked out in"), e.getMessage());
        }

        // Back on main, the next run recreates the worktree from the existing branch, keeping its base.
        git(repo, "checkout", "-q", "main");
        GitWorkspace.Prepared again = ws.prepare(repo, worktree, "main", "juno/juno-1-test");
        assertEquals(null, again.baseRef());
        assertTrue(Files.exists(worktree.resolve("b.txt")));
    }

    @Test
    void mergesTheUpdatedBaseBranch() throws Exception {
        Path origin = tmp.resolve("o.git");
        Path repo = tmp.resolve("r");
        git(tmp, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(tmp, "clone", "-q", origin.toString(), repo.toString());
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "T");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        Files.writeString(repo.resolve("b.txt"), "b\n");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "init");
        git(repo, "push", "-q", "origin", "main");
        GitWorkspace ws = new GitWorkspace("git", line -> { });
        Path wt = tmp.resolve("wt");
        ws.prepare(repo, wt, "main", "juno/x");

        assertTrue(ws.mergeBase(wt, "main").upToDate());

        // main moves on in another file: merges cleanly, and nothing is changed.
        Files.writeString(repo.resolve("b.txt"), "b2\n");
        git(repo, "commit", "-qam", "b2");
        git(repo, "push", "-q", "origin", "main");
        GitWorkspace.MergeResult clean = ws.mergeBase(wt, "main");
        assertFalse(clean.conflicts());
        assertFalse(clean.upToDate());
        assertFalse(ws.mergeInProgress(wt));

        // Both change the same line: conflicts are left for the agent, with the merge in progress.
        Files.writeString(wt.resolve("a.txt"), "mine\n");
        ws.commitLeftovers(wt, "mine");
        Files.writeString(repo.resolve("a.txt"), "theirs\n");
        git(repo, "commit", "-qam", "Merge pull request #7 from acme/juno/other");
        git(repo, "push", "-q", "origin", "main");
        GitWorkspace.MergeResult conflict = ws.mergeBase(wt, "main");
        assertTrue(conflict.conflicts());
        assertEquals(List.of("a.txt"), conflict.conflictedFiles());
        assertTrue(ws.mergeInProgress(wt));
        assertEquals(List.of("a.txt"), ws.filesWithConflictMarkers(wt, ws.changedFiles(wt)));
        assertTrue(ws.commitsTouching(wt, conflict.forkPoint(), conflict.baseRef(), conflict.conflictedFiles())
                .getFirst().contains("#7"));

        // Resolved: committing records the merge, and the branch now contains main.
        Files.writeString(wt.resolve("a.txt"), "mine and theirs\n");
        assertTrue(ws.filesWithConflictMarkers(wt, ws.changedFiles(wt)).isEmpty());
        ws.commitLeftovers(wt, "merge");
        assertFalse(ws.mergeInProgress(wt));
        assertTrue(ws.contains(wt, "origin/main"));
        assertTrue(ws.mergeCommitFiles(wt).contains("a.txt"));
    }

    @Test
    void rejectsBadInput() {
        GitWorkspace ws = new GitWorkspace("git", line -> { });
        assertThrows(() -> ws.prepare(tmp.resolve("missing"), tmp.resolve("wt"), "main", "x"));
        assertThrows(() -> ws.prepare(tmp, tmp.resolve("wt"), "main", "x")); // not a repository
    }

    private static void assertThrows(ThrowingRunnable r) {
        try {
            r.run();
        } catch (GitException expected) {
            return;
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e);
        }
        throw new AssertionError("expected GitException");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        }
        return out;
    }
}
