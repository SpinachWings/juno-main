package dev.juno.model;

import java.util.List;

/**
 * The tools a profile can allow or disallow, for the tools reference in Settings → Agent profiles (design §5.4).
 * Later milestones add the host (GitHub / GitLab) MCP tools once their names are verified (§13).
 */
public final class ToolCatalog {
    public enum Group {
        FILES("Files and search"), SHELL("Shell"), WEB("Web"), AGENTS("Sub-agents"), JUNO("Juno tools"),
        HOST("GitHub / GitLab");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    public record Tool(String rule, Group group, String what) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("Read", Group.FILES, "Read files."),
            new Tool("Grep", Group.FILES, "Search file contents."),
            new Tool("Glob", Group.FILES, "Find files by name pattern."),
            new Tool("Edit", Group.FILES, "Change files. Agents that edit get their own worktree (\"Edits files\")."),
            new Tool("Write", Group.FILES, "Create or overwrite files."),
            new Tool("NotebookEdit", Group.FILES, "Edit Jupyter notebook cells."),
            new Tool("Bash", Group.SHELL, "Any shell command. Prefer narrower rules below."),
            new Tool("Bash(git:*)", Group.SHELL, "Any git command. Pair with a disallowed rule such as "
                    + "Bash(git push --force:*)."),
            new Tool("Bash(git diff:*)", Group.SHELL, "Read-only git: diffs."),
            new Tool("Bash(git log:*)", Group.SHELL, "Read-only git: history."),
            new Tool("Bash(git show:*)", Group.SHELL, "Read-only git: show a commit."),
            new Tool("Bash(npm test:*)", Group.SHELL, "Example of a command prefix rule: anything starting with "
                    + "`npm test`. A project's build & test commands are added automatically for agents that "
                    + "edit files."),
            new Tool("WebSearch", Group.WEB, "Search the web."),
            new Tool("WebFetch", Group.WEB, "Fetch and read a web page."),
            new Tool("Task", Group.AGENTS, "Start a sub-agent (shown nested in the pane). Superpowers uses these."),
            new Tool("mcp__juno__list_agents", Group.JUNO, "Every profile. Agents the caller may talk to: name, "
                    + "profile, project, status, context use."),
            new Tool("mcp__juno__send_message", Group.JUNO, "Every profile. Message a linked agent; refused "
                    + "without a link."),
            new Tool("mcp__juno__report", Group.JUNO, "Every profile. Send a result or question to whoever "
                    + "assigned the current task."),
            new Tool("mcp__juno__request_folder", Group.JUNO, "Every profile. Ask the user to give the agent a "
                    + "project."),
            new Tool("mcp__juno__assign_task", Group.JUNO, "Orchestrator only. Hand an agent a task."),
            new Tool("mcp__juno__get_agent_output", Group.JUNO, "Orchestrator only. Recent output of an agent."),
            new Tool("mcp__juno__get_agent_context", Group.JUNO, "Orchestrator only. An agent's context summary."),
            new Tool("mcp__juno__set_link", Group.JUNO, "Orchestrator only. Open or close a link between agents."),
            new Tool("mcp__juno__start_review", Group.JUNO, "Reviewers. Wait for a PR/MR's pipelines, then open a "
                    + "review snapshot. Any profile that allows it can review."),
            new Tool("mcp__juno__end_review", Group.JUNO, "Reviewers. Remove the review snapshot."));

    private ToolCatalog() {}

    public static List<Tool> all() {
        return TOOLS;
    }

    /** Text shown above the table: how rules are written. */
    public static String syntaxHelp() {
        return "Rules go one per line. A bare name (Read) allows that tool. Bash(prefix:*) matches shell commands "
                + "starting with the prefix. mcp__<server>__<tool> names a tool of an MCP server. Disallowed rules "
                + "always win over allowed ones. GitHub / GitLab tools are added in Milestone 5, once their names "
                + "are verified; until then type them by hand if you know them.";
    }
}
