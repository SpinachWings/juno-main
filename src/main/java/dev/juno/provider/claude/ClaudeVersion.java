package dev.juno.provider.claude;

import dev.juno.provider.Provider.VersionCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        try {
            out = run(executable);
        } catch (IOException e) {
            return new VersionCheck(false, "", "Claude Code wasn't found at “" + executable
                    + "”. Install it, or set the Claude Code path in System settings.");
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

    /** npm-installed CLIs on Windows are launched through {@code cmd.exe /c} (design §12). */
    public static List<String> launcher(String executable) {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(executable);
        return cmd;
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
