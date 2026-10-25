package dev.juno.main.model;

import java.time.Instant;

/** A unit of development work shown on the board and handed to an agent. */
public final class Ticket {

    private String id;
    private String title = "";
    private String description = "";
    private TicketStatus status = TicketStatus.PENDING;
    private String projectId;
    private ExternalRef gitlab;
    private ExternalRef github;
    /** Agent profile override for this ticket; null = inherit from the project, then the organisation. */
    private String agentProfileId;

    /** Extra instructions from the user, given to the agent when it first picks up the ticket. */
    private String initialInstructions = "";
    /** Branch the work branches off and the pull request targets. */
    private String baseBranch = "main";
    /** Branch the agent works on; blank = generated from the ticket id and title on Start. */
    private String branchName = "";
    /** Git worktree the agent works in (set once work starts; the branch is fixed from then on). */
    private String worktreePath;
    /** Commit the branch was created from (e.g. {@code origin/main}), used to check for new commits. */
    private String baseRef;
    /** The pull/merge request opened when the agent finished. */
    private PullRequestRef pullRequest;
    /** Why pushing / opening the pull request / posting the review failed, if it did. */
    private String pullRequestError;
    /** When the pull request was opened (ISO-8601). */
    private String pullRequestOpenedAt;
    /** What the current or most recent agent run is for. */
    private RunKind runKind = RunKind.WORK;
    /** When the last AI review started (ISO-8601); comments after this are new. */
    private String lastReviewAt;
    /** The review agent's summary from its last run. */
    private String lastReviewSummary;
    /** How many AI reviews have completed. */
    private int reviewCount;
    /** The run in progress (persisted so an interrupted run can be resumed), or null. */
    private ActiveRun activeRun;
    /** True when a run or a push was cut short (Juno closed, crash, restart) and can be resumed. */
    private boolean interrupted;
    /** True while Juno is pushing the branch / opening the PR / posting the review. */
    private boolean deliveryPending;
    /** Journal of the most recent finished run. */
    private String lastJournal;

    /** Claude Code session id of the most recent agent run, used to resume after a decision. */
    private String agentSessionId;
    /** The agent's question while the ticket is awaiting a decision. */
    private String pendingQuestion;
    /** The agent's final summary from its last successful run. */
    private String lastSummary;

    private String createdAt = Instant.now().toString();
    private String updatedAt = createdAt;

    public Ticket() {
    }

    public Ticket(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public void touch() {
        updatedAt = Instant.now().toString();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public ExternalRef getRef(TrackerKind kind) {
        return kind == TrackerKind.GITLAB ? gitlab : github;
    }

    public void setRef(TrackerKind kind, ExternalRef ref) {
        if (kind == TrackerKind.GITLAB) {
            gitlab = ref;
        } else {
            github = ref;
        }
    }

    public String getAgentProfileId() {
        return agentProfileId;
    }

    public void setAgentProfileId(String agentProfileId) {
        this.agentProfileId = agentProfileId;
    }

    public String getInitialInstructions() {
        return initialInstructions == null ? "" : initialInstructions;
    }

    public void setInitialInstructions(String initialInstructions) {
        this.initialInstructions = initialInstructions == null ? "" : initialInstructions;
    }

    public String getBaseBranch() {
        return baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch.strip();
    }

    public String getBranchName() {
        return branchName == null ? "" : branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName == null ? "" : branchName.strip();
    }

    public String getWorktreePath() {
        return worktreePath;
    }

    public void setWorktreePath(String worktreePath) {
        this.worktreePath = worktreePath;
    }

    public String getBaseRef() {
        return baseRef;
    }

    public void setBaseRef(String baseRef) {
        this.baseRef = baseRef;
    }

    public PullRequestRef getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(PullRequestRef pullRequest) {
        this.pullRequest = pullRequest;
    }

    public String getPullRequestError() {
        return pullRequestError;
    }

    public void setPullRequestError(String pullRequestError) {
        this.pullRequestError = pullRequestError;
    }

    public String getPullRequestOpenedAt() {
        return pullRequestOpenedAt;
    }

    public void setPullRequestOpenedAt(String pullRequestOpenedAt) {
        this.pullRequestOpenedAt = pullRequestOpenedAt;
    }

    public RunKind getRunKind() {
        return runKind == null ? RunKind.WORK : runKind;
    }

    public void setRunKind(RunKind runKind) {
        this.runKind = runKind;
    }

    public String getLastReviewAt() {
        return lastReviewAt;
    }

    public void setLastReviewAt(String lastReviewAt) {
        this.lastReviewAt = lastReviewAt;
    }

    public String getLastReviewSummary() {
        return lastReviewSummary;
    }

    public void setLastReviewSummary(String lastReviewSummary) {
        this.lastReviewSummary = lastReviewSummary;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public ActiveRun getActiveRun() {
        return activeRun;
    }

    public void setActiveRun(ActiveRun activeRun) {
        this.activeRun = activeRun;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    public void setInterrupted(boolean interrupted) {
        this.interrupted = interrupted;
    }

    public boolean isDeliveryPending() {
        return deliveryPending;
    }

    public void setDeliveryPending(boolean deliveryPending) {
        this.deliveryPending = deliveryPending;
    }

    public String getLastJournal() {
        return lastJournal;
    }

    public void setLastJournal(String lastJournal) {
        this.lastJournal = lastJournal;
    }

    /** Whether work has started, which fixes the base and branch names. */
    public boolean isBranchLocked() {
        return worktreePath != null;
    }

    public String getAgentSessionId() {
        return agentSessionId;
    }

    public void setAgentSessionId(String agentSessionId) {
        this.agentSessionId = agentSessionId;
    }

    public String getPendingQuestion() {
        return pendingQuestion;
    }

    public void setPendingQuestion(String pendingQuestion) {
        this.pendingQuestion = pendingQuestion;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
