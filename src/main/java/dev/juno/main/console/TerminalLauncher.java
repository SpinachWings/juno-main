package dev.juno.main.console;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Opens terminal windows and brings them to the front, per operating system.
 *
 * <p>The command to run is written to a script first (so no shell quoting is needed on the command line):
 * <ul>
 *   <li>Windows: a {@code .cmd} file opened in Windows Terminal ({@code wt.exe}) if installed, else a new
 *       console window ({@code start}). Focus uses {@code WScript.Shell.AppActivate} on the window title.</li>
 *   <li>macOS: a {@code .command} file opened with Terminal.app; focus via AppleScript.</li>
 *   <li>Linux: a {@code .sh} file in the first available terminal emulator; focus via {@code wmctrl} or
 *       {@code xdotool} when installed.</li>
 * </ul>
 * A custom command can replace the automatic choice: {@code {title}} and {@code {script}} are substituted.
 */
final class TerminalLauncher {

    enum Os { WINDOWS, MAC, LINUX }

    private final Path scriptsDir;
    private final Consumer<String> log;
    private final Os os;

    TerminalLauncher(Path scriptsDir, Consumer<String> log) {
        this(scriptsDir, log, detect());
    }

    TerminalLauncher(Path scriptsDir, Consumer<String> log, Os os) {
        this.scriptsDir = scriptsDir;
        this.log = log;
        this.os = os;
    }

    static Os detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return Os.WINDOWS;
        }
        return name.contains("mac") ? Os.MAC : Os.LINUX;
    }

    /**
     * Opens a terminal window titled {@code title} running {@code command}.
     *
     * @param customCommand template with {title} and {script}, or blank for automatic
     */
    void open(String name, String title, List<String> command, String customCommand) throws IOException {
        Files.createDirectories(scriptsDir);
        Path script = writeScript(name, title, command);
        List<String> launch;
        if (customCommand != null && !customCommand.isBlank()) {
            String filled = customCommand.replace("{title}", title).replace("{script}", script.toString());
            launch = os == Os.WINDOWS ? List.of("cmd.exe", "/c", filled) : List.of("sh", "-c", filled);
        } else {
            launch = switch (os) {
                case WINDOWS -> List.of("cmd.exe", "/c", writeWindowsOpener(name, title, script).toString());
                case MAC -> List.of("open", "-a", "Terminal", script.toString());
                case LINUX -> linuxTerminal(title, script);
            };
        }
        Process p = new ProcessBuilder(launch).redirectErrorStream(true).start();
        try {
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() != 0) {
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new IOException("Terminal launch failed (" + String.join(" ", launch) + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Tries to bring the window with this title to the front. Returns true if it (probably) worked. */
    boolean focus(String title) {
        List<String> command = switch (os) {
            case WINDOWS -> List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$w = New-Object -ComObject WScript.Shell; if ($w.AppActivate('" + title.replace("'", "''")
                            + "')) { exit 0 } else { exit 1 }");
            case MAC -> List.of("osascript", "-e", """
                    tell application "Terminal"
                      repeat with w in windows
                        if name of w contains "%s" then
                          activate
                          set index of w to 1
                          return "ok"
                        end if
                      end repeat
                    end tell
                    error "not found"
                    """.formatted(title.replace("\"", "")));
            case LINUX -> onPath("wmctrl") ? List.of("wmctrl", "-a", title)
                    : onPath("xdotool") ? List.of("xdotool", "search", "--name", title, "windowactivate")
                    : List.of();
        };
        if (command.isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- scripts ----------------------------------------------------------------------------

    Path writeScript(String name, String title, List<String> command) throws IOException {
        Path script;
        String content;
        if (os == Os.WINDOWS) {
            script = scriptsDir.resolve(name + ".cmd");
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\r\n");
            sb.append("title ").append(title).append("\r\n");
            sb.append("chcp 65001 >nul\r\n");
            for (int i = 0; i < command.size(); i++) {
                sb.append(i == 0 ? "" : " ").append(cmdQuote(command.get(i)));
            }
            sb.append("\r\n");
            sb.append("echo.\r\n");
            sb.append("echo Console closed. Press any key to close this window.\r\n");
            sb.append("pause >nul\r\n");
            content = sb.toString();
        } else {
            script = scriptsDir.resolve(name + (os == Os.MAC ? ".command" : ".sh"));
            StringBuilder sb = new StringBuilder();
            sb.append("#!/bin/sh\n");
            sb.append("printf '\\033]0;%s\\007' ").append(shQuote(title)).append("\n");
            for (int i = 0; i < command.size(); i++) {
                sb.append(i == 0 ? "" : " ").append(shQuote(command.get(i)));
            }
            sb.append("\n");
            sb.append("echo\n");
            sb.append("printf 'Console closed. Press Enter to close this window.'\n");
            sb.append("read _\n");
            content = sb.toString();
        }
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (os != Os.WINDOWS) {
            script.toFile().setExecutable(true, true);
        }
        return script;
    }

    private Path writeWindowsOpener(String name, String title, Path script) throws IOException {
        Path opener = scriptsDir.resolve("open-" + name + ".cmd");
        String quotedScript = "\"" + script + "\"";
        String content = "@echo off\r\n"
                + "where wt.exe >nul 2>nul\r\n"
                + "if %ERRORLEVEL%==0 (\r\n"
                + "  start \"\" wt.exe -w new --title \"" + title + "\" cmd.exe /c " + quotedScript + "\r\n"
                + ") else (\r\n"
                + "  start \"" + title + "\" cmd.exe /c " + quotedScript + "\r\n"
                + ")\r\n";
        Files.writeString(opener, content, StandardCharsets.UTF_8);
        return opener;
    }

    private List<String> linuxTerminal(String title, Path script) throws IOException {
        String s = script.toString();
        List<List<String>> candidates = List.of(
                List.of("x-terminal-emulator", "-T", title, "-e", s),
                List.of("gnome-terminal", "--title", title, "--", s),
                List.of("konsole", "-p", "tabtitle=" + title, "-e", s),
                List.of("xfce4-terminal", "--title", title, "-x", s),
                List.of("kitty", "--title", title, s),
                List.of("alacritty", "--title", title, "-e", s),
                List.of("xterm", "-T", title, "-e", s));
        for (List<String> candidate : candidates) {
            if (onPath(candidate.getFirst())) {
                return candidate;
            }
        }
        throw new IOException("No terminal emulator found. Set a terminal command in Settings → General, "
                + "e.g. 'gnome-terminal -- {script}'.");
    }

    static String cmdQuote(String arg) {
        String escaped = arg.replace("%", "%%").replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    static String shQuote(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, executable))) {
                return true;
            }
        }
        return false;
    }
}
