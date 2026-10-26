package dev.juno.main.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything configurable from the Settings dialog. Persisted as {@code settings.json}.
 *
 * <p>Hierarchy: organisations (GitLab/GitHub credentials, default agent) contain projects (directory, colour,
 * repos, optional agent), which contain tickets (optional agent). Agent profiles are defined once and assigned
 * at any of those levels; the most specific assignment wins.
 */
public final class JunoSettings {

    private List<Organisation> organisations = new ArrayList<>();
    private List<Project> projects = new ArrayList<>();
    private List<AgentProfile> agentProfiles = new ArrayList<>();

    /**
     * The MCP servers listen on localhost ports and hold no credentials (Juno sends each organisation's token
     * with every call). Juno either starts their jars on those ports or connects to servers already running.
     */
    /** Blank = found automatically next to juno-main (see {@link #resolvedMcpJar}); never stored. */
    private String gitlabMcpJar = "";
    private String githubMcpJar = "";
    private McpLaunch gitlabMcpLaunch = McpLaunch.JAR;
    private McpLaunch githubMcpLaunch = McpLaunch.JAR;
    public static final int DEFAULT_GITHUB_MCP_PORT = 9950;
    public static final int DEFAULT_GITLAB_MCP_PORT = 9951;
    private int githubMcpPort = DEFAULT_GITHUB_MCP_PORT;
    private int gitlabMcpPort = DEFAULT_GITLAB_MCP_PORT;
    /** Java executable used to launch the MCP servers; blank means the JVM running Juno. */
    private String javaExecutable = "";
    /** Maximum number of agents running at once across all organisations. */
    private int maxConcurrentAgents = 3;
    /** Open a terminal window with a live agent console whenever an agent starts. */
    private boolean openTerminals = true;
    /**
     * Custom command to open a terminal, with {@code {title}} and {@code {script}} placeholders; blank picks
     * Windows Terminal / cmd, macOS Terminal or a Linux terminal emulator automatically.
     */
    private String terminalCommand = "";
    /** Skill packs given to agents (everywhere, per organisation or per project). */
    private List<SkillEntry> skills = new ArrayList<>();
    /** Bundled packs already added once; a pack the user removed is not added again. */
    private List<String> bundledSkillsOffered = new ArrayList<>();
    /** Where exported design docs (.txt) are written. */
    /** Blank = Documents/Juno/design-docs in your home folder (see {@link #resolvedDesignDocFolder}). */
    private String designDocFolder = "";

    /** Settings for a fresh install: one organisation with one agent profile assigned to it. */
    public static JunoSettings createDefault() {
        JunoSettings s = new JunoSettings();
        AgentProfile agent = new AgentProfile("Default agent");
        Organisation org = new Organisation("Default");
        org.setAgentProfileId(agent.getId());
        s.agentProfiles.add(agent);
        s.organisations.add(org);
        return s;
    }

    /** Repairs references after loading (e.g. projects whose organisation was removed by hand). */
    public List<String> normalise() {
        List<String> notes = new ArrayList<>();
        if (organisations.isEmpty() && !projects.isEmpty()) {
            organisations.add(new Organisation("Default"));
        }
        for (Project p : projects) {
            if (p.hasBothHosts()) {
                // A project lives on GitHub or GitLab, not both: keep the one its organisation has enabled.
                boolean gitlabOnly = organisation(p.getOrgId())
                        .map(o -> o.tracker(TrackerKind.GITLAB).isEnabled() && !o.tracker(TrackerKind.GITHUB).isEnabled())
                        .orElse(false);
                TrackerKind keep = gitlabOnly ? TrackerKind.GITLAB : TrackerKind.GITHUB;
                TrackerKind drop = keep == TrackerKind.GITHUB ? TrackerKind.GITLAB : TrackerKind.GITHUB;
                notes.add("Project '" + p.getName() + "' had both a GitHub repo and a GitLab project; kept "
                        + keep.displayName() + " " + p.location(keep) + " and removed " + drop.displayName() + " "
                        + p.location(drop) + ".");
                p.setRepository(keep, p.location(keep));
            }
            if (organisation(p.getOrgId()).isEmpty()) {
                p.setOrgId(organisations.getFirst().getId());
            }
            if (p.getAgentProfileId() != null && agentProfile(p.getAgentProfileId()).isEmpty()) {
                p.setAgentProfileId(null);
            }
        }
        for (Organisation o : organisations) {
            if (o.getAgentProfileId() != null && agentProfile(o.getAgentProfileId()).isEmpty()) {
                o.setAgentProfileId(null);
            }
        }
        return notes;
    }

    // ---- accessors --------------------------------------------------------------------------

    public List<Organisation> getOrganisations() {
        return organisations;
    }

    public List<Project> getProjects() {
        return projects;
    }

    public List<AgentProfile> getAgentProfiles() {
        return agentProfiles;
    }

    /** The jar as configured; blank means "find it automatically". */
    public String mcpJar(TrackerKind kind) {
        String jar = kind == TrackerKind.GITLAB ? gitlabMcpJar : githubMcpJar;
        return jar == null ? "" : jar;
    }

    /** The jar to start: the configured one, else the server project found next to juno-main. */
    public String resolvedMcpJar(TrackerKind kind) {
        String jar = mcpJar(kind).strip();
        return jar.isEmpty() ? defaultJar(kind == TrackerKind.GITLAB ? "juno-gitlab-mcp" : "juno-github-mcp") : jar;
    }

    /**
     * Blanks stored values that are just the automatic defaults (machine-specific paths written by earlier
     * versions), so settings only hold what you chose. Returns true if anything changed.
     */
    public boolean clearAutomaticDefaults() {
        boolean changed = false;
        if (!gitlabMcpJar.isBlank() && gitlabMcpJar.equals(defaultJar("juno-gitlab-mcp"))) {
            gitlabMcpJar = "";
            changed = true;
        }
        if (!githubMcpJar.isBlank() && githubMcpJar.equals(defaultJar("juno-github-mcp"))) {
            githubMcpJar = "";
            changed = true;
        }
        if (designDocFolder != null && !designDocFolder.isBlank() && designDocFolder.equals(defaultDesignDocFolder())) {
            designDocFolder = "";
            changed = true;
        }
        return changed;
    }

    public void setMcpJar(TrackerKind kind, String jar) {
        if (kind == TrackerKind.GITLAB) {
            gitlabMcpJar = jar;
        } else {
            githubMcpJar = jar;
        }
    }

    public McpLaunch mcpLaunch(TrackerKind kind) {
        McpLaunch l = kind == TrackerKind.GITLAB ? gitlabMcpLaunch : githubMcpLaunch;
        return l == null ? McpLaunch.JAR : l;
    }

    public void setMcpLaunch(TrackerKind kind, McpLaunch launch) {
        if (kind == TrackerKind.GITLAB) {
            gitlabMcpLaunch = launch;
        } else {
            githubMcpLaunch = launch;
        }
    }

    public int mcpPort(TrackerKind kind) {
        int port = kind == TrackerKind.GITLAB ? gitlabMcpPort : githubMcpPort;
        return port > 0 && port <= 65535 ? port
                : kind == TrackerKind.GITLAB ? DEFAULT_GITLAB_MCP_PORT : DEFAULT_GITHUB_MCP_PORT;
    }

    public void setMcpPort(TrackerKind kind, int port) {
        if (kind == TrackerKind.GITLAB) {
            gitlabMcpPort = port;
        } else {
            githubMcpPort = port;
        }
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public int getMaxConcurrentAgents() {
        return maxConcurrentAgents;
    }

    public void setMaxConcurrentAgents(int maxConcurrentAgents) {
        this.maxConcurrentAgents = maxConcurrentAgents;
    }

    public List<SkillEntry> getSkills() {
        if (skills == null) {
            skills = new ArrayList<>();
        }
        return skills;
    }

    public List<String> getBundledSkillsOffered() {
        if (bundledSkillsOffered == null) {
            bundledSkillsOffered = new ArrayList<>();
        }
        return bundledSkillsOffered;
    }

    /** The folder as configured; blank means the default. */
    public String getDesignDocFolder() {
        return designDocFolder == null ? "" : designDocFolder;
    }

    /** Where design docs are exported: the configured folder, else Documents/Juno/design-docs. */
    public Path resolvedDesignDocFolder() {
        String folder = getDesignDocFolder().strip();
        return folder.isEmpty() ? Path.of(defaultDesignDocFolder()) : Path.of(folder);
    }

    public void setDesignDocFolder(String designDocFolder) {
        this.designDocFolder = designDocFolder;
    }

    /** Skill packs that apply to tickets in this project (enabled ones only). */
    public List<SkillEntry> skillsFor(Project project) {
        return getSkills().stream().filter(SkillEntry::isEnabled).filter(e -> e.appliesTo(project)).toList();
    }

    public static String defaultDesignDocFolder() {
        Path home = Path.of(System.getProperty("user.home"));
        Path documents = home.resolve("Documents");
        return (Files.isDirectory(documents) ? documents.resolve("Juno") : home.resolve("Juno"))
                .resolve("design-docs").toString();
    }

    public boolean isOpenTerminals() {
        return openTerminals;
    }

    public void setOpenTerminals(boolean openTerminals) {
        this.openTerminals = openTerminals;
    }

    public String getTerminalCommand() {
        return terminalCommand == null ? "" : terminalCommand;
    }

    public void setTerminalCommand(String terminalCommand) {
        this.terminalCommand = terminalCommand;
    }

    // ---- lookups ----------------------------------------------------------------------------

    public Optional<Organisation> organisation(String id) {
        return id == null ? Optional.empty()
                : organisations.stream().filter(o -> o.getId().equals(id)).findFirst();
    }

    public Optional<Project> project(String id) {
        return id == null ? Optional.empty()
                : projects.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public Optional<AgentProfile> agentProfile(String id) {
        return id == null ? Optional.empty()
                : agentProfiles.stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    public Optional<Organisation> organisationOf(Project project) {
        return project == null ? Optional.empty() : organisation(project.getOrgId());
    }

    public List<Project> projectsIn(String orgId) {
        return projects.stream().filter(p -> p.getOrgId() != null && p.getOrgId().equals(orgId)).toList();
    }

    /**
     * Finds the project in the given organisation whose GitLab project / GitHub repo matches the location
     * (case-insensitive). Matching is scoped to the organisation because repo names can repeat across orgs.
     */
    public Optional<Project> projectForLocation(String orgId, TrackerKind kind, String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        return projectsIn(orgId).stream()
                .filter(p -> p.location(kind).equalsIgnoreCase(location.trim()))
                .findFirst();
    }

    /** The agent for a ticket: its own assignment, else its project's, else its organisation's. */
    public Optional<AgentAssignment> resolveAgent(Ticket ticket) {
        Optional<AgentProfile> own = agentProfile(ticket.getAgentProfileId());
        if (own.isPresent()) {
            return Optional.of(new AgentAssignment(own.get(), AgentAssignment.Level.TICKET, ticket.getTitle()));
        }
        Optional<Project> project = project(ticket.getProjectId());
        if (project.isEmpty()) {
            return Optional.empty();
        }
        return resolveAgent(project.get());
    }

    /** The agent a project's tickets inherit: the project's own, else its organisation's. */
    public Optional<AgentAssignment> resolveAgent(Project project) {
        Optional<AgentProfile> fromProject = agentProfile(project.getAgentProfileId());
        if (fromProject.isPresent()) {
            return Optional.of(new AgentAssignment(fromProject.get(), AgentAssignment.Level.PROJECT,
                    project.getName()));
        }
        return organisationOf(project).flatMap(org -> agentProfile(org.getAgentProfileId())
                .map(a -> new AgentAssignment(a, AgentAssignment.Level.ORGANISATION, org.getName())));
    }

    /**
     * Default jar location assuming the three repos sit side by side, e.g.
     * {@code juno/juno-main} (working dir) and {@code juno/juno-gitlab-mcp/target/juno-gitlab-mcp.jar}.
     */
    public static String defaultJar(String artifact) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path base = cwd; base != null; base = base.getParent()) {
            if (Files.isDirectory(base.resolve(artifact))) {
                return base.resolve(artifact).resolve("target").resolve(artifact + ".jar").toString();
            }
        }
        Path parent = cwd.getParent() == null ? cwd : cwd.getParent();
        return parent.resolve(artifact).resolve("target").resolve(artifact + ".jar").toString();
    }
}
