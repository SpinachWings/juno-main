package dev.juno.store;

import dev.juno.model.Agent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Agents under {@code ~/.juno/agents/<id>/agent.json}, saved atomically as things happen (design §12). */
public final class AgentStore {
    private final JunoHome home;

    public AgentStore(JunoHome home) {
        this.home = home;
    }

    public List<Agent> loadAll() {
        List<Agent> out = new ArrayList<>();
        if (!Files.isDirectory(home.agentsDir())) return out;
        try (Stream<Path> dirs = Files.list(home.agentsDir())) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                Path f = d.resolve("agent.json");
                if (!Files.isRegularFile(f)) continue;
                try {
                    out.add(Json.MAPPER.readValue(f.toFile(), Agent.class));
                } catch (IOException e) {
                    System.err.println("Skipping unreadable agent " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparingLong(a -> a.created));
        return out;
    }

    public synchronized void save(Agent a) {
        try {
            AtomicFiles.write(home.agentDir(a.id).resolve("agent.json"), Json.MAPPER.writeValueAsBytes(a));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save agent " + a.name + ": " + e.getMessage(), e);
        }
    }
}
