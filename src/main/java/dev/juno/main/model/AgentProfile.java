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
    public static final int DEFAULT_MAX_TURNS = 150;
    public static final int DEFAULT_MAX_MINUTES = 60;
    /** Turns before a run stops (Claude Code's {@code --max-turns}); caps runaway runs and their cost. */
    private int maxTurns = DEFAULT_MAX_TURNS;
    /** Minutes before Juno stops a run that is still going. */
    private int maxMinutes = DEFAULT_MAX_MINUTES;
    /** Optional API key (or {@code env:VAR}); when blank the CLI's own login / environment is used. */
    private String apiKey = "";
    /** Optional {@code ANTHROPIC_BASE_URL}, e.g. for a gateway or proxy instance. */
    private String baseUrl = "";
    /** Additional raw CLI arguments, whitespace separated. */
    private String extraArgs = "";

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

    /** Always a limit: profiles saved with 0 ("no limit") by earlier versions get the default. */
    public int getMaxTurns() {
        return maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;
    }

    public int getMaxMinutes() {
        return maxMinutes > 0 ? maxMinutes : DEFAULT_MAX_MINUTES;
    }

    public void setMaxMinutes(int maxMinutes) {
        this.maxMinutes = maxMinutes;
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



    /** A copy with a new id and the given name. */
    public AgentProfile duplicate(String newName) {
        AgentProfile copy = new AgentProfile(newName);
        copy.cliPath = cliPath;
        copy.model = model;
        copy.permissionMode = permissionMode;
        copy.allowedTools = allowedTools;
        copy.maxTurns = maxTurns;
        copy.maxMinutes = maxMinutes;
        copy.apiKey = apiKey;
        copy.baseUrl = baseUrl;
        copy.extraArgs = extraArgs;
        return copy;
    }

    @Override
    public String toString() {
        return name;
    }
}
