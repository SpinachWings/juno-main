package dev.juno.provider.claude;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.provider.Provider;
import dev.juno.store.AtomicFiles;
import dev.juno.store.JunoHome;
import dev.juno.store.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude Code behind {@link Provider} (design §10). Flags verified against Claude Code 2.1.314. */
public final class ClaudeCodeProvider implements Provider {
    /** Juno's MCP server's name inside agents: its tools are {@code mcp__juno__<tool>}. */
    public static final String JUNO_SERVER = "juno";

    /**
     * Variables a parent Claude Code session sets for its own children. Passed on, they make an agent behave as part
     * of that session (its tools, auth, messaging), e.g. when Juno itself is started from Claude Code.
     */
    private static final List<String> PARENT_SESSION_VARIABLES = List.of(
            "CLAUDECODE", "CLAUDE_PID", "CLAUDE_EFFORT", "AI_AGENT", "CLAUDE_AGENT_SDK_*",
            "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_HOST_SESSION_ID",
            "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_SDK_HAS_HOST_AUTH_REFRESH",
            "CLAUDE_CODE_MESSAGING_*", "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_TERMINAL_MCP_TOOLS",
            "CLAUDE_CODE_DESKTOP_APP_VERSION", "CLAUDE_CODE_ACCOUNT_UUID", "CLAUDE_CODE_ORGANIZATION_UUID",
            "CLAUDE_CODE_USER_EMAIL", "CLAUDE_CODE_OAUTH_SCOPES", "CLAUDE_CODE_EMIT_*", "CLAUDE_CODE_ENABLE_*",
            "CLAUDE_CODE_REPORT_FINDINGS", "CLAUDE_CODE_EAGER_FLUSH", "CLAUDE_CODE_STARTUP_FAILURE_RESULTS",
            "CLAUDE_CODE_DISABLE_CRON", "CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "CLAUDE_PREVIEW_CLASSIFIER_FLOOR",
            "CLAUDE_CODE_SIMPLE", "BAGGAGE");

    private final HookInstaller hook;

    public ClaudeCodeProvider(JunoHome home) {
        this(HookInstaller.defaultClaudeDir(), home);
    }

    public ClaudeCodeProvider(Path claudeDir, JunoHome home) {
        this.hook = new HookInstaller(claudeDir, home);
    }

    @Override public String id() { return "claude"; }

    @Override public VersionCheck checkVersion(String executable) { return ClaudeVersion.check(executable); }

    @Override public HookStatus hookStatus() { return hook.status(); }

    @Override public String installHook() { return hook.install(); }

    @Override public String uninstallHook() { return hook.uninstall(); }

    public HookInstaller hookInstaller() { return hook; }

    @Override
    public List<String> commandLine(LaunchSpec s) {
        List<String> c = ClaudeVersion.launcher(s.executable());
        c.addAll(List.of("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--forward-subagent-text"));
        if (notBlank(s.model())) c.addAll(List.of("--model", s.model()));
        if (notBlank(s.effort())) c.addAll(List.of("--effort", s.effort()));
        if (notBlank(s.permissionMode())) c.addAll(List.of("--permission-mode", s.permissionMode()));
        if (!s.allowedTools().isEmpty()) {
            c.add("--allowedTools");
            c.add(String.join(",", s.allowedTools()));
        }
        if (!s.disallowedTools().isEmpty()) {
            c.add("--disallowedTools");
            c.add(String.join(",", s.disallowedTools()));
        }
        if (s.maxTurns() > 0) c.addAll(List.of("--max-turns", String.valueOf(s.maxTurns())));
        if (s.spendLimit() != null && s.spendLimit() > 0) {
            c.addAll(List.of("--max-budget-usd", String.valueOf(s.spendLimit())));
        }
        if (s.systemPromptFile() != null) {
            c.addAll(List.of("--append-system-prompt-file", s.systemPromptFile().toString()));
        }
        c.add("--strict-mcp-config");
        if (s.mcpConfigFile() != null) {
            c.addAll(List.of("--mcp-config", s.mcpConfigFile().toString()));
            c.addAll(List.of("--permission-prompt-tool", permissionPromptTool()));
        }
        if (s.pluginDir() != null) c.addAll(List.of("--plugin-dir", s.pluginDir().toString()));
        if (notBlank(s.resumeSession())) c.addAll(List.of("--resume", s.resumeSession()));
        return c;
    }

    @Override
    public Map<String, String> environment(LaunchSpec s) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JUNO_AGENT_ID", s.agentId());
        return env;
    }

    @Override public List<String> environmentToRemove() { return PARENT_SESSION_VARIABLES; }

    @Override public StreamParser newParser() { return new ClaudeStreamParser(); }

    @Override
    public String userMessage(String text) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "user");
        ObjectNode msg = root.putObject("message");
        msg.put("role", "user");
        msg.put("content", text);
        return root.toString();
    }

    @Override
    public String interrupt(String requestId) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", requestId);
        root.putObject("request").put("subtype", "interrupt");
        return root.toString();
    }

    @Override public String compactMessage() { return "/compact"; }

    @Override
    public String mcpConfig(String junoUrl, String token) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode juno = root.putObject("mcpServers").putObject(JUNO_SERVER);
        juno.put("type", "http");
        juno.put("url", junoUrl);
        juno.putObject("headers").put("Authorization", "Bearer " + token);
        // TODO(Milestone 5): the host's MCP server for the agent's key (design §5.1.1).
        return root.toPrettyString();
    }

    @Override public String permissionPromptTool() { return "mcp__" + JUNO_SERVER + "__request_permission"; }

    /** One plugin per pack, named after the pack, so skills are {@code <pack>:<skill>} as packs expect. */
    @Override
    public Path writeSkills(Path dir, List<SkillSet> skills) throws IOException {
        deleteTree(dir);
        if (skills.isEmpty()) return null;
        for (SkillSet set : skills) {
            Path plugin = dir.resolve(set.packId());
            ObjectNode manifest = Json.MAPPER.createObjectNode();
            manifest.put("name", set.packId());
            manifest.put("version", "1.0.0");
            manifest.put("description", set.packName() + " (skills given by Juno)");
            AtomicFiles.writeString(plugin.resolve(".claude-plugin/plugin.json"), manifest.toPrettyString());
            for (String skill : set.skills()) copyTree(set.skillsDir().resolve(skill), plugin.resolve("skills/" + skill));
        }
        return dir;
    }

    @Override public String skillName(String packId, String skill) { return packId + ":" + skill; }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> all;
        try (var walk = Files.walk(dir)) {
            all = new ArrayList<>(walk.toList());
        }
        all.sort(java.util.Comparator.reverseOrder());
        for (Path p : all) Files.deleteIfExists(p);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
