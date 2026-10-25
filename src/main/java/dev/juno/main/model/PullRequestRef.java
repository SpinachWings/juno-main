package dev.juno.main.model;

/**
 * The pull request (GitHub) or merge request (GitLab) opened for a ticket's branch.
 *
 * @param tracker  where it was opened
 * @param location GitHub repo ({@code owner/repo}) or GitLab project ({@code group/project})
 * @param number   PR number / MR IID
 * @param url      browser URL
 */
public record PullRequestRef(TrackerKind tracker, String location, long number, String url) {

    /** e.g. {@code PR #12} or {@code MR !12}. */
    public String display() {
        return tracker == TrackerKind.GITLAB ? "MR !" + number : "PR #" + number;
    }
}
