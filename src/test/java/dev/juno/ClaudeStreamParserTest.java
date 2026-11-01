package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.provider.AgentEvent;
import dev.juno.provider.Provider;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.provider.claude.ClaudeStreamParser;
import dev.juno.store.JunoHome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lines shaped like Claude Code 2.1.314's stream-json output (recorded during Milestone 2, trimmed). */
class ClaudeStreamParserTest {
    @TempDir Path dir;

    private final ClaudeStreamParser p = new ClaudeStreamParser();

    @Test
    void initGivesTheSession() {
        List<AgentEvent> e = p.parse("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"C:\\\\w\",\"session_id\":\"s1\",\"model\":\"claude-haiku-5-5\"}");
        assertEquals(List.of(new AgentEvent.SessionStarted("s1", "claude-haiku-5-5", "C:\\w")), e);
    }

    @Test
    void assistantBlocksAndUsageOncePerMessage() {
        String usage = "\"usage\":{\"input_tokens\":2,\"output_tokens\":20,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":30}";
        List<AgentEvent> a = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]," + usage + "},\"parent_tool_use_id\":null}");
        List<AgentEvent> b = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]," + usage + "},\"parent_tool_use_id\":null}");
        assertEquals(new AgentEvent.Message("hi", null), a.get(0));
        assertEquals(new AgentEvent.Usage(2, 20, 100, 30, 152, 0, 0, false), a.get(1));
        assertEquals(1, b.size(), "usage counted once per message id");
        assertEquals(new AgentEvent.ToolUse("t1", "Bash", "{\"command\":\"ls\"}", null), b.get(0));
    }

    @Test
    void hiddenThinkingUsesTheEstimate() {
        p.parse("{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens\":227}");
        List<AgentEvent> e = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"model\":\"x\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"abc\"}]},\"parent_tool_use_id\":null}");
        assertEquals(new AgentEvent.Thinking("", 227, null), e.get(0));
    }

    @Test
    void editsBecomeDiffsAndWritesWaitForTheResult() {
        List<AgentEvent> edit = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m3\",\"model\":\"x\",\"content\":[{\"type\":\"tool_use\",\"id\":\"e1\",\"name\":\"Edit\",\"input\":{\"file_path\":\"a.py\",\"old_string\":\"x\",\"new_string\":\"y\"}}]},\"parent_tool_use_id\":null}");
        assertEquals(new AgentEvent.Diff("e1", "a.py", "x", "y", false, null), edit.get(1));
        p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m4\",\"model\":\"x\",\"content\":[{\"type\":\"tool_use\",\"id\":\"w1\",\"name\":\"Write\",\"input\":{\"file_path\":\"b.py\",\"content\":\"new\"}}]},\"parent_tool_use_id\":null}");
        List<AgentEvent> res = p.parse("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"w1\",\"type\":\"tool_result\",\"content\":\"File created\"}]},\"parent_tool_use_id\":null,\"tool_use_result\":{\"type\":\"create\"}}");
        assertEquals(new AgentEvent.ToolResult("w1", "File created", false, null), res.get(0));
        assertEquals(new AgentEvent.Diff("w1", "b.py", "", "new", true, null), res.get(1));
    }

    @Test
    void subAgentsNestAndFinishByNotification() {
        List<AgentEvent> start = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m5\",\"model\":\"x\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a1\",\"name\":\"Agent\",\"input\":{\"description\":\"Read it\",\"subagent_type\":\"general-purpose\",\"prompt\":\"Read hello.txt\"}}]},\"parent_tool_use_id\":null}");
        assertEquals(new AgentEvent.SubAgent("a1", "Read it", "general-purpose", "Read hello.txt", null), start.get(1));
        List<AgentEvent> inner = p.parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m6\",\"model\":\"x\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":5}},\"parent_tool_use_id\":\"a1\"}");
        assertEquals(List.of(new AgentEvent.Message("hi", "a1")), inner, "sub-agent usage isn't the conversation's");
        assertEquals(List.of(new AgentEvent.SubAgentDone("a1", "completed")),
                p.parse("{\"type\":\"system\",\"subtype\":\"task_notification\",\"tool_use_id\":\"a1\",\"status\":\"completed\"}"));
        assertEquals(List.of(new AgentEvent.Background(1)),
                p.parse("{\"type\":\"system\",\"subtype\":\"background_tasks_changed\",\"tasks\":[{\"task_id\":\"x\"}]}"));
    }

    @Test
    void resultCarriesCostWindowAndKind() {
        p.parse("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\",\"model\":\"claude-haiku-5-5\"}");
        List<AgentEvent> e = p.parse("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"total_cost_usd\":0.5,\"num_turns\":3,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2},\"modelUsage\":{\"claude-haiku-5-5\":{\"contextWindow\":1000000}}}");
        assertEquals(new AgentEvent.Usage(1, 2, 0, 0, 0, 1_000_000, 0.5, true), e.get(0));
        assertEquals(new AgentEvent.Result("success", false, "done", 0.5, 3), e.get(1));
        AgentEvent.Result maxTurns = (AgentEvent.Result) p.parse("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}").get(1);
        assertEquals("max_turns", maxTurns.kind());
        AgentEvent.Result auth = (AgentEvent.Result) p.parse("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Failed to authenticate\"}").get(1);
        assertTrue(auth.error());
    }

    @Test
    void rateLimitWindows() {
        AgentEvent e = p.parse("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":0.85,\"resetsAt\":10},\"seven_day\":{\"utilization\":0.14,\"resetsAt\":20}}}}").get(0);
        assertEquals(new AgentEvent.RateLimit(0.85, 10, 0.14, 20, "allowed"), e);
    }

    @Test
    void garbageBecomesADimNotice() {
        assertInstanceOf(AgentEvent.Notice.class, p.parse("not json").get(0));
        assertTrue(p.parse("").isEmpty());
    }

    @Test
    void commandLineHasEveryFlag() {
        ClaudeCodeProvider cc = new ClaudeCodeProvider(dir.resolve("claude"), new JunoHome(dir.resolve("home")));
        Provider.LaunchSpec s = new Provider.LaunchSpec("claude", dir, "haiku", "low", "default",
                List.of("Read", "Bash(git:*)"), List.of("Write"), 150, 2.5, dir.resolve("sp.md"), dir.resolve("mcp.json"),
                dir.resolve("plugins"), "sess-1", "a1");
        String line = String.join(" ", cc.commandLine(s));
        for (String flag : List.of("-p", "--input-format stream-json", "--output-format stream-json", "--verbose",
                "--forward-subagent-text", "--model haiku", "--effort low", "--permission-mode default",
                "--allowedTools Read,Bash(git:*)", "--disallowedTools Write", "--max-turns 150", "--max-budget-usd 2.5",
                "--append-system-prompt-file", "--strict-mcp-config", "--mcp-config",
                "--permission-prompt-tool mcp__juno__request_permission", "--plugin-dir", "--resume sess-1")) {
            assertTrue(line.contains(flag), "missing " + flag + " in " + line);
        }
        assertEquals("a1", cc.environment(s).get("JUNO_AGENT_ID"));
        assertTrue(cc.mcpConfig("http://127.0.0.1:1/mcp", "tok").contains("Bearer tok"));
        assertFalse(cc.userMessage("hi \"x\"").contains("\n"));
    }
}
