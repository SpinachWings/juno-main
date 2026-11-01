package dev.juno.provider.claude;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.Provider;
import dev.juno.store.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude Code's stream-json output → {@link AgentEvent}s. Verified against Claude Code 2.1.314: assistant messages
 * arrive one content block per line (sharing a message id and its usage), sub-agents' lines carry
 * {@code parent_tool_use_id} (with {@code --forward-subagent-text}), async sub-agents finish after the parent's
 * {@code result} via {@code task_notification}, and plan limits come as {@code rate_limit_event}.
 */
public final class ClaudeStreamParser implements Provider.StreamParser {
    static final Set<String> SUB_AGENT_TOOLS = Set.of("Agent", "Task");

    private final Set<String> usageSeen = new HashSet<>();
    private final Map<String, JsonNode> pendingWrites = new HashMap<>();
    private int thinkingEstimate;
    private String model = "";

    @Override
    public List<AgentEvent> parse(String line) {
        List<AgentEvent> out = new ArrayList<>();
        if (line == null || line.isBlank()) return out;
        JsonNode d;
        try {
            d = Json.MAPPER.readTree(line);
        } catch (Exception e) {
            out.add(new AgentEvent.Notice(line.strip(), "dim"));
            return out;
        }
        String parent = text(d, "parent_tool_use_id");
        switch (d.path("type").asText()) {
            case "system" -> system(d, out);
            case "assistant" -> assistant(d, parent, out);
            case "user" -> user(d, parent, out);
            case "result" -> result(d, out);
            case "rate_limit_event" -> rateLimit(d.path("rate_limit_info"), out);
            default -> { }
        }
        return out;
    }

    private void system(JsonNode d, List<AgentEvent> out) {
        switch (d.path("subtype").asText()) {
            case "init" -> {
                model = d.path("model").asText("");
                out.add(new AgentEvent.SessionStarted(d.path("session_id").asText(), model, d.path("cwd").asText()));
            }
            case "thinking_tokens" -> thinkingEstimate = d.path("estimated_tokens").asInt(thinkingEstimate);
            case "background_tasks_changed" -> out.add(new AgentEvent.Background(d.path("tasks").size()));
            case "task_notification" -> {
                String id = text(d, "tool_use_id");
                if (id != null) out.add(new AgentEvent.SubAgentDone(id, d.path("status").asText("completed")));
            }
            case "compact_boundary" -> {
                JsonNode meta = d.path("compact_metadata");
                long pre = meta.path("pre_tokens").asLong(0), post = meta.path("post_tokens").asLong(0);
                out.add(new AgentEvent.Notice("Conversation compacted" + (post > 0 ? ": " + pre + " → " + post
                        + " tokens." : "."), "info"));
                if (post > 0) out.add(new AgentEvent.Usage(0, 0, 0, 0, post, 0, 0, false));
            }
            default -> { }
        }
    }

    private void assistant(JsonNode d, String parent, List<AgentEvent> out) {
        JsonNode msg = d.path("message");
        for (JsonNode c : msg.path("content")) {
            switch (c.path("type").asText()) {
                case "text" -> {
                    String t = c.path("text").asText("");
                    if (!t.isBlank()) out.add(new AgentEvent.Message(t, parent));
                }
                case "thinking", "redacted_thinking" -> {
                    String t = c.path("thinking").asText("");
                    out.add(new AgentEvent.Thinking(t, t.isEmpty() ? thinkingEstimate : 0, parent));
                    thinkingEstimate = 0;
                }
                case "tool_use" -> toolUse(c, parent, out);
                default -> { }
            }
        }
        // One API call can span several lines; count its usage once. Sub-agents' usage isn't the conversation's.
        String id = msg.path("id").asText("");
        JsonNode u = msg.path("usage");
        if (parent == null && !u.isMissingNode() && !"<synthetic>".equals(msg.path("model").asText())
                && usageSeen.add(id)) {
            long in = u.path("input_tokens").asLong(), cr = u.path("cache_read_input_tokens").asLong();
            long cw = u.path("cache_creation_input_tokens").asLong(), o = u.path("output_tokens").asLong();
            out.add(new AgentEvent.Usage(in, o, cr, cw, in + cr + cw + o, 0, 0, false));
        }
    }

    private void toolUse(JsonNode c, String parent, List<AgentEvent> out) {
        String id = c.path("id").asText();
        String name = c.path("name").asText();
        JsonNode input = c.path("input");
        out.add(new AgentEvent.ToolUse(id, name, input.toString(), parent));
        switch (name) {
            case "Edit" -> out.add(new AgentEvent.Diff(id, input.path("file_path").asText(),
                    input.path("old_string").asText(""), input.path("new_string").asText(""), false, parent));
            case "MultiEdit" -> {
                for (JsonNode e : input.path("edits")) {
                    out.add(new AgentEvent.Diff(id, input.path("file_path").asText(),
                            e.path("old_string").asText(""), e.path("new_string").asText(""), false, parent));
                }
            }
            case "Write" -> pendingWrites.put(id, input);
            case "TodoWrite" -> {
                Map<String, String> items = new LinkedHashMap<>();
                for (JsonNode t : input.path("todos")) {
                    items.put(t.path("content").asText(), t.path("status").asText("pending"));
                }
                out.add(new AgentEvent.Todos(items, parent));
            }
            default -> {
                if (SUB_AGENT_TOOLS.contains(name)) {
                    out.add(new AgentEvent.SubAgent(id, input.path("description").asText(""),
                            input.path("subagent_type").asText(""), input.path("prompt").asText(""), parent));
                }
            }
        }
    }

    private void user(JsonNode d, String parent, List<AgentEvent> out) {
        JsonNode content = d.path("message").path("content");
        if (!content.isArray()) return; // our own messages echoed back: not needed
        JsonNode extra = d.path("tool_use_result");
        for (JsonNode c : content) {
            if (!"tool_result".equals(c.path("type").asText())) continue;
            String id = c.path("tool_use_id").asText();
            boolean error = c.path("is_error").asBoolean(false);
            out.add(new AgentEvent.ToolResult(id, resultText(c.path("content")), error, parent));
            JsonNode write = pendingWrites.remove(id);
            if (write != null && !error) {
                boolean update = "update".equals(extra.path("type").asText());
                String original = extra.path("originalFile").isTextual() ? extra.path("originalFile").asText() : "";
                out.add(new AgentEvent.Diff(id, write.path("file_path").asText(), update ? original : "",
                        write.path("content").asText(""), !update, parent));
            }
        }
    }

    static String resultText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText())) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(part.path("text").asText());
            } else if (part.has("type")) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append('[').append(part.path("type").asText()).append(']');
            }
        }
        return sb.toString();
    }

    private void result(JsonNode d, List<AgentEvent> out) {
        String sub = d.path("subtype").asText("success");
        boolean error = d.path("is_error").asBoolean(false) || !"success".equals(sub);
        String kind = switch (sub) {
            case "success" -> error ? "error" : "success";
            case "error_max_turns" -> "max_turns";
            case "error_max_budget_usd" -> "budget";
            default -> "error";
        };
        double cost = d.path("total_cost_usd").asDouble(0);
        long window = 0;
        JsonNode mu = d.path("modelUsage");
        JsonNode m = mu.path(model);
        if (m.isMissingNode() && mu.size() > 0) m = mu.elements().next();
        window = m.path("contextWindow").asLong(0);
        JsonNode u = d.path("usage");
        long in = u.path("input_tokens").asLong(), cr = u.path("cache_read_input_tokens").asLong();
        long cw = u.path("cache_creation_input_tokens").asLong(), o = u.path("output_tokens").asLong();
        out.add(new AgentEvent.Usage(in, o, cr, cw, 0, window, cost, true));
        String text = d.path("result").asText("");
        if (text.isEmpty() && d.has("errors")) text = d.path("errors").toString();
        out.add(new AgentEvent.Result(kind, error, text, cost, d.path("num_turns").asInt()));
    }

    private void rateLimit(JsonNode info, List<AgentEvent> out) {
        JsonNode w = info.path("unifiedWindows");
        double five = -1, week = -1;
        long fiveReset = 0, weekReset = 0;
        if (w.isObject()) {
            five = w.path("five_hour").path("utilization").asDouble(-1);
            fiveReset = w.path("five_hour").path("resetsAt").asLong(0);
            week = w.path("seven_day").path("utilization").asDouble(-1);
            weekReset = w.path("seven_day").path("resetsAt").asLong(0);
        } else if (info.has("utilization")) {
            if ("five_hour".equals(info.path("rateLimitType").asText())) {
                five = info.path("utilization").asDouble();
                fiveReset = info.path("resetsAt").asLong();
            } else {
                week = info.path("utilization").asDouble();
                weekReset = info.path("resetsAt").asLong();
            }
        }
        out.add(new AgentEvent.RateLimit(five, fiveReset, week, weekReset, info.path("status").asText("")));
    }

    private static String text(JsonNode d, String field) {
        JsonNode n = d.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
