package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.juno.prompts.Prompts;
import dev.juno.store.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Juno's MCP server (design §4.4): streamable HTTP on {@code 127.0.0.1}, JSON responses. Each agent process gets its
 * own random bearer token, which is how a call is tied to its agent (an agent can't speak for another).
 *
 * <p>Milestone 2 has only {@code request_permission} (Claude Code's permission prompt tool) and {@code request_folder}.
 * TODO(Milestone 4): the other tools of §4.4 and tool access by the profile's allowed tools.
 */
public final class JunoMcpServer {
    /** What the server asks the app to do. Calls may block (the user answers in the pane). */
    public interface Calls {
        /** Claude Code's permission prompt; returns its decision object ({@code behavior} allow / deny). */
        ObjectNode requestPermission(String agentId, String tool, JsonNode input, String toolUseId);

        /** Returns the text the agent gets back. */
        String requestFolder(String agentId, String reason);
    }

    static final List<String> TOOLS = List.of("request_permission", "request_folder");
    private static final Pattern PARAM = Pattern.compile("^- `(\\w+)`: (.*)$");

    private final Calls calls;
    private final Map<String, String> agentByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer http;
    private ExecutorService pool;

    public JunoMcpServer(Calls calls) {
        this.calls = calls;
    }

    public synchronized void start() throws IOException {
        if (http != null) return;
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "juno-mcp");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/mcp", this::handle);
        http.start();
    }

    public synchronized void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
        http = null;
    }

    public String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    /** A new token for one process of {@code agentId}. Never logged or shown. */
    public String register(String agentId) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        agentByToken.put(token, agentId);
        return token;
    }

    public void unregister(String token) {
        if (token != null) agentByToken.remove(token);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String agent = auth != null && auth.startsWith("Bearer ") ? agentByToken.get(auth.substring(7).trim()) : null;
            if (agent == null) {
                send(ex, 401, null);
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex, agent);
                case "DELETE" -> send(ex, 200, null);
                default -> send(ex, 405, null);
            }
        }
    }

    private void post(HttpExchange ex, String agent) throws IOException {
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(ex.getRequestBody().readAllBytes());
        } catch (IOException e) {
            send(ex, 400, error(null, -32700, "Parse error"));
            return;
        }
        if (body.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            for (JsonNode m : body) {
                ObjectNode r = dispatch(m, agent);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) send(ex, 202, null);
            else send(ex, 200, out);
            return;
        }
        ObjectNode r = dispatch(body, agent);
        if (r == null) send(ex, 202, null);
        else send(ex, 200, r);
    }

    /** Null for notifications. */
    ObjectNode dispatch(JsonNode m, String agent) {
        JsonNode id = m.get("id");
        if (id == null || id.isNull()) return null;
        String method = m.path("method").asText();
        JsonNode params = m.path("params");
        return switch (method) {
            case "initialize" -> {
                ObjectNode r = Json.MAPPER.createObjectNode();
                r.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
                r.putObject("capabilities").putObject("tools");
                ObjectNode info = r.putObject("serverInfo");
                info.put("name", "juno");
                info.put("version", "2");
                yield result(id, r);
            }
            case "server/discover", "ping" -> result(id, Json.MAPPER.createObjectNode());
            case "tools/list" -> {
                ObjectNode r = Json.MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                TOOLS.forEach(t -> tools.add(toolSchema(t)));
                yield result(id, r);
            }
            case "tools/call" -> result(id, call(agent, params.path("name").asText(), params.path("arguments")));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode call(String agent, String name, JsonNode args) {
        try {
            return switch (name) {
                case "request_permission" -> text(calls.requestPermission(agent, args.path("tool_name").asText(),
                        args.path("input"), args.path("tool_use_id").asText("")).toString(), false);
                case "request_folder" -> text(calls.requestFolder(agent, args.path("reason").asText("")), false);
                default -> text(common("unknown-tool", Map.of("tool", name)), true);
            };
        } catch (RuntimeException e) {
            return text(common("call-failed", Map.of("reason", String.valueOf(e.getMessage()))), true);
        }
    }

    private static String common(String key, Map<String, ?> values) {
        return Prompts.render(Prompts.sections(Prompts.load("results/common.md")).get(key), values);
    }

    private static ObjectNode text(String text, boolean error) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        ObjectNode c = r.putArray("content").addObject();
        c.put("type", "text");
        c.put("text", text);
        if (error) r.put("isError", true);
        return r;
    }

    /** A tool's description and parameters, from {@code docs/prompts/tools/<tool>.md}. */
    static ObjectNode toolSchema(String tool) {
        ObjectNode t = Json.MAPPER.createObjectNode();
        t.put("name", tool);
        String file = Prompts.load("tools/" + tool + ".md");
        String[] parts = file.split("\n## Parameters\n", 2);
        String description = parts[0].replaceFirst("^# .*\n", "").trim();
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        if (tool.equals("request_permission")) {
            props.putObject("tool_name").put("type", "string");
            props.putObject("input").put("type", "object");
            props.putObject("tool_use_id").put("type", "string");
            required.add("tool_name").add("input");
            return t;
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (parts.length > 1) {
            for (String line : parts[1].split("\n")) {
                Matcher m = PARAM.matcher(line.trim());
                if (m.matches()) params.put(m.group(1), m.group(2));
            }
        }
        params.forEach((k, v) -> {
            ObjectNode p = props.putObject(k);
            p.put("type", "string");
            p.put("description", v);
            required.add(k);
        });
        return t;
    }

    private static ObjectNode result(JsonNode id, JsonNode r) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("jsonrpc", "2.0");
        o.set("id", id);
        o.set("result", r);
        return o;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("jsonrpc", "2.0");
        o.set("id", id);
        ObjectNode e = o.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return o;
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
