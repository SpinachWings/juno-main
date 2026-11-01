package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.comms.PermissionRules;
import dev.juno.store.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionRulesTest {
    private static final Path FOLDER = Path.of("C:/work/repo");

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode bash(String cmd) {
        return Json.MAPPER.createObjectNode().put("command", cmd);
    }

    private static JsonNode file(String path) {
        return Json.MAPPER.createObjectNode().put("file_path", path);
    }

    @Test
    void bashPrefixRules() {
        List<String> rules = List.of("Bash(npm install:*)", "Bash(git status)");
        assertTrue(PermissionRules.allows(rules, "Bash", bash("npm install left-pad"), FOLDER));
        assertTrue(PermissionRules.allows(rules, "Bash", bash("npm install"), FOLDER));
        assertFalse(PermissionRules.allows(rules, "Bash", bash("npm installer"), FOLDER));
        assertTrue(PermissionRules.allows(rules, "Bash", bash("git status"), FOLDER));
        assertFalse(PermissionRules.allows(rules, "Bash", bash("git status --short"), FOLDER));
    }

    @Test
    void compoundCommandsNeedEveryPart() {
        List<String> rules = List.of("Bash(npm install:*)");
        assertFalse(PermissionRules.allows(rules, "Bash", bash("npm install x && rm -rf /"), FOLDER));
        assertTrue(PermissionRules.allows(List.of("Bash(npm install:*)", "Bash(npm test:*)"), "Bash",
                bash("npm install && npm test"), FOLDER));
        assertTrue(PermissionRules.allows(rules, "Bash", bash("npm install \"a && b\""), FOLDER), "quoted && isn't a split");
    }

    @Test
    void disallowedMatchesAnyPart() {
        List<String> deny = List.of("Bash(git push --force:*)", "Bash(gh:*)", "Edit");
        assertEquals("Bash(gh:*)", PermissionRules.refusedBy(deny, "Bash", bash("git status; gh pr merge 1"), FOLDER));
        assertEquals("Bash(git push --force:*)",
                PermissionRules.refusedBy(deny, "Bash", bash("git push --force origin x"), FOLDER));
        assertNull(PermissionRules.refusedBy(deny, "Bash", bash("git push origin x"), FOLDER));
        assertEquals("Edit", PermissionRules.refusedBy(deny, "Write", file("C:/work/repo/a.txt"), FOLDER),
                "Edit covers every file-editing tool");
    }

    @Test
    void fileRulesAreRelativeToTheFolder() {
        assertTrue(PermissionRules.allows(List.of("Edit(src/**)"), "Edit", file("C:\\work\\repo\\src\\a\\b.py"), FOLDER));
        assertFalse(PermissionRules.allows(List.of("Edit(src/**)"), "Edit", file("C:/work/repo/test/b.py"), FOLDER));
        assertTrue(PermissionRules.allows(List.of("Edit(*.md)"), "Write", file("C:/work/repo/README.md"), FOLDER));
        assertFalse(PermissionRules.allows(List.of("Edit(*.md)"), "Write", file("C:/work/repo/docs/x.md"), FOLDER));
        assertTrue(PermissionRules.allows(List.of("Read(//C:/other/**)"), "Read", file("C:/other/x/y.txt"), FOLDER));
        assertTrue(PermissionRules.allows(List.of("Read"), "Read", file("anything"), FOLDER));
    }

    @Test
    void mcpAndFetchRules() {
        assertTrue(PermissionRules.allows(List.of("mcp__github"), "mcp__github__create_pr", json("{}"), FOLDER));
        assertFalse(PermissionRules.allows(List.of("mcp__github"), "mcp__githubx__a", json("{}"), FOLDER));
        assertTrue(PermissionRules.allows(List.of("WebFetch(domain:example.com)"), "WebFetch",
                json("{\"url\":\"https://docs.example.com/a\"}"), FOLDER));
        assertFalse(PermissionRules.allows(List.of("WebFetch(domain:example.com)"), "WebFetch",
                json("{\"url\":\"https://evil-example.com/a\"}"), FOLDER));
    }

    @Test
    void proposals() {
        assertEquals(new PermissionRules.Proposal("Bash(npm install:*)", "any `npm install` command"),
                PermissionRules.propose("Bash", bash("npm install left-pad && npm test"), FOLDER));
        assertEquals("Bash(ls:*)", PermissionRules.propose("Bash", bash("ls -la"), FOLDER).rule());
        assertEquals("Bash(touch:*)", PermissionRules.propose("Bash", bash("touch beta.txt"), FOLDER).rule());
        assertEquals("Bash(mkdir:*)", PermissionRules.propose("Bash", bash("mkdir gamma"), FOLDER).rule());
        assertEquals("Bash(git commit:*)", PermissionRules.propose("Bash", bash("git commit -m x"), FOLDER).rule());
        assertEquals("Edit(src/**)", PermissionRules.propose("Write", file("C:\\work\\repo\\src\\a.py"), FOLDER).rule());
        assertEquals("Edit(**)", PermissionRules.propose("Edit", file("C:/work/repo/a.py"), FOLDER).rule());
        assertEquals("Edit(//C:/elsewhere/**)", PermissionRules.propose("Edit", file("C:/elsewhere/a.py"), FOLDER).rule());
        assertEquals("WebFetch(domain:example.com)",
                PermissionRules.propose("WebFetch", json("{\"url\":\"https://example.com/x\"}"), FOLDER).rule());
        assertEquals("NotebookRead", PermissionRules.propose("NotebookRead", json("{}"), FOLDER).rule());
        // a proposal always matches the call it was made for
        JsonNode in = bash("npm install left-pad");
        assertTrue(PermissionRules.allows(List.of(PermissionRules.propose("Bash", in, FOLDER).rule()), "Bash", in, FOLDER));
        JsonNode f = file("C:/elsewhere/a.py");
        assertTrue(PermissionRules.allows(List.of(PermissionRules.propose("Edit", f, FOLDER).rule()), "Edit", f, FOLDER));
    }

    @Test
    void validity() {
        assertTrue(PermissionRules.valid("Bash(npm test:*)"));
        assertTrue(PermissionRules.valid("mcp__juno__report"));
        assertFalse(PermissionRules.valid(""));
        assertFalse(PermissionRules.valid("Bash(npm"));
    }
}
