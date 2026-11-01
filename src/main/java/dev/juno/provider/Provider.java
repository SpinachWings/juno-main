package dev.juno.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything CLI-specific (design §10): command line, output → events, resume, MCP config, skills, hook install and
 * version check. The rest of Juno only sees {@link AgentEvent}s and {@link LaunchSpec}s.
 */
public interface Provider {
    String id();

    /** Runs the executable's version command. */
    VersionCheck checkVersion(String executable);

    HookStatus hookStatus();

    /** Installs or updates the hook; returns a one-line description of what changed. */
    String installHook();

    String uninstallHook();

    /** The full command line for one agent process (the executable first; wrapped for Windows where needed). */
    List<String> commandLine(LaunchSpec spec);

    /** Environment changes for the process: values to set, and names (with a trailing {@code *} as a prefix) to drop. */
    Map<String, String> environment(LaunchSpec spec);

    List<String> environmentToRemove();

    /** A parser for one process's output; it keeps state across lines. */
    StreamParser newParser();

    /** The line written to the process's input to send a message. */
    String userMessage(String text);

    /** The line that interrupts the current turn. */
    String interrupt(String requestId);

    /** The message that compacts the conversation. */
    String compactMessage();

    /** MCP config file contents connecting the agent to Juno's server. */
    String mcpConfig(String junoUrl, String token);

    /** The name the permission prompt tool has inside the agent. */
    String permissionPromptTool();

    /** Writes the agent's skills as a session-only plugin folder; returns the folder to load, or null if none. */
    Path writeSkills(Path dir, List<SkillSet> skills) throws IOException;

    /** How the agent refers to a skill of a pack, e.g. {@code superpowers:brainstorming}. */
    String skillName(String packId, String skill);

    /** One pack's enabled skills: the pack's folder of skills ({@code <dir>/<skill>/SKILL.md}) and the names to give. */
    record SkillSet(String packId, String packName, Path skillsDir, List<String> skills) {}

    /** Turns output lines into events. */
    interface StreamParser {
        List<AgentEvent> parse(String line);
    }

    /** Everything one launch needs. Blank strings and nulls mean "CLI default" / "none". */
    record LaunchSpec(String executable, Path workingDir, String model, String effort, String permissionMode,
                      List<String> allowedTools, List<String> disallowedTools, int maxTurns, Double spendLimit,
                      Path systemPromptFile, Path mcpConfigFile, Path pluginDir, String resumeSession,
                      String agentId) {}

    enum HookStatus { INSTALLED, OUTDATED, NOT_INSTALLED }

    /** Result of a version check; {@code message} is ready to show. */
    record VersionCheck(boolean ok, String version, String message) {}
}
