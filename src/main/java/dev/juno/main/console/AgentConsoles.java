package dev.juno.main.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Terminal windows with a live agent console per ticket.
 *
 * <p>Juno runs a small HTTP API bound to 127.0.0.1 (random port, random token stored in
 * {@code ~/.juno/console.token}). Each terminal runs {@link AgentConsole}, which long-polls the ticket's activity
 * log and state, and posts answers to the agent's questions. A console counts as open while it keeps polling.
 */
public final class AgentConsoles implements AutoCloseable {

    /** What the console server needs from the rest of Juno. */
    public interface Backend {
        /** Current state of a ticket, or empty if it doesn't exist. */
        Optional<TicketState> state(String ticketId);

        Path logFile(String ticketId);

        /** Answers the agent's question (same as Respond on the board). Throws with a user-facing message. */
        void answer(String ticketId, String answer) throws Exception;

        /** Exports the ticket's design doc as .txt and returns the file path. */
        String exportDesignDoc(String ticketId) throws Exception;
    }

    /** Snapshot of what a console shows besides the log. */
    public record TicketState(String status, String statusLabel, String title, String question, boolean running,
                              boolean delivering, String pullRequestUrl, String branch, boolean designReview,
                              String designDoc) {
        String signature() {
            return status + "|" + (question == null ? "" : Integer.toHexString(question.hashCode())) + "|" + running
                    + "|" + delivering + "|" + (pullRequestUrl == null ? "" : pullRequestUrl) + "|" + designReview
                    + "|" + (designDoc == null ? "" : Integer.toHexString(designDoc.hashCode()));
        }
    }

    /** How long a just-launched terminal counts as open before its console has connected. */
    private static final long STARTING_MS = 20_000;
    /** A console that hasn't polled for this long is gone, whatever its process looks like. */
    private static final long POLL_GRACE_MS = 45_000;
    private static final int MAX_CHUNK = 256 * 1024;

    private final Backend backend;
    private final TerminalLauncher launcher;
    private final Path tokenFile;
    private final String token;
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    /** When each console was last launched by Juno (cleared once it connects). */
    private final Map<String, Long> starting = new ConcurrentHashMap<>();
    /** Process id of each connected console, so a closed terminal is noticed immediately. */
    private final Map<String, Long> consolePids = new ConcurrentHashMap<>();
    /** When each console last polled. */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public AgentConsoles(Backend backend, Path home, Consumer<String> log) throws IOException {
        this.backend = backend;
        this.launcher = new TerminalLauncher(home.resolve("consoles"), log);
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.token = HexFormat.of().formatHex(random);
        this.tokenFile = home.resolve("console.token");
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file lives in the user's profile, which is private by default
        }

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "juno-console-api");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/api/stream", this::stream);
        server.createContext("/api/answer", this::answer);
        server.createContext("/api/design/export", this::exportDesign);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Console identity: the ticket, plus a variant for AI review runs (which get their own terminal). */
    public static String key(String ticketId, String variant) {
        return variant == null || variant.isBlank() ? ticketId : ticketId + "-" + variant;
    }

    /** Terminal window title: the ticket's title (plain characters only, so every shell accepts it). */
    public static String windowTitle(String ticketTitle, String variant) {
        String name = ticketTitle == null ? "" : ticketTitle.replaceAll("[^\\p{L}\\p{N} .,_()-]", "")
                .replaceAll("\\s+", " ").strip();
        if (name.length() > 50) {
            name = name.substring(0, 50).strip();
        }
        return "Juno - " + (name.isEmpty() ? "ticket" : name) + ("review".equals(variant) ? " (AI review)" : "");
    }

    private String windowTitleFor(String ticketId, String variant) {
        return windowTitle(backend.state(ticketId).map(TicketState::title).orElse(""), variant);
    }

    /**
     * Whether the console with this key is open: its process is still running (closing or force-closing the
     * terminal ends it, which is noticed at once), or Juno launched it moments ago and it hasn't connected yet.
     */
    public boolean isOpen(String consoleKey) {
        long now = System.currentTimeMillis();
        Long launched = starting.get(consoleKey);
        if (launched != null && now - launched < STARTING_MS) {
            return true;
        }
        Long seen = lastSeen.get(consoleKey);
        if (seen == null || now - seen >= POLL_GRACE_MS) {
            return false;
        }
        Long pid = consolePids.get(consoleKey);
        if (pid != null && !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            consolePids.remove(consoleKey, pid);
            lastSeen.remove(consoleKey, seen);
            return false;
        }
        return true;
    }

    /** Opens a console for the ticket's run unless one is already open. */
    public void ensureOpen(String ticketId, String variant, String terminalCommand) throws IOException {
        if (!isOpen(key(ticketId, variant))) {
            open(ticketId, variant, terminalCommand);
        }
    }

    /**
     * Brings the console to the front, opening a new one if none is running.
     *
     * @return a short description of what happened, for the status bar
     */
    public String show(String ticketId, String variant, String terminalCommand) throws IOException {
        String title = windowTitleFor(ticketId, variant);
        if (isOpen(key(ticketId, variant)) && launcher.focus(title)) {
            return "Switched to '" + title + "'";
        }
        // Closed (or its window can't be found to bring forward): open a fresh one. It replays the log.
        open(ticketId, variant, terminalCommand);
        return "Opened '" + title + "'";
    }

    private void open(String ticketId, String variant, String terminalCommand) throws IOException {
        String consoleKey = key(ticketId, variant);
        starting.put(consoleKey, System.currentTimeMillis()); // avoid opening twice while it starts
        consolePids.remove(consoleKey);
        lastSeen.remove(consoleKey);
        try {
            launcher.open(safe(consoleKey), windowTitleFor(ticketId, variant), consoleCommand(ticketId, consoleKey),
                    terminalCommand);
        } catch (IOException e) {
            starting.remove(consoleKey);
            throw e;
        }
    }

    /** The java command that runs {@link AgentConsole} with the same runtime and module path as Juno. */
    List<String> consoleCommand(String ticketId, String consoleKey) {
        String java = ProcessHandle.current().info().command().orElse("java");
        // javaw.exe (no console) would hide all output; use java.exe next to it.
        java = java.replaceFirst("(?i)javaw(\\.exe)?$", "java$1");
        List<String> cmd = new ArrayList<>(List.of(java, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        Module module = AgentConsoles.class.getModule();
        if (module.isNamed()) {
            String modulePath = System.getProperty("jdk.module.path");
            if (modulePath != null && !modulePath.isBlank()) {
                cmd.add("--module-path");
                cmd.add(modulePath);
            }
            cmd.add("-m");
            cmd.add(module.getName() + "/" + AgentConsole.class.getName());
        } else {
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(AgentConsole.class.getName());
        }
        cmd.addAll(List.of("--port", Integer.toString(port()), "--token-file", tokenFile.toString(),
                "--ticket", ticketId, "--console", consoleKey));
        return cmd;
    }

    // ---- HTTP API ---------------------------------------------------------------------------

    private void stream(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorised(exchange)) {
                return;
            }
            Map<String, String> q = query(exchange);
            String ticketId = q.getOrDefault("ticket", "");
            String consoleKey = q.getOrDefault("console", ticketId);
            long from = parseLong(q.get("from"), 0);
            String clientSig = q.getOrDefault("state", "");
            long waitMs = Math.min(25, Math.max(0, parseLong(q.get("wait"), 20))) * 1000;
            long pid = parseLong(q.get("pid"), 0);
            if (pid > 0) {
                consolePids.put(consoleKey, pid);
            }
            lastSeen.put(consoleKey, System.currentTimeMillis());
            starting.remove(consoleKey);

            Path log = backend.logFile(ticketId);
            long deadline = System.currentTimeMillis() + waitMs;
            Optional<TicketState> state;
            while (true) {
                state = backend.state(ticketId);
                long size = Files.exists(log) ? Files.size(log) : 0;
                if (from > size) {
                    from = 0; // log was replaced
                }
                boolean changed = state.map(s -> !s.signature().equals(clientSig)).orElse(true);
                if (size > from || changed || System.currentTimeMillis() >= deadline) {
                    break;
                }
                sleep(250);
            }

            ObjectNode body = mapper.createObjectNode();
            if (state.isEmpty()) {
                body.put("error", "Ticket " + ticketId + " no longer exists");
                respond(exchange, 404, body);
                return;
            }
            byte[] chunk = readChunk(log, from);
            body.put("text", new String(chunk, StandardCharsets.UTF_8));
            body.put("next", from + chunk.length);
            TicketState s = state.get();
            ObjectNode st = body.putObject("state");
            st.put("sig", s.signature());
            st.put("status", s.status());
            st.put("statusLabel", s.statusLabel());
            st.put("title", s.title());
            st.put("question", s.question());
            st.put("running", s.running());
            st.put("delivering", s.delivering());
            st.put("pr", s.pullRequestUrl());
            st.put("branch", s.branch());
            st.put("designReview", s.designReview());
            st.put("designDoc", s.designDoc());
            respond(exchange, 200, body);
        }
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorised(exchange)) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, mapper.createObjectNode().put("error", "POST required"));
                return;
            }
            String ticketId = query(exchange).getOrDefault("ticket", "");
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                backend.answer(ticketId, text);
                respond(exchange, 200, mapper.createObjectNode().put("ok", true));
            } catch (Exception e) {
                respond(exchange, 409, mapper.createObjectNode().put("error",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    private void exportDesign(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorised(exchange)) {
                return;
            }
            String ticketId = query(exchange).getOrDefault("ticket", "");
            try {
                respond(exchange, 200, mapper.createObjectNode().put("path", backend.exportDesignDoc(ticketId)));
            } catch (Exception e) {
                respond(exchange, 409, mapper.createObjectNode().put("error",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    /** Reads up to the last complete line after {@code from}, so multi-byte characters are never split. */
    private static byte[] readChunk(Path log, long from) throws IOException {
        if (!Files.exists(log)) {
            return new byte[0];
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long available = file.length() - from;
            if (available <= 0) {
                return new byte[0];
            }
            byte[] buffer = new byte[(int) Math.min(available, MAX_CHUNK)];
            file.seek(from);
            file.readFully(buffer);
            int end = buffer.length;
            while (end > 0 && buffer[end - 1] != '\n') {
                end--;
            }
            if (end == 0) {
                return new byte[0];
            }
            byte[] out = new byte[end];
            System.arraycopy(buffer, 0, out, 0, end);
            return out;
        }
    }

    private boolean authorised(HttpExchange exchange) throws IOException {
        if (token.equals(exchange.getRequestHeaders().getFirst("X-Juno-Token"))) {
            return true;
        }
        respond(exchange, 403, mapper.createObjectNode().put("error", "Invalid console token"));
        return false;
    }

    private void respond(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
