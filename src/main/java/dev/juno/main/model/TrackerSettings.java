package dev.juno.main.model;

/** One organisation's connection to GitLab or GitHub. */
public final class TrackerSettings {

    private boolean enabled;
    /** GitLab instance URL or GitHub API URL. */
    private String apiUrl = "";
    /**
     * Access token, or {@code env:VARIABLE} to read it from an environment variable you name. When blank the MCP
     * server runs without a token (public data only); nothing is inherited from Juno's environment.
     */
    private String token = "";

    public TrackerSettings() {
    }

    public TrackerSettings(boolean enabled, String apiUrl) {
        this.enabled = enabled;
        this.apiUrl = apiUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    /** The token with {@code env:VAR} references resolved; null if blank or the variable is unset. */
    public String resolvedToken() {
        return Secrets.resolve(token);
    }
}
