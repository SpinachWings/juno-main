package dev.juno.main.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model Context Protocol client for a server on a localhost port (MCP over HTTP, JSON responses). The caller's
 * credentials go in the request headers of every call; the server itself holds none.
 */
public final class McpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY) // always localhost
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI endpoint;
    private final Map<String, String> headers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong();
    private volatile String serverName = "?";
    private volatile String sessionId;
    private volatile boolean initialized;

    /**
     * @param endpoint e.g. {@code http://127.0.0.1:9950/mcp}
     * @param headers  sent with every request (Authorization, X-Api-Url)
     */
    public McpClient(URI endpoint, Map<String, String> headers) {
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers);
    }

    /** Performs the initialize handshake (once). */
    public synchronized void start() throws McpException {
        if (initialized) {
            return;
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "juno-main").put("version", "0.3.0");
        JsonNode result = request("initialize", params, Duration.ofSeconds(30));
        serverName = result.path("serverInfo").path("name").asText("?");
        notifyServer("notifications/initialized");
        initialized = true;
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
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", ids.incrementAndGet());
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        HttpResponse<String> response = post(message, timeout);
        if (response.statusCode() != 200) {
            throw new McpException("MCP server at " + endpoint + " answered HTTP " + response.statusCode()
                    + " to " + method + describe(response.body()));
        }
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new McpException("MCP server at " + endpoint + " sent an unreadable reply to " + method, e);
        }
        if (body.has("error")) {
            JsonNode error = body.get("error");
            throw new McpException(error.isObject()
                    ? "MCP error " + error.path("code").asInt() + ": " + error.path("message").asText()
                    : error.asText());
        }
        return body.path("result");
    }

    private void notifyServer(String method) throws McpException {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        post(message, Duration.ofSeconds(10));
    }

    private HttpResponse<String> post(JsonNode message, Duration timeout) throws McpException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(message)));
        } catch (IOException e) {
            throw new McpException("Could not encode an MCP request: " + e.getMessage(), e);
        }
        headers.forEach(request::header);
        String session = sessionId;
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
            return response;
        } catch (ConnectException e) {
            throw new McpException("No MCP server is answering at " + endpoint, e);
        } catch (IOException e) {
            throw new McpException("MCP request to " + endpoint + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted calling " + endpoint, e);
        }
    }

    private String describe(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.hasNonNull("error")) {
                return ": " + (node.get("error").isObject() ? node.get("error").path("message").asText()
                        : node.get("error").asText());
            }
        } catch (IOException ignored) {
            // not JSON
        }
        return ": " + (body.length() > 200 ? body.substring(0, 200) : body);
    }
}
