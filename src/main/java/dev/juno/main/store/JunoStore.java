package dev.juno.main.store;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Ticket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-based persistence under {@code ~/.juno} (override with the {@code JUNO_HOME} environment variable):
 * {@code settings.json}, {@code board.json} and one agent log per ticket in {@code logs/}.
 */
public final class JunoStore {

    /** On-disk shape of the board. */
    public static final class BoardData {
        private int nextTicketNumber = 1;
        private List<Ticket> tickets = new ArrayList<>();

        public int getNextTicketNumber() {
            return nextTicketNumber;
        }

        public void setNextTicketNumber(int nextTicketNumber) {
            this.nextTicketNumber = nextTicketNumber;
        }

        public List<Ticket> getTickets() {
            return tickets;
        }
    }

    private final Path home;
    private final ObjectMapper mapper;

    public JunoStore(Path home) {
        this.home = home;
        this.mapper = createMapper();
        try {
            Files.createDirectories(home.resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create Juno home " + home, e);
        }
    }

    public static JunoStore openDefault() {
        String override = System.getenv("JUNO_HOME");
        Path home = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".juno");
        return new JunoStore(home);
    }

    /** Field-based mapping so model classes don't need Jackson annotations or setters for everything. */
    public static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public Path home() {
        return home;
    }

    /** Changes made to settings while loading them (e.g. migrations), for Juno to report once. */
    private final List<String> settingsNotes = new ArrayList<>();

    public List<String> settingsNotes() {
        return List.copyOf(settingsNotes);
    }

    public JunoSettings loadSettings() {
        Path file = home.resolve("settings.json");
        if (!Files.exists(file)) {
            return JunoSettings.createDefault();
        }
        JunoSettings settings;
        try {
            JsonNode tree = mapper.readTree(file.toFile());
            if (tree instanceof ObjectNode root && SettingsMigration.isLegacy(root)) {
                Files.copy(file, file.resolveSibling("settings.pre-orgs.json"), StandardCopyOption.REPLACE_EXISTING);
                SettingsMigration.migrate(root);
            }
            settings = mapper.treeToValue(tree, JunoSettings.class);
        } catch (IOException e) {
            settings = read(file, JunoSettings.class, JunoSettings::createDefault);
        }
        List<String> notes = settings.normalise();
        if (!notes.isEmpty()) {
            try {
                Files.copy(file, file.resolveSibling("settings.pre-single-host.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // best effort backup
            }
            notes.forEach(n -> System.err.println("[juno] " + n));
            settingsNotes.addAll(notes);
            saveSettings(settings);
        }
        return settings;
    }

    public void saveSettings(JunoSettings settings) {
        write(home.resolve("settings.json"), settings);
    }

    public BoardData loadBoard() {
        Path file = home.resolve("board.json");
        if (!Files.exists(file)) {
            return new BoardData();
        }
        try {
            JsonNode tree = mapper.readTree(file.toFile());
            if (migrateBoard(tree)) {
                Files.copy(file, file.resolveSibling("board.pre-review-columns.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return mapper.treeToValue(tree, BoardData.class);
        } catch (IOException e) {
            return read(file, BoardData.class, BoardData::new);
        }
    }

    /** Maps statuses from earlier versions: AI review / Human review became the Review column. */
    static boolean migrateBoard(JsonNode tree) {
        boolean changed = false;
        for (JsonNode ticket : tree.path("tickets")) {
            String status = ticket.path("status").asText("");
            if ((status.equals("AI_REVIEW") || status.equals("HUMAN_REVIEW")) && ticket instanceof ObjectNode o) {
                o.put("status", "REVIEW");
                changed = true;
            }
        }
        return changed;
    }

    public void saveBoard(BoardData board) {
        write(home.resolve("board.json"), board);
    }

    /** Deep copy via JSON round-trip (used to edit settings without touching the live copy). */
    public <T> T copy(T value, Class<T> type) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(value), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Folder holding a ticket's run journals and prompts. */
    public Path runsDir(String ticketId) {
        return home.resolve("runs").resolve(ticketId.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    public Path logFile(String ticketId) {
        return home.resolve("logs").resolve(ticketId.replaceAll("[^A-Za-z0-9_.-]", "_") + ".log");
    }

    public void appendLog(String ticketId, String line) {
        try {
            Files.writeString(logFile(ticketId), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write log for " + ticketId + ": " + e.getMessage());
        }
    }

    public String readLog(String ticketId) {
        Path file = logFile(ticketId);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "Could not read log: " + e.getMessage();
        }
    }

    private <T> T read(Path file, Class<T> type, java.util.function.Supplier<T> fallback) {
        if (!Files.exists(file)) {
            return fallback.get();
        }
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            // Keep the unreadable file for inspection rather than silently overwriting it.
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.copy(file, backup);
            } catch (IOException ignored) {
                // best effort
            }
            System.err.println("Could not read " + file + " (backed up to " + backup + "): " + e.getMessage());
            return fallback.get();
        }
    }

    private synchronized void write(Path file, Object value) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, mapper.writeValueAsBytes(value));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + file, e);
        }
    }
}
