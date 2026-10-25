package dev.juno.main.agent;

import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;

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
        return "Human decision for ticket " + ticket.getId() + ":\n\n" + answer.strip()
                + "\n\nContinue working on the ticket with this decision.\n\n" + rules(ticket);
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
