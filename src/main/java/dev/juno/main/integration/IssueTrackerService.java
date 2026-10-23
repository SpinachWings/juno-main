package dev.juno.main.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.mcp.McpClient;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.model.TrackerSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Talks to GitLab and GitHub exclusively through the juno-gitlab-mcp / juno-github-mcp servers, which are
 * started lazily as child processes and kept alive until settings change or Juno exits.
 */
public final class IssueTrackerService implements AutoCloseable {

    private final Supplier<JunoSettings> settings;
    private final Consumer<String> serverLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<TrackerKind, McpClient> clients = new EnumMap<>(TrackerKind.class);

    public IssueTrackerService(Supplier<JunoSettings> settings, Consumer<String> serverLog) {
        this.settings = settings;
        this.serverLog = serverLog;
    }

    public IssueData getIssue(TrackerKind kind, String location, long number, boolean includeComments)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        args.put("include_comments", includeComments);
        return IssueData.fromJson(client(kind).callTool("get_issue", args));
    }

    public List<IssueData> listIssues(TrackerKind kind, String location, String state, int limit)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("state", state);
        args.put("limit", limit);
        List<IssueData> issues = new ArrayList<>();
        for (JsonNode issue : client(kind).callTool("list_issues", args).path("issues")) {
            issues.add(IssueData.fromJson(issue));
        }
        return issues;
    }

    public IssueData createIssue(TrackerKind kind, String location, String title, String body) throws McpException {
        ObjectNode args = args(kind, location);
        args.put("title", title);
        args.put("body", body);
        return IssueData.fromJson(client(kind).callTool("create_issue", args));
    }

    public IssueData updateIssue(TrackerKind kind, String location, long number, String title, String body)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        args.put("title", title);
        args.put("body", body);
        return IssueData.fromJson(client(kind).callTool("update_issue", args));
    }

    /** Starts (if needed) the server and returns the tool names it exposes; used by Settings' "Test". */
    public List<String> test(TrackerKind kind) throws McpException {
        return client(kind).listTools();
    }

    /** Stops running servers so the next call picks up new settings. */
    public synchronized void reset() {
        clients.values().forEach(McpClient::close);
        clients.clear();
    }

    @Override
    public void close() {
        reset();
    }

    private ObjectNode args(TrackerKind kind, String location) throws McpException {
        if (location == null || location.isBlank()) {
            throw new McpException("No " + kind.displayName() + " " + kind.locationHint() + " specified");
        }
        ObjectNode args = mapper.createObjectNode();
        args.put(kind.locationArgument(), location.trim());
        return args;
    }

    private synchronized McpClient client(TrackerKind kind) throws McpException {
        McpClient existing = clients.get(kind);
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        JunoSettings s = settings.get();
        McpClient client = new McpClient(command(s, kind), environment(s, kind),
                line -> serverLog.accept("[" + kind.displayName() + " MCP] " + line));
        try {
            client.start();
        } catch (McpException e) {
            client.close();
            throw e;
        }
        clients.put(kind, client);
        return client;
    }

    /** Command line used to launch the MCP server for a tracker. Also used for agents' MCP config. */
    public static List<String> command(JunoSettings s, TrackerKind kind) throws McpException {
        String jar = s.tracker(kind).getMcpJar();
        if (jar == null || jar.isBlank() || !Files.isRegularFile(Path.of(jar))) {
            String artifact = kind == TrackerKind.GITLAB ? "juno-gitlab-mcp" : "juno-github-mcp";
            throw new McpException(kind.displayName() + " MCP server jar not found"
                    + (jar == null || jar.isBlank() ? "" : " at " + jar)
                    + ". Build it with 'mvn package' in " + artifact
                    + " and set its path in Settings → Integrations.");
        }
        return List.of(javaExecutable(s), "-jar", jar);
    }

    /** Environment passed to the MCP server process. Blank token means "inherit from Juno's environment". */
    public static Map<String, String> environment(JunoSettings s, TrackerKind kind) {
        TrackerSettings t = s.tracker(kind);
        Map<String, String> env = new HashMap<>();
        String urlVar = kind == TrackerKind.GITLAB ? "GITLAB_URL" : "GITHUB_API_URL";
        String tokenVar = kind == TrackerKind.GITLAB ? "GITLAB_TOKEN" : "GITHUB_TOKEN";
        if (t.getApiUrl() != null && !t.getApiUrl().isBlank()) {
            env.put(urlVar, t.getApiUrl().trim());
        }
        if (t.getToken() != null && !t.getToken().isBlank()) {
            env.put(tokenVar, t.getToken().trim());
        }
        return env;
    }

    public static String javaExecutable(JunoSettings s) {
        if (s.getJavaExecutable() != null && !s.getJavaExecutable().isBlank()) {
            return s.getJavaExecutable().trim();
        }
        return ProcessHandle.current().info().command().orElse("java");
    }
}
