package dev.juno.main.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything configurable from the Settings dialog. Persisted as {@code settings.json}. */
public final class JunoSettings {

    private List<Project> projects = new ArrayList<>();
    private AgentSettings agent = new AgentSettings();
    private TrackerSettings gitlab = new TrackerSettings("https://gitlab.com", defaultJar("juno-gitlab-mcp"));
    private TrackerSettings github = new TrackerSettings("https://api.github.com", defaultJar("juno-github-mcp"));
    /** Java executable used to launch the MCP servers; blank means the JVM running Juno. */
    private String javaExecutable = "";

    public List<Project> getProjects() {
        return projects;
    }

    public void setProjects(List<Project> projects) {
        this.projects = projects;
    }

    public AgentSettings getAgent() {
        return agent;
    }

    public TrackerSettings tracker(TrackerKind kind) {
        return kind == TrackerKind.GITLAB ? gitlab : github;
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public Optional<Project> project(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return projects.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    /** Finds the project whose GitLab project / GitHub repo matches the given location (case-insensitive). */
    public Optional<Project> projectForLocation(TrackerKind kind, String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        return projects.stream()
                .filter(p -> p.location(kind).equalsIgnoreCase(location.trim()))
                .findFirst();
    }

    /**
     * Default jar location assuming the three repos sit side by side, e.g.
     * {@code juno/juno-main} (working dir) and {@code juno/juno-gitlab-mcp/target/juno-gitlab-mcp.jar}.
     */
    private static String defaultJar(String artifact) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path base = cwd; base != null; base = base.getParent()) {
            Path candidate = base.resolve(artifact).resolve("target").resolve(artifact + ".jar");
            if (Files.isDirectory(base.resolve(artifact))) {
                return candidate.toString();
            }
        }
        Path parent = cwd.getParent() == null ? cwd : cwd.getParent();
        return parent.resolve(artifact).resolve("target").resolve(artifact + ".jar").toString();
    }
}
