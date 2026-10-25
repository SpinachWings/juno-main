package dev.juno.main.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets Claude Code's {@code --output-format stream-json} output: turns each event into readable log
 * lines and remembers the session id and final result so the run can be classified.
 */
public final class StreamJsonParser {

    /** Marker the agent is instructed to emit when it needs a human decision. */
    public static final String DECISION_MARKER = "JUNO_DECISION_REQUIRED:";
    private static final Pattern DECISION = Pattern.compile(
            "(?s)" + Pattern.quote(DECISION_MARKER) + "\\s*(.+)$");

    private final ObjectMapper mapper = new ObjectMapper();

    private String sessionId;
    private String model;
    private boolean resultSeen;
    private boolean resultIsError;
    private String resultSubtype;
    private String resultText;
    private double costUsd = -1;
    private String lastAssistantText;

    /** Parses one stdout line and returns the log lines to display for it (possibly none). */
    public List<String> accept(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        JsonNode event;
        try {
            event = mapper.readTree(line);
        } catch (IOException e) {
            out.add(line);
            return out;
        }
        if (event.hasNonNull("session_id")) {
            sessionId = event.get("session_id").asText();
        }
        switch (event.path("type").asText()) {
            case "system" -> {
                if ("init".equals(event.path("subtype").asText())) {
                    model = event.path("model").asText(null);
                    out.add("[session] " + sessionId + (model == null ? "" : "  model=" + model)
                            + "  cwd=" + event.path("cwd").asText(""));
                }
            }
            case "assistant" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    switch (block.path("type").asText()) {
                        case "text" -> {
                            String text = block.path("text").asText("").strip();
                            if (!text.isEmpty()) {
                                lastAssistantText = text;
                                out.add("[claude] " + text);
                            }
                        }
                        case "tool_use" -> out.add("[tool] " + block.path("name").asText()
                                + " " + truncate(block.path("input").toString(), 300));
                        default -> {
                            // thinking / redacted blocks are not shown
                        }
                    }
                }
            }
            case "user" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        String text = contentText(block.path("content"));
                        String prefix = block.path("is_error").asBoolean(false) ? "  ! " : "  > ";
                        out.add(prefix + truncate(text.replace('\n', ' '), 300));
                    }
                }
            }
            case "result" -> {
                resultSeen = true;
                resultSubtype = event.path("subtype").asText("");
                resultIsError = event.path("is_error").asBoolean(false) || !resultSubtype.equals("success");
                resultText = event.path("result").asText(null);
                if (event.has("total_cost_usd")) {
                    costUsd = event.path("total_cost_usd").asDouble(-1);
                }
                out.add(String.format("[result] %s, %d turns%s", resultSubtype,
                        event.path("num_turns").asInt(),
                        costUsd >= 0 ? String.format(", $%.4f", costUsd) : ""));
            }
            default -> {
                // stream_event and future event types are ignored
            }
        }
        return out;
    }

    /** Classifies the run once the process has exited. */
    public AgentOutcome outcome(int exitCode, boolean cancelled, String stderrTail) {
        if (cancelled) {
            return new AgentOutcome(AgentOutcome.Kind.CANCELLED, sessionId, "Stopped by user", costUsd);
        }
        if (!resultSeen || resultIsError || exitCode != 0) {
            String why;
            if (resultSeen && resultIsError) {
                why = "Agent run ended with '" + resultSubtype + "'"
                        + (resultText == null || resultText.isBlank() ? "" : ": " + resultText);
            } else {
                why = "Claude Code exited with code " + exitCode
                        + (stderrTail == null || stderrTail.isBlank() ? "" : ": " + stderrTail.strip());
            }
            return new AgentOutcome(AgentOutcome.Kind.FAILED, sessionId, why, costUsd);
        }
        String text = resultText != null ? resultText : lastAssistantText;
        String question = extractDecision(text);
        if (question != null) {
            return new AgentOutcome(AgentOutcome.Kind.NEEDS_DECISION, sessionId, question, costUsd, text);
        }
        return new AgentOutcome(AgentOutcome.Kind.COMPLETED, sessionId, text == null ? "" : text.strip(), costUsd,
                text);
    }

    /** Returns the question following the decision marker, or null if the text contains no marker. */
    public static String extractDecision(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DECISION.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    public String sessionId() {
        return sessionId;
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if (c.has("text")) {
                sb.append(c.get("text").asText());
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
