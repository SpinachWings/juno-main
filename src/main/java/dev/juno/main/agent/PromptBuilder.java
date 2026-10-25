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
        sb.append("You are an autonomous software engineer working on a ticket in the ").append(project.getName())
                .append(" repository (your current working directory).\n\n");
        sb.append("# ").append(ticket.getTitle()).append("\n\n");
        sb.append(ticket.getDescription().isBlank() ? "(no description provided)" : ticket.getDescription().strip())
                .append("\n\n");

        if (!ticket.getInitialInstructions().isBlank()) {
            sb.append("## Instructions from the person who assigned this ticket\n")
                    .append(ticket.getInitialInstructions().strip()).append("\n\n");
        }
        if (ticket.isDesignDocApproved() && !ticket.getDesignDoc().isBlank()) {
            sb.append("## Approved design doc\n")
                    .append("Implement the ticket according to this design. If you find it can't work as written, ")
                    .append("stop and ask (decision line) rather than silently diverging.\n\n")
                    .append(ticket.getDesignDoc().strip()).append("\n\n");
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

    /** Design run: a conversation with the human that ends in an approved design doc; no code changes. */
    public static String design(Ticket ticket, Project project, boolean trackerToolsAvailable) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior software engineer writing a design doc for a ticket in the ").append(project.getName())
                .append(" repository (your current working directory), together with a human.\n\n");
        sb.append("# ").append(ticket.getTitle()).append("\n\n");
        sb.append(ticket.getDescription().isBlank() ? "(no description provided)" : ticket.getDescription().strip())
                .append("\n\n");
        if (!ticket.getInitialInstructions().isBlank()) {
            sb.append("## Instructions from the person who assigned this ticket\n")
                    .append(ticket.getInitialInstructions().strip()).append("\n\n");
        }
        if (!ticket.getDesignDoc().isBlank()) {
            sb.append("## Existing draft to start from\n").append(ticket.getDesignDoc().strip()).append("\n\n");
        }
        sb.append("""
                ## Process
                - Read the relevant parts of the codebase to understand the current design and constraints. Do NOT
                  modify files, run destructive commands or commit anything: this phase only produces a document.
                - This is a conversation. If you need answers before you can write a good design, ask them: put your
                  questions together (numbered) and make the LAST line of your reply exactly:
                  %1$s <one-line summary of what you need decided>
                - When you are ready, reply with the complete design doc wrapped exactly like this:
                  %2$s
                  ...the full design doc...
                  %3$s
                  Suggested sections: Summary; Goals and non-goals; Current state; Proposed design (components,
                  data model, APIs, UI); Alternatives considered; Risks and open questions; Testing plan; Rollout
                  and migration; Task breakdown.
                - The human then approves the doc or asks for changes. When asked for changes, reply with the FULL
                  revised doc in the same tags (never just a diff).
                - If skills are available that match this work (e.g. brainstorming or writing plans), you may use
                  them, but follow this process and the output format above.
                """.formatted(StreamJsonParser.DECISION_MARKER, DesignDocs.OPEN, DesignDocs.CLOSE));
        return sb.toString();
    }

    public static String resume(Ticket ticket, String answer) {
        if (ticket.getRunKind() == RunKind.DESIGN) {
            return "Reply from the human about the design for \"" + ticket.getTitle() + "\":\n\n" + answer.strip()
                    + "\n\nContinue the design conversation. Ask further questions with the "
                    + StreamJsonParser.DECISION_MARKER + " line if needed; otherwise reply with the FULL updated "
                    + "design doc wrapped in " + DesignDocs.OPEN + " ... " + DesignDocs.CLOSE
                    + ". Do not modify any files.";
        }
        String rest = ticket.getRunKind() == RunKind.REVIEW
                ? "Continue addressing the review feedback with this decision. Same rules as before: commit to `"
                        + ticket.getBranchName() + "`, do not push, and end with a summary (or the "
                        + StreamJsonParser.DECISION_MARKER + " line if you need another decision)."
                : rules(ticket);
        return "Human decision for \"" + ticket.getTitle() + "\":\n\n" + answer.strip()
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
                .append(" (").append(pr.url()).append(") for a ticket in the ").append(project.getName())
                .append(" repository (your current working directory).\n\n");
        sb.append("# ").append(ticket.getTitle()).append("\n\n");
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
                  Juno pushes your commits to the %2$s. Never merge, approve or close it: a human does that.
                  Skills may help, but these rules win over any skill.
                - When you have finished, reply with a concise summary listing each comment and how you handled it.
                - If you cannot proceed without a decision from a human, stop and make the LAST line of your final
                  reply exactly:
                  %3$s <your question, listing the options if there are any>
                """.formatted(ticket.getBranchName(), pr.tracker().pullRequestNoun(),
                StreamJsonParser.DECISION_MARKER));
        return sb.toString();
    }

    /**
     * Resuming an interrupted run within the same Claude session: the conversation is restored, so this only
     * explains the interruption and gives the recap and current branch state.
     */
    public static String recoveryResume(Ticket ticket, String recap, String gitState) {
        return """
                Juno was stopped (for example the computer restarted or Juno closed) while you were working on \
                the ticket "%1$s", so your previous run was cut off. Your conversation so far has been restored.

                ## What Juno recorded before the interruption
                %2$s
                ## Current state of branch `%3$s`
                %4$s
                ## Continue
                Pick up exactly where you left off. Re-check anything that may have been cut short (a file edit, a \
                command or build that was running, a commit that may not have happened) before moving on, and \
                don't redo work that is already done. %5$s
                """.formatted(ticket.getTitle(), recap, ticket.getBranchName(), gitState,
                ticket.getRunKind() == RunKind.DESIGN
                        ? "You were writing a design doc: don't modify files; ask questions with the "
                                + StreamJsonParser.DECISION_MARKER + " line or reply with the full doc in "
                                + DesignDocs.OPEN + " ... " + DesignDocs.CLOSE + "."
                        : "The same rules as before apply: commit to `" + ticket.getBranchName() + "`, don't push "
                                + "or switch branches, never merge, and finish with your summary (or the "
                                + StreamJsonParser.DECISION_MARKER + " line if you need a decision).");
    }

    /**
     * Recovering an interrupted run in a new session (the previous session could not be restored): the original
     * prompt plus the recap and branch state.
     */
    public static String recoveryFresh(String originalPrompt, Ticket ticket, String recap, String gitState) {
        return originalPrompt.strip() + """


                ## You are resuming interrupted work
                A previous agent session already worked on this %1$s and was cut off (for example the computer \
                restarted). Its session could not be restored, so here is Juno's record of what it did and the \
                current state of the branch. Continue from where it stopped: verify the work already done rather than \
                repeating it, and finish anything left incomplete.

                ### Recorded steps of the interrupted session
                %2$s
                ### Current state of branch `%3$s`
                %4$s""".formatted(ticket.getRunKind() == RunKind.REVIEW ? "review" : "ticket", recap,
                ticket.getBranchName(), gitState);
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
                  description. Never merge anything: merging is always done by a human.
                - Skills may be available. Use them where they help, but these rules win over any skill (e.g. one
                  that suggests merging, pushing, creating worktrees or switching branches).

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
