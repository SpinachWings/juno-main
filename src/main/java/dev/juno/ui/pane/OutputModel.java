package dev.juno.ui.pane;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.provider.AgentEvent;
import dev.juno.runtime.Meters;
import dev.juno.store.Json;
import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An agent's events as readable blocks (design §7.3). Sub-agents' events nest inside their block; tool results join
 * their call. {@link #add} returns the first top-level block that changed, so the view re-renders only from there.
 */
final class OutputModel {
    static final int LONG_OUTPUT = 12;
    static final int NEW_FILE_PREVIEW = 40;

    private final String agentName;
    private final java.util.function.Supplier<String> folder;
    private final Runnable changed;
    final List<Block> blocks = new ArrayList<>();
    private final Map<String, ToolBlock> tools = new HashMap<>();
    private final Map<String, SubAgentBlock> subAgents = new HashMap<>();
    private final Map<String, DiffBlock> diffsByTool = new HashMap<>();
    private final Map<String, PermissionBlock> permissions = new HashMap<>();
    private final Map<String, TodosBlock> lastTodos = new HashMap<>();
    private MessageBlock openQuestion;
    private long lastContext;
    /** First top-level index changed since the last render, or -1. */
    private int dirtyFrom = -1;

    OutputModel(String agentName, java.util.function.Supplier<String> folder, Runnable changed) {
        this.agentName = agentName;
        this.folder = folder;
        this.changed = changed;
    }

    int takeDirty() {
        int d = dirtyFrom;
        dirtyFrom = -1;
        return d;
    }

    void clear() {
        blocks.clear();
        tools.clear();
        subAgents.clear();
        diffsByTool.clear();
        permissions.clear();
        lastTodos.clear();
        openQuestion = null;
        dirtyFrom = 0;
    }

    void add(AgentEvent e) {
        switch (e) {
            case AgentEvent.UserText u -> {
                if (openQuestion != null) {
                    openQuestion.answered = true;
                    touch(openQuestion);
                    openQuestion = null;
                }
                put(null, new UserBlock(u.text(), u.from()));
            }
            case AgentEvent.Message m -> {
                MessageBlock b = new MessageBlock(m.text(), m.parent() == null ? agentName : subAgentName(m.parent()));
                put(m.parent(), b);
                if (m.parent() == null && openQuestion != null) {
                    // A newer reply (e.g. after async sub-agents) supersedes the earlier question.
                    openQuestion.answered = true;
                    touch(openQuestion);
                    openQuestion = null;
                }
                if (m.parent() == null && dev.juno.runtime.AgentRuntime.endsWithQuestion(m.text())) {
                    openQuestion = b;
                }
            }
            case AgentEvent.Thinking t -> put(t.parent(), new ThinkingBlock(t.text(), t.length()));
            case AgentEvent.ToolUse t -> {
                if (t.name().equals("Agent") || t.name().equals("Task")) return; // shown by its SubAgent block
                ToolBlock b = new ToolBlock(t);
                tools.put(t.id(), b);
                if (!List.of("Edit", "MultiEdit", "Write", "TodoWrite").contains(t.name())) put(t.parent(), b);
            }
            case AgentEvent.ToolResult r -> {
                SubAgentBlock sa = subAgents.get(r.toolUseId());
                if (sa != null) {
                    if (!r.text().startsWith("Async agent launched")) {
                        sa.result = r.text();
                        sa.status = r.error() ? "failed" : "completed";
                        if (!sa.toggled) sa.expanded = false;
                    }
                    touch(sa);
                    return;
                }
                ToolBlock b = tools.get(r.toolUseId());
                if (b == null) return;
                b.result = r.text();
                b.error = r.error();
                DiffBlock d = diffsByTool.get(r.toolUseId());
                if (d != null && r.error()) {
                    d.error = r.text();
                    touch(d);
                } else if (b.shown) {
                    touch(b);
                } else if (r.error() && !b.tool.name().equals("TodoWrite")) {
                    put(b.tool.parent(), b);
                }
            }
            case AgentEvent.Diff d -> {
                DiffBlock b = diffsByTool.get(d.toolUseId());
                if (b != null && b.file.equals(d.file())) {
                    b.edits.add(d);
                    touch(b);
                } else {
                    b = new DiffBlock(d);
                    diffsByTool.put(d.toolUseId(), b);
                    put(d.parent(), b);
                }
            }
            case AgentEvent.SubAgent s -> {
                SubAgentBlock b = new SubAgentBlock(s);
                subAgents.put(s.toolUseId(), b);
                put(s.parent(), b);
            }
            case AgentEvent.SubAgentDone d -> {
                SubAgentBlock b = subAgents.get(d.toolUseId());
                if (b != null) {
                    b.status = d.status();
                    if (!b.toggled) b.expanded = false;
                    touch(b);
                }
            }
            case AgentEvent.Todos t -> {
                String key = String.valueOf(t.parent());
                TodosBlock prev = lastTodos.get(key);
                if (prev != null) {
                    prev.superseded = true;
                    touch(prev);
                }
                TodosBlock b = new TodosBlock(t.items());
                lastTodos.put(key, b);
                put(t.parent(), b);
            }
            case AgentEvent.Usage u -> {
                if (!u.turnTotal()) lastContext = u.contextTokens();
                else put(null, new LineBlock("· turn " + Meters.tokens(u.input() + u.output() + u.cacheRead()
                        + u.cacheWrite()) + " tokens · " + Meters.usd(u.costUsd()) + " API-price estimate · ctx "
                        + Meters.context(lastContext, u.contextWindow()), "quiet"));
            }
            case AgentEvent.Notice n -> put(null, new LineBlock(n.text(), "notice-" + n.level()));
            case AgentEvent.Permission p -> {
                PermissionBlock b = permissions.get(p.requestId());
                if (b == null) {
                    b = new PermissionBlock(p);
                    permissions.put(p.requestId(), b);
                    put(null, b);
                } else {
                    b.p = p;
                    touch(b);
                }
            }
            case AgentEvent.Patch p -> put(null, new PatchBlock(p.title(), p.patch()));
            default -> { }
        }
    }

    /** A path inside the agent's folder, shown relative to it. */
    String shortPath(String path) {
        if (path == null) return "";
        String f = folder.get();
        if (f == null || f.isBlank()) return path;
        String p = path.replace('\\', '/'), base = f.replace('\\', '/');
        if (!base.endsWith("/")) base += "/";
        return p.toLowerCase(Locale.ROOT).startsWith(base.toLowerCase(Locale.ROOT)) ? p.substring(base.length()) : path;
    }

    private String subAgentName(String parent) {
        SubAgentBlock b = subAgents.get(parent);
        return b == null || b.s.type().isBlank() ? "sub-agent" : b.s.type();
    }

    private void put(String parent, Block b) {
        SubAgentBlock sa = parent == null ? null : subAgents.get(parent);
        b.shown = true;
        if (sa != null) {
            b.owner = sa;
            sa.children.add(b);
            touch(sa);
        } else {
            blocks.add(b);
            mark(blocks.size() - 1);
        }
    }

    /** Marks a block (or the top-level block containing it) for re-rendering. */
    void touch(Block b) {
        Block top = b;
        while (top.owner != null) top = top.owner;
        int i = blocks.indexOf(top);
        if (i >= 0) mark(i);
    }

    private void mark(int i) {
        if (dirtyFrom < 0 || i < dirtyFrom) dirtyFrom = i;
    }

    private void toggled(Block b) {
        touch(b);
        changed.run();
    }

    // ------------------------------------------------------------------ blocks

    abstract static class Block {
        SubAgentBlock owner;
        boolean shown;

        abstract void render(StyledDoc d, int depth);
    }

    static List<String> ind(int depth, String... more) {
        List<String> s = new ArrayList<>();
        s.add("ind" + Math.min(depth, 4));
        s.addAll(List.of(more));
        return s;
    }

    final class UserBlock extends Block {
        final String text, from;
        boolean expanded;

        UserBlock(String text, String from) {
            this.text = text;
            this.from = from;
        }

        @Override
        void render(StyledDoc d, int depth) {
            boolean fromUser = from.equals("user") || from.equals("hey");
            d.line(ind(depth, "gap-top"));
            if (fromUser) {
                d.add("› you" + (from.equals("hey") ? " (extra info)" : ""), "who-user").newline();
                for (String line : text.split("\n", -1)) d.line(ind(depth, "user-line")).add(line, "user-text").newline();
                return;
            }
            String label = switch (from) {
                case "recap" -> "resume recap";
                case "compact" -> "/compact";
                default -> "note from Juno";
            };
            d.action((expanded ? "▾ " : "▸ ") + "› Juno → " + agentName + ": " + label, () -> {
                expanded = !expanded;
                toggled(this);
            }, "who-juno", "toggle");
            d.newline();
            if (expanded) for (String line : text.split("\n", -1)) d.line(ind(depth + 1)).add(line, "dim").newline();
        }
    }

    final class MessageBlock extends Block {
        final String text, who;
        boolean answered;

        MessageBlock(String text, String who) {
            this.text = text;
            this.who = who;
        }

        @Override
        void render(StyledDoc d, int depth) {
            d.line(ind(depth, "gap-top"));
            d.add("◆ " + who + "  ", depth == 0 ? "who-agent" : "who-sub");
            d.action("⧉", () -> Ui.copy(text), "copy-btn");
            d.newline();
            boolean question = !answered && dev.juno.runtime.AgentRuntime.endsWithQuestion(text) && owner == null;
            MarkdownText.write(d, text, ind(depth), question ? List.of("question") : List.of());
        }
    }

    final class ThinkingBlock extends Block {
        final String text;
        final int length;
        boolean expanded;

        ThinkingBlock(String text, int length) {
            this.text = text;
            this.length = length;
        }

        @Override
        void render(StyledDoc d, int depth) {
            String size = text.isEmpty() ? (length > 0 ? "~" + length + " tokens" : "hidden") : text.length() + " chars";
            d.line(ind(depth));
            d.action((expanded ? "▾ " : "▸ ") + "Thinking… (" + size + ")", () -> {
                expanded = !expanded;
                toggled(this);
            }, "thinking", "toggle");
            d.newline();
            if (expanded) {
                String body = text.isEmpty() ? "The provider doesn't show its thinking text." : text;
                for (String line : body.split("\n", -1)) d.line(ind(depth + 1)).add(line, "thinking-text").newline();
            }
        }
    }

    final class ToolBlock extends Block {
        final AgentEvent.ToolUse tool;
        final JsonNode input;
        String result;
        boolean error;
        Boolean expanded;

        ToolBlock(AgentEvent.ToolUse tool) {
            this.tool = tool;
            JsonNode in;
            try {
                in = Json.MAPPER.readTree(tool.input());
            } catch (Exception e) {
                in = Json.MAPPER.createObjectNode();
            }
            this.input = in;
        }

        private String summary() {
            return switch (tool.name()) {
                case "Bash" -> input.path("command").asText();
                case "Read" -> shortPath(input.path("file_path").asText());
                case "Grep" -> input.path("pattern").asText() + (input.has("path") ? "  in " + shortPath(input.path("path").asText()) : "");
                case "Glob" -> input.path("pattern").asText();
                case "WebSearch" -> input.path("query").asText();
                case "WebFetch" -> input.path("url").asText();
                case "Skill" -> input.path("skill").asText(input.path("command").asText());
                default -> {
                    String s = input.toString();
                    yield s.equals("{}") ? "" : s;
                }
            };
        }

        private String icon() {
            return switch (tool.name()) {
                case "Bash" -> "$";
                case "WebSearch" -> "🔎";
                case "WebFetch" -> "🌐";
                case "Read" -> "📄";
                default -> "⚙";
            };
        }

        /** File reads and searches are collapsed; command output only when long. */
        private boolean defaultExpanded(List<String> lines) {
            if (error) return true;
            if (List.of("Read", "Grep", "Glob", "WebFetch", "Skill").contains(tool.name())) return false;
            return lines.size() <= LONG_OUTPUT;
        }

        @Override
        void render(StyledDoc d, int depth) {
            // Claude Code appends "Shell cwd was reset to <folder>" after commands that cd elsewhere: noise here.
            List<String> lines = result == null ? List.of() : java.util.Arrays.stream(result.stripTrailing().split("\n", -1))
                    .filter(x -> !x.startsWith("Shell cwd was reset to ")).toList();
            boolean open = expanded != null ? expanded : defaultExpanded(lines);
            d.line(ind(depth, "tool-line"));
            String status = result == null ? " …" : error ? " ✗" : " ✓";
            String head = icon() + " " + tool.name() + "  ";
            String sum = summary();
            String first = sum.split("\n", 2)[0];
            if (result != null && !lines.isEmpty() && !(lines.size() == 1 && lines.get(0).isBlank())) {
                d.action((open ? "▾ " : "▸ ") + head, () -> {
                    expanded = !(expanded != null ? expanded : defaultExpanded(lines));
                    toggled(this);
                }, "tool-head", "toggle");
            } else {
                d.add("  " + head, "tool-head");
            }
            if (tool.name().equals("Bash")) Highlight.write(d, first, true, "tool-input");
            else d.add(first, "tool-input");
            if (sum.contains("\n")) d.add(" …", "dim");
            d.add(status, result == null ? "dim" : error ? "bad" : "good");
            d.newline();
            if (tool.name().equals("Bash") && sum.contains("\n") && open) {
                for (String l : sum.split("\n")) d.line(ind(depth + 1, "code-block")).add(l, "md-codeblock").newline();
            }
            if (result == null || lines.isEmpty()) return;
            if (!open) {
                if (lines.size() > 1 || !lines.get(0).isBlank()) {
                    d.line(ind(depth + 1)).add(lines.size() + " line" + (lines.size() == 1 ? "" : "s") + " of output",
                            "quiet").newline();
                }
                return;
            }
            for (String l : lines) d.line(ind(depth + 1, "out-line")).add(l, error ? "bad" : "tool-output").newline();
        }
    }

    final class DiffBlock extends Block {
        final String file;
        final boolean newFile;
        final List<AgentEvent.Diff> edits = new ArrayList<>();
        String error;
        boolean expanded;

        DiffBlock(AgentEvent.Diff d) {
            this.file = d.file();
            this.newFile = d.newFile();
            edits.add(d);
        }

        @Override
        void render(StyledDoc d, int depth) {
            d.line(ind(depth, "gap-top"));
            d.add(newFile ? "✚ new file  " : "✎ edit  ", "diff-head");
            d.add(shortPath(file), "diff-file");
            if (newFile) {
                d.add("  ");
                String content = edits.get(0).newText();
                d.action("⧉", () -> Ui.copy(content), "copy-btn");
            }
            d.newline();
            if (error != null) {
                d.line(ind(depth + 1)).add("✗ " + AgentPromptsFirst.line(error), "bad").newline();
                return;
            }
            if (newFile) {
                String[] lines = edits.get(0).newText().split("\n", -1);
                int show = expanded ? lines.length : Math.min(lines.length, NEW_FILE_PREVIEW);
                boolean hash = Highlight.hashComments(file);
                for (int i = 0; i < show; i++) {
                    d.line(ind(depth + 1, "code-block"));
                    Highlight.write(d, lines[i], hash, "md-codeblock");
                    d.newline();
                }
                if (lines.length > NEW_FILE_PREVIEW) {
                    d.line(ind(depth + 1));
                    d.action(expanded ? "▴ show less" : "▾ " + (lines.length - show) + " more lines", () -> {
                        expanded = !expanded;
                        toggled(this);
                    }, "toggle", "quiet");
                    d.newline();
                }
                return;
            }
            boolean firstEdit = true;
            for (AgentEvent.Diff e : edits) {
                if (!firstEdit) d.line(ind(depth + 1)).add("⋯", "quiet").newline();
                firstEdit = false;
                List<LineDiff.Line> lines = LineDiff.diff(e.oldText(), e.newText());
                boolean whole = e.oldText().length() > 2000 || e.newText().length() > 2000;
                if (whole) lines = LineDiff.withContext(lines, 3);
                boolean hash = Highlight.hashComments(file);
                for (LineDiff.Line l : lines) {
                    if (l == null) {
                        d.line(ind(depth + 1)).add("⋯", "quiet").newline();
                        continue;
                    }
                    switch (l.kind()) {
                        case DEL -> d.line(ind(depth + 1, "diff-del-line")).add("- " + l.text(), "diff-del");
                        case ADD -> d.line(ind(depth + 1, "diff-add-line")).add("+ " + l.text(), "diff-add");
                        default -> {
                            d.line(ind(depth + 1, "diff-same-line")).add("  ", "diff-same");
                            Highlight.write(d, l.text(), hash, "diff-same");
                        }
                    }
                    d.newline();
                }
            }
        }
    }

    final class PatchBlock extends Block {
        final String title, patch;

        PatchBlock(String title, String patch) {
            this.title = title;
            this.patch = patch;
        }

        @Override
        void render(StyledDoc d, int depth) {
            d.line(ind(depth, "gap-top")).add("Δ ", "diff-head").add(title, "diff-file").add("  ");
            d.action("⧉", () -> Ui.copy(patch), "copy-btn");
            d.newline();
            for (String l : patch.split("\n", -1)) {
                if (l.startsWith("diff --git") || l.startsWith("index ") || l.startsWith("--- ") || l.startsWith("+++ ")) {
                    continue;
                }
                String style = l.startsWith("@@") ? "diff-hunk" : l.startsWith("+") ? "diff-add"
                        : l.startsWith("-") ? "diff-del" : "diff-same";
                String line = l.startsWith("+") ? "diff-add-line" : l.startsWith("-") ? "diff-del-line" : "diff-same-line";
                d.line(ind(depth + 1, line)).add(l, style).newline();
            }
        }
    }

    final class SubAgentBlock extends Block {
        final AgentEvent.SubAgent s;
        final List<Block> children = new ArrayList<>();
        String status = "running";
        String result;
        boolean expanded = true;
        boolean toggled;

        SubAgentBlock(AgentEvent.SubAgent s) {
            this.s = s;
        }

        @Override
        void render(StyledDoc d, int depth) {
            d.line(ind(depth, "gap-top", "sub-line"));
            String mark = status.equals("running") ? "◌" : status.equals("completed") ? "✓" : "✗";
            d.action((expanded ? "▾ " : "▸ ") + "⑂ " + (s.type().isBlank() ? "sub-agent" : s.type()) + ": "
                    + s.description(), () -> {
                expanded = !expanded;
                toggled = true;
                toggled(this);
            }, "sub-head", "toggle");
            d.add("  " + mark + " " + status, status.equals("failed") ? "bad" : status.equals("running") ? "dim" : "good");
            d.newline();
            if (!expanded) return;
            if (!s.prompt().isBlank()) {
                d.line(ind(depth + 1)).add("task: ", "quiet").add(AgentPromptsFirst.line(s.prompt()), "dim").newline();
            }
            for (Block c : children) c.render(d, depth + 1);
            if (result != null && !result.isBlank() && children.stream().noneMatch(c -> c instanceof MessageBlock)) {
                d.line(ind(depth + 1)).add("result:", "quiet").newline();
                for (String l : result.split("\n", -1)) d.line(ind(depth + 1)).add(l, "tool-output").newline();
            }
        }
    }

    final class TodosBlock extends Block {
        final Map<String, String> items;
        boolean superseded;

        TodosBlock(Map<String, String> items) {
            this.items = items;
        }

        @Override
        void render(StyledDoc d, int depth) {
            long done = items.values().stream().filter("completed"::equals).count();
            d.line(ind(depth)).add("☰ plan  " + done + "/" + items.size() + " done", "plan-head").newline();
            if (superseded) return;
            for (Map.Entry<String, String> e : items.entrySet()) {
                String box = switch (e.getValue()) {
                    case "completed" -> "☑ ";
                    case "in_progress" -> "◐ ";
                    default -> "☐ ";
                };
                d.line(ind(depth + 1)).add(box + e.getKey(), "todo-" + e.getValue().toLowerCase(Locale.ROOT)).newline();
            }
        }
    }

    final class PermissionBlock extends Block {
        AgentEvent.Permission p;

        PermissionBlock(AgentEvent.Permission p) {
            this.p = p;
        }

        @Override
        void render(StyledDoc d, int depth) {
            boolean pending = p.answer() == null;
            JsonNode in;
            try {
                in = Json.MAPPER.readTree(p.input());
            } catch (Exception e) {
                in = Json.MAPPER.createObjectNode();
            }
            String what = switch (p.tool()) {
                case "Bash" -> in.path("command").asText();
                case "Edit", "Write", "MultiEdit", "Read", "NotebookEdit" -> shortPath(in.path("file_path").asText(in.path("notebook_path").asText()));
                case "WebFetch" -> in.path("url").asText();
                default -> p.input();
            };
            List<String> line = ind(depth, pending ? "perm-pending" : "perm-done", "gap-top");
            d.line(line).add("🔐 " + agentName + " wants " + p.tool() + ": ", pending ? "perm-title" : "dim");
            d.add(AgentPromptsFirst.line(what), pending ? "plain" : "dim");
            d.newline();
            if (pending) {
                if (p.tool().equals("Edit") || p.tool().equals("MultiEdit")) {
                    String oldText = in.path("old_string").asText(""), newText = in.path("new_string").asText("");
                    for (LineDiff.Line l : LineDiff.withContext(LineDiff.diff(oldText, newText), 2)) {
                        if (l == null) continue;
                        String st = l.kind() == LineDiff.Kind.DEL ? "diff-del" : l.kind() == LineDiff.Kind.ADD ? "diff-add" : "diff-same";
                        String pre = l.kind() == LineDiff.Kind.DEL ? "- " : l.kind() == LineDiff.Kind.ADD ? "+ " : "  ";
                        d.line(ind(depth + 1, "perm-pending")).add(pre + l.text(), st).newline();
                    }
                } else if (p.tool().equals("Write")) {
                    String[] lines = in.path("content").asText("").split("\n", -1);
                    for (int i = 0; i < Math.min(lines.length, 15); i++) {
                        d.line(ind(depth + 1, "perm-pending")).add("+ " + lines[i], "diff-add").newline();
                    }
                    if (lines.length > 15) d.line(ind(depth + 1, "perm-pending")).add("… " + (lines.length - 15) + " more lines", "quiet").newline();
                }
                d.line(ind(depth + 1, "perm-pending")).add("Answer below, or /approve · /deny", "quiet").newline();
            } else {
                d.line(ind(depth + 1, "perm-done")).add("→ " + p.answer(), p.answer().startsWith("denied")
                        || p.answer().equals("cancelled") ? "bad" : "good").newline();
            }
        }
    }

    final class LineBlock extends Block {
        final String text, style;

        LineBlock(String text, String style) {
            this.text = text;
            this.style = style;
        }

        @Override
        void render(StyledDoc d, int depth) {
            for (String l : text.split("\n", -1)) d.line(ind(depth)).add(l, style).newline();
        }
    }

    /** First line of a text, shortened. */
    static final class AgentPromptsFirst {
        static String line(String s) {
            if (s == null) return "";
            String t = s.strip();
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(0, nl) + " …";
            return t.length() > 200 ? t.substring(0, 200) + "…" : t;
        }
    }
}
