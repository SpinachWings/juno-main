package dev.juno.main.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.mcp.McpClient;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.model.TrackerSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Talks to GitLab and GitHub exclusively through the juno-gitlab-mcp / juno-github-mcp servers. Each
 * organisation gets its own server process per tracker, holding only that organisation's credentials.
 * Processes start lazily and stay alive until settings change or Juno exits.
 */
public final class IssueTrackerService implements AutoCloseable {

    private record Key(String orgId, TrackerKind kind) {
    }

    private final Supplier<JunoSettings> settings;
    private final Consumer<String> serverLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Key, McpClient> clients = new HashMap<>();

    public IssueTrackerService(Supplier<JunoSettings> settings, Consumer<String> serverLog) {
        this.settings = settings;
        this.serverLog = serverLog;
    }

    public IssueData getIssue(Organisation org, TrackerKind kind, String location, long number,
                              boolean includeComments) throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        args.put("include_comments", includeComments);
        return IssueData.fromJson(client(org, kind).callTool("get_issue", args));
    }

    public List<IssueData> listIssues(Organisation org, TrackerKind kind, String location, String state, int limit)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("state", state);
        args.put("limit", limit);
        List<IssueData> issues = new ArrayList<>();
        for (JsonNode issue : client(org, kind).callTool("list_issues", args).path("issues")) {
            issues.add(IssueData.fromJson(issue));
        }
        return issues;
    }

    public IssueData createIssue(Organisation org, TrackerKind kind, String location, String title, String body)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("title", title);
        args.put("body", body);
        return IssueData.fromJson(client(org, kind).callTool("create_issue", args));
    }

    public IssueData updateIssue(Organisation org, TrackerKind kind, String location, long number, String title,
                                 String body) throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        args.put("title", title);
        args.put("body", body);
        return IssueData.fromJson(client(org, kind).callTool("update_issue", args));
    }

    /**
     * Opens a pull request (GitHub) or merge request (GitLab) from {@code head} into {@code base}, or returns
     * the open one that already exists for that branch.
     */
    public PullRequestRef createPullRequest(Organisation org, TrackerKind kind, String location, String head,
                                            String base, String title, String body) throws McpException {
        ObjectNode args = args(kind, location);
        args.put(kind.headArgument(), head);
        args.put(kind.baseArgument(), base);
        args.put("title", title);
        args.put("body", body);
        JsonNode pr = client(org, kind).callTool(kind.pullRequestTool(), args);
        return new PullRequestRef(kind, location.trim(), pr.path("number").asLong(), pr.path("url").asText(""));
    }

    /** All comments on a pull/merge request. */
    public List<ReviewComment> pullRequestComments(Organisation org, TrackerKind kind, String location, long number)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        List<ReviewComment> comments = new ArrayList<>();
        for (JsonNode c : client(org, kind).callTool(kind.pullRequestCommentsTool(), args).path("comments")) {
            comments.add(ReviewComment.fromJson(c));
        }
        return comments;
    }

    /** Starts (if needed) the organisation's server and returns its tool names; used by Settings' "Test". */
    public List<String> test(Organisation org, TrackerKind kind) throws McpException {
        return client(org, kind).listTools();
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

    private synchronized McpClient client(Organisation org, TrackerKind kind) throws McpException {
        requireEnabled(org, kind);
        Key key = new Key(org.getId(), kind);
        McpClient existing = clients.get(key);
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        JunoSettings s = settings.get();
        McpClient client = new McpClient(command(s, kind), environment(org, kind),
                line -> serverLog.accept("[" + org.getName() + " / " + kind.displayName() + " MCP] " + line));
        try {
            client.start();
        } catch (McpException e) {
            client.close();
            throw e;
        }
        clients.put(key, client);
        return client;
    }

    public static void requireEnabled(Organisation org, TrackerKind kind) throws McpException {
        if (!org.tracker(kind).isEnabled()) {
            throw new McpException(kind.displayName() + " is not enabled for organisation '" + org.getName()
                    + "'. Enable it in Settings → Organisations & projects.");
        }
    }

    /** Command line used to launch the MCP server for a tracker. Also used for agents' MCP config. */
    public static List<String> command(JunoSettings s, TrackerKind kind) throws McpException {
        String jar = s.mcpJar(kind);
        if (jar == null || jar.isBlank() || !Files.isRegularFile(Path.of(jar))) {
            String artifact = kind == TrackerKind.GITLAB ? "juno-gitlab-mcp" : "juno-github-mcp";
            throw new McpException(kind.displayName() + " MCP server jar not found"
                    + (jar == null || jar.isBlank() ? "" : " at " + jar)
                    + ". Build it with 'mvn package' in " + artifact
                    + " and set its path in Settings → General.");
        }
        return List.of(javaExecutable(s), "-jar", jar);
    }

    /**
     * Environment for an organisation's MCP server process. A blank token means "inherit GITLAB_TOKEN /
     * GITHUB_TOKEN from Juno's environment"; {@code env:VAR} reads the token from another variable.
     */
    public static Map<String, String> environment(Organisation org, TrackerKind kind) {
        TrackerSettings t = org.tracker(kind);
        Map<String, String> env = new HashMap<>();
        String urlVar = kind == TrackerKind.GITLAB ? "GITLAB_URL" : "GITHUB_API_URL";
        String tokenVar = kind == TrackerKind.GITLAB ? "GITLAB_TOKEN" : "GITHUB_TOKEN";
        if (t.getApiUrl() != null && !t.getApiUrl().isBlank()) {
            env.put(urlVar, t.getApiUrl().trim());
        }
        String token = t.resolvedToken();
        if (token != null) {
            env.put(tokenVar, token);
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
