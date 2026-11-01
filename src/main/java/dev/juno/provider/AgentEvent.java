package dev.juno.provider;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * What an agent's process said, independent of the CLI (design §10). {@code parent} is the tool use id of the
 * sub-agent an event belongs to, or null for the agent itself. Events are journalled as JSON, so every type is a record.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.SessionStarted.class, name = "session"),
        @JsonSubTypes.Type(value = AgentEvent.UserText.class, name = "user"),
        @JsonSubTypes.Type(value = AgentEvent.Message.class, name = "message"),
        @JsonSubTypes.Type(value = AgentEvent.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = AgentEvent.ToolUse.class, name = "tool"),
        @JsonSubTypes.Type(value = AgentEvent.ToolResult.class, name = "toolResult"),
        @JsonSubTypes.Type(value = AgentEvent.Diff.class, name = "diff"),
        @JsonSubTypes.Type(value = AgentEvent.SubAgent.class, name = "subAgent"),
        @JsonSubTypes.Type(value = AgentEvent.SubAgentDone.class, name = "subAgentDone"),
        @JsonSubTypes.Type(value = AgentEvent.Todos.class, name = "todos"),
        @JsonSubTypes.Type(value = AgentEvent.Usage.class, name = "usage"),
        @JsonSubTypes.Type(value = AgentEvent.Result.class, name = "result"),
        @JsonSubTypes.Type(value = AgentEvent.RateLimit.class, name = "rateLimit"),
        @JsonSubTypes.Type(value = AgentEvent.Background.class, name = "background"),
        @JsonSubTypes.Type(value = AgentEvent.Notice.class, name = "notice"),
        @JsonSubTypes.Type(value = AgentEvent.Permission.class, name = "permission"),
        @JsonSubTypes.Type(value = AgentEvent.Exited.class, name = "exited"),
        @JsonSubTypes.Type(value = AgentEvent.Patch.class, name = "patch"),
})
public sealed interface AgentEvent {

    /** The CLI told us its conversation id (also on resume). */
    record SessionStarted(String sessionId, String model, String cwd) implements AgentEvent {}

    /** Something sent into the conversation: typed by the user or injected by Juno ({@code from} names who). */
    record UserText(String text, String from) implements AgentEvent {}

    /** Assistant text, Markdown. */
    record Message(String text, String parent) implements AgentEvent {}

    /** {@code text} is empty when the provider hides it; {@code length} is then an estimate in tokens. */
    record Thinking(String text, int length, String parent) implements AgentEvent {}

    /** A tool call. {@code input} is the tool's input as JSON text. */
    record ToolUse(String id, String name, String input, String parent) implements AgentEvent {}

    record ToolResult(String toolUseId, String text, boolean error, String parent) implements AgentEvent {}

    /** A file edit; {@code newFile} when the whole file was written and didn't exist before (or is unknown). */
    record Diff(String toolUseId, String file, String oldText, String newText, boolean newFile, String parent)
            implements AgentEvent {}

    /** A sub-agent started (its tool use id is the {@code parent} of its own events). */
    record SubAgent(String toolUseId, String description, String type, String prompt, String parent)
            implements AgentEvent {}

    record SubAgentDone(String toolUseId, String status) implements AgentEvent {}

    /** A plan / to-do list; each item is "pending", "in_progress" or "completed". */
    record Todos(Map<String, String> items, String parent) implements AgentEvent {}

    /**
     * Usage of one API call (or a turn's total when {@code turnTotal}). {@code contextTokens} is what the conversation
     * holds after it; {@code contextWindow} is 0 when unknown.
     */
    record Usage(long input, long output, long cacheRead, long cacheWrite, long contextTokens, long contextWindow,
                 double costUsd, boolean turnTotal) implements AgentEvent {}

    /** A turn ended. {@code kind}: success, max_turns, budget, error. */
    record Result(String kind, boolean error, String text, double costUsd, int turns) implements AgentEvent {}

    /** Plan limits used, 0–1, and reset times (epoch seconds, 0 when unknown). */
    record RateLimit(double fiveHour, long fiveHourReset, double week, long weekReset, String status)
            implements AgentEvent {}

    /** How many background tasks (async sub-agents, background commands) are running. */
    record Background(int count) implements AgentEvent {}

    /** A quiet line from Juno in the pane (never sent to the agent). {@code level}: info, dim, good, warn, bad. */
    record Notice(String text, String level) implements AgentEvent {}

    /** A permission request and, once answered, its outcome ({@code answer} null while pending). */
    record Permission(String requestId, String tool, String input, String rule, String ruleDescription,
                      String answer) implements AgentEvent {}

    record Exited(int code, String detail) implements AgentEvent {}

    /** A unified diff shown in the pane by Juno (/diff), not by the agent. */
    record Patch(String title, String patch) implements AgentEvent {}
}
