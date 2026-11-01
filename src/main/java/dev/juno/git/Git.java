package dev.juno.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Local git commands Juno runs itself (no network, no tokens). */
public final class Git {
    private Git() {}

    /** Runs {@code git -C folder args...}; returns stdout, or throws with stderr on failure. */
    public static String run(Path folder, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", folder.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (p.exitValue() != 0) throw new IOException(new String(err, StandardCharsets.UTF_8).strip());
        return new String(out, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static boolean isRepository(Path folder) {
        return Files.exists(folder.resolve(".git"));
    }

    /** One file's uncommitted change, as a unified diff. */
    public record FileDiff(String file, String patch) {}

    /**
     * Uncommitted changes in {@code folder} (design §8.1 /diff): staged and unstaged against HEAD, plus untracked
     * files as new files.
     */
    public static List<FileDiff> uncommitted(Path folder) throws IOException {
        List<FileDiff> out = new ArrayList<>();
        boolean hasHead;
        try {
            run(folder, "rev-parse", "--verify", "HEAD");
            hasHead = true;
        } catch (IOException e) {
            hasHead = false;
        }
        String diff = hasHead ? run(folder, "diff", "HEAD", "--no-color", "--no-ext-diff")
                : run(folder, "diff", "--cached", "--no-color", "--no-ext-diff");
        StringBuilder cur = null;
        String file = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (cur != null) out.add(new FileDiff(file, cur.toString().stripTrailing()));
                cur = new StringBuilder();
                int b = line.lastIndexOf(" b/");
                file = b >= 0 ? line.substring(b + 3) : line.substring(11);
            }
            if (cur != null) cur.append(line).append('\n');
        }
        if (cur != null) out.add(new FileDiff(file, cur.toString().stripTrailing()));
        for (String u : run(folder, "ls-files", "--others", "--exclude-standard").split("\n")) {
            if (u.isBlank()) continue;
            Path f = folder.resolve(u);
            String body;
            try {
                body = Files.size(f) > 200_000 ? "(large file)" : Files.readString(f);
            } catch (IOException | java.io.UncheckedIOException e) {
                body = "(binary or unreadable)";
            }
            StringBuilder sb = new StringBuilder("new file " + u + "\n@@ -0,0 +1 @@\n");
            for (String l : body.split("\n", -1)) sb.append('+').append(l).append('\n');
            out.add(new FileDiff(u, sb.toString().stripTrailing()));
        }
        return out;
    }
}
