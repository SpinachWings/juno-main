package dev.juno.main.model;

/** What an agent run is for. */
public enum RunKind {
    /** Implementing the ticket; ends with the branch pushed and a pull request opened. */
    WORK,
    /** Addressing review comments on the ticket's pull request; ends with the fixes pushed to it. */
    REVIEW,
    /** Writing a design doc with the human, back and forth, before any code is written. */
    DESIGN
}
