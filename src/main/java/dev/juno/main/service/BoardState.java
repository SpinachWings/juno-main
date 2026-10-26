package dev.juno.main.service;

import dev.juno.main.agent.AgentRunner;
import dev.juno.main.git.GitWorkspace;
import dev.juno.main.integration.IssueTrackerService;
import dev.juno.main.integration.McpServers;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.store.JunoStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State shared by the board's services: tickets, settings, the agent runner, the GitHub/GitLab connections and
 * what is in progress. This object is also the lock: ticket and settings changes happen while holding it, and
 * network or process work never does.
 */
final class BoardState {

    final JunoStore store;
    final JunoStore.BoardData board;
    /** Current settings; replaced (not edited) when Settings are saved. */
    JunoSettings settings;
    /** Set once by {@link BoardService} (the runner reports to {@link AgentRuns}). */
    AgentRunner runner;
    final McpServers mcpServers;
    final IssueTrackerService trackers;
    final List<BoardService.ChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    final List<BoardService.LogListener> logListeners = new CopyOnWriteArrayList<>();
    /** Recent output of the MCP servers, for troubleshooting. */
    final List<String> trackerLog = new CopyOnWriteArrayList<>();
    /** Tickets whose branch is being prepared before an agent starts. */
    final Set<String> preparing = new HashSet<>();
    /** Tickets being delivered (push + PR / review / merge). */
    final Set<String> delivering = ConcurrentHashMap.newKeySet();
    /** When each in-progress AI review started (comments after this are "new" next time). */
    final Map<String, Instant> reviewStarts = new ConcurrentHashMap<>();
    /** Journal of each running agent. */
    final Map<String, Path> journals = new ConcurrentHashMap<>();

    BoardState(JunoStore store) {
        this.store = store;
        this.settings = store.loadSettings();
        this.board = store.loadBoard();
        this.mcpServers = new McpServers(this::addTrackerLog);
        this.trackers = new IssueTrackerService(this::currentSettings, mcpServers);
    }

    synchronized JunoSettings currentSettings() {
        return settings;
    }

    // ---- changes and logs ---------------------------------------------------------------------

    void fireChanged() {
        changeListeners.forEach(BoardService.ChangeListener::boardChanged);
    }

    /** Appends to the ticket's agent log (shown in Log and the terminal). */
    void log(String ticketId, String line) {
        store.appendLog(ticketId, line);
        logListeners.forEach(l -> l.logged(ticketId, line));
    }

    void addTrackerLog(String line) {
        System.err.println(line);
        trackerLog.add(line);
        while (trackerLog.size() > 200) {
            trackerLog.removeFirst();
        }
    }

    /** Saves the board. Caller holds the lock. */
    void persist() {
        store.saveBoard(board);
    }

    // ---- lookups (caller holds the lock) ------------------------------------------------------

    /** The live ticket (not a snapshot), if it still exists. */
    Optional<Ticket> find(String ticketId) {
        return board.getTickets().stream().filter(t -> t.getId().equals(ticketId)).findFirst();
    }

    Ticket require(String ticketId) throws JunoException {
        return find(ticketId).orElseThrow(() -> new JunoException("The ticket no longer exists"));
    }

    Optional<Project> projectOf(Ticket ticket) {
        return settings.project(ticket.getProjectId());
    }

    Optional<Organisation> organisationOf(Ticket ticket) {
        return projectOf(ticket).flatMap(settings::organisationOf);
    }

    Organisation requireOrganisation(String orgId) throws JunoException {
        synchronized (this) {
            return settings.organisation(orgId).orElseThrow(() -> new JunoException("Choose an organisation"));
        }
    }

    Project requireProject(Ticket t) throws JunoException {
        Project project = settings.project(t.getProjectId())
                .orElseThrow(() -> new JunoException(name(t) + " has no project. Tickets must have a project "
                        + "to run: open its Details and choose one."));
        if (project.getPath() == null || project.getPath().isBlank()
                || !Files.isDirectory(Path.of(project.getPath()))) {
            throw new JunoException("Project '" + project.getName() + "' has no valid directory. "
                    + "Set it in Settings → Organisations & projects.");
        }
        return project;
    }

    Project requireProjectUnchecked(Ticket t) {
        return settings.project(t.getProjectId()).orElseThrow();
    }

    /** Whether an agent is starting, running or being delivered for the ticket. */
    boolean busy(String ticketId) {
        synchronized (this) {
            return preparing.contains(ticketId) || runner.isRunning(ticketId) || delivering.contains(ticketId);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A ticket as named in messages: its title in quotes. */
    static String name(Ticket t) {
        return t.getTitle() == null || t.getTitle().isBlank() ? "This ticket" : "'" + t.getTitle().strip() + "'";
    }

    String nameOf(String ticketId) {
        synchronized (this) {
            return find(ticketId).map(BoardState::name).orElse("The ticket");
        }
    }

    GitWorkspace git(String ticketId) {
        return new GitWorkspace("git", line -> log(ticketId, line));
    }

    static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(iso);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
