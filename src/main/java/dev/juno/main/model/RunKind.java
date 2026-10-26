package dev.juno.main.model;

/** What an agent run is for. */
public enum RunKind {
    /** Implementing the ticket; ends with the branch pushed and a pull request opened. */
    WORK,
    /** Addressing review comments on the ticket's pull request; ends with the fixes pushed to it. */
    REVIEW,
    /** Writing a design doc with the human, back and forth, before any code is written. */
    DESIGN,
    /**
     * Resolving merge conflicts after the base branch moved on (e.g. another ticket was merged): Juno merges the
     * base branch into the ticket's branch and the agent resolves the conflicts; ends with the merge pushed.
     */
    CONFLICTS;

    /** Runs that start from, and return to, the Review column (the pull request already exists). */
    public boolean onPullRequest() {
        return this == REVIEW || this == CONFLICTS;
    }

    /** The column a ticket is in while this kind of run is going. */
    public TicketStatus runningStatus() {
        return switch (this) {
            case REVIEW -> TicketStatus.IN_AI_REVIEW;
            case CONFLICTS -> TicketStatus.RESOLVING_CONFLICTS;
            default -> TicketStatus.WORKING;
        };
    }

    /** Where the ticket goes when the run is stopped or abandoned. */
    public TicketStatus homeStatus() {
        return onPullRequest() ? TicketStatus.REVIEW : TicketStatus.PENDING;
    }

    /** Terminal variant: AI reviews and conflict resolutions get their own terminal. */
    public String consoleVariant() {
        return switch (this) {
            case REVIEW -> "review";
            case CONFLICTS -> "conflicts";
            default -> "";
        };
    }

    /** Human description, e.g. for "Resuming interrupted ...". */
    public String label() {
        return switch (this) {
            case WORK -> "work";
            case REVIEW -> "AI review";
            case DESIGN -> "design doc";
            case CONFLICTS -> "merge conflict resolution";
        };
    }
}
