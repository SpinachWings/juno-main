package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.prompts.Prompts;
import dev.juno.provider.AgentEvent;
import dev.juno.store.Json;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders the built-in prompts an agent gets (design §11): system prompt, wrappers, recap, profile commands. */
public final class AgentPrompts {
    private AgentPrompts() {}

    /** Placeholders shared by system prompts and profile command templates (docs/prompts/README.md). */
    static Map<String, Object> profileValues(Settings s, Agent a, Project project) {
        Map<String, Object> v = new HashMap<>();
        v.put("agent_name", a.name);
        v.put("profile_name", a.profile);
        v.put("fresh_start_threshold", s.system.freshStartThreshold);
        List<String> cmds = project == null ? List.of() : project.buildAndTestCommands;
        v.put("build_and_test_commands", cmds.isEmpty() ? "none" : String.join(", ", cmds));
        return v;
    }

    /** Preamble + profile system prompt. */
    public static String systemPrompt(Settings s, Agent a, Profile profile, Project project, Path folder) {
        Map<String, Object> v = profileValues(s, a, project);
        v.put("project", project == null ? "" : project.name);
        v.put("folder", folder.toString());
        // TODO(Milestone 3): the agent's own worktree branch (design §7.8).
        v.put("branch", "");
        // TODO(Milestone 5): the agent's GitHub/GitLab key (design §5.1.1).
        v.put("host_access", "");
        // TODO(Milestone 4): linked agents with their profile and status (design §4.1).
        v.put("contacts", "- Juno (`orchestrator`)");
        v.put("report_length", s.system.reportLength);
        return Prompts.render(Prompts.load("preamble.md"), v).trim() + "\n\n"
                + Prompts.render(profile.systemPrompt, v).trim() + "\n";
    }

    public static String activateSkill(String skill, String message) {
        return Prompts.render(Prompts.load("wrappers/activate-skill.md"), Map.of("skill", skill)).trim()
                + "\n\n" + message;
    }

    public static String hey(String text) {
        return Prompts.render(Prompts.load("wrappers/hey.md"), Map.of("text", text)).trim();
    }

    public static String folderSet(Project p, Path folder) {
        return Prompts.render(Prompts.load("wrappers/folder-set.md"),
                Map.of("project", p.name, "folder", folder.toString(), "branch", "")).trim();
    }

    public static String profileCommand(Settings s, Agent a, Project project, String template, String args) {
        Map<String, Object> v = profileValues(s, a, project);
        v.put("args", args == null ? "" : args.trim());
        return Prompts.render(template, v).trim();
    }

    static String result(String file, String key, Map<String, ?> values) {
        return Prompts.render(Prompts.sections(Prompts.load("results/" + file + ".md")).get(key), values);
    }

    /** The resume recap (design §12), built from the journal. */
    static String recap(Agent a, List<AgentEvent> history, String changes, Path folder) {
        String task = "";
        List<String> actions = new ArrayList<>();
        String pending = "";
        for (AgentEvent e : history) {
            switch (e) {
                case AgentEvent.UserText u when "user".equals(u.from()) || "task".equals(u.from()) -> {
                    task = firstLine(u.text(), 300);
                    actions.clear();
                }
                case AgentEvent.ToolUse t when t.parent() == null -> actions.add(describeTool(t, folder));
                case AgentEvent.Permission p -> pending = p.answer() == null ? "your answer to " + p.tool() + " ("
                        + firstLine(p.input(), 120) + ")" : "";
                default -> { }
            }
        }
        List<String> last = actions.subList(Math.max(0, actions.size() - 6), actions.size());
        Map<String, Object> v = new HashMap<>();
        v.put("when", ago(a.interruptedAt));
        v.put("reason", a.interruptReason == null || a.interruptReason.isBlank() ? "interrupted" : a.interruptReason);
        v.put("task", task.isEmpty() ? "(none recorded)" : task);
        v.put("recent_actions", last.isEmpty() ? "none" : String.join("; ", last));
        v.put("pending", pending);
        v.put("changes", changes == null ? "" : changes);
        return Prompts.render(Prompts.load("resume-recap.md"), v).trim();
    }

    static String describeTool(AgentEvent.ToolUse t, Path folder) {
        JsonNode in;
        try {
            in = Json.MAPPER.readTree(t.input());
        } catch (Exception e) {
            return t.name();
        }
        String detail = switch (t.name()) {
            case "Bash" -> in.path("command").asText();
            case "Read", "Edit", "Write", "MultiEdit" -> relative(in.path("file_path").asText(), folder);
            case "Grep", "Glob" -> in.path("pattern").asText();
            case "WebFetch" -> in.path("url").asText();
            case "WebSearch" -> in.path("query").asText();
            default -> "";
        };
        return detail.isBlank() ? t.name() : t.name() + " " + firstLine(detail, 120);
    }

    /** A path inside the agent's folder, relative to it; other paths unchanged. */
    static String relative(String path, Path folder) {
        if (path == null || folder == null) return path;
        String p = path.replace('\\', '/');
        String base = folder.toString().replace('\\', '/');
        if (!base.endsWith("/")) base += "/";
        return p.toLowerCase(java.util.Locale.ROOT).startsWith(base.toLowerCase(java.util.Locale.ROOT))
                ? p.substring(base.length()) : path;
    }

    static String firstLine(String s, int max) {
        if (s == null) return "";
        String line = s.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl) + " …";
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    static String ago(long at) {
        if (at <= 0) return "earlier";
        long min = (System.currentTimeMillis() - at) / 60000;
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        if (min < 48 * 60) return (min / 60) + " h ago";
        return (min / 1440) + " days ago";
    }
}
