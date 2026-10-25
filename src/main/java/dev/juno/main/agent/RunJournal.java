package dev.juno.main.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.model.RunKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A durable record of one agent run, written as it happens so it survives crashes and restarts:
 * <ul>
 *   <li>{@code <run>.prompt.md} - the prompt the agent was given</li>
 *   <li>{@code <run>.jsonl} - every event Claude Code streamed (assistant messages, tool calls and results,
 *       the final result), plus {@code {"type":"juno",...}} lines for Juno's own events</li>
 * </ul>
 * After an interruption, {@link #recap} condenses the journal into a summary a new agent can continue from.
 */
public final class RunJournal {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunJournal() {
    }

    /** Creates the files for a new run and returns the journal path. */
    public static Path create(Path runsDir, RunKind kind, String prompt) throws IOException {
        Files.createDirectories(runsDir);
        String base = NAME.format(Instant.now()) + "-" + kind.name().toLowerCase();
        Path journal = runsDir.resolve(base + ".jsonl");
        for (int i = 2; Files.exists(journal); i++) {
            journal = runsDir.resolve(base + "-" + i + ".jsonl");
        }
        Files.writeString(promptFile(journal), prompt, StandardCharsets.UTF_8);
        Files.writeString(journal, "", StandardCharsets.UTF_8);
        return journal;
    }

    public static Path promptFile(Path journal) {
        String name = journal.getFileName().toString().replaceFirst("\\.jsonl$", "");
        return journal.resolveSibling(name + ".prompt.md");
    }

    /** Appends a raw stream-json line from the agent. */
    public static void append(Path journal, String line) {
        if (journal == null || line == null || line.isBlank()) {
            return;
        }
        try {
            Files.writeString(journal, line.strip() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write run journal " + journal + ": " + e.getMessage());
        }
    }

    /** Appends a Juno event, e.g. {@code event("start", "mode", "fresh")}. */
    public static void event(Path journal, String event, String... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "juno");
        node.put("event", event);
        node.put("at", Instant.now().toString());
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        append(journal, node.toString());
    }

    public static String readPrompt(Path journal) {
        try {
            return Files.readString(promptFile(journal), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A readable account of what the agent did in the run, newest last, for handing to the agent that resumes
     * it. Keeps the most recent actions when the run was long.
     */
    public static String recap(Path journal, int maxChars) {
        List<String> entries = new ArrayList<>();
        String started = null;
        boolean finished = false;
        try {
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                JsonNode e;
                try {
                    e = MAPPER.readTree(line);
                } catch (IOException notJson) {
                    continue;
                }
                switch (e.path("type").asText()) {
                    case "juno" -> {
                        if ("start".equals(e.path("event").asText()) && started == null) {
                            started = e.path("at").asText();
                        }
                    }
                    case "assistant" -> {
                        for (JsonNode block : e.path("message").path("content")) {
                            switch (block.path("type").asText()) {
                                case "text" -> {
                                    String text = block.path("text").asText("").strip();
                                    if (!text.isEmpty()) {
                                        entries.add("- You said: " + clip(text, 600));
                                    }
                                }
                                case "tool_use" -> entries.add("- You ran " + block.path("name").asText()
                                        + ": " + clip(block.path("input").toString(), 400));
                                default -> {
                                    // thinking blocks are not replayed
                                }
                            }
                        }
                    }
                    case "user" -> {
                        for (JsonNode block : e.path("message").path("content")) {
                            if ("tool_result".equals(block.path("type").asText())) {
                                String text = contentText(block.path("content")).replace('\n', ' ').strip();
                                boolean error = block.path("is_error").asBoolean(false);
                                entries.add((error ? "  -> failed: " : "  -> result: ") + clip(text, error ? 400 : 200));
                            }
                        }
                    }
                    case "result" -> finished = true;
                    default -> {
                        // system/init and other events
                    }
                }
            }
        } catch (IOException e) {
            return "(The previous run's journal could not be read: " + e.getMessage() + ")";
        }
        if (entries.isEmpty()) {
            return "(The previous run was interrupted before the agent recorded any actions.)";
        }
        StringBuilder out = new StringBuilder();
        int total = entries.size();
        int from = total;
        int size = 0;
        while (from > 0 && size + entries.get(from - 1).length() + 1 < maxChars) {
            from--;
            size += entries.get(from).length() + 1;
        }
        out.append("Run started ").append(started == null ? "(unknown time)" : started)
                .append(finished ? "; it reported a final result." : "; it was cut off before finishing.")
                .append('\n');
        if (from > 0) {
            out.append("(Showing the last ").append(total - from).append(" of ").append(total)
                    .append(" recorded steps.)\n");
        }
        for (int i = from; i < total; i++) {
            out.append(entries.get(i)).append('\n');
        }
        out.append("- <interrupted here>\n");
        return out.toString();
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

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
