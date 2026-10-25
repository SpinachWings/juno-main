package dev.juno.main.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.agent.AgentOutcome;
import dev.juno.main.agent.AgentRunner;
import dev.juno.main.agent.PromptBuilder;
import dev.juno.main.agent.RunJournal;
import dev.juno.main.console.AgentConsoles;
import dev.juno.main.git.GitException;
import dev.juno.main.git.GitWorkspace;
import dev.juno.main.integration.IssueData;
import dev.juno.main.integration.IssueTrackerService;
import dev.juno.main.integration.ReviewComment;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.ActiveRun;
import dev.juno.main.model.AgentAssignment;
import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.store.JunoStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private final JunoStore store;
    private final JunoStore.BoardData board;
    private JunoSettings settings;
    private final AgentRunner runner;
    private final IssueTrackerService trackers;
    private final List<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final List<String> trackerLog = new CopyOnWriteArrayList<>();
    /** Tickets whose branch is being prepared before an agent starts. */
    private final Set<String> preparing = new HashSet<>();
    /** Tickets being delivered (push + PR / review comment). */
    private final Set<String> delivering = ConcurrentHashMap.newKeySet();
    /** When each in-progress AI review started (comments after this are "new" next time). */
    private final Map<String, Instant> reviewStarts = new ConcurrentHashMap<>();
    /** Journal of each running agent. */
    private final Map<String, Path> journals = new ConcurrentHashMap<>();
    /** Terminal consoles; null if the local console server could not start. */
    private final AgentConsoles consoles;

    public BoardService(JunoStore store) {
        this.store = store;
        this.settings = store.loadSettings();
        this.board = store.loadBoard();
        this.runner = new AgentRunner(this);
        this.trackers = new IssueTrackerService(this::settings, this::trackerLog);
        recoverInterruptedWork();
        AgentConsoles c;
        try {
            c = new AgentConsoles(consoleBackend(), store.home(), this::trackerLog);
        } catch (IOException e) {
            System.err.println("Agent consoles unavailable: " + e.getMessage());
            c = null;
        }
        this.consoles = c;
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

    /** A ticket's organisation, derived from its project. */
    public synchronized Optional<Organisation> organisationOf(Ticket ticket) {
        return projectOf(ticket).flatMap(settings::organisationOf);
    }

    /** The agent that would work on a ticket (ticket, then project, then organisation assignment). */
    public synchronized Optional<AgentAssignment> agentFor(Ticket ticket) {
        return settings.resolveAgent(ticket);
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

    /**
     * Links a ticket to a project. Issue links belong to the old organisation's credentials, so they are
     * dropped when the ticket moves to a project in a different organisation.
     *
     * @return true if issue links were removed
     */
    public boolean assignProject(Ticket ticket, String projectId) {
        boolean dropped = false;
        synchronized (this) {
            String oldOrg = organisationOf(ticket).map(Organisation::getId).orElse(null);
            String newOrg = settings.project(projectId).map(Project::getOrgId).orElse(null);
            if (oldOrg != null && !oldOrg.equals(newOrg)) {
                for (TrackerKind kind : TrackerKind.values()) {
                    if (ticket.getRef(kind) != null) {
                        ticket.setRef(kind, null);
                        dropped = true;
                    }
                }
            }
            ticket.setProjectId(projectId);
            ticket.touch();
            persist();
        }
        fireChanged();
        return dropped;
    }

    /** Whether {@link #assignProject} would drop issue links. */
    public synchronized boolean wouldDropLinks(Ticket ticket, String projectId) {
        String oldOrg = organisationOf(ticket).map(Organisation::getId).orElse(null);
        String newOrg = settings.project(projectId).map(Project::getOrgId).orElse(null);
        boolean hasLinks = ticket.getRef(TrackerKind.GITLAB) != null || ticket.getRef(TrackerKind.GITHUB) != null;
        return hasLinks && oldOrg != null && !oldOrg.equals(newOrg);
    }

    /**
     * Manually moves a ticket between columns (not into Processing or In AI review, which only agents do, and not
     * while an agent runs). Moving an interrupted ticket abandons the interrupted run.
     */
    public void move(String ticketId, TicketStatus target) throws JunoException {
        if (target == TicketStatus.WORKING) {
            throw new JunoException("Use Start to put a ticket into Processing");
        }
        if (target == TicketStatus.IN_AI_REVIEW) {
            throw new JunoException("Use Run AI review to put a ticket into In AI review");
        }
        if (preparing.contains(ticketId) || delivering.contains(ticketId)) {
            throw new JunoException(ticketId + " is busy; wait for it to finish first");
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
            if (t.isInterrupted()) {
                t.setInterrupted(false);
                t.setActiveRun(null);
                t.setDeliveryPending(false);
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

    /**
     * Starts an agent on the ticket: creates (or reuses) the ticket's branch in its own git worktree, then runs
     * the resolved agent there with the ticket and its initial instructions as the prompt.
     */
    public void startWork(String ticketId) throws JunoException {
        beginRun(ticketId, RunKind.WORK, null, null, null);
    }

    /** Answers an agent's question (or retries after a failure / interruption) and resumes its session. */
    public void respond(String ticketId, String answer) throws JunoException {
        if (answer == null || answer.isBlank()) {
            throw new JunoException("Enter a response for the agent");
        }
        RunKind kind;
        synchronized (this) {
            Ticket t = require(ticketId);
            if (t.getStatus() != TicketStatus.AWAITING_DECISION) {
                throw new JunoException(ticketId + " is not waiting for a decision");
            }
            kind = t.getRunKind();
        }
        beginRun(ticketId, kind, answer, null, null);
    }

    /** Feedback gathered for an AI review run. */
    private record ReviewInput(List<ReviewComment> comments, Instant newSince) {
    }

    /**
     * Starts an AI review: reads the feedback on the ticket's pull/merge request (plus comments on its linked
     * issues since the PR was opened), brings the branch up to date and runs the ticket's agent to address it.
     * The fixes are committed, pushed to the same PR, and the agent's summary is posted as a PR comment.
     *
     * @return the number of comments handed to the agent
     */
    public int startReview(String ticketId) throws JunoException {
        Ticket t;
        Organisation org;
        PullRequestRef pr;
        Instant since;
        Instant prOpened;
        synchronized (this) {
            if (preparing.contains(ticketId) || runner.isRunning(ticketId) || delivering.contains(ticketId)) {
                throw new JunoException(ticketId + " already has an agent running");
            }
            t = require(ticketId);
            if (t.getStatus() != TicketStatus.REVIEW) {
                throw new JunoException(t.getId() + " is not in the Review column");
            }
            pr = t.getPullRequest();
            if (pr == null) {
                throw new JunoException(t.getId() + " has no pull request to review yet");
            }
            Project project = requireProject(t);
            org = settings.organisationOf(project)
                    .orElseThrow(() -> new JunoException("Project '" + project.getName() + "' has no organisation"));
            since = parseInstant(t.getLastReviewAt());
            prOpened = parseInstant(t.getPullRequestOpenedAt());
        }
        List<ReviewComment> feedback = new ArrayList<>();
        try {
            for (ReviewComment c : trackers.pullRequestComments(org, pr.tracker(), pr.location(), pr.number())) {
                if (!c.resolved() && !c.postedByJuno() && !c.body().isBlank()) {
                    feedback.add(c);
                }
            }
            for (TrackerKind kind : TrackerKind.values()) {
                ExternalRef issue = t.getRef(kind);
                if (issue == null || !org.tracker(kind).isEnabled()) {
                    continue;
                }
                for (IssueData.Comment c : trackers.getIssue(org, kind, issue.location(), issue.number(), true)
                        .comments()) {
                    boolean afterPr = prOpened == null
                            || parseInstant(c.createdAt()) == null || parseInstant(c.createdAt()).isAfter(prOpened);
                    if (afterPr && !c.body().isBlank() && !c.body().contains(ReviewComment.JUNO_MARKER)) {
                        feedback.add(new ReviewComment("issue", 0, c.author(), c.body(), null, 0, null,
                                c.createdAt(), false));
                    }
                }
            }
        } catch (McpException e) {
            throw new JunoException("Could not read the review comments: " + e.getMessage(), e);
        }
        boolean anyNew = feedback.stream().anyMatch(c -> since == null || parseInstant(c.createdAt()) == null
                || parseInstant(c.createdAt()).isAfter(since));
        if (!anyNew) {
            // Nothing to address: the review completes immediately and the ticket stays in Review.
            String summary = "No " + (since == null ? "" : "new ") + "comments on " + pr.display()
                    + (since == null ? "" : " since the last AI review") + "; nothing to change.";
            synchronized (this) {
                t.setReviewCount(t.getReviewCount() + 1);
                t.setLastReviewAt(Instant.now().toString());
                t.setLastReviewSummary(summary);
                t.touch();
                persist();
            }
            log(ticketId, "");
            log(ticketId, "=== AI review " + t.getReviewCount() + ": " + summary + " ===");
            fireChanged();
            return 0;
        }
        reviewStarts.put(ticketId, Instant.now());
        beginRun(ticketId, RunKind.REVIEW, null, new ReviewInput(feedback, since), null);
        return feedback.size();
    }

    /** How to recover an interrupted run: the Claude session to resume (if any) and the journal to recap. */
    private record Recovery(String sessionId, Path sourceJournal) {
    }

    /**
     * Resumes a ticket whose agent run (or push) was cut short by Juno closing, a crash or a restart. A run is
     * continued in its own Claude session with a recap of the journal and the branch's current state; if that
     * session can't be restored, a fresh session gets the original prompt plus the same recap. An interrupted
     * push / pull request is simply retried.
     */
    public void resumeInterrupted(String ticketId) throws JunoException {
        ActiveRun run;
        boolean deliveryOnly;
        RunKind kind;
        String session;
        Path source;
        synchronized (this) {
            Ticket t = require(ticketId);
            if (!t.isInterrupted()) {
                throw new JunoException(ticketId + " is not interrupted");
            }
            run = t.getActiveRun();
            deliveryOnly = run == null && t.isDeliveryPending();
            kind = run != null ? run.kind() : t.getRunKind();
            session = run != null && run.sessionId() != null ? run.sessionId() : t.getAgentSessionId();
            source = run != null && run.journal() != null ? Path.of(run.journal())
                    : t.getLastJournal() != null ? Path.of(t.getLastJournal()) : null;
        }
        if (run != null && AgentRunner.killOrphan(run.pid(), parseInstant(run.startedAt()))) {
            log(ticketId, "[juno] stopped an agent process left over from the interrupted run");
        }
        log(ticketId, "");
        log(ticketId, "=== Resuming interrupted " + (deliveryOnly ? "push / pull request"
                : kind == RunKind.REVIEW ? "AI review" : "work") + " ===");
        if (deliveryOnly) {
            synchronized (this) {
                ticket(ticketId).ifPresent(t -> t.setInterrupted(false));
                persist();
            }
            fireChanged();
            Thread.ofPlatform().daemon().name("deliver-" + ticketId).start(() -> deliver(ticketId));
            return;
        }
        beginRun(ticketId, kind, null, null, new Recovery(session, source));
    }

    private void beginRun(String ticketId, RunKind kind, String answer, ReviewInput review, Recovery recovery)
            throws JunoException {
        boolean resume = answer != null;
        Path repo;
        Path worktree;
        String base;
        String branch;
        synchronized (this) {
            if (preparing.contains(ticketId) || runner.isRunning(ticketId) || delivering.contains(ticketId)) {
                throw new JunoException(ticketId + " is already starting or running");
            }
            Ticket t = require(ticketId);
            Project project = requireProject(t);
            settings.resolveAgent(t).orElseThrow(() -> new JunoException(
                    "No agent is assigned to " + t.getId() + ", project '" + project.getName()
                            + "' or its organisation. Assign one in the ticket's Details or in Settings."));
            checkCapacity();
            if (t.getBranchName().isBlank()) {
                t.setBranchName(GitWorkspace.suggestBranchName(t.getId(), t.getTitle()));
                persist();
            }
            repo = Path.of(project.getPath());
            worktree = t.getWorktreePath() != null
                    ? Path.of(t.getWorktreePath())
                    : store.home().resolve("worktrees").resolve(safeName(t.getId()));
            base = t.getBaseBranch();
            branch = t.getBranchName();
            preparing.add(ticketId);
        }
        fireChanged();
        try {
            log(ticketId, "");
            log(ticketId, "=== " + (kind == RunKind.REVIEW && !resume ? "AI review: preparing" : "Preparing")
                    + " branch " + branch + " (from " + base + ") ===");
            GitWorkspace.Prepared prepared;
            try {
                GitWorkspace git = git(ticketId);
                prepared = git.prepare(repo, worktree, base, branch);
                if (kind == RunKind.REVIEW && !resume && recovery == null) {
                    git.syncWithRemote(prepared.worktree(), branch);
                }
            } catch (GitException e) {
                log(ticketId, "[juno] " + e.getMessage());
                throw new JunoException("Could not prepare the ticket's branch: " + e.getMessage(), e);
            }
            // For recovery: what the interrupted run did, and where the branch stands now.
            String recap = null;
            String gitState = null;
            String originalPrompt = null;
            if (recovery != null) {
                recap = recovery.sourceJournal() != null && Files.exists(recovery.sourceJournal())
                        ? RunJournal.recap(recovery.sourceJournal(), 12_000)
                        : "(No journal was recorded for the interrupted run.)";
                String baseRef;
                synchronized (this) {
                    Ticket t = require(ticketId);
                    baseRef = t.getBaseRef() != null ? t.getBaseRef() : base;
                }
                gitState = git(ticketId).describe(prepared.worktree(), baseRef);
                originalPrompt = recovery.sourceJournal() == null ? null : RunJournal.readPrompt(recovery.sourceJournal());
            }
            String recapText = recap;
            String gitText = gitState;
            String original = originalPrompt;

            AgentRunner.Request request;
            Path journal;
            ActiveRun previousRun;
            synchronized (this) {
                Ticket t = require(ticketId);
                t.setWorktreePath(prepared.worktree().toString());
                if (prepared.baseRef() != null) {
                    t.setBaseRef(prepared.baseRef());
                }
                t.setRunKind(kind);
                t.setPullRequestError(null);
                persist();
                String session = recovery != null ? recovery.sessionId() : resume ? t.getAgentSessionId() : null;
                String mode = recovery != null ? (session != null ? "recovery-resume" : "recovery-fresh")
                        : resume ? "resume" : "fresh";
                request = buildRequest(t, session, prepared.worktree(), tools -> {
                    Project p = requireProjectUnchecked(t);
                    if (recovery != null) {
                        if (session != null) {
                            return PromptBuilder.recoveryResume(t, recapText, gitText);
                        }
                        String basePrompt = original != null ? original
                                : kind == RunKind.REVIEW
                                ? "You are addressing review feedback on " + (t.getPullRequest() == null ? "the pull request"
                                        : t.getPullRequest().url()) + " for ticket " + t.getId() + ": " + t.getTitle()
                                        + ". Read the pull request's comments with the MCP tools if available."
                                : PromptBuilder.initial(t, p, tools);
                        return PromptBuilder.recoveryFresh(basePrompt, t, recapText, gitText);
                    }
                    if (!resume) {
                        return kind == RunKind.REVIEW
                                ? PromptBuilder.review(t, p, review.comments(), review.newSince())
                                : PromptBuilder.initial(t, p, tools);
                    }
                    if (session != null) {
                        return PromptBuilder.resume(t, answer);
                    }
                    return PromptBuilder.initial(t, p, tools)
                            + "\n## Additional guidance from a human\n" + answer.strip() + "\n";
                });
                try {
                    journal = RunJournal.create(store.runsDir(ticketId), kind, request.prompt());
                } catch (IOException e) {
                    throw new JunoException("Could not create the run journal: " + e.getMessage(), e);
                }
                previousRun = t.getActiveRun();
                RunJournal.event(journal, "start", "mode", mode, "kind", kind.name(),
                        "session", session == null ? "" : session, "workDir", prepared.worktree().toString());
                t.setActiveRun(new ActiveRun(kind, mode, session, Instant.now().toString(), journal.toString(),
                        recovery == null || recovery.sourceJournal() == null ? null
                                : recovery.sourceJournal().toString(), 0));
                persist();
                journals.put(ticketId, journal);
            }
            log(ticketId, "[juno] journal: " + journal);
            try {
                launch(request, kind == RunKind.REVIEW ? TicketStatus.IN_AI_REVIEW : TicketStatus.WORKING);
            } catch (JunoException e) {
                synchronized (this) {
                    journals.remove(ticketId);
                    ticket(ticketId).ifPresent(t -> t.setActiveRun(previousRun));
                    persist();
                }
                RunJournal.event(journal, "launch-failed", "error", String.valueOf(e.getMessage()));
                throw e;
            }
        } finally {
            synchronized (this) {
                preparing.remove(ticketId);
            }
            fireChanged();
        }
        openTerminalIfEnabled(ticketId, kind);
    }

    // ---- terminals --------------------------------------------------------------------------

    private static String consoleVariant(RunKind kind) {
        return kind == RunKind.REVIEW ? "review" : "";
    }

    private void openTerminalIfEnabled(String ticketId, RunKind kind) {
        JunoSettings s = settings();
        if (consoles == null || !s.isOpenTerminals()) {
            return;
        }
        try {
            consoles.ensureOpen(ticketId, consoleVariant(kind), s.getTerminalCommand());
        } catch (IOException e) {
            log(ticketId, "[juno] could not open a terminal: " + e.getMessage());
        }
    }

    /**
     * Brings the terminal for the ticket's current (or last) run to the front, opening one if it isn't running.
     * Work runs and AI review runs have separate terminals.
     */
    public String showTerminal(String ticketId) throws JunoException {
        if (consoles == null) {
            throw new JunoException("The agent console is unavailable (see Juno's startup output)");
        }
        RunKind kind;
        synchronized (this) {
            kind = require(ticketId).getRunKind();
        }
        try {
            return consoles.show(ticketId, consoleVariant(kind), settings().getTerminalCommand());
        } catch (IOException e) {
            throw new JunoException("Could not open a terminal: " + e.getMessage(), e);
        }
    }

    public boolean isTerminalOpen(String ticketId) {
        RunKind kind;
        synchronized (this) {
            kind = ticket(ticketId).map(Ticket::getRunKind).orElse(RunKind.WORK);
        }
        return consoles != null && consoles.isOpen(AgentConsoles.key(ticketId, consoleVariant(kind)));
    }

    /** Backend for the agent consoles. */
    private AgentConsoles.Backend consoleBackend() {
        return new AgentConsoles.Backend() {
            @Override
            public Optional<AgentConsoles.TicketState> state(String ticketId) {
                synchronized (BoardService.this) {
                    return ticket(ticketId).map(t -> new AgentConsoles.TicketState(
                            t.getStatus().name(), t.getStatus().label(), t.getTitle(),
                            t.getStatus() == TicketStatus.AWAITING_DECISION ? t.getPendingQuestion() : null,
                            runner.isRunning(ticketId) || preparing.contains(ticketId),
                            delivering.contains(ticketId),
                            t.getPullRequest() == null ? null : t.getPullRequest().url(),
                            t.getBranchName()));
                }
            }

            @Override
            public Path logFile(String ticketId) {
                return store.logFile(ticketId);
            }

            @Override
            public void answer(String ticketId, String answer) throws JunoException {
                respond(ticketId, answer);
            }
        };
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(iso);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /** Whether the ticket's branch is being prepared for an agent. */
    public synchronized boolean isPreparing(String ticketId) {
        return preparing.contains(ticketId);
    }

    private GitWorkspace git(String ticketId) {
        return new GitWorkspace("git", line -> log(ticketId, line));
    }

    private static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Resolves project, organisation and agent for a run. Caller holds the lock. */
    private AgentRunner.Request buildRequest(Ticket t, String resumeSession, Path workDir,
                                             java.util.function.Function<Boolean, String> prompt)
            throws JunoException {
        Project project = requireProject(t);
        Organisation org = settings.organisationOf(project)
                .orElseThrow(() -> new JunoException("Project '" + project.getName() + "' has no organisation"));
        AgentAssignment agent = settings.resolveAgent(t).orElseThrow(() -> new JunoException(
                "No agent is assigned to " + t.getId() + ", project '" + project.getName()
                        + "' or organisation '" + org.getName() + "'. Assign one in the ticket's Details "
                        + "or in Settings."));
        checkCapacity();

        AgentProfile profile = store.copy(agent.profile(), AgentProfile.class);
        String extra = project.getExtraAllowedTools();
        if (extra != null && !extra.isBlank()) {
            String base = profile.getAllowedTools() == null ? "" : profile.getAllowedTools().strip();
            profile.setAllowedTools((base + " " + extra.strip()).strip());
        }
        Path mcpConfig = profile.isExposeTrackerMcp() ? writeAgentMcpConfig(t, org) : null;
        String label = "'" + agent.profile().getName() + "' (" + profile.getModel() + ", assigned on "
                + agent.level().label() + (agent.level() == AgentAssignment.Level.TICKET ? "" : " " + agent.owner())
                + ")";
        return new AgentRunner.Request(t.getId(), workDir, prompt.apply(mcpConfig != null),
                resumeSession, profile, mcpConfig, label);
    }

    private Project requireProjectUnchecked(Ticket t) {
        return settings.project(t.getProjectId()).orElseThrow();
    }

    public void stopWork(String ticketId) {
        runner.stop(ticketId);
    }

    private void launch(AgentRunner.Request request, TicketStatus statusWhileRunning) throws JunoException {
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
            t.setStatus(statusWhileRunning);
            t.setPendingQuestion(null);
            t.touch();
            persist();
        }
        long pid;
        try {
            pid = runner.start(request);
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
        synchronized (this) {
            ticket(request.ticketId()).ifPresent(t -> {
                t.setInterrupted(false);
                t.setDeliveryPending(false);
                if (t.getActiveRun() != null) {
                    t.setActiveRun(t.getActiveRun().withPid(pid));
                }
            });
            persist();
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
        boolean deliver = false;
        Recovery fallback = null;
        Path journal = journals.remove(ticketId);
        if (journal != null) {
            RunJournal.event(journal, "finish", "outcome", outcome.kind().name(),
                    "message", outcome.message() == null ? "" : outcome.message());
        }
        synchronized (this) {
            Optional<Ticket> found = board.getTickets().stream().filter(t -> t.getId().equals(ticketId)).findFirst();
            if (found.isEmpty()) {
                return; // deleted while running
            }
            Ticket t = found.get();
            RunKind kind = t.getRunKind();
            ActiveRun run = t.getActiveRun();
            t.setActiveRun(null);
            if (run != null) {
                t.setLastJournal(run.journal());
            }
            if (outcome.sessionId() != null) {
                t.setAgentSessionId(outcome.sessionId());
            }
            // Resuming the interrupted session failed before it started (e.g. the session is gone): fall back to a
            // fresh session that gets the original prompt plus the recap.
            if (run != null && "recovery-resume".equals(run.mode())
                    && outcome.kind() == AgentOutcome.Kind.FAILED && outcome.sessionId() == null) {
                fallback = new Recovery(null, run.recoveredFrom() == null ? null : Path.of(run.recoveredFrom()));
                t.setInterrupted(true);
                t.setActiveRun(run.withSessionId(null));
                t.touch();
                persist();
            }
            if (fallback == null) {
            switch (outcome.kind()) {
                case COMPLETED -> {
                    if (kind == RunKind.REVIEW) {
                        t.setLastReviewSummary(outcome.message());
                    } else {
                        t.setLastSummary(outcome.message());
                    }
                    t.setPendingQuestion(null);
                    if (t.getWorktreePath() != null) {
                        // Stay in Working (or AI review) until the branch is pushed; delivery moves it on.
                        deliver = true;
                        delivering.add(ticketId);
                    } else {
                        t.setStatus(TicketStatus.REVIEW);
                    }
                }
                case NEEDS_DECISION -> {
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion(outcome.message());
                }
                case FAILED -> {
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion("The agent failed: " + outcome.message()
                            + "\n\nReply with guidance to retry, or move the ticket elsewhere.");
                }
                case CANCELLED -> {
                    t.setStatus(kind == RunKind.REVIEW ? TicketStatus.REVIEW : TicketStatus.PENDING);
                    t.setPendingQuestion(null);
                }
            }
            t.touch();
            persist();
            }
        }
        fireChanged();
        if (deliver) {
            Thread.ofPlatform().daemon().name("deliver-" + ticketId).start(() -> deliver(ticketId));
        }
        if (fallback != null) {
            Recovery recovery = fallback;
            log(ticketId, "[juno] the interrupted session could not be resumed; starting a new session from the journal");
            Thread.ofPlatform().daemon().name("recover-" + ticketId).start(() -> {
                try {
                    beginRun(ticketId, kindOf(ticketId), null, null, recovery);
                } catch (JunoException e) {
                    log(ticketId, "[juno] could not resume: " + e.getMessage());
                    fireChanged();
                }
            });
        }
    }

    private synchronized RunKind kindOf(String ticketId) {
        return ticket(ticketId).map(Ticket::getRunKind).orElse(RunKind.WORK);
    }

    @Override
    public void raw(String ticketId, String line) {
        Path journal = journals.get(ticketId);
        RunJournal.append(journal, line);
        // Persist the Claude session id as soon as the agent reports it, so an interruption can resume it.
        if (line.contains("\"init\"") && line.contains("session_id")) {
            try {
                com.fasterxml.jackson.databind.JsonNode event = store.mapper().readTree(line);
                String sid = event.path("session_id").asText(null);
                if ("system".equals(event.path("type").asText()) && sid != null) {
                    synchronized (this) {
                        ticket(ticketId).ifPresent(t -> {
                            t.setAgentSessionId(sid);
                            if (t.getActiveRun() != null) {
                                t.setActiveRun(t.getActiveRun().withSessionId(sid));
                            }
                        });
                        persist();
                    }
                }
            } catch (IOException ignored) {
                // not JSON
            }
        }
    }

    // ---- delivery: push + pull request / review comment -------------------------------------

    /** Whether Juno is pushing the ticket's branch / opening its PR / posting its review. */
    public boolean isOpeningPullRequest(String ticketId) {
        return delivering.contains(ticketId);
    }

    /** Whether an AI review agent is working on the ticket. */
    public synchronized boolean isReviewRunning(String ticketId) {
        return runner.isRunning(ticketId) && ticket(ticketId).map(t -> t.getRunKind() == RunKind.REVIEW).orElse(false);
    }

    /**
     * Finishes an agent run (also used by Retry): commits leftovers and pushes the branch, then opens the pull
     * request (work run) or posts the review summary on it (review run). On success the ticket moves to
     * AI review; on failure to Awaiting decision with the reason, from where it can be retried.
     */
    public void deliver(String ticketId) {
        RunKind kind;
        synchronized (this) {
            Optional<Ticket> t = ticket(ticketId);
            if (t.isEmpty() || t.get().getWorktreePath() == null) {
                delivering.remove(ticketId);
                return;
            }
            kind = t.get().getRunKind();
            delivering.add(ticketId);
            t.get().setDeliveryPending(true);
            persist();
        }
        fireChanged();
        try {
            if (kind == RunKind.REVIEW) {
                pushReview(ticketId);
            } else {
                openPullRequest(ticketId);
            }
        } catch (JunoException | GitException | McpException e) {
            synchronized (this) {
                ticket(ticketId).ifPresent(t -> {
                    t.setPullRequestError(e.getMessage());
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion("Juno couldn't finish " + (kind == RunKind.REVIEW ? "the AI review" : "the ticket")
                            + ": " + e.getMessage() + "\n\nFix the cause and use Retry, or reply here to have the "
                            + "agent deal with it.");
                    t.touch();
                });
                persist();
            }
            log(ticketId, "[juno] " + e.getMessage());
        } finally {
            synchronized (this) {
                delivering.remove(ticketId);
                ticket(ticketId).ifPresent(t -> t.setDeliveryPending(false));
                persist();
            }
            fireChanged();
        }
    }

    private void openPullRequest(String ticketId) throws JunoException, GitException, McpException {
        Ticket t;
        Project project;
        Organisation org;
        Path worktree;
        String branch;
        String base;
        String baseRef;
        String title;
        String body;
        synchronized (this) {
            t = require(ticketId);
            project = settings.project(t.getProjectId()).orElse(null);
            org = project == null ? null : settings.organisationOf(project).orElse(null);
            worktree = Path.of(t.getWorktreePath());
            branch = t.getBranchName();
            base = t.getBaseBranch();
            baseRef = t.getBaseRef() != null ? t.getBaseRef() : base;
            title = t.getId() + ": " + t.getTitle();
            body = pullRequestBody(t);
        }
        if (project == null || org == null) {
            throw new JunoException("The ticket is not linked to a project in an organisation");
        }
        TrackerKind kind = pullRequestTracker(t, project, org);
        String location = project.location(kind);
        GitWorkspace git = git(ticketId);
        log(ticketId, "");
        log(ticketId, "=== Pushing " + branch + " and opening a " + kind.pullRequestNoun() + " into " + base + " on "
                + kind.displayName() + " " + location + " ===");
        if (git.commitLeftovers(worktree, title + "\n\nChanges left uncommitted by the agent, committed by Juno.")) {
            log(ticketId, "[juno] committed changes the agent left uncommitted");
        }
        if (git.commitsAhead(worktree, baseRef) == 0) {
            throw new JunoException("There are no commits on " + branch + " beyond " + base
                    + ", so there is nothing to open a " + kind.pullRequestNoun() + " for.");
        }
        git.push(worktree, branch);
        PullRequestRef pr = trackers.createPullRequest(org, kind, location, branch, base, title,
                body + closesLine(t, kind, location));
        synchronized (this) {
            if (t.getPullRequestOpenedAt() == null || t.getPullRequest() == null
                    || t.getPullRequest().number() != pr.number()) {
                t.setPullRequestOpenedAt(Instant.now().toString());
            }
            t.setPullRequest(pr);
            t.setPullRequestError(null);
            t.setStatus(TicketStatus.REVIEW);
            t.setPendingQuestion(null);
            t.touch();
            persist();
        }
        log(ticketId, "[juno] " + kind.pullRequestNoun() + " " + pr.display() + ": " + pr.url());
        log(ticketId, "[juno] moved to Review");
    }

    private void pushReview(String ticketId) throws JunoException, GitException, McpException {
        Ticket t;
        Organisation org;
        PullRequestRef pr;
        Path worktree;
        String branch;
        String summary;
        synchronized (this) {
            t = require(ticketId);
            pr = t.getPullRequest();
            Project project = settings.project(t.getProjectId()).orElse(null);
            org = project == null ? null : settings.organisationOf(project).orElse(null);
            worktree = Path.of(t.getWorktreePath());
            branch = t.getBranchName();
            summary = t.getLastReviewSummary() == null || t.getLastReviewSummary().isBlank()
                    ? "_The agent did not provide a summary._" : t.getLastReviewSummary().strip();
        }
        if (pr == null || org == null) {
            throw new JunoException("The ticket has no pull request or organisation");
        }
        GitWorkspace git = git(ticketId);
        log(ticketId, "");
        log(ticketId, "=== AI review: pushing fixes to " + pr.display() + " ===");
        if (git.commitLeftovers(worktree, t.getId() + ": address review feedback\n\n"
                + "Changes left uncommitted by the review agent, committed by Juno.")) {
            log(ticketId, "[juno] committed changes the agent left uncommitted");
        }
        int ahead = git.commitsAhead(worktree, "origin/" + branch);
        if (ahead > 0) {
            git.push(worktree, branch);
        } else {
            log(ticketId, "[juno] the review agent made no code changes");
        }
        String comment = "### AI review\n\n" + summary + "\n\n"
                + (ahead > 0 ? "_Pushed " + ahead + " commit(s) to `" + branch + "`._"
                : "_No code changes were made._")
                + "\n\n" + ReviewComment.JUNO_MARKER;
        trackers.commentOnPullRequest(org, pr.tracker(), pr.location(), pr.number(), comment);
        synchronized (this) {
            Instant started = reviewStarts.remove(ticketId);
            t.setLastReviewAt((started == null ? Instant.now() : started).toString());
            t.setReviewCount(t.getReviewCount() + 1);
            t.setPullRequestError(null);
            t.setStatus(TicketStatus.REVIEW);
            t.setPendingQuestion(null);
            t.touch();
            persist();
        }
        log(ticketId, "[juno] posted the review summary on " + pr.display() + "; AI review " + t.getReviewCount()
                + " complete, moved back to Review");
    }

    /**
     * Where to open the pull request: a tracker the ticket is linked to first, then GitHub, then GitLab; the
     * first that is enabled for the organisation and configured on the project wins.
     */
    static TrackerKind pullRequestTracker(Ticket t, Project project, Organisation org) throws JunoException {
        List<TrackerKind> order = new ArrayList<>();
        for (TrackerKind kind : TrackerKind.values()) {
            if (t.getRef(kind) != null) {
                order.add(kind);
            }
        }
        for (TrackerKind kind : List.of(TrackerKind.GITHUB, TrackerKind.GITLAB)) {
            if (!order.contains(kind)) {
                order.add(kind);
            }
        }
        for (TrackerKind kind : order) {
            if (org.tracker(kind).isEnabled() && !project.location(kind).isEmpty()) {
                return kind;
            }
        }
        throw new JunoException("Project '" + project.getName() + "' has no GitHub repo or GitLab project with "
                + "that integration enabled for organisation '" + org.getName() + "'. Set one in Settings, then "
                + "use Retry PR.");
    }

    private static String pullRequestBody(Ticket t) {
        String summary = t.getLastSummary() == null || t.getLastSummary().isBlank()
                ? "_The agent did not provide a summary._" : t.getLastSummary().strip();
        return summary + "\n\n---\nTicket " + t.getId() + ": " + t.getTitle() + "\n\n_Opened by Juno._";
    }

    /** Adds "Closes #n" when the ticket's linked issue lives in the same repository as the pull request. */
    private static String closesLine(Ticket t, TrackerKind kind, String location) {
        ExternalRef ref = t.getRef(kind);
        if (ref != null && ref.location().equalsIgnoreCase(location.trim())) {
            return "\n\nCloses #" + ref.number();
        }
        return "";
    }

    /**
     * On start-up, any ticket that was mid-run or mid-push when Juno last stopped (closed, crashed, computer
     * restarted) is marked interrupted. It stays in its column, greyed out, until Resume picks it up again.
     */
    private void recoverInterruptedWork() {
        boolean changed = false;
        for (Ticket t : board.getTickets()) {
            boolean midRun = t.getActiveRun() != null || t.isDeliveryPending()
                    || t.getStatus() == TicketStatus.WORKING || t.getStatus() == TicketStatus.IN_AI_REVIEW;
            if (midRun && !t.isInterrupted()) {
                t.setInterrupted(true);
                if (t.getActiveRun() != null && t.getActiveRun().journal() != null) {
                    RunJournal.event(Path.of(t.getActiveRun().journal()), "interrupted",
                            "detectedAt", "Juno start-up");
                }
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private void checkCapacity() throws JunoException {
        int max = Math.max(1, settings.getMaxConcurrentAgents());
        if (runner.runningCount() >= max) {
            throw new JunoException(max + " agent(s) already running (limit set in Settings → General)");
        }
    }

    /**
     * Writes a per-ticket MCP config containing only the ticket's organisation's tracker servers, so an
     * agent can never use another organisation's credentials. Returns null if nothing is available.
     */
    private Path writeAgentMcpConfig(Ticket ticket, Organisation org) throws JunoException {
        ObjectMapper mapper = store.mapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (TrackerKind kind : TrackerKind.values()) {
            if (!org.tracker(kind).isEnabled()) {
                continue;
            }
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
            for (Map.Entry<String, String> e : IssueTrackerService.environment(org, kind).entrySet()) {
                env.put(e.getKey(), e.getValue());
            }
        }
        if (servers.isEmpty()) {
            return null;
        }
        Path dir = store.home().resolve("agent-mcp");
        Path file = dir.resolve(ticket.getId().replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
        try {
            Files.createDirectories(dir);
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

    public List<IssueData> listIssues(String orgId, TrackerKind kind, String location) throws JunoException {
        Organisation org = requireOrganisation(orgId);
        try {
            return trackers.listIssues(org, kind, location, "open", 100);
        } catch (McpException e) {
            throw new JunoException(e.getMessage(), e);
        }
    }

    /**
     * Replaces the ticket's title and description with the remote issue's and links the issue. The issue is
     * read with the chosen project's organisation's credentials. The ticket is linked to the project in that
     * organisation whose GitLab project / GitHub repo matches the location, falling back to the chosen one.
     */
    public void importIssue(String ticketId, String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        Project chosen;
        Organisation org;
        synchronized (this) {
            chosen = settings.project(projectId)
                    .orElseThrow(() -> new JunoException("Choose the project to import into"));
            org = settings.organisationOf(chosen)
                    .orElseThrow(() -> new JunoException("Project '" + chosen.getName() + "' has no organisation"));
        }
        IssueData issue;
        try {
            issue = trackers.getIssue(org, kind, location, number, false);
        } catch (McpException e) {
            throw new JunoException("Import from " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        synchronized (this) {
            Ticket t = require(ticketId);
            Project target = settings.projectForLocation(org.getId(), kind, location).orElse(chosen);
            String previousOrg = organisationOf(t).map(Organisation::getId).orElse(null);
            if (previousOrg != null && !previousOrg.equals(org.getId())) {
                for (TrackerKind k : TrackerKind.values()) {
                    t.setRef(k, null); // links from another organisation's credentials
                }
            }
            t.setProjectId(target.getId());
            t.setTitle(issue.title());
            t.setDescription(issue.body());
            t.setRef(kind, new ExternalRef(location.trim(), issue.number(), issue.url(), Instant.now().toString()));
            t.touch();
            persist();
        }
        fireChanged();
    }

    /** Creates a new Pending ticket from a remote issue. */
    public Ticket importAsNewTicket(String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        Ticket ticket = createTicket("Importing " + kind.shortName() + " #" + number + "…", "", projectId);
        try {
            importIssue(ticket.getId(), projectId, kind, location, number);
        } catch (JunoException e) {
            deleteTicket(ticket.getId());
            throw e;
        }
        return ticket;
    }

    /**
     * Uploads the ticket's title and description with its organisation's credentials: updates the linked
     * issue if there is one, otherwise creates an issue in {@code location} and links it.
     */
    public ExternalRef upload(String ticketId, TrackerKind kind, String location) throws JunoException {
        String title;
        String body;
        ExternalRef existing;
        Organisation org;
        synchronized (this) {
            Ticket t = require(ticketId);
            title = t.getTitle();
            body = t.getDescription();
            existing = t.getRef(kind);
            org = organisationOf(t).orElseThrow(() -> new JunoException(
                    t.getId() + " is not linked to a project. Choose a project first: its organisation's "
                            + kind.displayName() + " credentials are used for the upload."));
        }
        if (title == null || title.isBlank()) {
            throw new JunoException("The ticket needs a title before it can be uploaded");
        }
        IssueData issue;
        String target = existing != null ? existing.location() : location;
        try {
            issue = existing != null
                    ? trackers.updateIssue(org, kind, existing.location(), existing.number(), title, body)
                    : trackers.createIssue(org, kind, location, title, body);
        } catch (McpException e) {
            throw new JunoException("Upload to " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        ExternalRef ref = new ExternalRef(target.trim(), issue.number(), issue.url(), Instant.now().toString());
        synchronized (this) {
            Ticket t = require(ticketId);
            t.setRef(kind, ref);
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

    /** Starts an organisation's MCP server with the given (unsaved) settings and lists its tools. */
    public List<String> testTracker(JunoSettings candidate, String orgId, TrackerKind kind) throws JunoException {
        Organisation org = candidate.organisation(orgId)
                .orElseThrow(() -> new JunoException("Unknown organisation"));
        try (IssueTrackerService probe = new IssueTrackerService(() -> candidate, this::trackerLog)) {
            return probe.test(org, kind);
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

    private synchronized Organisation requireOrganisation(String orgId) throws JunoException {
        return settings.organisation(orgId).orElseThrow(() -> new JunoException("Choose an organisation"));
    }

    private Project requireProject(Ticket t) throws JunoException {
        Project project = settings.project(t.getProjectId())
                .orElseThrow(() -> new JunoException(t.getId() + " is not linked to a project. "
                        + "Open its details and choose a project first."));
        if (project.getPath() == null || project.getPath().isBlank()
                || !Files.isDirectory(Path.of(project.getPath()))) {
            throw new JunoException("Project '" + project.getName() + "' has no valid directory. "
                    + "Set it in Settings → Organisations & projects.");
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
        if (consoles != null) {
            consoles.close();
        }
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
