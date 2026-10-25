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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Talks to GitLab and GitHub exclusively through the juno-gitlab-mcp / juno-github-mcp servers, which listen on
 * localhost ports (see {@link McpServers}). The servers hold no credentials: every call carries the ticket's
 * organisation's token and API URL in its headers, so organisations never share credentials.
 */
public final class IssueTrackerService implements AutoCloseable {

    private record Key(String orgId, TrackerKind kind) {
    }

    private record Connection(McpClient client, java.net.URI endpoint, Map<String, String> headers) {
    }

    private final Supplier<JunoSettings> settings;
    private final McpServers servers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Key, Connection> clients = new HashMap<>();

    public IssueTrackerService(Supplier<JunoSettings> settings, McpServers servers) {
        this.settings = settings;
        this.servers = servers;
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

    /** Connects to (starting if needed) the tracker's server and returns its tool names; Settings' "Test". */
    public List<String> test(Organisation org, TrackerKind kind) throws McpException {
        return client(org, kind).listTools();
    }

    /** Forgets connections (and stops servers Juno started whose port or jar changed) after a settings change. */
    public synchronized void reset() {
        clients.clear();
        servers.reconcile(settings.get());
    }

    @Override
    public synchronized void close() {
        clients.clear();
    }

    private ObjectNode args(TrackerKind kind, String location) throws McpException {
        if (location == null || location.isBlank()) {
            throw new McpException("No " + kind.displayName() + " " + kind.locationHint() + " specified");
        }
        ObjectNode args = mapper.createObjectNode();
        args.put(kind.locationArgument(), location.trim());
        return args;
    }

    private McpClient client(Organisation org, TrackerKind kind) throws McpException {
        requireEnabled(org, kind);
        java.net.URI endpoint = servers.ensure(settings.get(), kind);
        Map<String, String> headers = headers(org, kind);
        Key key = new Key(org.getId(), kind);
        synchronized (this) {
            Connection existing = clients.get(key);
            if (existing != null && existing.endpoint().equals(endpoint) && existing.headers().equals(headers)) {
                return existing.client();
            }
        }
        McpClient client = new McpClient(endpoint, headers);
        client.start();
        synchronized (this) {
            clients.put(key, new Connection(client, endpoint, headers));
        }
        return client;
    }

    public static void requireEnabled(Organisation org, TrackerKind kind) throws McpException {
        if (!org.tracker(kind).isEnabled()) {
            throw new McpException(kind.displayName() + " is not enabled for organisation '" + org.getName()
                    + "'. Enable it in Settings → Organisations & projects.");
        }
    }

    /**
     * Request headers carrying an organisation's credentials to the tracker's MCP server: its token
     * ({@code Authorization: Bearer}) and API / instance URL ({@code X-Api-Url}). These come only from Juno's
     * settings; nothing is read from Juno's own environment except a variable named with {@code env:VAR}.
     */
    public static Map<String, String> headers(Organisation org, TrackerKind kind) {
        TrackerSettings t = org.tracker(kind);
        Map<String, String> headers = new java.util.TreeMap<>();
        String token = t.resolvedToken();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token.strip());
        }
        String url = t.getApiUrl() == null ? "" : t.getApiUrl().strip();
        if (!url.isEmpty()) {
            headers.put("X-Api-Url", url);
        }
        return headers;
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
