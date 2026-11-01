package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.provider.AgentEvent;
import dev.juno.store.Json;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One conversation's event journal: {@code journal/<conversation>.jsonl}, one event per line, written as it happens
 * (design §12). It is the pane's history after a restart and the source of the resume recap and usage history.
 * Lines are appended and flushed one at a time; a torn last line (crash mid-write) is skipped on reading.
 */
public final class Journal {
    public record Entry(long at, AgentEvent event) {}

    private final Path file;
    private BufferedWriter out;

    public Journal(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized void append(AgentEvent e) {
        try {
            if (out == null) {
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            ObjectNode line = Json.MAPPER.createObjectNode();
            line.put("at", System.currentTimeMillis());
            line.set("ev", Json.MAPPER.valueToTree(e));
            out.write(Json.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(line));
            out.write('\n');
            out.flush();
        } catch (IOException ex) {
            System.err.println("Journal write failed (" + file + "): " + ex.getMessage());
        }
    }

    public synchronized void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        out = null;
    }

    public static List<Entry> read(Path file) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = Json.MAPPER.readTree(line);
                    out.add(new Entry(n.path("at").asLong(), Json.MAPPER.treeToValue(n.get("ev"), AgentEvent.class)));
                } catch (Exception torn) {
                    // a line cut short by a crash
                }
            }
        } catch (IOException e) {
            System.err.println("Journal read failed (" + file + "): " + e.getMessage());
        }
        return out;
    }
}
