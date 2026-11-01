package dev.juno.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.model.Agent;
import dev.juno.model.BuiltInProfiles;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.provider.AgentEvent;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptsAndCommandsTest {
    @TempDir Path dir;

    @Test
    void everyBuiltInSystemPromptRenders() {
        Settings s = new Settings();
        Agent a = new Agent();
        a.name = "bob";
        Project p = new Project();
        p.name = "api";
        p.buildAndTestCommands = List.of("npm ci", "npm test");
        for (Profile profile : BuiltInProfiles.create()) {
            a.profile = profile.name;
            String withProject = AgentPrompts.systemPrompt(s, a, profile, p, Path.of("C:/w/api"));
            String without = AgentPrompts.systemPrompt(s, a, profile, null, Path.of("C:/h"));
            assertTrue(withProject.startsWith("# You are bob"), profile.name);
            assertTrue(withProject.contains("Project: api"), profile.name);
            assertTrue(without.contains("request_folder"), profile.name);
            assertFalse(withProject.contains("{{"), profile.name);
        }
        Profile coder = BuiltInProfiles.create().get(1);
        a.profile = coder.name;
        assertTrue(AgentPrompts.systemPrompt(s, a, coder, p, Path.of("C:/w")).contains("npm ci, npm test"));
        String cmd = AgentPrompts.profileCommand(s, a, p, coder.commands.get(0).template, "only #42");
        assertTrue(cmd.contains("only #42"));
        assertFalse(cmd.contains("{{"));
    }

    @Test
    void attachedAndSpacedCommandForms() {
        List<String> names = List.of("hey", "activate", "model", "checkforcomments");
        assertEquals(new PaneCommands.Parsed("hey", "bob do this"), PaneCommands.parse("/heybob do this", names));
        assertEquals(new PaneCommands.Parsed("hey", "bob do this"), PaneCommands.parse("/hey bob do this", names));
        assertEquals(new PaneCommands.Parsed("activate", "Brainstorming"), PaneCommands.parse("/activateBrainstorming", names));
        assertEquals(new PaneCommands.Parsed("model", "haiku"), PaneCommands.parse("/model haiku", names));
        assertEquals(new PaneCommands.Parsed("checkforcomments", ""), PaneCommands.parse("/checkforcomments", names));
        assertEquals(new PaneCommands.Parsed("init", ""), PaneCommands.parse("/init", names));
    }

    @Test
    void recapSaysWhatHappened() {
        Agent a = new Agent();
        a.interruptReason = "the process ended unexpectedly";
        a.interruptedAt = System.currentTimeMillis() - 5 * 60_000;
        String recap = AgentPrompts.recap(a, List.of(
                new AgentEvent.UserText("old task", "user"),
                new AgentEvent.UserText("fix the login bug", "user"),
                new AgentEvent.ToolUse("t1", "Read", "{\"file_path\":\"C:\\\\w\\\\src\\\\login.py\"}", null),
                new AgentEvent.ToolUse("t2", "Bash", "{\"command\":\"pytest\"}", null),
                new AgentEvent.ToolUse("t3", "Read", "{\"file_path\":\"inner\"}", "sub"),
                new AgentEvent.Permission("p", "Bash", "{\"command\":\"npm i\"}", "r", "d", null)),
                "M src/login.py", Path.of("C:/w"));
        assertTrue(recap.contains("5 min ago"), recap);
        assertTrue(recap.contains("the process ended unexpectedly"));
        assertTrue(recap.contains("Task: fix the login bug"));
        assertTrue(recap.contains("Read src/login.py; Bash pytest"));
        assertFalse(recap.contains("inner"), "sub-agents' steps aren't the agent's own");
        assertTrue(recap.contains("Still waiting on"));
        assertTrue(recap.contains("M src/login.py"));
    }

    @Test
    void journalRoundTripSkipsATornLine() throws Exception {
        Path f = dir.resolve("j.jsonl");
        Journal j = new Journal(f);
        j.append(new AgentEvent.Message("hi", null));
        j.append(new AgentEvent.Usage(1, 2, 3, 4, 10, 200, 0.1, true));
        j.close();
        java.nio.file.Files.writeString(f, "{\"at\":1,\"ev\":{\"event\":\"mess", java.nio.file.StandardOpenOption.APPEND);
        List<Journal.Entry> back = Journal.read(f);
        assertEquals(2, back.size());
        assertEquals(new AgentEvent.Message("hi", null), back.get(0).event());
    }

    @Test
    void meters() {
        assertEquals("46k / 200k · 23%", Meters.context(46_000, 200_000));
        assertEquals("950", Meters.tokens(950));
        assertEquals("1.2M", Meters.tokens(1_200_000));
    }
}
