package dev.juno.main.model;

/** External issue trackers Juno can sync tickets with (each backed by its own MCP server). */
public enum TrackerKind {
    GITLAB("GitLab", "project", "GL"),
    GITHUB("GitHub", "repo", "GH");

    private final String displayName;
    private final String locationArgument;
    private final String shortName;

    TrackerKind(String displayName, String locationArgument, String shortName) {
        this.displayName = displayName;
        this.locationArgument = locationArgument;
        this.shortName = shortName;
    }

    public String displayName() {
        return displayName;
    }

    /** Name of the MCP tool argument that identifies the project/repository. */
    public String locationArgument() {
        return locationArgument;
    }

    public String shortName() {
        return shortName;
    }

    /** Human description of what a location looks like for this tracker. */
    public String locationHint() {
        return this == GITLAB ? "group/project" : "owner/repo";
    }
}
