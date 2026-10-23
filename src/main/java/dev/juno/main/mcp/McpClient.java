package dev.juno.main.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Model Context Protocol client for a server launched as a child process (stdio transport,
 * newline-delimited JSON-RPC 2.0).
 */
public final class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final List<String> command;
    private final Map<String, String> environment;
    private final Consumer<String> stderrSink;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    private Process process;
    private OutputStream stdin;
    private volatile String serverName = "?";

    public McpClient(List<String> command, Map<String, String> environment, Consumer<String> stderrSink) {
        this.command = List.copyOf(command);
        this.environment = Map.copyOf(environment);
        this.stderrSink = stderrSink;
    }

    /** Starts the server process and performs the initialize handshake. */
    public synchronized void start() throws McpException {
        if (process != null) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(environment);
            process = pb.start();
        } catch (IOException e) {
            throw new McpException("Could not start MCP server " + command + ": " + e.getMessage(), e);
        }
        stdin = process.getOutputStream();
        Thread.ofPlatform().daemon().name("mcp-stdout-" + process.pid()).start(this::readLoop);
        Thread.ofPlatform().daemon().name("mcp-stderr-" + process.pid()).start(this::drainStderr);

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "juno-main").put("version", "0.1.0");
        JsonNode result = request("initialize", params, Duration.ofSeconds(30));
        serverName = result.path("serverInfo").path("name").asText("?");
        notify("notifications/initialized", null);
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public String serverName() {
        return serverName;
    }

    public List<String> listTools() throws McpException {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : request("tools/list", mapper.createObjectNode(), DEFAULT_TIMEOUT).path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    /**
     * Calls a tool and returns its structured output: {@code structuredContent} when present, otherwise the
     * first text content parsed as JSON (or as a JSON string if it isn't JSON).
     *
     * @throws McpException when the call fails or the tool reports {@code isError}
     */
    public JsonNode callTool(String name, ObjectNode arguments) throws McpException {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        JsonNode result = request("tools/call", params, DEFAULT_TIMEOUT);
        String text = "";
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText())) {
                text = c.path("text").asText();
                break;
            }
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpException(text.isBlank() ? "Tool " + name + " failed" : text);
        }
        if (result.hasNonNull("structuredContent")) {
            return result.get("structuredContent");
        }
        try {
            return mapper.readTree(text);
        } catch (IOException notJson) {
            return mapper.getNodeFactory().textNode(text);
        }
    }

    public JsonNode request(String method, JsonNode params, Duration timeout) throws McpException {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        try {
            send(message);
            JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                JsonNode error = response.get("error");
                throw new McpException("MCP error " + error.path("code").asInt() + ": "
                        + error.path("message").asText());
            }
            return response.path("result");
        } catch (TimeoutException e) {
            throw new McpException("Timed out waiting for " + method + " from " + serverName, e);
        } catch (ExecutionException e) {
            throw new McpException(e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for " + method, e);
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonNode params) throws McpException {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        send(message);
    }

    private void send(JsonNode message) throws McpException {
        if (!isAlive()) {
            throw new McpException("MCP server is not running");
        }
        try {
            synchronized (this) {
                stdin.write(mapper.writeValueAsBytes(message));
                stdin.write('\n');
                stdin.flush();
            }
        } catch (IOException e) {
            throw new McpException("Could not write to MCP server: " + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = mapper.readTree(line);
                } catch (IOException e) {
                    stderrSink.accept("[mcp] ignoring non-JSON output: " + line);
                    continue;
                }
                JsonNode id = message.get("id");
                if (message.has("method")) {
                    // Server-initiated request or notification. We advertise no client capabilities.
                    if (id != null && !id.isNull()) {
                        ObjectNode reply = mapper.createObjectNode();
                        reply.put("jsonrpc", "2.0");
                        reply.set("id", id);
                        reply.putObject("error").put("code", -32601).put("message", "Not supported by juno-main");
                        try {
                            send(reply);
                        } catch (McpException ignored) {
                            // server is going away
                        }
                    }
                    continue;
                }
                if (id != null && id.canConvertToLong()) {
                    CompletableFuture<JsonNode> future = pending.get(id.asLong());
                    if (future != null) {
                        future.complete(message);
                    }
                }
            }
        } catch (IOException e) {
            stderrSink.accept("[mcp] read failed: " + e.getMessage());
        } finally {
            McpException gone = new McpException("MCP server " + serverName + " exited");
            pending.values().forEach(f -> f.completeExceptionally(gone));
        }
    }

    private void drainStderr() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderrSink.accept(line);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }
}
