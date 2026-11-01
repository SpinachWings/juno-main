package dev.juno.provider.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.provider.Provider.HookStatus;
import dev.juno.store.AtomicFiles;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;

/**
 * Installs Juno's hook into Claude Code's {@code settings.json} (design §5.2).
 *
 * <p>The hook is a tiny shell script that appends Claude's hook JSON to {@code <JUNO_HOME>/hook-events/<agent>.jsonl}
 * and does nothing unless {@code JUNO_AGENT_ID} is set. It never fails or blocks, and works with Juno closed.
 * Milestone 2 reads the event files. Installing merges only Juno's own entries, is idempotent, and takes a
 * timestamped backup before every change.
 */
public final class HookInstaller {
    /** Bump when the script or entry changes, so installed copies show as outdated. */
    static final int SCRIPT_VERSION = 1;
    static final List<String> EVENTS = List.of("Notification", "Stop");
    static final String SCRIPT_NAME = "juno-hook.sh";
    private static final int TIMEOUT_SECONDS = 5;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path claudeDir;
    private final JunoHome home;

    public HookInstaller(Path claudeDir, JunoHome home) {
        this.claudeDir = claudeDir;
        this.home = home;
    }

    /** Claude Code's config folder: {@code CLAUDE_CONFIG_DIR} if set, else {@code ~/.claude}. */
    public static Path defaultClaudeDir() {
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".claude");
    }

    public Path settingsFile() { return claudeDir.resolve("settings.json"); }

    public Path scriptFile() { return home.hookScriptsDir().resolve(SCRIPT_NAME); }

    /** The exact command written into Claude's settings. */
    String command() {
        return "sh \"" + scriptFile().toAbsolutePath().toString().replace('\\', '/') + "\"";
    }

    String scriptText() {
        String dir = home.hookEventsDir().toAbsolutePath().toString().replace('\\', '/');
        return "#!/bin/sh\n"
                + "# juno-hook v" + SCRIPT_VERSION + " - written by Juno; reinstalled from Settings.\n"
                + "[ -z \"$JUNO_AGENT_ID\" ] && exit 0\n"
                + "mkdir -p \"" + dir + "\" 2>/dev/null\n"
                + "{ cat; echo; } >> \"" + dir + "/$JUNO_AGENT_ID.jsonl\" 2>/dev/null\n"
                + "exit 0\n";
    }

    public HookStatus status() {
        ObjectNode root;
        try {
            root = readSettings();
        } catch (IOException e) {
            return HookStatus.NOT_INSTALLED;
        }
        int found = 0;
        boolean current = true;
        for (String event : EVENTS) {
            boolean here = false;
            for (JsonNode hook : ourHooks(root, event)) {
                here = true;
                if (!command().equals(hook.path("command").asText())) current = false;
            }
            if (here) found++;
        }
        if (found == 0) return HookStatus.NOT_INSTALLED;
        if (found < EVENTS.size() || !current) return HookStatus.OUTDATED;
        try {
            if (!Files.exists(scriptFile()) || !Files.readString(scriptFile()).equals(scriptText()))
                return HookStatus.OUTDATED;
        } catch (IOException e) {
            return HookStatus.OUTDATED;
        }
        return HookStatus.INSTALLED;
    }

    /** Installs or updates the hook. Returns what happened. */
    public String install() {
        try {
            AtomicFiles.writeString(scriptFile(), scriptText());
            ObjectNode root = readSettings();
            ObjectNode before = root.deepCopy();
            ObjectNode hooks = root.has("hooks") && root.get("hooks").isObject()
                    ? (ObjectNode) root.get("hooks") : root.putObject("hooks");
            for (String event : EVENTS) {
                ArrayNode groups = hooks.has(event) && hooks.get(event).isArray()
                        ? (ArrayNode) hooks.get(event) : hooks.putArray(event);
                removeOurs(groups);
                ObjectNode group = groups.addObject();
                ObjectNode hook = group.putArray("hooks").addObject();
                hook.put("type", "command");
                hook.put("command", command());
                hook.put("timeout", TIMEOUT_SECONDS);
            }
            if (root.equals(before)) return "The Claude hook was already installed; nothing changed.";
            String backup = backupAndWrite(root);
            return "Installed the Claude hook." + backup;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not install the Claude hook: " + e.getMessage(), e);
        }
    }

    /** Removes only Juno's entries. */
    public String uninstall() {
        try {
            ObjectNode root = readSettings();
            ObjectNode before = root.deepCopy();
            if (root.has("hooks") && root.get("hooks").isObject()) {
                ObjectNode hooks = (ObjectNode) root.get("hooks");
                for (String event : EVENTS) {
                    if (!hooks.has(event) || !hooks.get(event).isArray()) continue;
                    ArrayNode groups = (ArrayNode) hooks.get(event);
                    removeOurs(groups);
                    if (groups.isEmpty()) hooks.remove(event);
                }
                if (hooks.isEmpty()) root.remove("hooks");
            }
            Files.deleteIfExists(scriptFile());
            if (root.equals(before)) return "The Claude hook wasn't installed; nothing changed.";
            String backup = backupAndWrite(root);
            return "Removed the Claude hook." + backup;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not remove the Claude hook: " + e.getMessage(), e);
        }
    }

    private ObjectNode readSettings() throws IOException {
        if (!Files.exists(settingsFile())) return Json.MAPPER.createObjectNode();
        String text = Files.readString(settingsFile());
        if (text.isBlank()) return Json.MAPPER.createObjectNode();
        JsonNode node = Json.MAPPER.readTree(text);
        if (!(node instanceof ObjectNode obj))
            throw new IOException(settingsFile() + " isn't a JSON object, so Juno left it alone.");
        return obj;
    }

    private String backupAndWrite(ObjectNode root) throws IOException {
        String note = "";
        if (Files.exists(settingsFile())) {
            Path backup = claudeDir.resolve("settings.json.juno-" + STAMP.format(LocalDateTime.now()) + ".bak");
            Files.copy(settingsFile(), backup);
            note = " Backup: " + backup.getFileName();
        }
        AtomicFiles.write(settingsFile(), Json.MAPPER.writeValueAsBytes(root));
        return note;
    }

    private static boolean isOurs(JsonNode hook) {
        return hook.path("command").asText("").contains(SCRIPT_NAME);
    }

    private static List<JsonNode> ourHooks(ObjectNode root, String event) {
        List<JsonNode> out = new java.util.ArrayList<>();
        JsonNode groups = root.path("hooks").path(event);
        if (!groups.isArray()) return out;
        for (JsonNode group : groups)
            for (JsonNode hook : group.path("hooks")) if (isOurs(hook)) out.add(hook);
        return out;
    }

    /** Drops Juno's hooks from every group, and groups left empty by that; others stay untouched. */
    private static void removeOurs(ArrayNode groups) {
        Iterator<JsonNode> it = groups.iterator();
        while (it.hasNext()) {
            JsonNode group = it.next();
            JsonNode hooks = group.path("hooks");
            if (!hooks.isArray()) continue;
            int before = hooks.size();
            Iterator<JsonNode> hi = hooks.iterator();
            while (hi.hasNext()) if (isOurs(hi.next())) hi.remove();
            if (hooks.size() != before && hooks.isEmpty()) it.remove();
        }
    }
}
