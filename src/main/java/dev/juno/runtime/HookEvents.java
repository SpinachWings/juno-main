package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.store.Json;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Tails {@code ~/.juno/hook-events/<agent>.jsonl}, written by the Claude hook (docs/progress.md, Milestone 1
 * decision 2). Polls once a second; each file is reset when its agent's process starts.
 */
final class HookEvents {
    private final Path dir;
    private final BiConsumer<String, JsonNode> onEvent;
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    private Thread thread;
    private volatile boolean running;

    HookEvents(Path dir, BiConsumer<String, JsonNode> onEvent) {
        this.dir = dir;
        this.onEvent = onEvent;
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "juno-hook-events");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** Forgets old events of an agent (called when its process starts). */
    void reset(String agentId) {
        try {
            Files.deleteIfExists(dir.resolve(agentId + ".jsonl"));
        } catch (IOException ignored) {
            // the hook may hold it open for a moment; the offset below still skips old lines
        }
        Path f = dir.resolve(agentId + ".jsonl");
        long size = 0;
        try {
            if (Files.exists(f)) size = Files.size(f);
        } catch (IOException ignored) {
            // treat as empty
        }
        offsets.put(agentId, size);
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            for (Map.Entry<String, Long> e : offsets.entrySet()) poll(e.getKey(), e.getValue());
        }
    }

    private void poll(String agentId, long offset) {
        Path f = dir.resolve(agentId + ".jsonl");
        try {
            if (!Files.exists(f)) return;
            long size = Files.size(f);
            if (size < offset) offset = 0;
            if (size == offset) return;
            byte[] buf;
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                raf.seek(offset);
                buf = new byte[(int) Math.min(size - offset, 1_000_000)];
                raf.readFully(buf);
            }
            String text = new String(buf, StandardCharsets.UTF_8);
            int lastNl = text.lastIndexOf('\n');
            if (lastNl < 0) return; // wait for the rest of the line
            offsets.put(agentId, offset + text.substring(0, lastNl + 1).getBytes(StandardCharsets.UTF_8).length);
            for (String line : text.substring(0, lastNl).split("\n")) {
                if (line.isBlank()) continue;
                try {
                    onEvent.accept(agentId, Json.MAPPER.readTree(line));
                } catch (IOException ignored) {
                    // not JSON: skip
                }
            }
        } catch (IOException ignored) {
            // try again next second
        }
    }
}
