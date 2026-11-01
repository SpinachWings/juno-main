package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Settings;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.Journal;
import dev.juno.runtime.PaneCommands;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.AgentStore;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runtime behaviour that needs no CLI: crash recovery, held messages, commands, permissions over MCP. */
class RuntimeTest {
    @TempDir Path dir;
    private AgentRuntime rt;
    private Settings settings;
    private JunoHome home;

    private AgentRuntime runtime() throws Exception {
        home = new JunoHome(dir.resolve("home"));
        SettingsStore store = new SettingsStore(home);
        settings = store.load();
        Profile t = settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow()
                .duplicate("tester");
        t.disallowedTools.add("Bash(rm:*)");
        settings.profiles.add(t);
        // A Claude Code path that doesn't exist: nothing here may start a real process.
        settings.system.claudePath = dir.resolve("no-claude").toString();
        SkillLibrary lib = new SkillLibrary(home);
        rt = new AgentRuntime(home, new ClaudeCodeProvider(dir.resolve("claude"), home), () -> settings,
                () -> store.save(settings), lib, new SkillResolver(lib));
        rt.start();
        return rt;
    }

    @AfterEach
    void stop() {
        if (rt != null) rt.shutdown();
    }

    @Test
    void anAgentCutOffMidTurnIsInterruptedAfterARestart() throws Exception {
        home = new JunoHome(dir.resolve("home"));
        Agent a = new Agent();
        a.id = "a1";
        a.name = "bob";
        a.profile = "tester";
        a.conversationId = "c1";
        a.status = Agent.Status.WORKING;
        a.turnOpen = true;
        a.sessionId = "s1";
        new AgentStore(home).save(a);
        new Journal(home.journalsDir("a1").resolve("c1.jsonl")).append(new AgentEvent.UserText("fix the bug", "user"));
        runtime();
        Agent back = rt.agents().get(0);
        assertEquals(Agent.Status.AWAITING, back.status);
        assertEquals(Agent.Reason.INTERRUPTED, back.reason);
        assertTrue(back.interrupted);
        assertTrue(back.unread);
        assertEquals("s1", back.sessionId, "the conversation is kept for Resume");
        List<AgentEvent> history = rt.live("a1").history();
        assertEquals(new AgentEvent.UserText("fix the bug", "user"), history.get(0),
                "the pane gets its history back from the journal");
        assertTrue(((AgentEvent.Notice) history.get(1)).text().contains("Juno crashed"), "and is told why");
        assertEquals("Juno crashed while you were working", back.interruptReason);
    }

    @Test
    void needsAFolderHoldsTheFirstMessage() throws Exception {
        runtime();
        Agent c = rt.create("cody", "coder", null, null);
        rt.send(c, "fix it", "user");
        assertEquals(Agent.Status.AWAITING, c.status);
        assertEquals(Agent.Reason.FOLDER, c.reason);
        assertEquals("fix it", c.heldMessage);
        assertFalse(rt.live(c.id).running());
        assertEquals("Dropped the held message.", rt.stop(c));
        assertNull(c.heldMessage);
    }

    @Test
    void aMissingClaudeCodeFailsWithTheVersionMessage() throws Exception {
        runtime();
        Agent a = rt.create("bob", "tester", null, null);
        rt.send(a, "hi", "user");
        assertEquals(Agent.Reason.FAILED, a.reason);
        assertTrue(a.statusDetail.contains("wasn't found"), a.statusDetail);
        assertFalse(a.turnOpen);
    }

    @Test
    void namesAreUniqueIgnoringCaseAndSpaces() throws Exception {
        runtime();
        rt.create("Big Bob", "tester", null, null);
        assertTrue(rt.byName("bigbob").isPresent());
        try {
            rt.create("bigbob", "tester", null, null);
            throw new AssertionError("duplicate allowed");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    void commands() throws Exception {
        runtime();
        Agent a = rt.create("bob", "tester", null, null);
        PaneCommands pc = new PaneCommands(rt);
        assertEquals(new PaneCommands.Outcome.Said("`/init` isn't supported in Juno — see /help.", "warn"),
                pc.handle(a, "/init"));
        assertTrue(pc.handle(a, "/help") instanceof PaneCommands.Outcome.Help);
        assertTrue(pc.handle(a, "/clear") instanceof PaneCommands.Outcome.Clear);
        pc.handle(a, "/model haiku");
        assertEquals("haiku", a.modelOverride);
        pc.handle(a, "/effort default");
        assertEquals("", a.effortOverride);
        PaneCommands.Outcome bad = pc.handle(a, "/effort huge");
        assertEquals("warn", ((PaneCommands.Outcome.Said) bad).level());
        assertTrue(((PaneCommands.Outcome.Said) pc.handle(a, "/resume")).text().contains("nothing to resume"));
        a.turnOpen = true;
        PaneCommands.Outcome refused = pc.handle(a, "hello");
        assertTrue(((PaneCommands.Outcome.Said) refused).text().contains("/heybob"), "plain messages refused while working");
    }

    @Test
    void activateTogglesAnEnabledSkill() throws Exception {
        runtime();
        new SkillLibrary(home).refreshBundled(settings);
        settings.skillPacks.forEach(p -> p.profiles.add("tester"));
        Agent a = rt.create("bob", "tester", null, null);
        PaneCommands pc = new PaneCommands(rt);
        pc.handle(a, "/activatebrainstorming");
        assertEquals("superpowers:brainstorming", a.pendingSkill);
        pc.handle(a, "/activate brainstorming");
        assertNull(a.pendingSkill, "repeat cancels");
        pc.handle(a, "/activatesuperpowers");
        assertEquals("superpowers:using-superpowers", a.pendingSkill, "a pack name loads its entry skill");
        assertTrue(((PaneCommands.Outcome.Said) pc.handle(a, "/activate nosuch")).text().contains("no skill"));
    }

    @Test
    void permissionsOverMcp() throws Exception {
        runtime();
        Agent a = rt.create("bob", "tester", null, null);
        String token = registerToken(a.id);
        // Disallowed tools always win, without asking.
        JsonNode denied = permission(token, "Bash", "rm -rf x").get(5, TimeUnit.SECONDS);
        assertEquals("deny", denied.path("behavior").asText());
        assertTrue(denied.path("message").asText().contains("disallowed"));
        // Otherwise the agent waits for the user.
        CompletableFuture<JsonNode> pending = permission(token, "Bash", "npm install x");
        waitUntil(() -> !rt.live(a.id).pendingPermissions().isEmpty());
        assertEquals(Agent.Reason.PERMISSION, a.reason);
        assertEquals("Bash(npm install:*)", rt.live(a.id).pendingPermissions().get(0).proposedRule);
        assertNull(rt.answer(a, null, AgentRuntime.Answer.ALLOW_AGENT, "Bash(npm:*)"));
        JsonNode allowed = pending.get(5, TimeUnit.SECONDS);
        assertEquals("allow", allowed.path("behavior").asText());
        assertEquals("npm install x", allowed.path("updatedInput").path("command").asText());
        assertEquals("Bash(npm:*)", a.rules.get(0).rule, "the edited rule is saved");
        // The saved rule now answers by itself.
        assertEquals("allow", permission(token, "Bash", "npm test").get(5, TimeUnit.SECONDS).path("behavior").asText());
        // Built-in profiles can't be changed from a prompt.
        Agent s = rt.create("sue", "searcher", null, null);
        String t2 = registerToken(s.id);
        CompletableFuture<JsonNode> p2 = permission(t2, "Bash", "ls");
        waitUntil(() -> !rt.live(s.id).pendingPermissions().isEmpty());
        assertTrue(rt.answer(s, null, AgentRuntime.Answer.ALLOW_PROFILE, null).contains("Built-in"));
        assertNull(rt.answer(s, null, AgentRuntime.Answer.DENY, null));
        assertEquals("deny", p2.get(5, TimeUnit.SECONDS).path("behavior").asText());
        // A wrong token is refused.
        HttpResponse<String> r = post("nope", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertEquals(401, r.statusCode());
    }

    @Test
    void toolsListComesFromThePromptFiles() throws Exception {
        runtime();
        Agent a = rt.create("bob", "tester", null, null);
        HttpResponse<String> r = post(registerToken(a.id), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        JsonNode tools = Json.MAPPER.readTree(r.body()).path("result").path("tools");
        assertEquals(2, tools.size());
        JsonNode folder = tools.get(1);
        assertEquals("request_folder", folder.path("name").asText());
        assertTrue(folder.path("description").asText().contains("Awaiting decision"));
        assertEquals("reason", folder.path("inputSchema").path("required").get(0).asText());
    }

    @Test
    void questionsAreTheLastParagraph() {
        assertTrue(AgentRuntime.endsWithQuestion("Done.\n\n❓ Which branch?"));
        assertFalse(AgentRuntime.endsWithQuestion("❓ Which branch?\n\nNever mind, done."));
        assertFalse(AgentRuntime.endsWithQuestion(""));
    }

    // ---- helpers

    private String registerToken(String agentId) throws Exception {
        var f = AgentRuntime.class.getDeclaredField("mcp");
        f.setAccessible(true);
        return ((dev.juno.comms.JunoMcpServer) f.get(rt)).register(agentId);
    }

    private CompletableFuture<JsonNode> permission(String token, String tool, String command) {
        ObjectNode call = Json.MAPPER.createObjectNode();
        call.put("jsonrpc", "2.0");
        call.put("id", 7);
        call.put("method", "tools/call");
        ObjectNode params = call.putObject("params");
        params.put("name", "request_permission");
        ObjectNode args = params.putObject("arguments");
        args.put("tool_name", tool);
        args.putObject("input").put("command", command);
        args.put("tool_use_id", "t-" + command.hashCode());
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode res = Json.MAPPER.readTree(post(token, call.toString()).body());
                return Json.MAPPER.readTree(res.path("result").path("content").get(0).path("text").asText());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(rt.mcpUrl()))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void waitUntil(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue(c.getAsBoolean(), "timed out");
    }

    @SuppressWarnings("unused")
    private static List<String> lines(Path f) throws Exception {
        return Files.readAllLines(f);
    }
}
