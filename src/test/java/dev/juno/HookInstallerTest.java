package dev.juno;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.provider.Provider.HookStatus;
import dev.juno.provider.claude.ClaudeVersion;
import dev.juno.provider.claude.HookInstaller;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All against a scratch copy of ~/.claude, never the real one. */
class HookInstallerTest {
    @TempDir Path tmp;

    private HookInstaller installer() throws Exception {
        Path claude = tmp.resolve("claude");
        Files.createDirectories(claude);
        return new HookInstaller(claude, new JunoHome(tmp.resolve("home")));
    }

    private long backups(HookInstaller h) throws Exception {
        try (var s = Files.list(h.settingsFile().getParent())) {
            return s.filter(f -> f.getFileName().toString().endsWith(".bak")).count();
        }
    }

    private static final String EXISTING = """
            {"theme":"dark","permissions":{"allow":["Bash(ls:*)"]},
             "hooks":{"Stop":[{"hooks":[{"type":"command","command":"echo mine"}]}],
                      "PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"check.sh"}]}]}}
            """;

    @Test
    void installMergesWithoutTouchingOtherSettingsAndBacksUp() throws Exception {
        HookInstaller h = installer();
        Files.writeString(h.settingsFile(), EXISTING);
        assertEquals(HookStatus.NOT_INSTALLED, h.status());

        h.install();

        assertEquals(HookStatus.INSTALLED, h.status());
        assertEquals(1, backups(h));
        JsonNode root = Json.MAPPER.readTree(h.settingsFile().toFile());
        assertEquals("dark", root.get("theme").asText());
        assertEquals("Bash(ls:*)", root.at("/permissions/allow/0").asText());
        assertEquals("check.sh", root.at("/hooks/PreToolUse/0/hooks/0/command").asText());
        assertEquals("echo mine", root.at("/hooks/Stop/0/hooks/0/command").asText());
        assertTrue(root.at("/hooks/Stop/1/hooks/0/command").asText().contains("juno-hook.sh"));
        assertTrue(root.at("/hooks/Notification/0/hooks/0/command").asText().contains("juno-hook.sh"));
        assertEquals(5, root.at("/hooks/Stop/1/hooks/0/timeout").asInt());
        assertTrue(Files.exists(h.scriptFile()));
    }

    @Test
    void installIsIdempotent() throws Exception {
        HookInstaller h = installer();
        Files.writeString(h.settingsFile(), EXISTING);
        h.install();
        String once = Files.readString(h.settingsFile());
        String second = h.install();
        assertEquals(once, Files.readString(h.settingsFile()));
        assertEquals(1, backups(h), "no change, no second backup");
        assertTrue(second.contains("nothing changed"));
    }

    @Test
    void uninstallRemovesOnlyOurEntries() throws Exception {
        HookInstaller h = installer();
        Files.writeString(h.settingsFile(), EXISTING);
        h.install();
        Thread.sleep(5);
        h.uninstall();

        assertEquals(HookStatus.NOT_INSTALLED, h.status());
        JsonNode root = Json.MAPPER.readTree(h.settingsFile().toFile());
        assertEquals(1, root.at("/hooks/Stop").size());
        assertEquals("echo mine", root.at("/hooks/Stop/0/hooks/0/command").asText());
        assertFalse(root.at("/hooks").has("Notification"));
        assertEquals("check.sh", root.at("/hooks/PreToolUse/0/hooks/0/command").asText());
        assertEquals(2, backups(h));
        assertFalse(Files.exists(h.scriptFile()));
    }

    @Test
    void worksWithNoSettingsFileAndLeavesNoEmptyHooksBehind() throws Exception {
        HookInstaller h = installer();
        h.install();
        assertEquals(HookStatus.INSTALLED, h.status());
        assertEquals(0, backups(h), "nothing to back up");
        h.uninstall();
        assertEquals("{ }", Json.MAPPER.readTree(h.settingsFile().toFile()).toString().replace("{}", "{ }"));
    }

    @Test
    void movedOrEditedHookShowsAsOutdated() throws Exception {
        HookInstaller h = installer();
        h.install();
        Files.writeString(h.scriptFile(), "#!/bin/sh\n# juno-hook v0\n");
        assertEquals(HookStatus.OUTDATED, h.status());
        h.install();
        assertEquals(HookStatus.INSTALLED, h.status());
    }

    @Test
    void refusesToTouchSettingsThatAreNotAnObject() throws Exception {
        HookInstaller h = installer();
        Files.writeString(h.settingsFile(), "[1,2]");
        assertThrows(RuntimeException.class, h::install);
        assertEquals("[1,2]", Files.readString(h.settingsFile()));
    }

    @Test
    void hookScriptIgnoresOtherProcessesAndNeverFails() throws Exception {
        HookInstaller h = installer();
        h.install();
        String sh = findSh();
        org.junit.jupiter.api.Assumptions.assumeTrue(sh != null, "no sh available");
        Path events = tmp.resolve("home").resolve("hook-events");

        run(sh, h, null, "{\"hook_event_name\":\"Stop\"}");
        assertFalse(Files.exists(events), "no JUNO_AGENT_ID: writes nothing");

        int exit = run(sh, h, "bob", "{\"hook_event_name\":\"Stop\"}");
        assertEquals(0, exit);
        assertTrue(Files.readString(events.resolve("bob.jsonl")).contains("\"Stop\""));
    }

    private static int run(String sh, HookInstaller h, String agent, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(sh, h.scriptFile().toString());
        if (agent != null) pb.environment().put("JUNO_AGENT_ID", agent);
        else pb.environment().remove("JUNO_AGENT_ID");
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes());
        p.getOutputStream().close();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private static String findSh() {
        for (String c : new String[] {"sh", "C:/Program Files/Git/bin/sh.exe", "/bin/sh"}) {
            try {
                Process p = new ProcessBuilder(c, "-c", "exit 0").start();
                if (p.waitFor() == 0) return c;
            } catch (Exception ignored) {
                // try the next
            }
        }
        return null;
    }

    @Test
    void versionCheck() {
        assertTrue(ClaudeVersion.evaluate("2.1.314 (Claude Code)").ok());
        assertTrue(ClaudeVersion.evaluate("3.0.0 (Claude Code)").ok());
        var old = ClaudeVersion.evaluate("2.0.9 (Claude Code)");
        assertFalse(old.ok());
        assertEquals("Claude Code " + ClaudeVersion.MINIMUM + " or later is needed; you have 2.0.9. Run `claude update`.",
                old.message());
        assertFalse(ClaudeVersion.evaluate("garbage").ok());
        assertTrue(ClaudeVersion.compare("2.1.10", "2.1.9") > 0);
        assertFalse(ClaudeVersion.check("definitely-not-a-real-claude-binary").ok());
    }
}
