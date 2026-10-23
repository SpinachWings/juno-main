package dev.juno.main.model;

/** The board columns, in display order. */
public enum TicketStatus {
    PENDING("Pending"),
    WORKING("Working"),
    AWAITING_DECISION("Awaiting decision"),
    AI_REVIEW("AI review"),
    HUMAN_REVIEW("Human review"),
    DONE("Done");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
