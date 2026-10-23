package dev.juno.main.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.agent.AgentOutcome;
import dev.juno.main.agent.AgentRunner;
import dev.juno.main.agent.PromptBuilder;
import dev.juno.main.integration.IssueData;
import dev.juno.main.integration.IssueTrackerService;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.AgentSettings;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.store.JunoStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The orchestrator's state and operations: tickets, settings, agent lifecycle and tracker sync.
 * Thread-safe; listeners are called on whichever thread caused the change, so UI code must marshal.
 *
 * <p>Network and process work is never done while holding this object's lock.
 */
public final class BoardService implements AgentRunner.Listener, AutoCloseable {

    public interface ChangeListener {
        void boardChanged();
    }

    public interface LogListener {
        void logged(String ticketId, String line);
    }

    static final String INTERRUPTED_QUESTION = "Juno was closed while the agent was working on this ticket. "
            + "Reply to resume the agent's session, or move the ticket back to Pending.";

    private final JunoStore store;
    private final JunoStore.BoardData board;
    private JunoSettings settings;
    private final AgentRunner runner;
    private final IssueTrackerService trackers;
    private final List<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final List<String> trackerLog = new CopyOnWriteArrayList<>();

    public BoardService(JunoStore store) {
        this.store = store;
        this.settings = store.loadSettings();
        this.board = store.loadBoard();
        this.runner = new AgentRunner(this);
        this.trackers = new IssueTrackerService(this::settings, this::trackerLog);
        recoverInterruptedWork();
    }

    // ---- listeners --------------------------------------------------------------------------

    public void addChangeListener(ChangeListener l) {
        changeListeners.add(l);
    }

    public void addLogListener(LogListener l) {
        logListeners.add(l);
    }

    public void removeLogListener(LogListener l) {
        logListeners.remove(l);
    }

    private void fireChanged() {
        changeListeners.forEach(ChangeListener::boardChanged);
    }

    // ---- queries ----------------------------------------------------------------------------

    public synchronized List<Ticket> tickets() {
        return List.copyOf(board.getTickets());
    }

    public synchronized JunoSettings settings() {
        return settings;
    }

    public synchronized Optional<Ticket> ticket(String id) {
        return board.getTickets().stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    public synchronized Optional<Project> projectOf(Ticket ticket) {
        return settings.project(ticket.getProjectId());
    }

    public boolean isAgentRunning(String ticketId) {
        return runner.isRunning(ticketId);
    }

    public int runningAgents() {
        return runner.runningCount();
    }

    public String readLog(String ticketId) {
        return store.readLog(ticketId);
    }

    public JunoStore store() {
        return store;
    }

    /** Recent stderr output from the MCP servers, for troubleshooting. */
    public List<String> trackerLog() {
        return List.copyOf(trackerLog);
    }

    // ---- ticket & settings mutations --------------------------------------------------------

    public Ticket createTicket(String title, String description, String projectId) {
        Ticket ticket;
        synchronized (this) {
            int n = board.getNextTicketNumber();
            board.setNextTicketNumber(n + 1);
            ticket = new Ticket("JUNO-" + n, title, description);
            ticket.setProjectId(projectId);
            board.getTickets().add(ticket);
            persist();
        }
        fireChanged();
        return ticket;
    }

    /** Persists edits made directly to a ticket object obtained from this service. */
    public void saveTicket(Ticket ticket) {
        synchronized (this) {
            ticket.touch();
            persist();
        }
        fireChanged();
    }

    public void deleteTicket(String ticketId) {
        runner.stop(ticketId);
        synchronized (this) {
            board.getTickets().removeIf(t -> t.getId().equals(ticketId));
            persist();
        }
        fireChanged();
    }

    /** Manually moves a ticket between columns (not into or out of Working while an agent runs). */
    public void move(String ticketId, TicketStatus target) throws JunoException {
        if (target == TicketStatus.WORKING) {
            throw new JunoException("Use Start to put a ticket into Working");
        }
        if (runner.isRunning(ticketId)) {
            throw new JunoException("Stop the running agent before moving " + ticketId);
        }
        synchronized (this) {
            Ticket t = require(ticketId);
            t.setStatus(target);
            if (target != TicketStatus.AWAITING_DECISION) {
                t.setPendingQuestion(null);
            }
            t.touch();
            persist();
        }
        fireChanged();
    }

    public void updateSettings(JunoSettings newSettings) {
        synchronized (this) {
            settings = newSettings;
            store.saveSettings(newSettings);
        }
        trackers.reset();
        fireChanged();
    }

    // ---- agents -----------------------------------------------------------------------------

    public void startWork(String ticketId) throws JunoException {
        AgentRunner.Request request;
        synchronized (this) {
            Ticket t = require(ticketId);
            Project project = requireProject(t);
            checkCapacity();
            Path mcpConfig = writeAgentMcpConfig();
            String prompt = PromptBuilder.initial(t, project, mcpConfig != null);
            request = new AgentRunner.Request(t.getId(), Path.of(project.getPath()), prompt, null,
                    store.copy(settings.getAgent(), AgentSettings.class), mcpConfig);
        }
        launch(request);
    }

    /** Answers an agent's question (or retries after a failure / interruption) and resumes its session. */
    public void respond(String ticketId, String answer) throws JunoException {
        if (answer == null || answer.isBlank()) {
            throw new JunoException("Enter a response for the agent");
        }
        AgentRunner.Request request;
        synchronized (this) {
            Ticket t = require(ticketId);
            Project project = requireProject(t);
            checkCapacity();
            Path mcpConfig = writeAgentMcpConfig();
            String session = t.getAgentSessionId();
            String prompt = session != null
                    ? PromptBuilder.resume(t, answer)
                    : PromptBuilder.initial(t, project, mcpConfig != null)
                            + "\n## Additional guidance from a human\n" + answer.strip() + "\n";
            request = new AgentRunner.Request(t.getId(), Path.of(project.getPath()), prompt, session,
                    store.copy(settings.getAgent(), AgentSettings.class), mcpConfig);
        }
        launch(request);
    }

    public void stopWork(String ticketId) {
        runner.stop(ticketId);
    }

    private void launch(AgentRunner.Request request) throws JunoException {
        if (runner.isRunning(request.ticketId())) {
            throw new JunoException(request.ticketId() + " already has a running agent");
        }
        TicketStatus previousStatus;
        String previousQuestion;
        // Mark as Working before the process starts so a very fast finish can't be overwritten.
        synchronized (this) {
            Ticket t = require(request.ticketId());
            previousStatus = t.getStatus();
            previousQuestion = t.getPendingQuestion();
            t.setStatus(TicketStatus.WORKING);
            t.setPendingQuestion(null);
            t.touch();
            persist();
        }
        try {
            runner.start(request);
        } catch (IOException | IllegalStateException e) {
            synchronized (this) {
                ticket(request.ticketId()).ifPresent(t -> {
                    t.setStatus(previousStatus);
                    t.setPendingQuestion(previousQuestion);
                });
                persist();
            }
            fireChanged();
            throw new JunoException(e.getMessage(), e);
        }
        fireChanged();
    }

    @Override
    public void log(String ticketId, String line) {
        store.appendLog(ticketId, line);
        logListeners.forEach(l -> l.logged(ticketId, line));
    }

    @Override
    public void finished(String ticketId, AgentOutcome outcome) {
        synchronized (this) {
            Optional<Ticket> found = board.getTickets().stream().filter(t -> t.getId().equals(ticketId)).findFirst();
            if (found.isEmpty()) {
                return; // deleted while running
            }
            Ticket t = found.get();
            if (outcome.sessionId() != null) {
                t.setAgentSessionId(outcome.sessionId());
            }
            switch (outcome.kind()) {
                case COMPLETED -> {
                    t.setStatus(TicketStatus.AI_REVIEW);
                    t.setLastSummary(outcome.message());
                    t.setPendingQuestion(null);
                }
                case NEEDS_DECISION -> {
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion(outcome.message());
                }
                case FAILED -> {
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion("The agent failed: " + outcome.message()
                            + "\n\nReply with guidance to retry, or move the ticket back to Pending.");
                }
                case CANCELLED -> {
                    t.setStatus(TicketStatus.PENDING);
                    t.setPendingQuestion(null);
                }
            }
            t.touch();
            persist();
        }
        fireChanged();
    }

    private void recoverInterruptedWork() {
        boolean changed = false;
        for (Ticket t : board.getTickets()) {
            if (t.getStatus() == TicketStatus.WORKING) {
                t.setStatus(TicketStatus.AWAITING_DECISION);
                t.setPendingQuestion(INTERRUPTED_QUESTION);
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private void checkCapacity() throws JunoException {
        int max = Math.max(1, settings.getAgent().getMaxConcurrentAgents());
        if (runner.runningCount() >= max) {
            throw new JunoException(max + " agent(s) already running (limit set in Settings → Agent)");
        }
    }

    /** Writes the MCP config that gives agents the tracker tools, or returns null when disabled. */
    private Path writeAgentMcpConfig() throws JunoException {
        if (!settings.getAgent().isExposeTrackerMcp()) {
            return null;
        }
        ObjectMapper mapper = store.mapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (TrackerKind kind : TrackerKind.values()) {
            List<String> command;
            try {
                command = IssueTrackerService.command(settings, kind);
            } catch (McpException e) {
                continue; // jar not built/configured; skip this tracker
            }
            ObjectNode server = servers.putObject(kind == TrackerKind.GITLAB ? "juno-gitlab" : "juno-github");
            server.put("type", "stdio");
            server.put("command", command.getFirst());
            ArrayNode args = server.putArray("args");
            command.subList(1, command.size()).forEach(args::add);
            ObjectNode env = server.putObject("env");
            for (Map.Entry<String, String> e : IssueTrackerService.environment(settings, kind).entrySet()) {
                env.put(e.getKey(), e.getValue());
            }
        }
        if (servers.isEmpty()) {
            return null;
        }
        Path file = store.home().resolve("agent-mcp.json");
        try {
            Files.write(file, mapper.writeValueAsBytes(root));
        } catch (IOException e) {
            throw new JunoException("Could not write agent MCP config: " + e.getMessage(), e);
        }
        return file;
    }

    // ---- tracker sync -----------------------------------------------------------------------

    /** Suggested GitLab project / GitHub repo for a ticket: its existing link, else its project's setting. */
    public synchronized String defaultLocation(Ticket ticket, TrackerKind kind) {
        ExternalRef ref = ticket.getRef(kind);
        if (ref != null) {
            return ref.location();
        }
        return projectOf(ticket).map(p -> p.location(kind)).orElse("");
    }

    public List<IssueData> listIssues(TrackerKind kind, String location) throws JunoException {
        try {
            return trackers.listIssues(kind, location, "open", 100);
        } catch (McpException e) {
            throw new JunoException(e.getMessage(), e);
        }
    }

    /**
     * Replaces the ticket's title and description with the remote issue's, links the issue and, when a
     * project is configured for that GitLab project / GitHub repo, links the ticket to it.
     */
    public void importIssue(String ticketId, TrackerKind kind, String location, long number) throws JunoException {
        IssueData issue;
        try {
            issue = trackers.getIssue(kind, location, number, false);
        } catch (McpException e) {
            throw new JunoException("Import from " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        synchronized (this) {
            Ticket t = require(ticketId);
            t.setTitle(issue.title());
            t.setDescription(issue.body());
            t.setRef(kind, new ExternalRef(location.trim(), issue.number(), issue.url(), Instant.now().toString()));
            settings.projectForLocation(kind, location).ifPresent(p -> t.setProjectId(p.getId()));
            t.touch();
            persist();
        }
        fireChanged();
    }

    /** Creates a new Pending ticket from a remote issue. */
    public Ticket importAsNewTicket(TrackerKind kind, String location, long number) throws JunoException {
        Ticket ticket = createTicket("Importing " + kind.shortName() + " #" + number + "…", "", null);
        try {
            importIssue(ticket.getId(), kind, location, number);
        } catch (JunoException e) {
            deleteTicket(ticket.getId());
            throw e;
        }
        return ticket;
    }

    /**
     * Uploads the ticket's title and description: updates the linked issue if there is one, otherwise
     * creates an issue in {@code location} and links it.
     */
    public ExternalRef upload(String ticketId, TrackerKind kind, String location) throws JunoException {
        String title;
        String body;
        ExternalRef existing;
        synchronized (this) {
            Ticket t = require(ticketId);
            title = t.getTitle();
            body = t.getDescription();
            existing = t.getRef(kind);
        }
        if (title == null || title.isBlank()) {
            throw new JunoException("The ticket needs a title before it can be uploaded");
        }
        IssueData issue;
        String target = existing != null ? existing.location() : location;
        try {
            issue = existing != null
                    ? trackers.updateIssue(kind, existing.location(), existing.number(), title, body)
                    : trackers.createIssue(kind, location, title, body);
        } catch (McpException e) {
            throw new JunoException("Upload to " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        ExternalRef ref = new ExternalRef(target.trim(), issue.number(), issue.url(), Instant.now().toString());
        synchronized (this) {
            Ticket t = require(ticketId);
            t.setRef(kind, ref);
            if (t.getProjectId() == null) {
                settings.projectForLocation(kind, target).ifPresent(p -> t.setProjectId(p.getId()));
            }
            t.touch();
            persist();
        }
        fireChanged();
        return ref;
    }

    public void unlink(String ticketId, TrackerKind kind) {
        synchronized (this) {
            ticket(ticketId).ifPresent(t -> {
                t.setRef(kind, null);
                t.touch();
            });
            persist();
        }
        fireChanged();
    }

    /** Starts the tracker's MCP server with the given (unsaved) settings and lists its tools. */
    public List<String> testTracker(JunoSettings candidate, TrackerKind kind) throws JunoException {
        try (IssueTrackerService probe = new IssueTrackerService(() -> candidate, this::trackerLog)) {
            return probe.test(kind);
        } catch (McpException e) {
            throw new JunoException(e.getMessage(), e);
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private void trackerLog(String line) {
        System.err.println(line);
        trackerLog.add(line);
        while (trackerLog.size() > 200) {
            trackerLog.removeFirst();
        }
    }

    private Ticket require(String ticketId) throws JunoException {
        return board.getTickets().stream().filter(t -> t.getId().equals(ticketId)).findFirst()
                .orElseThrow(() -> new JunoException("Ticket " + ticketId + " no longer exists"));
    }

    private Project requireProject(Ticket t) throws JunoException {
        Project project = settings.project(t.getProjectId())
                .orElseThrow(() -> new JunoException(t.getId() + " is not linked to a project. "
                        + "Open its details and choose a project first."));
        if (project.getPath() == null || project.getPath().isBlank()
                || !Files.isDirectory(Path.of(project.getPath()))) {
            throw new JunoException("Project '" + project.getName() + "' has no valid directory. "
                    + "Set it in Settings → Projects.");
        }
        return project;
    }

    private void persist() {
        store.saveBoard(board);
    }

    @Override
    public void close() {
        runner.shutdown();
        trackers.close();
    }

    /** Snapshot of tickets in a column, oldest first. */
    public synchronized List<Ticket> column(TicketStatus status) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : board.getTickets()) {
            if (t.getStatus() == status) {
                out.add(t);
            }
        }
        return out;
    }
}
