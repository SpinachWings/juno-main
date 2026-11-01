package dev.juno;

import static org.junit.jupiter.api.Assertions.*;

import dev.juno.model.BuiltInProfiles;
import dev.juno.model.Profile;
import dev.juno.prompts.Prompts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsAndProfilesTest {
    private static final Map<String, Object> VALUES = Map.of(
            "agent_name", "bob", "profile_name", "coder", "fresh_start_threshold", 60,
            "build_and_test_commands", "npm test", "args", "");

    @Test
    void sixBuiltInProfilesWithTheirPrompts() {
        List<Profile> all = BuiltInProfiles.create();
        assertEquals(List.of("orchestrator", "coder", "reviewer", "planner", "searcher", "notes writer"),
                all.stream().map(p -> p.name).toList());
        for (Profile p : all) {
            assertTrue(p.builtIn);
            assertFalse(p.systemPrompt.isBlank(), p.name);
            // Every placeholder in a system prompt is one the app supplies.
            assertDoesNotThrow(() -> Prompts.render(p.systemPrompt, VALUES), p.name);
        }
    }

    @Test
    void coderHasLockedCheckforcomments() {
        Profile coder = BuiltInProfiles.create().get(1);
        assertTrue(coder.editsFiles && coder.needsProjectFolder);
        assertEquals(1, coder.commands.size());
        assertEquals("checkforcomments", coder.commands.get(0).name);
        assertTrue(coder.commands.get(0).builtIn);
        assertDoesNotThrow(() -> Prompts.render(coder.commands.get(0).template, VALUES));
        assertTrue(coder.disallowedTools.contains("Bash(gh:*)"));
    }

    @Test
    void renderSectionsAndUnknownPlaceholders() {
        assertEquals("hi bob!", Prompts.render("hi {{n}}{{#x}}!{{/x}}{{^x}}?{{/x}}", Map.of("n", "bob", "x", "yes")));
        assertEquals("hi bob?", Prompts.render("hi {{n}}{{#x}}!{{/x}}{{^x}}?{{/x}}", Map.of("n", "bob", "x", "")));
        assertThrows(IllegalArgumentException.class, () -> Prompts.render("{{nope}}", Map.of()));
    }

    @Test
    void keyedFilesSplitIntoSections() {
        Map<String, String> s = Prompts.sections("# Title\nnotes\n## a\nfirst\n## b\nsecond\nline");
        assertEquals(List.of("a", "b"), List.copyOf(s.keySet()));
        assertEquals("second\nline", s.get("b"));
    }
}
