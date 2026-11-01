package dev.juno.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * The single registry of slash commands (design §8). Settings → Commands and, later, autocomplete and /help read it.
 * Later milestones add behaviour; the list itself stays here.
 */
public final class CommandRegistry {
    public enum Scope {
        ANY_PANE("Any pane"), JUNO_ONLY("Juno's pane only"), PROFILE("Profile command");

        public final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    public record Command(String name, String usage, Scope scope, String description, String example) {}

    private static final List<Command> COMMANDS = new ArrayList<>(List.of(
            new Command("hey", "/hey<name> <text>", Scope.ANY_PANE,
                    "Instructions or a question for <name> while it works; read when its current step ends.",
                    "/heybob also update the changelog"),
            new Command("cantalkto", "/cantalkto<name>", Scope.ANY_PANE,
                    "Open a link between this agent and <name>.", "/cantalktoalice"),
            new Command("stoptalkingto", "/stoptalkingto<name>", Scope.ANY_PANE,
                    "Close the link between this agent and <name>.", "/stoptalkingtoalice"),
            new Command("getcontext", "/getcontext", Scope.ANY_PANE,
                    "This agent's context summary; in Juno's pane, Juno's.", "/getcontext"),
            new Command("activate", "/activate<skill>", Scope.ANY_PANE,
                    "Load and follow one of its skills from the next message (a pack name loads its entry skill). "
                            + "Repeat to cancel. Not while working.", "/activatebrainstorming"),
            new Command("compact", "/compact", Scope.ANY_PANE,
                    "Summarise the conversation to free context.", "/compact"),
            new Command("new", "/new", Scope.ANY_PANE,
                    "Start a new conversation from the next message.", "/new"),
            new Command("stop", "/stop", Scope.ANY_PANE,
                    "Stop the agent; the conversation is kept.", "/stop"),
            new Command("resume", "/resume", Scope.ANY_PANE,
                    "Continue an interrupted or stopped agent.", "/resume"),
            new Command("approve", "/approve", Scope.ANY_PANE,
                    "Answer the pending permission request or proposal with yes.", "/approve"),
            new Command("deny", "/deny", Scope.ANY_PANE,
                    "Answer the pending permission request or proposal with no.", "/deny"),
            new Command("model", "/model <model>", Scope.ANY_PANE,
                    "Change the model from the next message (allowed while working).", "/model claude-haiku-5-5"),
            new Command("effort", "/effort <level>", Scope.ANY_PANE,
                    "Change the effort from the next message (allowed while working).", "/effort low"),
            new Command("diff", "/diff", Scope.ANY_PANE,
                    "Uncommitted changes in the agent's folder, as diffs (no tokens).", "/diff"),
            new Command("usage", "/usage", Scope.ANY_PANE,
                    "Token, cost and context history.", "/usage"),
            new Command("copy", "/copy", Scope.ANY_PANE,
                    "Copy the agent's last reply.", "/copy"),
            new Command("clear", "/clear", Scope.ANY_PANE,
                    "Clear the pane (the log is kept).", "/clear"),
            new Command("help", "/help", Scope.ANY_PANE,
                    "Commands available here.", "/help"),
            new Command("getcontextfrom", "/getcontextfrom<name>", Scope.JUNO_ONLY,
                    "That agent's context summary.", "/getcontextfrombob"),
            new Command("assign", "/assign<name> <task>", Scope.JUNO_ONLY,
                    "Give a task through Juno without spending her tokens; she tracks the result.",
                    "/assignbob fix the failing login test"),
            new Command("status", "/status", Scope.JUNO_ONLY,
                    "Every agent: status, project, current task, context %.", "/status"),
            new Command("broadcast", "/broadcast <text>", Scope.JUNO_ONLY,
                    "Message every agent.", "/broadcast stop touching the build files")));

    private CommandRegistry() {}

    public static List<Command> all() {
        return List.copyOf(COMMANDS);
    }

    public static List<Command> ofScope(Scope scope) {
        return COMMANDS.stream().filter(c -> c.scope() == scope).toList();
    }

    /** Later milestones register further commands here. */
    public static void register(Command command) {
        COMMANDS.removeIf(c -> c.name().equals(command.name()));
        COMMANDS.add(command);
    }
}
