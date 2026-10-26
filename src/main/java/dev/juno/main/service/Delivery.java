package dev.juno.main.service;

import dev.juno.main.git.GitException;
import dev.juno.main.git.GitWorkspace;
import dev.juno.main.integration.ReviewComment;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finishes agent runs: commits what the agent left, pushes the branch, then opens the pull request (work run),
 * posts the AI review summary (review run) or completes the merge and posts its summary (conflict resolution).
 * Afterwards the ticket's branch is freed for checking out elsewhere.
 */
final class Delivery {

    private final BoardState state;
    private final Housekeeping housekeeping;

    Delivery(BoardState state, Housekeeping housekeeping) {
        this.state = state;
        this.housekeeping = housekeeping;
    }

    /**
     * Finishes an agent run (also used by Retry): commits leftovers and pushes the branch, then opens the pull
     * request (work run) or posts the review summary on it (review run). On success the ticket moves to
     * AI review; on failure to Awaiting decision with the reason, from where it can be retried.
     */
    public void deliver(String ticketId) {
        RunKind kind;
        synchronized (state) {
            Optional<Ticket> t = state.find(ticketId);
            if (t.isEmpty() || t.get().getWorktreePath() == null) {
                state.delivering.remove(ticketId);
                return;
            }
            kind = t.get().getRunKind();
            state.delivering.add(ticketId);
            t.get().setDeliveryPending(true);
            state.persist();
        }
        state.fireChanged();
        try {
            if (kind == RunKind.REVIEW) {
                pushReview(ticketId);
            } else if (kind == RunKind.CONFLICTS) {
                pushConflictResolution(ticketId);
            } else {
                openPullRequest(ticketId);
            }
        } catch (JunoException | GitException | McpException e) {
            synchronized (state) {
                state.find(ticketId).ifPresent(t -> {
                    t.setPullRequestError(e.getMessage());
                    t.setStatus(TicketStatus.AWAITING_DECISION);
                    t.setPendingQuestion("Juno couldn't finish " + (kind == RunKind.REVIEW ? "the AI review"
                            : kind == RunKind.CONFLICTS ? "resolving the merge conflicts" : "the ticket")
                            + ": " + e.getMessage() + "\n\nFix the cause and use Retry, or reply here to have the "
                            + "agent deal with it.");
                    t.touch();
                });
                state.persist();
            }
            state.log(ticketId, "[juno] " + e.getMessage());
        } finally {
            synchronized (state) {
                state.delivering.remove(ticketId);
                state.find(ticketId).ifPresent(t -> t.setDeliveryPending(false));
                state.persist();
            }
            state.fireChanged();
        }
    }

    private void openPullRequest(String ticketId) throws JunoException, GitException, McpException {
        Ticket t;
        Project project;
        Organisation org;
        Path worktree;
        String branch;
        String base;
        String baseRef;
        String title;
        String body;
        synchronized (state) {
            t = state.require(ticketId);
            project = state.settings.project(t.getProjectId()).orElse(null);
            org = project == null ? null : state.settings.organisationOf(project).orElse(null);
            worktree = Path.of(t.getWorktreePath());
            branch = t.getBranchName();
            base = t.getBaseBranch();
            baseRef = t.getBaseRef() != null ? t.getBaseRef() : base;
            title = t.getTitle();
            body = pullRequestBody(t);
        }
        if (project == null || org == null) {
            throw new JunoException("The ticket is not linked to a project in an organisation");
        }
        TrackerKind kind = pullRequestTracker(t, project, org);
        String location = project.location(kind);
        GitWorkspace git = state.git(ticketId);
        state.log(ticketId, "");
        state.log(ticketId, "=== Pushing " + branch + " and opening a " + kind.pullRequestNoun() + " into " + base + " on "
                + kind.displayName() + " " + location + " ===");
        if (git.commitLeftovers(worktree, title + "\n\nChanges left uncommitted by the agent, committed by Juno.")) {
            state.log(ticketId, "[juno] committed changes the agent left uncommitted");
        }
        if (git.commitsAhead(worktree, baseRef) == 0) {
            throw new JunoException("There are no commits on " + branch + " beyond " + base
                    + ", so there is nothing to open a " + kind.pullRequestNoun() + " for.");
        }
        git.push(worktree, branch);
        PullRequestRef pr = state.trackers.createPullRequest(org, kind, location, branch, base, title,
                body + closesLine(t, kind, location));
        synchronized (state) {
            if (t.getPullRequestOpenedAt() == null || t.getPullRequest() == null
                    || t.getPullRequest().number() != pr.number()) {
                t.setPullRequestOpenedAt(Instant.now().toString());
            }
            t.setPullRequest(pr);
            t.setPullRequestError(null);
            t.setStatus(TicketStatus.REVIEW);
            t.setPendingQuestion(null);
            t.touch();
            state.persist();
        }
        state.log(ticketId, "[juno] " + kind.pullRequestNoun() + " " + pr.display() + ": " + pr.url());
        state.log(ticketId, "[juno] moved to Review");
        housekeeping.releaseBranch(ticketId, Path.of(project.getPath()), worktree, branch);
    }

    private void pushReview(String ticketId) throws JunoException, GitException, McpException {
        Ticket t;
        Organisation org;
        Path repo;
        PullRequestRef pr;
        Path worktree;
        String branch;
        String summary;
        synchronized (state) {
            t = state.require(ticketId);
            pr = t.getPullRequest();
            Project project = state.settings.project(t.getProjectId()).orElse(null);
            org = project == null ? null : state.settings.organisationOf(project).orElse(null);
            repo = project == null ? null : Path.of(project.getPath());
            worktree = Path.of(t.getWorktreePath());
            branch = t.getBranchName();
            summary = t.getLastReviewSummary() == null || t.getLastReviewSummary().isBlank()
                    ? "_The agent did not provide a summary._" : t.getLastReviewSummary().strip();
        }
        if (pr == null || org == null) {
            throw new JunoException("The ticket has no pull request or organisation");
        }
        GitWorkspace git = state.git(ticketId);
        state.log(ticketId, "");
        state.log(ticketId, "=== AI review: pushing fixes to " + pr.display() + " ===");
        if (git.commitLeftovers(worktree, "Address review feedback: " + t.getTitle() + "\n\n"
                + "Changes left uncommitted by the review agent, committed by Juno.")) {
            state.log(ticketId, "[juno] committed changes the agent left uncommitted");
        }
        int ahead = git.commitsAhead(worktree, "origin/" + branch);
        if (ahead > 0) {
            git.push(worktree, branch);
        } else {
            state.log(ticketId, "[juno] the review agent made no code changes");
        }
        state.log(ticketId, ahead > 0 ? "[juno] pushed " + ahead + " commit(s) to " + pr.display()
                : "[juno] nothing to push");
        int number;
        synchronized (state) {
            number = t.getReviewCount() + 1;
        }
        String comment = "### AI review " + number + "\n\n" + summary + "\n\n"
                + (ahead > 0 ? "_" + ahead + " commit(s) pushed to this " + pr.tracker().pullRequestNoun() + "._"
                : "_No code changes were needed._")
                + "\n\n" + ReviewComment.JUNO_MARKER;
        state.trackers.commentOnPullRequest(org, pr.tracker(), pr.location(), pr.number(), comment);
        state.log(ticketId, "[juno] posted the AI review summary on " + pr.display());
        synchronized (state) {
            Instant started = state.reviewStarts.remove(ticketId);
            t.setLastReviewAt((started == null ? Instant.now() : started).toString());
            t.setReviewCount(t.getReviewCount() + 1);
            t.setPullRequestError(null);
            t.setStatus(TicketStatus.REVIEW);
            t.setPendingQuestion(null);
            t.touch();
            state.persist();
        }
        state.log(ticketId, "[juno] AI review " + t.getReviewCount() + " complete, moved back to Review");
        if (repo != null) {
            housekeeping.releaseBranch(ticketId, repo, worktree, branch);
        }
    }

    /**
     * Completes a conflict resolution: checks no conflict markers remain, commits the merge (both parents), checks
     * the branch now contains the latest base, and pushes it to the pull request. The ticket returns to Review.
     */
    private void pushConflictResolution(String ticketId) throws JunoException, GitException, McpException {
        Ticket t;
        Organisation org;
        String summary;
        Path repo;
        Path worktree;
        String branch;
        String base;
        PullRequestRef pr;
        synchronized (state) {
            t = state.require(ticketId);
            Project project = state.settings.project(t.getProjectId()).orElse(null);
            repo = project == null ? null : Path.of(project.getPath());
            org = project == null ? null : state.settings.organisationOf(project).orElse(null);
            worktree = Path.of(t.getWorktreePath());
            branch = t.getBranchName();
            base = t.getBaseBranch();
            pr = t.getPullRequest();
            summary = t.getLastConflictSummary() == null || t.getLastConflictSummary().isBlank()
                    ? "_The agent did not provide a summary._" : t.getLastConflictSummary().strip();
        }
        GitWorkspace git = state.git(ticketId);
        String baseRef = "origin/" + base;
        state.log(ticketId, "");
        state.log(ticketId, "=== Merge conflicts: completing the merge of " + baseRef + " into " + branch + " ===");
        List<String> markers = git.filesWithConflictMarkers(worktree, git.changedFiles(worktree));
        if (!markers.isEmpty()) {
            throw new JunoException("Conflict markers are still in " + String.join(", ", markers)
                    + ". Reply to have the agent finish them, or fix them in " + worktree + " and use Retry.");
        }
        if (git.commitLeftovers(worktree, "Merge " + baseRef + " into " + branch + "\n\nResolved conflicts with "
                + "the latest " + base + " (Juno).")) {
            state.log(ticketId, "[juno] committed the merge");
        }
        List<String> committedMarkers = git.filesWithConflictMarkers(worktree, git.mergeCommitFiles(worktree));
        if (!committedMarkers.isEmpty()) {
            throw new JunoException("The merge commit still has conflict markers in "
                    + String.join(", ", committedMarkers) + ". Fix them in " + worktree + ", commit, and use Retry.");
        }
        if (git.mergeInProgress(worktree) || !git.contains(worktree, baseRef)) {
            throw new JunoException("The merge of " + baseRef + " into " + branch + " wasn't completed. Use Retry, "
                    + "or move the ticket back to Review and run Resolve conflicts again.");
        }
        git.push(worktree, branch);
        if (pr != null && org != null) {
            List<String> files = git.mergeCommitFiles(worktree);
            String comment = "### Merge conflicts resolved\n\nMerged the latest `" + base + "` into `" + branch
                    + "` and resolved the conflicts.\n\n" + summary + "\n\n"
                    + (files.isEmpty() ? "" : "_Files changed by the merge: " + String.join(", ", files.stream()
                            .limit(30).map(f -> "`" + f + "`").toList()) + (files.size() > 30 ? ", ..." : "") + "_\n\n")
                    + ReviewComment.JUNO_MARKER;
            state.trackers.commentOnPullRequest(org, pr.tracker(), pr.location(), pr.number(), comment);
            state.log(ticketId, "[juno] posted the conflict resolution summary on " + pr.display());
        }
        synchronized (state) {
            t.setConflictResolutions(t.getConflictResolutions() + 1);
            t.setPullRequestError(null);
            t.setStatus(TicketStatus.REVIEW);
            t.setPendingQuestion(null);
            t.touch();
            state.persist();
        }
        state.log(ticketId, "[juno] pushed the resolved merge to " + (pr == null ? branch : pr.display())
                + "; moved back to Review");
        if (repo != null) {
            housekeeping.releaseBranch(ticketId, repo, worktree, branch);
        }
    }

    /**
     * Where to open the pull request: the project's repository host (a project is on GitHub or GitLab, never
     * both), which must be enabled for its organisation.
     */
    static TrackerKind pullRequestTracker(Ticket t, Project project, Organisation org) throws JunoException {
        TrackerKind host = project.host();
        if (host == null || project.hasBothHosts()) {
            throw new JunoException("Project '" + project.getName() + "' needs exactly one repository: a GitHub "
                    + "repo or a GitLab project. Set it in Settings, then use Retry PR.");
        }
        if (!org.tracker(host).isEnabled()) {
            throw new JunoException(host.displayName() + " isn't enabled for organisation '" + org.getName()
                    + "'. Enable it in Settings, then use Retry PR.");
        }
        return host;
    }

    private static String pullRequestBody(Ticket t) {
        String summary = t.getLastSummary() == null || t.getLastSummary().isBlank()
                ? "_The agent did not provide a summary._" : t.getLastSummary().strip();
        return summary + "\n\n---\nTicket: " + t.getTitle() + "\n\n_Opened by Juno._";
    }

    /** Adds "Closes #n" when the ticket's linked issue lives in the same repository as the pull request. */
    private static String closesLine(Ticket t, TrackerKind kind, String location) {
        ExternalRef ref = t.getIssue();
        if (ref != null && ref.tracker() == kind && ref.location().equalsIgnoreCase(location.trim())) {
            return "\n\nCloses #" + ref.number();
        }
        return "";
    }
}
