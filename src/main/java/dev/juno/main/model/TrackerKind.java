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

    /** MCP tool that opens a pull request (GitHub) / merge request (GitLab). */
    public String pullRequestTool() {
        return this == GITLAB ? "create_merge_request" : "create_pull_request";
    }

    /** Tool argument names for the source and target branches. */
    public String headArgument() {
        return this == GITLAB ? "source_branch" : "head";
    }

    public String baseArgument() {
        return this == GITLAB ? "target_branch" : "base";
    }

    /** "pull request" or "merge request". */
    public String pullRequestNoun() {
        return this == GITLAB ? "merge request" : "pull request";
    }

    /** MCP tool listing comments on a pull/merge request. */
    public String pullRequestCommentsTool() {
        return this == GITLAB ? "get_merge_request_comments" : "get_pull_request_comments";
    }

    /** MCP tool adding a comment to a pull/merge request. */
    public String pullRequestCommentTool() {
        return this == GITLAB ? "add_merge_request_comment" : "add_comment";
    }

    /** Human description of what a location looks like for this tracker. */
    public String locationHint() {
        return this == GITLAB ? "group/project" : "owner/repo";
    }
}
