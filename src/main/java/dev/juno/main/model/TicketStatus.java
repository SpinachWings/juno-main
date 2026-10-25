package dev.juno.main.model;

/** The board columns, in display order. */
public enum TicketStatus {
    PENDING("Pending"),
    /** An agent is implementing the ticket (shown as "Processing"). */
    WORKING("Processing"),
    AWAITING_DECISION("Awaiting decision"),
    /** Pull/merge request open; waiting for a human, who can run an AI review or move it to Done. */
    REVIEW("Review"),
    /** An AI review agent is addressing the pull request's comments. */
    IN_AI_REVIEW("In AI review"),
    DONE("Done");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
