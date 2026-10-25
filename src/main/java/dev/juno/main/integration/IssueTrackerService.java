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

    /** Posts a comment (the AI review summary) on a pull/merge request. */
    public void commentOnPullRequest(Organisation org, TrackerKind kind, String location, long number, String body)
            throws McpException {
        ObjectNode args = args(kind, location);
        args.put("number", number);
        args.put("body", body);
        client(org, kind).callTool(kind.pullRequestCommentTool(), args);
    }

    /** Result of {@link #checkRepository}. */
    public record RepositoryCheck(boolean exists, String defaultBranch, Boolean branchExists) {
    }

    /** Read-only check that a repository (and optionally a branch in it) exists and the token can see it. */
    public RepositoryCheck checkRepository(Organisation org, TrackerKind kind, String location, String branch)
            throws McpException {
        ObjectNode args = args(kind, location);
        if (branch != null && !branch.isBlank()) {
            args.put("branch", branch.trim());
        }
        JsonNode r = client(org, kind).callTool("check_repository", args);
        return new RepositoryCheck(r.path("exists").asBoolean(false), r.path("defaultBranch").asText(""),
                r.has("branchExists") ? r.get("branchExists").asBoolean() : null);
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

    /**
     * Command line used to launch the MCP server for a tracker. Also used for agents' MCP config. If the jar
     * hasn't been built yet but its project is next to it (the usual {@code juno/<server>/target/<server>.jar}
     * layout), it is built with Maven first, so the servers need no setup.
     */
    public static List<String> command(JunoSettings s, TrackerKind kind) throws McpException {
        String jar = s.mcpJar(kind);
        String artifact = kind == TrackerKind.GITLAB ? "juno-gitlab-mcp" : "juno-github-mcp";
        if (jar == null || jar.isBlank()) {
            throw new McpException("No path is set for the " + kind.displayName() + " MCP server (" + artifact
                    + ".jar). Set it in Settings → General.");
        }
        Path jarPath = Path.of(jar.trim());
        if (!Files.isRegularFile(jarPath)) {
            McpJars.build(jarPath, artifact);
        }
        return List.of(javaExecutable(s), "-jar", jarPath.toString());
    }

    /**
     * Environment for an organisation's MCP server process: the organisation's token and API URL from Juno's
     * settings, and nothing inherited. Every variable the servers read is set explicitly (blank when unset), so
     * a GITHUB_TOKEN / GITLAB_TOKEN in Juno's own environment can never leak into another organisation's server.
     * {@code env:VAR} as the token reads it from a variable you name, which is still configured in Juno.
     */
    public static Map<String, String> environment(Organisation org, TrackerKind kind) {
        TrackerSettings t = org.tracker(kind);
        Map<String, String> env = new HashMap<>();
        String url = t.getApiUrl() == null ? "" : t.getApiUrl().trim();
        String token = t.resolvedToken();
        if (kind == TrackerKind.GITLAB) {
            env.put("GITLAB_URL", url.isEmpty() ? "https://gitlab.com" : url);
            env.put("GITLAB_TOKEN", token == null ? "" : token);
        } else {
            env.put("GITHUB_API_URL", url.isEmpty() ? "https://api.github.com" : url);
            env.put("GITHUB_TOKEN", token == null ? "" : token);
            env.put("GH_TOKEN", "");
        }
        return env;
    }

    /** True if the organisation has a token for the tracker (directly or via {@code env:VAR}). */
    public static boolean hasToken(Organisation org, TrackerKind kind) {
        String token = org.tracker(kind).resolvedToken();
        return token != null && !token.isBlank();
    }

    public static String javaExecutable(JunoSettings s) {
        if (s.getJavaExecutable() != null && !s.getJavaExecutable().isBlank()) {
            return s.getJavaExecutable().trim();
        }
        return ProcessHandle.current().info().command().orElse("java");
    }
}
