package dev.juno.main.agent;

import dev.juno.main.model.RunKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunJournalTest {

    @TempDir
    Path tmp;

    @Test
    void recordsAndRecapsARun() throws Exception {
        Path journal = RunJournal.create(tmp, RunKind.WORK, "Do the thing");
        assertEquals("Do the thing", RunJournal.readPrompt(journal));
        RunJournal.event(journal, "start", "mode", "fresh");
        RunJournal.append(journal, """
                {"type":"system","subtype":"init","session_id":"s1"}""");
        RunJournal.append(journal, """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Reading the code"},{"type":"tool_use","name":"Edit","input":{"file_path":"A.java"}}]}}""");
        RunJournal.append(journal, """
                {"type":"user","message":{"content":[{"type":"tool_result","content":"compile error","is_error":true}]}}""");
        RunJournal.append(journal, "not json at all");

        String recap = RunJournal.recap(journal, 10_000);
        assertTrue(recap.contains("cut off before finishing"), recap);
        assertTrue(recap.contains("You said: Reading the code"), recap);
        assertTrue(recap.contains("You ran Edit: {\"file_path\":\"A.java\"}"), recap);
        assertTrue(recap.contains("-> failed: compile error"), recap);
        assertTrue(recap.strip().endsWith("<interrupted here>"), recap);
    }

    @Test
    void keepsTheMostRecentStepsWhenLong() throws Exception {
        Path journal = RunJournal.create(tmp, RunKind.REVIEW, "p");
        for (int i = 0; i < 500; i++) {
            RunJournal.append(journal, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"step "
                    + i + "\"}]}}");
        }
        String recap = RunJournal.recap(journal, 2_000);
        assertTrue(recap.contains("step 499"));
        assertTrue(recap.contains("Showing the last"));
        assertTrue(recap.length() < 2_300);
    }

    @Test
    void emptyJournal() throws Exception {
        Path journal = RunJournal.create(tmp, RunKind.WORK, "p");
        assertTrue(RunJournal.recap(journal, 1000).contains("before the agent recorded any actions"));
    }
}
