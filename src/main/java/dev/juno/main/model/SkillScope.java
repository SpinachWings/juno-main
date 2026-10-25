package dev.juno.main.model;

/** Where a skill pack applies. */
public enum SkillScope {
    /** Every agent Juno runs. */
    GLOBAL,
    /** Agents working on tickets of one organisation. */
    ORGANISATION,
    /** Agents working on tickets of one project. */
    PROJECT
}
