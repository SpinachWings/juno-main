package dev.juno.model;

import dev.juno.prompts.Prompts;
import java.util.ArrayList;
import java.util.List;

/**
 * The six built-in profiles (design §6). Locked fields come from here every time settings load; only model, effort,
 * executable path, max turns and spend limit are taken from the saved copy.
 */
public final class BuiltInProfiles {
    public static final String ORCHESTRATOR = "orchestrator";
    public static final String CODER = "coder";
    public static final String REVIEWER = "reviewer";
    public static final String PLANNER = "planner";
    public static final String SEARCHER = "searcher";
    public static final String NOTES_WRITER = "notes writer";

    /** Tools every profile may call on Juno's MCP server (design §4.4). */
    public static final List<String> EVERY_PROFILE_TOOLS = List.of(
            "mcp__juno__list_agents", "mcp__juno__send_message", "mcp__juno__report", "mcp__juno__request_folder");

    private BuiltInProfiles() {}

    public static List<Profile> create() {
        List<Profile> all = new ArrayList<>();
        all.add(orchestrator());
        all.add(coder());
        all.add(reviewer());
        all.add(planner());
        all.add(searcher());
        all.add(notesWriter());
        return all;
    }

    public static boolean isBuiltIn(String name) {
        return create().stream().anyMatch(p -> p.name.equals(name));
    }

    private static Profile base(String name, String file, String model, String effort, String permission) {
        Profile p = new Profile();
        p.name = name;
        p.builtIn = true;
        p.model = model;
        p.effort = effort;
        p.permissionMode = permission;
        p.systemPrompt = Prompts.load("profiles/" + file + ".md");
        p.allowedTools.addAll(EVERY_PROFILE_TOOLS);
        return p;
    }

    private static Profile orchestrator() {
        Profile p = base(ORCHESTRATOR, "orchestrator", "claude-opus-5-5", "high", "default");
        p.allowedTools.addAll(List.of("mcp__juno__assign_task", "mcp__juno__get_agent_output",
                "mcp__juno__get_agent_context", "mcp__juno__set_link", "Read", "Grep", "Glob"));
        return p;
    }

    private static Profile coder() {
        Profile p = base(CODER, "coder", "claude-sonnet-5-5", "high", "acceptEdits");
        p.needsProjectFolder = true;
        p.editsFiles = true;
        p.allowedTools.addAll(List.of("Read", "Edit", "Write", "Grep", "Glob", "Bash(git:*)", "Task"));
        // Host MCP tools (open PR/MR, read comments, reply) are added in Milestone 5, once the names are verified (§13).
        p.disallowedTools.addAll(List.of("Bash(git push --force:*)", "Bash(gh:*)", "Bash(glab:*)"));
        p.commands.add(new ProfileCommand("checkforcomments",
                Prompts.load("commands/coder-checkforcomments.md"), true));
        return p;
    }

    private static Profile reviewer() {
        Profile p = base(REVIEWER, "reviewer", "claude-sonnet-5-5", "high", "default");
        p.allowedTools.addAll(List.of("Read", "Grep", "Glob", "Bash(git diff:*)", "Bash(git log:*)",
                "Bash(git show:*)", "mcp__juno__start_review", "mcp__juno__end_review"));
        p.disallowedTools.addAll(List.of("Edit", "Write", "Bash(gh:*)", "Bash(glab:*)"));
        return p;
    }

    private static Profile planner() {
        Profile p = base(PLANNER, "planner", "claude-opus-5-5", "max", "default");
        p.allowedTools.addAll(List.of("Read", "Grep", "Glob", "WebSearch", "WebFetch"));
        p.disallowedTools.addAll(List.of("Edit", "Write"));
        return p;
    }

    private static Profile searcher() {
        Profile p = base(SEARCHER, "searcher", "claude-sonnet-5-5", "medium", "default");
        p.allowedTools.addAll(List.of("WebSearch", "WebFetch", "Read"));
        return p;
    }

    private static Profile notesWriter() {
        Profile p = base(NOTES_WRITER, "notes-writer", "claude-sonnet-5-5", "medium", "default");
        p.allowedTools.addAll(List.of("Read", "Grep", "Glob", "Bash(git diff:*)", "Bash(git log:*)"));
        p.disallowedTools.addAll(List.of("Edit", "Write"));
        return p;
    }
}
