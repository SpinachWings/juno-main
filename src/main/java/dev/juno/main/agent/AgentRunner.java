package dev.juno.main.agent;

import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.Secrets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs Claude Code agents as child processes, one per ticket, in headless mode with streamed JSON output.
 * The prompt is written to the process's stdin (avoiding command-line quoting issues on Windows).
 */
public final class AgentRunner {

    /** Callbacks are invoked on background threads. */
    public interface Listener {
        void log(String ticketId, String line);

        void finished(String ticketId, AgentOutcome outcome);

        /** Every raw stdout line (stream-json), before formatting; used for the run journal. */
        default void raw(String ticketId, String line) {
        }
    }

    /**
     * @param ticketId        ticket the run belongs to
     * @param workDir         project directory the agent works in
     * @param prompt          prompt sent on stdin
     * @param resumeSessionId session to continue, or null for a fresh session
     * @param settings        snapshot of the effective agent profile (allowed tools already merged)
     * @param agentLabel      human description of the agent and where it was assigned, for the log
     */
    public record Request(String ticketId, Path workDir, String prompt, String resumeSessionId,
                          AgentProfile settings, String agentLabel) {
    }

    private static final class Run {
        final Process process;
        volatile boolean cancelled;
        /** Stopped by Juno because it ran longer than the agent's time limit. */
        volatile boolean timedOut;

        Run(Process process) {
            this.process = process;
        }
    }

    private final Listener listener;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public AgentRunner(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning(String ticketId) {
        return runs.containsKey(ticketId);
    }

    public int runningCount() {
        return runs.size();
    }

    /** Starts the agent and returns its process id. */
    public synchronized long start(Request request) throws IOException {
        if (runs.containsKey(request.ticketId())) {
            throw new IllegalStateException(request.ticketId() + " already has a running agent");
        }
        if (!Files.isDirectory(request.workDir())) {
            throw new IOException("Project directory does not exist: " + request.workDir());
        }
        List<String> command = buildCommand(request.settings(), request.resumeSessionId(),
                isWindows());
        ProcessBuilder pb = new ProcessBuilder(command).directory(request.workDir().toFile());
        AgentProfile s = request.settings();
        String apiKey = Secrets.resolve(s.getApiKey());
        if (apiKey != null) {
            pb.environment().put("ANTHROPIC_API_KEY", apiKey);
        }
        if (notBlank(s.getBaseUrl())) {
            pb.environment().put("ANTHROPIC_BASE_URL", s.getBaseUrl().trim());
        }

        listener.log(request.ticketId(), "");
        listener.log(request.ticketId(), "=== " + (request.resumeSessionId() == null ? "Starting" : "Resuming")
                + " agent " + request.agentLabel() + " in " + request.workDir() + " ===");
        listener.log(request.ticketId(), "$ " + String.join(" ", command));

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Could not launch Claude Code ('" + s.getCliPath() + "'). "
                    + "Check the CLI path in Settings → Agent. " + e.getMessage(), e);
        }
        Run run = new Run(process);
        runs.put(request.ticketId(), run);

        Thread.ofPlatform().daemon().name("agent-" + request.ticketId()).start(() -> supervise(request, run));
        int minutes = s.getMaxMinutes();
        Thread.ofVirtual().name("agent-limit-" + request.ticketId()).start(() -> {
            try {
                if (!process.waitFor(minutes, TimeUnit.MINUTES) && runs.get(request.ticketId()) == run) {
                    run.timedOut = true;
                    listener.log(request.ticketId(), "[juno] stopping the agent: it reached its " + minutes
                            + "-minute time limit");
                    kill(run.process);
                }
            } catch (InterruptedException ignored) {
                // Juno is closing
            }
        });
        return process.pid();
    }

    private void supervise(Request request, Run run) {
        String ticketId = request.ticketId();
        Process process = run.process;
        StreamJsonParser parser = new StreamJsonParser();
        Deque<String> stderrTail = new ArrayDeque<>();

        Thread stderrThread = Thread.ofPlatform().daemon().name("agent-err-" + ticketId).start(() -> {
            try (var err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    listener.log(ticketId, "[stderr] " + line);
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > 5) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(request.prompt().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            listener.log(ticketId, "[juno] could not send prompt: " + e.getMessage());
        }

        try (var out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                listener.raw(ticketId, line);
                for (String entry : parser.accept(line)) {
                    listener.log(ticketId, entry);
                }
            }
        } catch (IOException e) {
            listener.log(ticketId, "[juno] output stream failed: " + e.getMessage());
        }

        int exit;
        try {
            exit = process.waitFor();
            stderrThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        }
        runs.remove(ticketId);
        String tail;
        synchronized (stderrTail) {
            tail = String.join("\n", stderrTail);
        }
        if (shuttingDown) {
            return;
        }
        AgentOutcome outcome = run.timedOut
                ? new AgentOutcome(AgentOutcome.Kind.FAILED, parser.sessionId(), "it was stopped after "
                        + minutes(request.settings().getMaxMinutes()) + " (the agent's time limit in Settings → "
                        + "Agents). Its work so far is in its worktree; reply to let it continue where it stopped.", -1)
                : parser.outcome(exit, run.cancelled, tail);
        listener.log(ticketId, "=== Agent finished: " + outcome.kind() + " ===");
        listener.finished(ticketId, outcome);
    }

    private static String minutes(int n) {
        return n == 1 ? "1 minute" : n + " minutes";
    }

    /** Stops a running agent (and any processes it spawned). */
    public void stop(String ticketId) {
        Run run = runs.get(ticketId);
        if (run != null) {
            run.cancelled = true;
            kill(run.process);
        }
    }

    /** Kills all agents without reporting outcomes; tickets are recovered on next start-up. */
    public void shutdown() {
        shuttingDown = true;
        runs.values().forEach(r -> kill(r.process));
    }

    /**
     * Kills an agent left running by a previous Juno session (e.g. Juno crashed but the agent didn't), so it
     * can't keep editing the worktree while a resumed agent works there. To avoid hitting an unrelated process
     * that reused the id after a restart, it only acts if the process started within two minutes of the run.
     *
     * @return true if a process was stopped
     */
    public static boolean killOrphan(long pid, Instant runStartedAt) {
        if (pid <= 0 || runStartedAt == null) {
            return false;
        }
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(h -> h.info().startInstant()
                .map(start -> Duration.between(runStartedAt, start).abs().toMinutes() < 2)
                .orElse(false)).map(h -> {
                    h.descendants().forEach(ProcessHandle::destroy);
                    h.destroy();
                    return true;
                }).orElse(false);
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
    }

    // ---- command line -----------------------------------------------------------------------

    /**
     * Tool rules always denied to agents. Juno itself pushes branches and opens pull/merge requests; merging is
     * only ever done by a human. (The MCP servers enforce the same rule on their side.)
     */
    public static final List<String> FORBIDDEN_TOOLS = List.of(
            "Bash(git push:*)", "Bash(git merge:*)", "Bash(git rebase:*)",
            "Bash(gh pr merge:*)", "Bash(gh pr review:*)", "Bash(gh pr close:*)", "Bash(gh api:*)",
            "Bash(glab mr merge:*)", "Bash(glab mr approve:*)", "Bash(glab mr close:*)", "Bash(glab api:*)");

    public static List<String> buildCommand(AgentProfile s, String resumeSessionId, boolean windows) {
        List<String> cmd = new ArrayList<>();
        String cli = notBlank(s.getCliPath()) ? s.getCliPath().trim() : "claude";
        // npm-installed CLIs on Windows are .cmd shims that ProcessBuilder cannot launch directly.
        if (windows && !cli.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(cli);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        if (notBlank(s.getModel())) {
            cmd.add("--model");
            cmd.add(s.getModel().trim());
        }
        if (notBlank(s.getPermissionMode())) {
            cmd.add("--permission-mode");
            cmd.add(s.getPermissionMode().trim());
        }
        cmd.add("--max-turns");
        cmd.add(Integer.toString(s.getMaxTurns()));
        if (notBlank(s.getAllowedTools())) {
            cmd.add("--allowedTools");
            cmd.add(s.getAllowedTools().trim());
        }
        if (notBlank(resumeSessionId)) {
            cmd.add("--resume");
            cmd.add(resumeSessionId.trim());
        }
        // Hard guard, whatever the profile allows: agents never merge, push, or drive GitHub/GitLab directly.
        cmd.add("--disallowedTools");
        cmd.add(String.join(" ", FORBIDDEN_TOOLS));
        cmd.addAll(splitArgs(s.getExtraArgs()));
        return cmd;
    }

    /** Splits on whitespace, honouring double quotes. */
    static List<String> splitArgs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean inToken = false;
        for (char c : raw.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                inToken = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (inToken) {
                    out.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(current.toString());
        }
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
