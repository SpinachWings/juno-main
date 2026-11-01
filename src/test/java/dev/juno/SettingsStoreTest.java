package dev.juno;

import static org.junit.jupiter.api.Assertions.*;

import dev.juno.model.*;
import dev.juno.store.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
    @TempDir Path tmp;

    @Test
    void roundTripKeepsEverythingAndNeverWritesTokens() throws Exception {
        JunoHome home = new JunoHome(tmp);
        SettingsStore store = new SettingsStore(home);
        Settings s = store.load();
        assertEquals(6, s.profiles.size());

        Key k = new Key();
        k.label = "bot";
        k.token = TokenStore.keyringReference("abc");
        s.keys.add(k);
        Project p = new Project();
        p.name = "api";
        p.folder = tmp.toString();
        p.buildAndTestCommands = List.of("npm ci", "npm test");
        s.projects.add(p);
        s.system.hopLimit = 7;
        s.profiles.get(1).model = "claude-haiku-5-5";
        s.profiles.get(1).systemPrompt = "tampered";
        s.profiles.get(1).allowedTools.clear();
        s.profiles.add(s.profiles.get(1).duplicate("coder 2"));
        store.save(s);

        Settings back = store.load();
        assertEquals(7, back.system.hopLimit);
        assertNotNull(back.keys.get(0).id);
        assertEquals("keyring:juno/key/abc", back.keys.get(0).token);
        assertEquals(List.of("npm ci", "npm test"), back.projects.get(0).buildAndTestCommands);
        assertEquals(7, back.profiles.size());
        Profile coder = back.profiles.get(1);
        assertEquals("claude-haiku-5-5", coder.model, "editable fields survive");
        assertNotEquals("tampered", coder.systemPrompt, "locked fields come from code");
        assertFalse(coder.allowedTools.isEmpty());
        assertFalse(back.profiles.get(6).builtIn);
        assertEquals("coder 2", back.profiles.get(6).name);
        assertEquals(0, Files.list(tmp).filter(f -> f.toString().endsWith(".tmp")).count(), "no temp files left");
    }

    @Test
    void userProfileCannotShadowABuiltIn() {
        SettingsStore store = new SettingsStore(new JunoHome(tmp));
        Settings s = store.load();
        Profile imposter = new Profile();
        imposter.name = "coder";
        s.profiles.add(imposter);
        store.save(s);
        assertEquals(6, store.load().profiles.size());
    }

    @Test
    void validation() {
        Project p = new Project();
        p.id = "1";
        assertFalse(Validation.project(p, List.of()).isEmpty());
        p.name = "api";
        p.folder = tmp.resolve("nope").toString();
        assertTrue(Validation.project(p, List.of()).get(0).contains("doesn't exist"));
        p.folder = tmp.toString();
        p.remote = "not a remote";
        assertEquals(1, Validation.project(p, List.of()).size());
        p.remote = "acme/api";
        assertTrue(Validation.project(p, List.of()).isEmpty());
        p.remote = "group/sub/project";
        assertTrue(Validation.project(p, List.of()).isEmpty());

        Key k = new Key();
        k.id = "1";
        k.label = "me";
        assertFalse(Validation.key(k, List.of(), false, "").isEmpty(), "needs a token");
        assertTrue(Validation.key(k, List.of(), true, "").isEmpty());
        assertTrue(Validation.key(k, List.of(), false, "GH_TOKEN").isEmpty());
        assertFalse(Validation.key(k, List.of(), false, "9 bad").isEmpty());
        k.gitName = "Bot";
        assertFalse(Validation.key(k, List.of(), true, "").isEmpty(), "name without email");
    }

    @Test
    void mcpUrls() {
        Key k = new Key();
        assertEquals(Optional.of("https://api.githubcopilot.com/mcp/"), McpUrls.forKey(k));
        k.host = "acme.ghe.com";
        assertEquals(Optional.of("https://copilot-api.acme.ghe.com/mcp"), McpUrls.forKey(k));
        k.host = "git.corp.example";
        assertEquals(Optional.empty(), McpUrls.forKey(k));
        k.mcpUrlOverride = "https://x.example/mcp";
        assertEquals(Optional.of("https://x.example/mcp"), McpUrls.forKey(k));
        Key g = new Key();
        g.kind = Key.Kind.GITLAB;
        g.host = "gitlab.example.org";
        assertEquals(Optional.of("https://gitlab.example.org/api/v4/mcp"), McpUrls.forKey(g));
    }

    @Test
    void maskingNeverShowsTheWholeToken() {
        assertEquals("", TokenStore.mask(""));
        assertFalse(TokenStore.mask("ghp_abcdefghijklmnop").contains("ghp_abcdefghijkl"));
        assertTrue(TokenStore.mask("ghp_abcdefghijklmnop").endsWith("mnop"));
        assertEquals("••••••••", TokenStore.mask("short"));
    }
}
