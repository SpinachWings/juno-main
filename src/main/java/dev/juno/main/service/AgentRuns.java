package dev.juno.main.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.main.agent.AgentOutcome;
import dev.juno.main.agent.AgentRunner;
import dev.juno.main.agent.DesignDocs;
import dev.juno.main.agent.PromptBuilder;
import dev.juno.main.agent.RunJournal;
import dev.juno.main.git.GitException;
import dev.juno.main.git.GitWorkspace;
import dev.juno.main.integration.IssueData;
import dev.juno.main.integration.ReviewComment;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.ActiveRun;
import dev.juno.main.model.AgentAssignment;
import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.SkillEntry;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.skills.SkillLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Starts, resumes and finishes agent runs: work, design docs, AI reviews and merge conflict resolutions. Receives
 * the agent processes' output and outcomes, and hands finished runs to {@link Delivery}.
 */
final class AgentRuns implements AgentRunner.Listener {

    private final BoardState state;
    private final Preflight preflight;
    private final Delivery delivery;
    private final Housekeeping housekeeping;
    /** Opens a ticket's terminal when a run starts; set once the consoles exist. */
    private volatile Terminals terminals;

    AgentRuns(BoardState state, Preflight preflight, Delivery delivery, Housekeeping housekeeping) {
        this.state = state;
        this.preflight = preflight;
        this.delivery = delivery;
        this.housekeeping = housekeeping;
    }

    void setTerminals(Terminals terminals) {
        this.terminals = terminals;
    }

    /**
     * Starts an agent on the ticket: creates (or reuses) the ticket's branch in its own git worktree, then runs
     * the resolved agent there with the ticket and its initial instructions as the prompt.
     */
    public void startWork(String ticketId) throws JunoException {
        RunKind kind = RunKind.WORK;
        synchronized (state) {
            Ticket t = state.require(ticketId);
            if (t.isGenerateDesignDoc() && !t.isDesignDocApproved()) {
                if (!t.getDesignDoc().isBlank() && "user".equals(t.getDesignDocSource())) {
                    // The user supplied their own design doc: use it as-is.
                    t.setDesignDocApproved(true);
                    state.persist();
                } else {
                    kind = RunKind.DESIGN;
                }
            }
        }
        beginRun(ticketId, kind, null, null, null);
    }

    /** Approves the ticket's design doc and starts implementing it. */
    public void approveDesign(String ticketId) throws JunoException {
        synchronized (state) {
            Ticket t = state.require(ticketId);
            if (t.getDesignDoc().isBlank()) {
                throw new JunoException(BoardState.name(t) + " has no design doc to approve");
            }
            t.setDesignDocApproved(true);
            t.setPendingQuestion(null);
            t.touch();
            state.persist();
        }
        log(ticketId, "");
        log(ticketId, "=== Design doc approved; starting implementation ===");
        beginRun(ticketId, RunKind.WORK, null, null, null);
    }

    /** Answers an agent's question (or retries after a failure / interruption) and resumes its session. */
    public void respond(String ticketId, String answer) throws JunoException {
        if (answer == null || answer.isBlank()) {
            throw new JunoException("Enter a response for the agent");
        }
        RunKind kind;
        synchronized (state) {
            Ticket t = state.require(ticketId);
            if (t.getStatus() != TicketStatus.AWAITING_DECISION) {
                throw new JunoException(BoardState.name(t) + " is not waiting for a decision");
            }
            kind = t.getRunKind();
        }
        if (kind == RunKind.DESIGN && DesignDocs.isApproval(answer)) {
            approveDesign(ticketId);
            return;
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
        synchronized (state) {
            if (state.preparing.contains(ticketId) || state.runner.isRunning(ticketId) || state.delivering.contains(ticketId)) {
                throw new JunoException(state.nameOf(ticketId) + " already has an agent running");
            }
            t = state.require(ticketId);
            if (t.getStatus() != TicketStatus.REVIEW) {
                throw new JunoException(BoardState.name(t) + " is not in the Review column");
            }
            pr = t.getPullRequest();
            if (pr == null) {
                throw new JunoException(BoardState.name(t) + " has no pull request to review yet");
            }
            Project project = state.requireProject(t);
            org = state.settings.organisationOf(project)
                    .orElseThrow(() -> new JunoException("Project '" + project.getName() + "' has no organisation"));
            since = BoardState.parseInstant(t.getLastReviewAt());
            prOpened = BoardState.parseInstant(t.getPullRequestOpenedAt());
        }
        List<ReviewComment> feedback = new ArrayList<>();
        try {
            for (ReviewComment c : state.trackers.pullRequestComments(org, pr.tracker(), pr.location(), pr.number())) {
                if (!c.resolved() && !c.postedByJuno() && !c.body().isBlank()) {
                    feedback.add(c);
                }
            }
            ExternalRef issue = t.getIssue();
            if (issue != null && org.tracker(issue.tracker()).isEnabled()) {
                for (IssueData.Comment c : state.trackers.getIssue(org, issue.tracker(), issue.location(), issue.number(),
                        true).comments()) {
                    boolean afterPr = prOpened == null
                            || BoardState.parseInstant(c.createdAt()) == null || BoardState.parseInstant(c.createdAt()).isAfter(prOpened);
                    if (afterPr && !c.body().isBlank() && !ReviewComment.isJunoText(c.body())) {
                        feedback.add(new ReviewComment("issue", 0, c.author(), c.body(), null, 0, null,
                                c.createdAt(), false));
                    }
                }
            }
        } catch (McpException e) {
            throw new JunoException("Could not read the review comments: " + e.getMessage(), e);
        }
        boolean anyNew = feedback.stream().anyMatch(c -> since == null || BoardState.parseInstant(c.createdAt()) == null
                || BoardState.parseInstant(c.createdAt()).isAfter(since));
        if (!anyNew) {
            // Nothing to address: the review completes immediately and the ticket stays in Review.
            String summary = "No " + (since == null ? "" : "new ") + "comments on " + pr.display()
                    + (since == null ? "" : " since the last AI review") + "; nothing to change.";
            synchronized (state) {
                t.setReviewCount(t.getReviewCount() + 1);
                t.setLastReviewAt(Instant.now().toString());
                t.setLastReviewSummary(summary);
                t.touch();
                state.persist();
            }
            log(ticketId, "");
            log(ticketId, "=== AI review " + t.getReviewCount() + ": " + summary + " ===");
            state.fireChanged();
            return 0;
        }
        state.reviewStarts.put(ticketId, Instant.now());
        beginRun(ticketId, RunKind.REVIEW, null, new ReviewInput(feedback, since), null);
        return feedback.size();
    }

    /** Thrown inside {@link #beginRun} when a conflict resolution finds nothing to resolve. */
    private static final class NoConflictsException extends JunoException {
        NoConflictsException(String message) {
            super(message);
        }
    }

    /** What a conflict resolution run needs to know (filled in after Juno has started the merge). */
    private record ConflictContext(String baseRef, List<String> files, List<PromptBuilder.MergedTicket> merged,
                                   List<String> commits) {
    }

    /**
     * Resolves merge conflicts between a ticket in Review and its updated base branch (typically after another
     * ticket was merged into it). Juno merges {@code origin/<base>} into the ticket's branch; if that conflicts,
     * an agent resolves the conflicts keeping the requirements of both this ticket and the merged work, then Juno
     * completes the merge and pushes it to the same pull request.
     *
     * @return a message when there was nothing to resolve (the run doesn't start), empty when the agent started
     */
    public Optional<String> resolveConflicts(String ticketId) throws JunoException {
        synchronized (state) {
            if (state.preparing.contains(ticketId) || state.runner.isRunning(ticketId) || state.delivering.contains(ticketId)) {
                throw new JunoException(state.nameOf(ticketId) + " already has an agent running");
            }
            Ticket t = state.require(ticketId);
            if (t.getStatus() != TicketStatus.REVIEW) {
                throw new JunoException(BoardState.name(t) + " is not in the Review column");
            }
            if (t.getPullRequest() == null) {
                throw new JunoException(BoardState.name(t) + " has no pull request yet");
            }
        }
        try {
            beginRun(ticketId, RunKind.CONFLICTS, null, null, null);
            return Optional.empty();
        } catch (NoConflictsException e) {
            log(ticketId, "[juno] " + e.getMessage());
            return Optional.of(e.getMessage());
        }
    }

    /** Starts the merge and gathers what the agent needs; throws {@link NoConflictsException} if it's clean. */
    private ConflictContext startMerge(String ticketId, Path repo, Path worktree, String base, String branch)
            throws JunoException {
        GitWorkspace git = state.git(ticketId);
        GitWorkspace.MergeResult merge;
        try {
            merge = git.mergeBase(worktree, base);
        } catch (GitException e) {
            throw new JunoException("Could not merge the latest " + base + ": " + e.getMessage(), e);
        }
        if (!merge.conflicts()) {
            housekeeping.releaseBranch(ticketId, repo, worktree, branch);
            throw new NoConflictsException(merge.upToDate()
                    ? "Branch " + branch + " already contains the latest " + base + ": there's nothing to resolve."
                    : "Branch " + branch + " merges cleanly with the latest " + base
                            + ": there are no conflicts to resolve.");
        }
        log(ticketId, "[juno] merging " + merge.baseRef() + " into " + branch + " conflicts in: "
                + String.join(", ", merge.conflictedFiles()));
        List<String> commits = git.commitsTouching(worktree, merge.forkPoint(), merge.baseRef(),
                merge.conflictedFiles());
        List<PromptBuilder.MergedTicket> merged = new ArrayList<>();
        String history = String.join("\n", commits);
        synchronized (state) {
            Ticket self = state.require(ticketId);
            for (Ticket other : state.board.getTickets()) {
                if (other == self || !Objects.equals(other.getProjectId(), self.getProjectId())) {
                    continue;
                }
                boolean byBranch = other.getBranchName() != null && !other.getBranchName().isBlank()
                        && history.contains(other.getBranchName());
                boolean byPr = other.getPullRequest() != null && Pattern
                        .compile("[#!]" + other.getPullRequest().number() + "\\b").matcher(history).find();
                if (byBranch || byPr) {
                    merged.add(new PromptBuilder.MergedTicket(other.getTitle(), other.getDescription(),
                            other.getInitialInstructions(), other.getDesignDoc(),
                            other.getPullRequest() == null ? null : other.getPullRequest().display()));
                }
            }
        }
        if (!merged.isEmpty()) {
            log(ticketId, "[juno] conflicting work already merged: "
                    + String.join(", ", merged.stream().map(m -> "'" + m.title() + "'").toList()));
        }
        return new ConflictContext(merge.baseRef(), merge.conflictedFiles(), merged, commits);
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
        synchronized (state) {
            Ticket t = state.require(ticketId);
            if (!t.isInterrupted()) {
                throw new JunoException(BoardState.name(t) + " is not interrupted");
            }
            run = t.getActiveRun();
            deliveryOnly = run == null && t.isDeliveryPending();
            kind = run != null ? run.kind() : t.getRunKind();
            session = run != null && run.sessionId() != null ? run.sessionId() : t.getAgentSessionId();
            source = run != null && run.journal() != null ? Path.of(run.journal())
                    : t.getLastJournal() != null ? Path.of(t.getLastJournal()) : null;
        }
        if (run != null && AgentRunner.killOrphan(run.pid(), BoardState.parseInstant(run.startedAt()))) {
            log(ticketId, "[juno] stopped an agent process left over from the interrupted run");
        }
        log(ticketId, "");
        log(ticketId, "=== Resuming interrupted " + (deliveryOnly ? "push / pull request" : kind.label()) + " ===");
        if (deliveryOnly) {
            synchronized (state) {
                state.find(ticketId).ifPresent(t -> t.setInterrupted(false));
                state.persist();
            }
            state.fireChanged();
            Thread.ofPlatform().daemon().name("deliver-" + ticketId).start(() -> delivery.deliver(ticketId));
            return;
        }
        beginRun(ticketId, kind, null, null, new Recovery(session, source));
    }

    private void beginRun(String ticketId, RunKind kind, String answer, ReviewInput review, Recovery recovery)
            throws JunoException {
        boolean resume = answer != null;
        preflight.check(ticketId, !resume);
        List<SkillEntry> packs;
        Path repo;
        Path worktree;
        String base;
        String branch;
        synchronized (state) {
            if (state.preparing.contains(ticketId) || state.runner.isRunning(ticketId) || state.delivering.contains(ticketId)) {
                throw new JunoException(state.nameOf(ticketId) + " is already starting or running");
            }
            Ticket t = state.require(ticketId);
            Project project = state.requireProject(t);
            state.settings.resolveAgent(t).orElseThrow(() -> new JunoException(
                    "No agent is assigned to " + BoardState.name(t) + ", project '" + project.getName()
                            + "' or its organisation. Assign one in the ticket's Details or in Settings."));
            preflight.checkCapacity();
            if (t.getBranchName().isBlank()) {
                t.setBranchName(preflight.uniqueBranchName(t));
                state.persist();
            }
            repo = Path.of(project.getPath());
            packs = List.copyOf(state.settings.skillsFor(project));
            worktree = t.getWorktreePath() != null
                    ? Path.of(t.getWorktreePath())
                    : state.store.home().resolve("worktrees").resolve(BoardState.safeName(t.getId()));
            base = t.getBaseBranch();
            branch = t.getBranchName();
            state.preparing.add(ticketId);
        }
        state.fireChanged();
        try {
            log(ticketId, "");
            log(ticketId, "=== " + (kind.onPullRequest() && !resume ? capitalise(kind.label()) + ": state.preparing"
                    : "Preparing") + " branch " + branch + " (from " + base + ") ===");
            GitWorkspace.Prepared prepared;
            boolean fresh = !resume && recovery == null;
            try {
                GitWorkspace git = state.git(ticketId);
                prepared = git.prepare(repo, worktree, base, branch);
                if (fresh) {
                    git.abortMerge(prepared.worktree()); // left over from an abandoned conflict resolution
                }
                if (kind.onPullRequest() && fresh) {
                    git.syncWithRemote(prepared.worktree(), branch);
                }
            } catch (GitException e) {
                log(ticketId, "[juno] " + e.getMessage());
                throw new JunoException("Could not prepare the ticket's branch: " + e.getMessage(), e);
            }
            final ConflictContext conflictContext = kind == RunKind.CONFLICTS && fresh
                    ? startMerge(ticketId, repo, prepared.worktree(), base, branch) : null;
            // Skills that apply to this ticket (global, its organisation, its project) go into the worktree.
            List<String> skillNames = List.of();
            try {
                state.git(ticketId).exclude(prepared.worktree(), "/.claude/skills/" + SkillLibrary.WORKTREE_PREFIX + "*/");
                skillNames = SkillLibrary.materialise(prepared.worktree(), packs);
                if (!skillNames.isEmpty()) {
                    log(ticketId, "[juno] skills available to the agent: " + String.join(", ", skillNames));
                }
            } catch (IOException | GitException e) {
                log(ticketId, "[juno] could not set up skills: " + e.getMessage());
            }
            boolean haveSkills = !skillNames.isEmpty();

            // For recovery: what the interrupted run did, and where the branch stands now.
            String recap = null;
            String gitState = null;
            String originalPrompt = null;
            if (recovery != null) {
                recap = recovery.sourceJournal() != null && Files.exists(recovery.sourceJournal())
                        ? RunJournal.recap(recovery.sourceJournal(), 12_000)
                        : "(No journal was recorded for the interrupted run.)";
                String baseRef;
                synchronized (state) {
                    Ticket t = state.require(ticketId);
                    baseRef = t.getBaseRef() != null ? t.getBaseRef() : base;
                }
                gitState = state.git(ticketId).describe(prepared.worktree(), baseRef);
                originalPrompt = recovery.sourceJournal() == null ? null : RunJournal.readPrompt(recovery.sourceJournal());
            }
            String recapText = recap;
            String gitText = gitState;
            String original = originalPrompt;

            AgentRunner.Request request;
            Path journal;
            ActiveRun previousRun;
            synchronized (state) {
                Ticket t = state.require(ticketId);
                t.setWorktreePath(prepared.worktree().toString());
                if (prepared.baseRef() != null) {
                    t.setBaseRef(prepared.baseRef());
                }
                t.setRunKind(kind);
                t.setPullRequestError(null);
                state.persist();
                String session = recovery != null ? recovery.sessionId() : resume ? t.getAgentSessionId() : null;
                String mode = recovery != null ? (session != null ? "recovery-resume" : "recovery-fresh")
                        : resume ? "resume" : "fresh";
                request = buildRequest(t, session, prepared.worktree(), haveSkills, () -> {
                    Project p = state.requireProjectUnchecked(t);
                    if (recovery != null) {
                        if (session != null) {
                            return PromptBuilder.recoveryResume(t, recapText, gitText);
                        }
                        String basePrompt = original != null ? original
                                : kind == RunKind.CONFLICTS
                                ? "You are resolving merge conflicts for the ticket \"" + t.getTitle() + "\": Juno "
                                        + "started merging origin/" + t.getBaseBranch() + " into its branch. Keep the "
                                        + "requirements of both this ticket and the merged work, leave no conflict "
                                        + "markers, and don't commit: Juno completes the merge."
                                : kind == RunKind.REVIEW
                                ? "You are addressing review feedback on " + (t.getPullRequest() == null ? "the pull request"
                                        : t.getPullRequest().url()) + " for the ticket \"" + t.getTitle() + "\""
                                        + "."
                                : PromptBuilder.initial(t, p);
                        return PromptBuilder.recoveryFresh(basePrompt, t, recapText, gitText);
                    }
                    if (!resume) {
                        return switch (kind) {
                            case REVIEW -> PromptBuilder.review(t, p, review.comments(), review.newSince());
                            case CONFLICTS -> PromptBuilder.conflicts(t, p, conflictContext.baseRef(),
                                    conflictContext.files(), conflictContext.merged(), conflictContext.commits());
                            case DESIGN -> PromptBuilder.design(t, p);
                            case WORK -> PromptBuilder.initial(t, p);
                        };
                    }
                    if (session != null) {
                        return PromptBuilder.resume(t, answer);
                    }
                    return PromptBuilder.initial(t, p)
                            + "\n## Additional guidance from a human\n" + answer.strip() + "\n";
                });
                try {
                    journal = RunJournal.create(state.store.runsDir(ticketId), kind, request.prompt());
                } catch (IOException e) {
                    throw new JunoException("Could not create the run journal: " + e.getMessage(), e);
                }
                previousRun = t.getActiveRun();
                RunJournal.event(journal, "start", "mode", mode, "kind", kind.name(),
                        "session", session == null ? "" : session, "workDir", prepared.worktree().toString());
                t.setActiveRun(new ActiveRun(kind, mode, session, Instant.now().toString(), journal.toString(),
                        recovery == null || recovery.sourceJournal() == null ? null
                                : recovery.sourceJournal().toString(), 0));
                state.persist();
                state.journals.put(ticketId, journal);
            }
            log(ticketId, "[juno] journal: " + journal);
            housekeeping.pruneRuns(ticketId);
            try {
                launch(request, kind.runningStatus());
            } catch (JunoException e) {
                synchronized (state) {
                    state.journals.remove(ticketId);
                    state.find(ticketId).ifPresent(t -> t.setActiveRun(previousRun));
                    state.persist();
                }
                RunJournal.event(journal, "launch-failed", "error", String.valueOf(e.getMessage()));
                throw e;
            }
        } finally {
            synchronized (state) {
                state.preparing.remove(ticketId);
            }
            state.fireChanged();
        }
        terminals.openTerminalIfEnabled(ticketId, kind);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Resolves project, organisation and agent for a run. Caller holds the lock. */
    private AgentRunner.Request buildRequest(Ticket t, String resumeSession, Path workDir, boolean skillsAvailable,
                                             Supplier<String> prompt)
            throws JunoException {
        Project project = state.requireProject(t);
        Organisation org = state.settings.organisationOf(project)
                .orElseThrow(() -> new JunoException("Project '" + project.getName() + "' has no organisation"));
        AgentAssignment agent = state.settings.resolveAgent(t).orElseThrow(() -> new JunoException(
                "No agent is assigned to " + BoardState.name(t) + ", project '" + project.getName()
                        + "' or organisation '" + org.getName() + "'. Assign one in the ticket's Details "
                        + "or in Settings."));
        preflight.checkCapacity();

        AgentProfile profile = state.store.copy(agent.profile(), AgentProfile.class);
        String extra = project.getExtraAllowedTools();
        if (extra != null && !extra.isBlank()) {
            String base = profile.getAllowedTools() == null ? "" : profile.getAllowedTools().strip();
            profile.setAllowedTools((base + " " + extra.strip()).strip());
        }
        if (skillsAvailable) {
            String base = profile.getAllowedTools() == null ? "" : profile.getAllowedTools().strip();
            profile.setAllowedTools((base + " Skill").strip());
        }
        String label = "'" + agent.profile().getName() + "' (" + profile.getModel() + ", assigned on "
                + agent.level().label() + (agent.level() == AgentAssignment.Level.TICKET ? "" : " " + agent.owner())
                + ")";
        String text = prompt.get() + PromptBuilder.allowedCommands(profile.getAllowedTools());
        return new AgentRunner.Request(t.getId(), workDir, text, resumeSession, profile, label);
    }

    public void stopWork(String ticketId) {
        state.runner.stop(ticketId);
    }

    private void launch(AgentRunner.Request request, TicketStatus statusWhileRunning) throws JunoException {
        if (state.runner.isRunning(request.ticketId())) {
            throw new JunoException(request.ticketId() + " already has a running agent");
        }
        TicketStatus previousStatus;
        String previousQuestion;
        // Mark as Working before the process starts so a very fast finish can't be overwritten.
        synchronized (state) {
            Ticket t = state.require(request.ticketId());
            previousStatus = t.getStatus();
            previousQuestion = t.getPendingQuestion();
            t.setStatus(statusWhileRunning);
            t.setPendingQuestion(null);
            t.touch();
            state.persist();
        }
        long pid;
        try {
            pid = state.runner.start(request);
        } catch (IOException | IllegalStateException e) {
            synchronized (state) {
                state.find(request.ticketId()).ifPresent(t -> {
                    t.setStatus(previousStatus);
                    t.setPendingQuestion(previousQuestion);
                });
                state.persist();
            }
            state.fireChanged();
            throw new JunoException(e.getMessage(), e);
        }
        synchronized (state) {
            state.find(request.ticketId()).ifPresent(t -> {
                t.setInterrupted(false);
                t.setDeliveryPending(false);
                if (t.getActiveRun() != null) {
                    t.setActiveRun(t.getActiveRun().withPid(pid));
                }
            });
            state.persist();
        }
        state.fireChanged();
    }

    @Override
    public void log(String ticketId, String line) {
        state.log(ticketId, line);
    }

    @Override
    public void finished(String ticketId, AgentOutcome outcome) {
        boolean deliver = false;
        Path abandonMerge = null;
        Recovery fallback = null;
        Path journal = state.journals.remove(ticketId);
        if (journal != null) {
            RunJournal.event(journal, "finish", "outcome", outcome.kind().name(),
                    "message", outcome.message() == null ? "" : outcome.message());
        }
        synchronized (state) {
            Optional<Ticket> found = state.board.getTickets().stream().filter(t -> t.getId().equals(ticketId)).findFirst();
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
                state.persist();
            }
            if (fallback == null && kind == RunKind.DESIGN
                    && (outcome.kind() == AgentOutcome.Kind.COMPLETED || outcome.kind() == AgentOutcome.Kind.NEEDS_DECISION)) {
                String doc = DesignDocs.extract(outcome.fullText());
                if (doc != null) {
                    t.setDesignDoc(doc);
                    t.setDesignDocSource("agent");
                    t.setDesignDocApproved(false);
                }
                t.setStatus(TicketStatus.AWAITING_DECISION);
                if (outcome.kind() == AgentOutcome.Kind.NEEDS_DECISION) {
                    t.setPendingQuestion(outcome.message()
                            + (doc != null ? "\n\n(A draft design doc came with this question: see the terminal or Details.)" : ""));
                } else if (doc != null) {
                    t.setPendingQuestion("The design doc is ready for review. Approve it to start implementation "
                            + "(/approve), or reply with the changes you want. Export it as .txt with /export in the "
                            + "terminal, or from the ticket's Details.");
                } else {
                    t.setPendingQuestion("The agent finished without producing a design doc:\n" + outcome.message()
                            + "\n\nReply to continue the design conversation.");
                }
                t.touch();
                state.persist();
            } else if (fallback == null) {
            switch (outcome.kind()) {
                case COMPLETED -> {
                    if (kind == RunKind.REVIEW) {
                        t.setLastReviewSummary(outcome.message());
                    } else if (kind == RunKind.CONFLICTS) {
                        t.setLastConflictSummary(outcome.message());
                    } else {
                        t.setLastSummary(outcome.message());
                    }
                    t.setPendingQuestion(null);
                    if (t.getWorktreePath() != null && kind != RunKind.DESIGN) {
                        // Stay in Working (or AI review) until the branch is pushed; delivery moves it on.
                        deliver = true;
                        state.delivering.add(ticketId);
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
                    t.setStatus(kind.homeStatus());
                    t.setPendingQuestion(null);
                    if (kind == RunKind.CONFLICTS && t.getWorktreePath() != null) {
                        abandonMerge = Path.of(t.getWorktreePath());
                    }
                }
            }
            t.touch();
            state.persist();
            }
        }
        if (abandonMerge != null) {
            state.git(ticketId).abortMerge(abandonMerge); // stopped mid-resolution: leave the branch as it was
            log(ticketId, "[juno] conflict resolution stopped; the unfinished merge was abandoned");
        }
        state.fireChanged();
        if (deliver) {
            Thread.ofPlatform().daemon().name("deliver-" + ticketId).start(() -> delivery.deliver(ticketId));
        }
        if (fallback != null) {
            Recovery recovery = fallback;
            log(ticketId, "[juno] the interrupted session could not be resumed; starting a new session from the journal");
            Thread.ofPlatform().daemon().name("recover-" + ticketId).start(() -> {
                try {
                    beginRun(ticketId, kindOf(ticketId), null, null, recovery);
                } catch (JunoException e) {
                    log(ticketId, "[juno] could not resume: " + e.getMessage());
                    state.fireChanged();
                }
            });
        }
    }

    private RunKind kindOf(String ticketId) {
        synchronized (state) {
            return state.find(ticketId).map(Ticket::getRunKind).orElse(RunKind.WORK);
    
        }
    }

    @Override
    public void raw(String ticketId, String line) {
        Path journal = state.journals.get(ticketId);
        RunJournal.append(journal, line);
        // Persist the Claude session id as soon as the agent reports it, so an interruption can resume it.
        if (line.contains("\"init\"") && line.contains("session_id")) {
            try {
                JsonNode event = state.store.mapper().readTree(line);
                String sid = event.path("session_id").asText(null);
                if ("system".equals(event.path("type").asText()) && sid != null) {
                    synchronized (state) {
                        state.find(ticketId).ifPresent(t -> {
                            t.setAgentSessionId(sid);
                            if (t.getActiveRun() != null) {
                                t.setActiveRun(t.getActiveRun().withSessionId(sid));
                            }
                        });
                        state.persist();
                    }
                }
            } catch (IOException ignored) {
                // not JSON
            }
        }
    }

    /**
     * On start-up, any ticket that was mid-run or mid-push when Juno last stopped (closed, crashed, computer
     * restarted) is marked interrupted. It stays in its column, greyed out, until Resume picks it up again.
     */
    void recoverInterruptedWork() {
        boolean changed = false;
        for (Ticket t : state.board.getTickets()) {
            boolean midRun = t.getActiveRun() != null || t.isDeliveryPending()
                    || t.getStatus().isAgentOnly();
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
            state.persist();
        }
    }
}
