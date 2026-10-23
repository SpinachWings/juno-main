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

        sb.append(rules());
        return sb.toString();
    }

    public static String resume(Ticket ticket, String answer) {
        return "Human decision for ticket " + ticket.getId() + ":\n\n" + answer.strip()
                + "\n\nContinue working on the ticket with this decision.\n\n" + rules();
    }

    private static String rules() {
        return """
                ## How to work
                - Implement the ticket fully in this repository, following its existing conventions.
                - Build the code and run the relevant tests where possible.
                - Do not push to any remote and do not open merge/pull requests; a reviewer handles that.
                - When you have finished, reply with a concise summary of what you changed and how you verified it.
                - If you cannot proceed without a decision from a human (ambiguous requirements, a risky or
                  destructive change, missing credentials or access), stop working and make the LAST line of
                  your final reply exactly:
                  %s <your question, listing the options if there are any>
                """.formatted(StreamJsonParser.DECISION_MARKER);
    }
}
