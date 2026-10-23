package dev.juno.main.model;

import java.util.List;
import java.util.UUID;

/**
 * A named agent configuration: which Claude instance and model to run, and how. Profiles are assigned to
 * organisations, projects and tickets; the most specific assignment wins (ticket, then project, then org).
 *
 * <p>Agents are Claude Code processes started in headless ({@code -p}) mode in the project directory.
 */
public final class AgentProfile {

    /** Suggested model identifiers; the field is free text so any model or alias can be used. */
    public static final List<String> SUGGESTED_MODELS = List.of(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1",
            "opus", "sonnet", "haiku");

    public static final List<String> PERMISSION_MODES =
            List.of("acceptEdits", "default", "plan", "bypassPermissions");

    private String id = UUID.randomUUID().toString();
    private String name = "New agent";
    /** Claude Code executable: a name on PATH or an absolute path. */
    private String cliPath = "claude";
    private String model = "claude-sonnet-5-5";
    private String permissionMode = "acceptEdits";
    /** Space- or comma-separated tool rules passed to {@code --allowedTools}, e.g. {@code Bash(mvn test:*)}. */
    private String allowedTools = "";
    /** 0 means no limit. */
    private int maxTurns = 0;
    /** Optional API key (or {@code env:VAR}); when blank the CLI's own login / environment is used. */
    private String apiKey = "";
    /** Optional {@code ANTHROPIC_BASE_URL}, e.g. for a gateway or proxy instance. */
    private String baseUrl = "";
    /** Additional raw CLI arguments, whitespace separated. */
    private String extraArgs = "";
    /** Give agents their organisation's GitLab/GitHub MCP servers so they can read and comment on issues. */
    private boolean exposeTrackerMcp = false;

    public AgentProfile() {
    }

    public AgentProfile(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCliPath() {
        return cliPath;
    }

    public void setCliPath(String cliPath) {
        this.cliPath = cliPath;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public String getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(String extraArgs) {
        this.extraArgs = extraArgs;
    }

    public boolean isExposeTrackerMcp() {
        return exposeTrackerMcp;
    }

    public void setExposeTrackerMcp(boolean exposeTrackerMcp) {
        this.exposeTrackerMcp = exposeTrackerMcp;
    }

    /** A copy with a new id and the given name. */
    public AgentProfile duplicate(String newName) {
        AgentProfile copy = new AgentProfile(newName);
        copy.cliPath = cliPath;
        copy.model = model;
        copy.permissionMode = permissionMode;
        copy.allowedTools = allowedTools;
        copy.maxTurns = maxTurns;
        copy.apiKey = apiKey;
        copy.baseUrl = baseUrl;
        copy.extraArgs = extraArgs;
        copy.exposeTrackerMcp = exposeTrackerMcp;
        return copy;
    }

    @Override
    public String toString() {
        return name;
    }
}
