package dev.juno.provider.claude;

import dev.juno.provider.Provider.VersionCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Claude Code version check (design §10). */
public final class ClaudeVersion {
    /** The version Juno's flags were verified against. Raise it when a milestone relies on newer flags. */
    public static final String MINIMUM = "2.1.314";

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private ClaudeVersion() {}

    public static VersionCheck check(String executable) {
        String out;
        VersionCheck notFound = new VersionCheck(false, "", "Claude Code wasn't found at “" + executable
                + "”. Install it, or set the Claude Code path in System settings.");
        // Through cmd.exe a missing command is only an error message on stdout; look for it on PATH first.
        if (isWindows() && resolveOnWindows(executable) == null) return notFound;
        try {
            out = run(executable);
        } catch (IOException e) {
            return notFound;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VersionCheck(false, "", "Checking the Claude Code version was interrupted.");
        }
        return evaluate(out);
    }

    /** Judges the text printed by {@code claude --version}. */
    public static VersionCheck evaluate(String output) {
        Matcher m = VERSION.matcher(output == null ? "" : output);
        if (!m.find()) {
            return new VersionCheck(false, "", "Couldn't read a Claude Code version from: "
                    + (output == null ? "" : output.trim()));
        }
        String found = m.group();
        if (compare(found, MINIMUM) < 0) {
            return new VersionCheck(false, found, "Claude Code " + MINIMUM + " or later is needed; you have " + found
                    + ". Run `claude update`.");
        }
        return new VersionCheck(true, found, "Claude Code " + found + " is installed.");
    }

    public static int compare(String a, String b) {
        Matcher x = VERSION.matcher(a), y = VERSION.matcher(b);
        if (!x.find() || !y.find()) throw new IllegalArgumentException(a + " vs " + b);
        for (int i = 1; i <= 3; i++) {
            int c = Integer.compare(Integer.parseInt(x.group(i)), Integer.parseInt(y.group(i)));
            if (c != 0) return c;
        }
        return 0;
    }

    /**
     * The command that starts {@code executable}: native executables directly, anything else (npm's {@code .cmd}
     * shims) through {@code cmd.exe /c} on Windows (design §12).
     */
    public static List<String> launcher(String executable) {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            Path resolved = resolveOnWindows(executable);
            String name = resolved == null ? "" : resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe")) {
                cmd.add(resolved.toString());
                return cmd;
            }
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(executable);
        return cmd;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Finds a command the way cmd.exe would (PATH and PATHEXT); null if not found. */
    static Path resolveOnWindows(String executable) {
        String pathExt = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        List<String> exts = new ArrayList<>(List.of(""));
        for (String e : pathExt.split(";")) if (!e.isBlank()) exts.add(e.toLowerCase(Locale.ROOT));
        List<Path> dirs = new ArrayList<>();
        Path given = Path.of(executable);
        if (given.isAbsolute() || executable.contains("/") || executable.contains("\\")) {
            dirs.add(given.toAbsolutePath().getParent());
            given = given.getFileName();
        } else {
            for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                if (!d.isBlank()) {
                    try {
                        dirs.add(Path.of(d.trim()));
                    } catch (java.nio.file.InvalidPathException ignored) {
                        // skip malformed PATH entries
                    }
                }
            }
        }
        for (Path dir : dirs) {
            for (String ext : exts) {
                Path f = dir.resolve(given + ext);
                if (Files.isRegularFile(f)) return f;
            }
        }
        return null;
    }

    private static String run(String executable) throws IOException, InterruptedException {
        List<String> cmd = launcher(executable);
        cmd.add("--version");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        byte[] bytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timed out");
        }
        if (p.exitValue() != 0 && bytes.length == 0) throw new IOException("exit " + p.exitValue());
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
