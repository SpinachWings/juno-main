package dev.juno.main.model;

import java.util.UUID;

/**
 * A codebase within an organisation: where it lives on disk, its colour on the board, its remote
 * repositories (reached with the organisation's credentials) and, optionally, its own agent profile.
 */
public final class Project {

    private String id = UUID.randomUUID().toString();
    private String orgId;
    private String name = "New project";
    private String path = "";
    private String color = "#4C8BF5";
    private String gitlabProject = "";
    private String githubRepo = "";
    /** Agent profile for this project's tickets; null = inherit from the organisation. */
    private String agentProfileId;
    /** Extra {@code --allowedTools} rules for this codebase (e.g. its build/test commands). */
    private String extraAllowedTools = "";

    public Project() {
    }

    public String getId() {
        return id;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /** Colour as a CSS hex string, e.g. {@code #4C8BF5}. */
    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getGitlabProject() {
        return gitlabProject;
    }

    public void setGitlabProject(String gitlabProject) {
        this.gitlabProject = gitlabProject;
    }

    public String getGithubRepo() {
        return githubRepo;
    }

    public void setGithubRepo(String githubRepo) {
        this.githubRepo = githubRepo;
    }

    public String getAgentProfileId() {
        return agentProfileId;
    }

    public void setAgentProfileId(String agentProfileId) {
        this.agentProfileId = agentProfileId;
    }

    public String getExtraAllowedTools() {
        return extraAllowedTools;
    }

    public void setExtraAllowedTools(String extraAllowedTools) {
        this.extraAllowedTools = extraAllowedTools;
    }

    /** The configured remote location for the given tracker, or an empty string. */
    public String location(TrackerKind kind) {
        String v = kind == TrackerKind.GITLAB ? gitlabProject : githubRepo;
        return v == null ? "" : v.trim();
    }

    @Override
    public String toString() {
        return name;
    }
}
