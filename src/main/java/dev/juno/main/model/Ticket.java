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
