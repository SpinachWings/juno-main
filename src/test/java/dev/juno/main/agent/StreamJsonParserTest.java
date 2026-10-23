package dev.juno.main.agent;

import dev.juno.main.model.AgentSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamJsonParserTest {

    private static final String INIT = """
            {"type":"system","subtype":"init","session_id":"abc","model":"claude-sonnet-5-5","cwd":"/repo"}""";

    @Test
    void completedRunGoesToReview() {
        var p = new StreamJsonParser();
        p.accept(INIT);
        List<String> lines = p.accept("""
                {"type":"assistant","message":{"content":[{"type":"text","text":"Editing"},{"type":"tool_use","name":"Edit","input":{"file_path":"A.java"}}]}}""");
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("[tool] Edit"));
        p.accept("""
                {"type":"result","subtype":"success","is_error":false,"result":"All done","session_id":"abc","num_turns":4,"total_cost_usd":0.12}""");
        AgentOutcome o = p.outcome(0, false, "");
        assertEquals(AgentOutcome.Kind.COMPLETED, o.kind());
        assertEquals("abc", o.sessionId());
        assertEquals("All done", o.message());
    }

    @Test
    void decisionMarkerIsDetected() {
        var p = new StreamJsonParser();
        p.accept(INIT);
        p.accept("""
                {"type":"result","subtype":"success","is_error":false,"result":"Found two options.\\nJUNO_DECISION_REQUIRED: Keep the v1 API or remove it?","session_id":"abc"}""");
        AgentOutcome o = p.outcome(0, false, "");
        assertEquals(AgentOutcome.Kind.NEEDS_DECISION, o.kind());
        assertEquals("Keep the v1 API or remove it?", o.message());
    }

    @Test
    void errorsAndCancellation() {
        var p = new StreamJsonParser();
        p.accept(INIT);
        p.accept("""
                {"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"abc"}""");
        assertEquals(AgentOutcome.Kind.FAILED, p.outcome(0, false, "").kind());
        assertEquals(AgentOutcome.Kind.CANCELLED, p.outcome(143, true, "").kind());
        assertEquals(AgentOutcome.Kind.FAILED, new StreamJsonParser().outcome(1, false, "not logged in").kind());
    }

    @Test
    void extractDecisionHandlesAbsence() {
        assertNull(StreamJsonParser.extractDecision("nothing to see"));
        assertNull(StreamJsonParser.extractDecision(null));
    }

    @Test
    void buildsCommandLine() {
        var s = new AgentSettings();
        s.setModel("claude-opus-5-5");
        s.setMaxTurns(30);
        s.setAllowedTools("Bash(mvn test:*)");
        s.setExtraArgs("--add-dir \"C:\\My Docs\"");
        List<String> cmd = AgentRunner.buildCommand(s, "sess", Path.of("mcp.json"), false);
        assertEquals(List.of("claude", "-p", "--output-format", "stream-json", "--verbose",
                "--model", "claude-opus-5-5", "--permission-mode", "acceptEdits", "--max-turns", "30",
                "--allowedTools", "Bash(mvn test:*)", "--mcp-config", "mcp.json", "--resume", "sess",
                "--add-dir", "C:\\My Docs"), cmd);

        List<String> windows = AgentRunner.buildCommand(s, null, null, true);
        assertEquals(List.of("cmd.exe", "/c", "claude"), windows.subList(0, 3));
        s.setCliPath("C:\\tools\\claude.exe");
        assertEquals("C:\\tools\\claude.exe", AgentRunner.buildCommand(s, null, null, true).getFirst());
    }
}
