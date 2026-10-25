package dev.juno.main.agent;

import dev.juno.main.integration.ReviewComment;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;

import java.util.List;

/** Builds the prompts handed to agents. */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String initial(Ticket ticket, Project project, boolean trackerToolsAvailable) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous software engineer working on ticket ").append(ticket.getId())
                .append(" in the ").append(project.getName())
                .append(" repository (your current working directory).\n\n");
        sb.append("# ").append(ticket.getId()).append(": ").append(ticket.getTitle()).append("\n\n");
        sb.append(ticket.getDescription().isBlank() ? "(no description provided)" : ticket.getDescription().strip())
                .append("\n\n");

        if (!ticket.getInitialInstructions().isBlank()) {
            sb.append("## Instructions from the person who assigned this ticket\n")
                    .append(ticket.getInitialInstructions().strip()).append("\n\n");
        }

        StringBuilder links = new StringBuilder();
        for (TrackerKind kind : TrackerKind.values()) {
            ExternalRef ref = ticket.getRef(kind);
            if (ref != null) {
                links.append("- ").append(kind.displayName()).append(" issue ").append(ref.display())
                        .append(ref.url() == null || ref.url().isBlank() ? "" : " (" + ref.url() + ")").append('\n');
            }
        }
        if (!links.isEmpty()) {
            sb.append("## Linked issues\n").append(links);
            if (trackerToolsAvailable) {
                sb.append("You can read these issues (including comments) with the juno-gitlab / juno-github "
                        + "MCP tools if you need more context.\n");
            }
            sb.append('\n');
        }

        sb.append(rules(ticket));
        return sb.toString();
    }

    public static String resume(Ticket ticket, String answer) {
        String rest = ticket.getRunKind() == RunKind.REVIEW
                ? "Continue addressing the review feedback with this decision. Same rules as before: commit to `"
                        + ticket.getBranchName() + "`, do not push, and end with a summary (or the "
                        + StreamJsonParser.DECISION_MARKER + " line if you need another decision)."
                : rules(ticket);
        return "Human decision for ticket " + ticket.getId() + ":\n\n" + answer.strip()
                + "\n\nContinue with this decision.\n\n" + rest;
    }

    /**
     * Prompt for an AI review run: address the feedback left on the ticket's pull request.
     *
     * @param comments     feedback to address (already filtered: unresolved, not posted by Juno)
     * @param newSince     comments created after this instant are marked new (null = all new)
     */
    public static String review(Ticket ticket, Project project, List<ReviewComment> comments,
                                java.time.Instant newSince) {
        PullRequestRef pr = ticket.getPullRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous software engineer addressing review feedback on ")
                .append(pr.tracker().pullRequestNoun()).append(' ').append(pr.display())
                .append(" (").append(pr.url()).append(") for ticket ").append(ticket.getId())
                .append(" in the ").append(project.getName())
                .append(" repository (your current working directory).\n\n");
        sb.append("# ").append(ticket.getId()).append(": ").append(ticket.getTitle()).append("\n\n");
        sb.append(ticket.getDescription().isBlank() ? "(no description provided)" : ticket.getDescription().strip())
                .append("\n\n");
        if (ticket.getLastSummary() != null && !ticket.getLastSummary().isBlank()) {
            sb.append("## What was implemented\n").append(ticket.getLastSummary().strip()).append("\n\n");
        }

        sb.append("## Feedback to address\n");
        int n = 1;
        for (ReviewComment c : comments) {
            boolean isNew = newSince == null || parse(c.createdAt()).map(t -> t.isAfter(newSince)).orElse(true);
            sb.append("\n### ").append(n++).append(". ")
                    .append(switch (c.kind()) {
                        case "inline" -> "Inline comment on `" + c.path() + (c.line() > 0 ? ":" + c.line() : "") + "`";
                        case "review" -> "Review summary";
                        case "issue" -> "Comment on the linked issue";
                        default -> "Comment";
                    })
                    .append(" by ").append(c.author().isBlank() ? "unknown" : c.author())
                    .append(isNew ? " (new)" : " (from an earlier round; check it is still addressed)")
                    .append("\n");
            if (c.diffHunk() != null && !c.diffHunk().isBlank()) {
                sb.append("```diff\n").append(c.diffHunk().strip()).append("\n```\n");
            }
            sb.append(c.body().strip()).append("\n");
        }

        sb.append("""

                ## How to work
                - Address each actionable comment with code changes, following the repository's conventions.
                  If a comment needs no change, or you disagree with it, say why in your summary instead.
                - Build the code and run the relevant tests where possible.
                - You are on branch `%1$s` (the pull request's branch, already up to date). Commit your changes to it
                  with clear messages. Do not switch branches, rebase, push or comment on the pull request yourself:
                  Juno pushes your commits to the %2$s and posts your summary there.
                - When you have finished, reply with a concise summary listing each comment and how you handled it.
                - If you cannot proceed without a decision from a human, stop and make the LAST line of your final
                  reply exactly:
                  %3$s <your question, listing the options if there are any>
                """.formatted(ticket.getBranchName(), pr.tracker().pullRequestNoun(),
                StreamJsonParser.DECISION_MARKER));
        return sb.toString();
    }

    private static java.util.Optional<java.time.Instant> parse(String iso) {
        try {
            return java.util.Optional.of(java.time.OffsetDateTime.parse(iso).toInstant());
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static String rules(Ticket ticket) {
        return """
                ## Git
                - You are on branch `%1$s`, created from `%2$s`, in a worktree dedicated to this ticket.
                - Commit your work to this branch as you go, with clear commit messages.
                - Do not switch branches, rebase onto other branches, push, or open pull requests yourself. When you
                  finish, Juno pushes `%1$s` and opens a pull request into `%2$s` with your summary as its
                  description.

                ## How to work
                - Implement the ticket fully, following the repository's existing conventions.
                - Build the code and run the relevant tests where possible.
                - When you have finished, reply with a concise summary of what you changed and how you verified it.
                  It becomes the pull request description.
                - If you cannot proceed without a decision from a human (ambiguous requirements, a risky or
                  destructive change, missing credentials or access), stop working and make the LAST line of
                  your final reply exactly:
                  %3$s <your question, listing the options if there are any>
                """.formatted(ticket.getBranchName(), ticket.getBaseBranch(), StreamJsonParser.DECISION_MARKER);
    }
}
