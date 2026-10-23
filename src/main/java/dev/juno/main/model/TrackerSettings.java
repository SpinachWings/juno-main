package dev.juno.main.model;

/** Connection settings for one tracker and the MCP server jar that talks to it. */
public final class TrackerSettings {

    /** GitLab instance URL or GitHub API URL. */
    private String apiUrl = "";
    /** Access token; when blank the MCP server inherits GITLAB_TOKEN / GITHUB_TOKEN from Juno's environment. */
    private String token = "";
    /** Path to the built MCP server jar (juno-gitlab-mcp.jar / juno-github-mcp.jar). */
    private String mcpJar = "";

    public TrackerSettings() {
    }

    public TrackerSettings(String apiUrl, String mcpJar) {
        this.apiUrl = apiUrl;
        this.mcpJar = mcpJar;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMcpJar() {
        return mcpJar;
    }

    public void setMcpJar(String mcpJar) {
        this.mcpJar = mcpJar;
    }
}
