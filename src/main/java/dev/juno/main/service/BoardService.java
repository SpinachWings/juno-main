package dev.juno.main.service;

import dev.juno.main.agent.AgentRunner;
import dev.juno.main.agent.DesignDocs;
import dev.juno.main.console.AgentConsoles;
import dev.juno.main.integration.IssueData;
import dev.juno.main.model.AgentAssignment;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.skills.SkillLibrary;
import dev.juno.main.store.JunoStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The board: tickets and settings, and the entry point for everything the UI does with them. The work is done by
 * package-private parts sharing one {@link BoardState}:
 * <ul>
 *   <li>{@link Preflight}: checks before an agent starts</li>
 *   <li>{@link AgentRuns}: starting, resuming and finishing agent runs</li>
 *   <li>{@link Delivery}: pushing, pull requests, review and merge summaries</li>
 *   <li>{@link TrackerSync}: copying ticket info to and from GitHub/GitLab issues</li>
 *   <li>{@link Terminals}: the agent terminals</li>
 *   <li>{@link Housekeeping}: freeing branches and removing files Juno no longer needs</li>
 * </ul>
 *
 * <p>Thread-safe. Tickets returned by this class are snapshots: change them through its methods. Listeners are
 * called on whichever thread caused the change, so UI code must marshal.
 */
public final class BoardService implements AutoCloseable {

    public interface ChangeListener {
        void boardChanged();
    }

    public interface LogListener {
        void logged(String ticketId, String line);
    }

    private final BoardState state;
    private final Housekeeping housekeeping;
    private final AgentRuns runs;
    private final Delivery delivery;
    private final TrackerSync sync;
    private final Terminals terminals;
    private final SkillLibrary skills;

    public BoardService(JunoStore store) {
        state = new BoardState(store);
        housekeeping = new Housekeeping(state);
        Preflight preflight = new Preflight(state);
        delivery = new Delivery(state, housekeeping);
        runs = new AgentRuns(state, preflight, delivery, housekeeping);
        state.runner = new AgentRunner(runs);
        sync = new TrackerSync(state, this);
        skills = new SkillLibrary(store.home());
        if (skills.installBundled(state.settings)) {
            store.saveSettings(state.settings);
        }
        runs.recoverInterruptedWork();
        // Agents no longer get GitHub/GitLab connections; remove config files (with tokens) earlier versions left.
        store.deleteTree(store.home().resolve("agent-mcp"));
        Thread.ofVirtual().name("housekeeping").start(housekeeping::cleanUpOrphans);
        AgentConsoles consoles;
        try {
            consoles = new AgentConsoles(consoleBackend(), store.home(), state::addTrackerLog);
        } catch (IOException e) {
            System.err.println("Agent consoles unavailable: " + e.getMessage());
            consoles = null;
        }
        terminals = new Terminals(state, consoles);
        runs.setTerminals(terminals);
    }

    // ---- listeners ----------------------------------------------------------------------------

    public void addChangeListener(ChangeListener l) {
        state.changeListeners.add(l);
    }

    public void addLogListener(LogListener l) {
        state.logListeners.add(l);
    }

    public void removeLogListener(LogListener l) {
        state.logListeners.remove(l);
    }

    // ---- queries (snapshots) ------------------------------------------------------------------

    /** Snapshots of all tickets. */
    public List<Ticket> tickets() {
        synchronized (state) {
            return state.board.getTickets().stream().map(this::snapshot).toList();
        }
    }

    /** Snapshots of the tickets in a column, oldest first. */
    public List<Ticket> column(TicketStatus status) {
        synchronized (state) {
            List<Ticket> out = new ArrayList<>();
            for (Ticket t : state.board.getTickets()) {
                if (t.getStatus() == status) {
                    out.add(snapshot(t));
                }
            }
            return out;
        }
    }

    /** A snapshot of the ticket as it is now. */
    public Optional<Ticket> ticket(String id) {
        synchronized (state) {
            return state.find(id).map(this::snapshot);
        }
    }

    private Ticket snapshot(Ticket live) {
        return state.store.copy(live, Ticket.class);
    }

    public JunoSettings settings() {
        synchronized (state) {
            return state.settings;
        }
    }

    public Optional<Project> projectOf(Ticket ticket) {
        synchronized (state) {
            return state.projectOf(ticket);
        }
    }

    /** A ticket's organisation, derived from its project. */
    public Optional<Organisation> organisationOf(Ticket ticket) {
        synchronized (state) {
            return state.organisationOf(ticket);
        }
    }

    /** The agent that would work on a ticket (ticket, then project, then organisation assignment). */
    public Optional<AgentAssignment> agentFor(Ticket ticket) {
        synchronized (state) {
            return state.settings.resolveAgent(ticket);
        }
    }

    public boolean isAgentRunning(String ticketId) {
        return state.runner.isRunning(ticketId);
    }

    public int runningAgents() {
        return state.runner.runningCount();
    }

    /** Whether the ticket's branch is being prepared for an agent. */
    public boolean isPreparing(String ticketId) {
        synchronized (state) {
            return state.preparing.contains(ticketId);
        }
    }

    /** Whether Juno is pushing the ticket's branch / opening its PR / posting its review or merge. */
    public boolean isOpeningPullRequest(String ticketId) {
        return state.delivering.contains(ticketId);
    }

    public String readLog(String ticketId) {
        return state.store.readLog(ticketId);
    }

    public JunoStore store() {
        return state.store;
    }

    public SkillLibrary skillLibrary() {
        return skills;
    }

    /** Recent output from the MCP servers, for troubleshooting. */
    public List<String> trackerLog() {
        return List.copyOf(state.trackerLog);
    }

    // ---- tickets and settings -----------------------------------------------------------------

    /** Creates a Pending ticket and returns a snapshot of it. */
    public Ticket createTicket(String title, String description, String projectId) {
        Ticket ticket;
        synchronized (state) {
            // The id is internal only (keys, file names); tickets are always shown by their title.
            String id;
            do {
                id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            } while (state.find(id).isPresent());
            ticket = new Ticket(id, title, description);
            ticket.setProjectId(projectId);
            state.board.getTickets().add(ticket);
            state.persist();
            ticket = snapshot(ticket);
        }
        state.fireChanged();
        return ticket;
    }

    /**
     * Saves the fields a person edits in a ticket's Details (title, description, instructions, design doc,
     * branches until work has started, agent) from an edited snapshot. Everything else (status, runs, links)
     * belongs to Juno and is left as it is.
     */
    public void saveTicket(Ticket edited) {
        synchronized (state) {
            Optional<Ticket> found = state.find(edited.getId());
            if (found.isEmpty()) {
                return;
            }
            Ticket t = found.get();
            t.setTitle(edited.getTitle());
            t.setDescription(edited.getDescription());
            t.setInitialInstructions(edited.getInitialInstructions());
            t.setGenerateDesignDoc(edited.isGenerateDesignDoc());
            t.setDesignDoc(edited.getDesignDoc());
            t.setDesignDocSource(edited.getDesignDocSource());
            t.setDesignDocApproved(edited.isDesignDocApproved());
            if (!t.isBranchLocked()) {
                t.setBaseBranch(edited.getBaseBranch());
                t.setBranchName(edited.getBranchName());
            }
            t.setAgentProfileId(edited.getAgentProfileId());
            t.touch();
            state.persist();
        }
        state.fireChanged();
    }

    /**
     * Deletes a ticket from Juno and everything it left on this computer: its worktree (with any uncommitted
     * changes), logs, run records and terminal scripts. Its branch, pull request and linked issue are kept.
     */
    public void deleteTicket(String ticketId) {
        state.runner.stop(ticketId);
        Ticket removed;
        Path repo;
        synchronized (state) {
            removed = state.find(ticketId).orElse(null);
            repo = removed == null ? null : state.projectOf(removed).map(p -> Path.of(p.getPath())).orElse(null);
            state.board.getTickets().removeIf(t -> t.getId().equals(ticketId));
            state.persist();
        }
        state.reviewStarts.remove(ticketId);
        state.journals.remove(ticketId);
        terminals.forget(ticketId);
        state.fireChanged();
        if (removed != null) {
            housekeeping.deleteTicketFiles(removed, repo);
        }
    }

    /**
     * Links a ticket to a project. Its issue link belongs to the old organisation's credentials, so it is dropped
     * when the ticket moves to a project in a different organisation.
     *
     * @return true if the issue link was removed
     */
    public boolean assignProject(String ticketId, String projectId) {
        boolean dropped = false;
        synchronized (state) {
            Optional<Ticket> found = state.find(ticketId);
            if (found.isEmpty()) {
                return false;
            }
            Ticket ticket = found.get();
            String oldOrg = state.organisationOf(ticket).map(Organisation::getId).orElse(null);
            String newOrg = state.settings.project(projectId).map(Project::getOrgId).orElse(null);
            if (oldOrg != null && !oldOrg.equals(newOrg) && ticket.getIssue() != null) {
                ticket.setIssue(null);
                dropped = true;
            }
            ticket.setProjectId(projectId);
            ticket.touch();
            state.persist();
        }
        state.fireChanged();
        return dropped;
    }

    /** Whether {@link #assignProject} would drop the ticket's issue link. */
    public boolean wouldDropLinks(Ticket ticket, String projectId) {
        synchronized (state) {
            String oldOrg = state.organisationOf(ticket).map(Organisation::getId).orElse(null);
            String newOrg = state.settings.project(projectId).map(Project::getOrgId).orElse(null);
            return ticket.getIssue() != null && oldOrg != null && !oldOrg.equals(newOrg);
        }
    }

    /**
     * Manually moves a ticket between columns (not into the columns only agents use, and not while an agent
     * runs). Moving an interrupted ticket abandons the interrupted run.
     */
    public void move(String ticketId, TicketStatus target) throws JunoException {
        switch (target) {
            case WORKING -> throw new JunoException("Use Start to put a ticket into Processing");
            case IN_AI_REVIEW -> throw new JunoException("Use Run AI review to put a ticket into In AI review");
            case RESOLVING_CONFLICTS ->
                    throw new JunoException("Use Resolve conflicts to put a ticket into Resolving conflicts");
            default -> {
                // allowed
            }
        }
        if (state.runner.isRunning(ticketId)) {
            throw new JunoException("Stop the running agent before moving " + state.nameOf(ticketId));
        }
        if (state.busy(ticketId)) {
            throw new JunoException(state.nameOf(ticketId) + " is busy; wait for it to finish first");
        }
        Path abandonMerge = null;
        synchronized (state) {
            Ticket t = state.require(ticketId);
            if (t.getRunKind() == RunKind.CONFLICTS && t.getStatus() == TicketStatus.AWAITING_DECISION
                    && target != TicketStatus.AWAITING_DECISION && t.getWorktreePath() != null) {
                abandonMerge = Path.of(t.getWorktreePath()); // giving up on the resolution
            }
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
            state.persist();
        }
        if (abandonMerge != null && Files.isDirectory(abandonMerge)) {
            state.git(ticketId).abortMerge(abandonMerge);
            state.log(ticketId, "[juno] the unfinished conflict resolution was abandoned; the branch is as it was");
        }
        state.fireChanged();
    }

    public void updateSettings(JunoSettings newSettings) {
        synchronized (state) {
            state.settings = newSettings;
            state.store.saveSettings(newSettings);
        }
        state.trackers.reset();
        state.fireChanged();
    }

    /** Writes the ticket's design doc to the design doc folder as a .txt file and returns its path. */
    public Path exportDesignDoc(String ticketId) throws JunoException {
        Ticket t;
        String projectName;
        Path folder;
        synchronized (state) {
            t = snapshot(state.require(ticketId));
            projectName = state.projectOf(t).map(Project::getName).orElse(null);
            folder = state.settings.resolvedDesignDocFolder();
        }
        try {
            Path file = DesignDocs.export(t, projectName, folder);
            synchronized (state) {
                state.find(ticketId).ifPresent(live -> live.setDesignDocFile(file.toString()));
                state.persist();
            }
            state.log(ticketId, "[juno] design doc exported to " + file);
            state.fireChanged();
            return file;
        } catch (IOException e) {
            throw new JunoException("Could not export the design doc: " + e.getMessage(), e);
        }
    }

    // ---- agents -------------------------------------------------------------------------------

    /** Starts an agent on the ticket (a design conversation first, if the ticket asks for a design doc). */
    public void startWork(String ticketId) throws JunoException {
        runs.startWork(ticketId);
    }

    /** Approves the ticket's design doc and starts implementing it. */
    public void approveDesign(String ticketId) throws JunoException {
        runs.approveDesign(ticketId);
    }

    /** Answers an agent's question (or retries after a failure) and resumes its session. */
    public void respond(String ticketId, String answer) throws JunoException {
        runs.respond(ticketId, answer);
    }

    /** Starts an AI review; returns the number of comments handed to the agent (0: completed immediately). */
    public int startReview(String ticketId) throws JunoException {
        return runs.startReview(ticketId);
    }

    /** Resolves merge conflicts with the updated base branch; returns a message when there was nothing to do. */
    public Optional<String> resolveConflicts(String ticketId) throws JunoException {
        return runs.resolveConflicts(ticketId);
    }

    /** Resumes a run cut short by Juno closing, a crash or a restart. */
    public void resumeInterrupted(String ticketId) throws JunoException {
        runs.resumeInterrupted(ticketId);
    }

    public void stopWork(String ticketId) {
        runs.stopWork(ticketId);
    }

    /** Pushes and opens the PR / posts the review / completes the merge again after a failure (Retry). */
    public void deliver(String ticketId) {
        delivery.deliver(ticketId);
    }

    /** Brings the ticket's terminal to the front, opening one if it isn't running. */
    public String showTerminal(String ticketId) throws JunoException {
        return terminals.showTerminal(ticketId);
    }

    public boolean isTerminalOpen(String ticketId) {
        return terminals.isTerminalOpen(ticketId);
    }

    /** Runs the start checks (used by tests). */
    void preflight(String ticketId, boolean checkRemote) throws JunoException {
        new Preflight(state).check(ticketId, checkRemote);
    }

    // ---- GitHub / GitLab issues ---------------------------------------------------------------

    /** Suggested GitLab project / GitHub repo for a ticket: its linked issue's, else its project's. */
    public String defaultLocation(Ticket ticket, TrackerKind kind) {
        return sync.defaultLocation(ticket, kind);
    }

    public List<IssueData> listIssues(String orgId, TrackerKind kind, String location) throws JunoException {
        return sync.listIssues(orgId, kind, location);
    }

    public void importIssue(String ticketId, String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        sync.importIssue(ticketId, projectId, kind, location, number);
    }

    /** Creates a Pending ticket from an issue; returns a snapshot of it. */
    public Ticket importAsNewTicket(String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        Ticket created = sync.importAsNewTicket(projectId, kind, location, number);
        return ticket(created.getId()).orElse(created);
    }

    public ExternalRef upload(String ticketId, TrackerKind kind, String location) throws JunoException {
        return sync.upload(ticketId, kind, location);
    }

    public void unlink(String ticketId) {
        sync.unlink(ticketId);
    }

    // ---- consoles -----------------------------------------------------------------------------

    /** What the agent terminals show and can do. */
    private AgentConsoles.Backend consoleBackend() {
        return new AgentConsoles.Backend() {
            @Override
            public Optional<AgentConsoles.TicketState> state(String ticketId) {
                synchronized (state) {
                    return state.find(ticketId).map(t -> new AgentConsoles.TicketState(
                            t.getStatus().name(), t.getStatus().label(), t.getTitle(),
                            t.getStatus() == TicketStatus.AWAITING_DECISION ? t.getPendingQuestion() : null,
                            state.runner.isRunning(ticketId) || state.preparing.contains(ticketId),
                            state.delivering.contains(ticketId),
                            t.getPullRequest() == null ? null : t.getPullRequest().url(),
                            t.getBranchName(), t.isDesignAwaitingApproval(),
                            t.getDesignDoc().isBlank() ? null : t.getDesignDoc()));
                }
            }

            @Override
            public Path logFile(String ticketId) {
                return state.store.logFile(ticketId);
            }

            @Override
            public void answer(String ticketId, String answer) throws JunoException {
                respond(ticketId, answer);
            }

            @Override
            public String exportDesignDoc(String ticketId) throws JunoException {
                return BoardService.this.exportDesignDoc(ticketId).toString();
            }
        };
    }

    @Override
    public void close() {
        state.runner.shutdown();
        state.trackers.close();
        state.mcpServers.close();
        terminals.close();
    }
}
