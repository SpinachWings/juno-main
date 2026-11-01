package dev.juno.runtime;

import dev.juno.provider.AgentEvent;
import dev.juno.provider.Provider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One CLI process of one agent (design §10): writes messages to its input as JSON lines, reads its events on a
 * thread, and keeps the raw output in the agent's log (rotated above 5 MB, one old copy; design §7.9).
 */
public final class AgentProcess {
    static final long LOG_LIMIT = 5L * 1024 * 1024;

    private final Process process;
    private final OutputStream stdin;
    private final Provider.StreamParser parser;
    private final Path log;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private volatile boolean stopping;

    private AgentProcess(Process process, Provider.StreamParser parser, Path log) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.parser = parser;
        this.log = log;
    }

    /**
     * Starts the process. {@code onEvent} is called on the reader thread for every event, ending with
     * {@link AgentEvent.Exited}.
     */
    public static AgentProcess start(Provider provider, Provider.LaunchSpec spec, Path log,
                                     Consumer<AgentEvent> onEvent) throws IOException {
        List<String> cmd = provider.commandLine(spec);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(spec.workingDir().toFile());
        Map<String, String> env = pb.environment();
        for (String drop : provider.environmentToRemove()) {
            if (drop.endsWith("*")) {
                String prefix = drop.substring(0, drop.length() - 1);
                env.keySet().removeIf(k -> k.startsWith(prefix));
            } else {
                env.remove(drop);
            }
        }
        env.putAll(provider.environment(spec));
        Process p = pb.start();
        AgentProcess ap = new AgentProcess(p, provider.newParser(), log);
        ap.writeLog("# start " + java.time.Instant.now() + " in " + spec.workingDir());
        Thread out = new Thread(() -> ap.readOutput(onEvent), "juno-agent-out-" + spec.agentId());
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(ap::readErrors, "juno-agent-err-" + spec.agentId());
        err.setDaemon(true);
        err.start();
        return ap;
    }

    private void readOutput(Consumer<AgentEvent> onEvent) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                writeLog(line);
                for (AgentEvent e : parser.parse(line)) {
                    try {
                        onEvent.accept(e);
                    } catch (RuntimeException ex) {
                        System.err.println("Agent event handler failed: " + ex);
                    }
                }
            }
        } catch (IOException ignored) {
            // the process went away
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        // Give the stderr reader a moment to collect the last lines.
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String detail;
        synchronized (stderrTail) {
            detail = String.join("\n", stderrTail);
        }
        writeLog("# exit " + code);
        onEvent.accept(new AgentEvent.Exited(stopping ? 0 : code, stopping ? "stopped" : detail));
    }

    private void readErrors() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                writeLog("# stderr " + line);
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > 20) stderrTail.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // the process went away
        }
    }

    public synchronized boolean send(String line) {
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean alive() {
        return process.isAlive();
    }

    /** Ends the process and its children (a {@code cmd.exe} wrapper's child too). */
    public void kill() {
        stopping = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Closes the input and lets the process end by itself (idle timeout), killing it if it doesn't. */
    public void close() {
        stopping = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        process.onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(t -> { kill(); return null; });
    }

    public long pid() {
        return process.pid();
    }

    private synchronized void writeLog(String line) {
        try {
            Files.createDirectories(log.getParent());
            if (Files.exists(log) && Files.size(log) > LOG_LIMIT) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the log is best effort
        }
    }
}
