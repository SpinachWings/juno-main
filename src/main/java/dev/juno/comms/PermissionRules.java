package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool rules in Claude Code's syntax (design §7.5): {@code Tool}, {@code Bash(prefix:*)}, {@code Bash(exact)},
 * {@code Edit(glob)} for file tools (relative to the agent's folder, or absolute), {@code WebFetch(domain:host)},
 * {@code mcp__server} / {@code mcp__server__tool}. A compound Bash command is allowed only if every part matches,
 * and disallowed if any part does.
 */
public final class PermissionRules {
    private static final Pattern RULE = Pattern.compile("^([A-Za-z0-9_\\-]+)(?:\\((.*)\\))?$", Pattern.DOTALL);
    private static final List<String> FILE_TOOLS = List.of("Read", "Edit", "Write", "MultiEdit", "NotebookEdit",
            "Glob", "Grep");
    private static final List<String> EDIT_TOOLS = List.of("Edit", "Write", "MultiEdit", "NotebookEdit");

    /** Programs whose first word is a subcommand, so a proposed rule keeps it ({@code Bash(npm install:*)}). */
    static final java.util.Set<String> SUBCOMMAND_PROGRAMS = java.util.Set.of("npm", "npx", "pnpm", "yarn", "bun",
            "deno", "git", "cargo", "go", "docker", "podman", "kubectl", "helm", "mvn", "mvnw", "gradle", "gradlew",
            "dotnet", "pip", "pip3", "uv", "poetry", "conda", "brew", "apt", "apt-get", "winget", "choco", "make",
            "cmake", "terraform", "rustup", "composer", "bundle", "rake", "rails", "swift", "flutter", "dart", "az",
            "aws", "gcloud", "gh", "glab", "claude", "systemctl");

    private PermissionRules() {}

    /** A proposed rule and its plain-words description. */
    public record Proposal(String rule, String description) {}

    /** Whether any rule in {@code rules} allows the call (every part of a compound command must be covered). */
    public static boolean allows(List<String> rules, String tool, JsonNode input, Path folder) {
        if (rules.isEmpty()) return false;
        if (tool.equals("Bash")) {
            List<String> parts = commandParts(input.path("command").asText(""));
            if (parts.isEmpty()) return false;
            for (String part : parts) {
                if (rules.stream().noneMatch(r -> matches(r, tool, input, part, folder))) return false;
            }
            return true;
        }
        return rules.stream().anyMatch(r -> matches(r, tool, input, null, folder));
    }

    /** The first rule that refuses the call (any part of a compound command), or null. */
    public static String refusedBy(List<String> rules, String tool, JsonNode input, Path folder) {
        for (String r : rules) {
            if (tool.equals("Bash")) {
                for (String part : commandParts(input.path("command").asText(""))) {
                    if (matches(r, tool, input, part, folder)) return r;
                }
            } else if (matches(r, tool, input, null, folder)) {
                return r;
            }
        }
        return null;
    }

    static boolean matches(String rule, String tool, JsonNode input, String commandPart, Path folder) {
        String r = rule.strip();
        if (r.startsWith("mcp__")) {
            return tool.equals(r) || tool.startsWith(r + "__") || (r.endsWith("__*") && tool.startsWith(r.substring(0, r.length() - 1)));
        }
        Matcher m = RULE.matcher(r);
        if (!m.matches()) return false;
        String name = m.group(1), arg = m.group(2);
        boolean sameTool = name.equals(tool) || (name.equals("Edit") && EDIT_TOOLS.contains(tool));
        if (!sameTool) return false;
        if (arg == null || arg.isBlank() || arg.equals("*")) return true;
        if (tool.equals("Bash")) return commandMatches(arg, commandPart == null ? "" : commandPart);
        if (FILE_TOOLS.contains(tool)) {
            String file = filePath(input);
            return file != null && pathMatches(arg, file, folder);
        }
        if (tool.equals("WebFetch") && arg.startsWith("domain:")) {
            String host = host(input.path("url").asText(""));
            String want = arg.substring(7).toLowerCase(Locale.ROOT);
            return host.equals(want) || host.endsWith("." + want);
        }
        return false;
    }

    static boolean commandMatches(String arg, String command) {
        String c = command.strip();
        if (arg.endsWith(":*")) {
            String prefix = arg.substring(0, arg.length() - 2).strip();
            return c.equals(prefix) || c.startsWith(prefix + " ");
        }
        if (arg.endsWith(" *") || arg.endsWith("*")) {
            String prefix = arg.substring(0, arg.length() - 1).strip();
            return c.equals(prefix) || c.startsWith(prefix + " ") || (!arg.endsWith(" *") && c.startsWith(prefix));
        }
        return c.equals(arg.strip());
    }

    /** {@code a && b; c | d} → its parts. Quoted separators aren't split (quotes are respected). */
    static List<String> commandParts(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                cur.append(ch);
                continue;
            }
            boolean two = i + 1 < command.length()
                    && ((ch == '&' && command.charAt(i + 1) == '&') || (ch == '|' && command.charAt(i + 1) == '|'));
            if (two || ch == ';' || ch == '|' || ch == '\n') {
                if (!cur.toString().isBlank()) parts.add(cur.toString().strip());
                cur.setLength(0);
                if (two) i++;
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().isBlank()) parts.add(cur.toString().strip());
        return parts;
    }

    /** {@code //abs/**} is absolute; anything else is relative to the agent's folder. Case-insensitive on Windows. */
    static boolean pathMatches(String glob, String file, Path folder) {
        String g = glob.replace('\\', '/');
        String f = file.replace('\\', '/');
        boolean windows = isWindowsLike(f);
        String target;
        if (g.startsWith("//")) {
            g = g.substring(2);
            if (!isWindowsLike(g)) g = "/" + g;
            target = f;
        } else {
            g = g.replaceFirst("^\\./", "").replaceFirst("^/", "");
            String base = folder == null ? "" : folder.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (base.isEmpty()) return false;
            String fc = windows ? lower(f) : f, bc = windows ? lower(base) : base;
            if (!fc.startsWith(bc + "/")) return false;
            target = f.substring(base.length() + 1);
        }
        if (windows) {
            g = lower(g);
            target = lower(target);
        }
        return globRegex(g).matcher(target).matches();
    }

    private static boolean isWindowsLike(String p) {
        return p.matches("^[A-Za-z]:/.*");
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static Pattern globRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        sb.append("(?:.*/)?");
                    } else {
                        sb.append(".*");
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }

    static String filePath(JsonNode input) {
        for (String k : List.of("file_path", "notebook_path", "path")) {
            if (input.hasNonNull(k) && !input.path(k).asText().isBlank()) return input.path(k).asText();
        }
        return null;
    }

    private static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * The default rule to save (design §7.5): program plus first word for commands ({@code Bash(npm install:*)}),
     * the folder for file tools ({@code Edit(src/**)}), the domain for fetches, else the tool.
     */
    public static Proposal propose(String tool, JsonNode input, Path folder) {
        if (tool.equals("Bash")) {
            List<String> parts = commandParts(input.path("command").asText(""));
            String first = parts.isEmpty() ? "" : parts.get(0);
            String[] words = first.split("\\s+");
            String prefix = words.length == 0 ? "" : words[0];
            // The first word counts when it's a subcommand (npm install, git commit), not an argument (touch a.txt).
            if (words.length > 1 && words[1].matches("[a-z][a-z0-9:-]*") && SUBCOMMAND_PROGRAMS.contains(
                    words[0].replaceFirst("(?i)\\.(exe|cmd|bat)$", "").replaceFirst(".*[/\\\\]", ""))) {
                prefix += " " + words[1];
            }
            if (prefix.isBlank()) return new Proposal("Bash", "any command");
            return new Proposal("Bash(" + prefix + ":*)", "any `" + prefix + "` command");
        }
        if (FILE_TOOLS.contains(tool)) {
            String file = filePath(input);
            String ruleTool = EDIT_TOOLS.contains(tool) ? "Edit" : tool;
            String verb = switch (ruleTool) {
                case "Edit" -> "edits to files";
                case "Read" -> "reading files";
                default -> "`" + tool + "`";
            };
            if (file == null) return new Proposal(ruleTool, verb + " anywhere");
            String f = file.replace('\\', '/');
            String base = folder == null ? "" : folder.toAbsolutePath().normalize().toString().replace('\\', '/');
            String dir = f.contains("/") ? f.substring(0, f.lastIndexOf('/')) : "";
            if (!base.isEmpty() && (lower(dir) + "/").startsWith(lower(base) + "/")) {
                String rel = dir.length() > base.length() ? dir.substring(base.length() + 1) : "";
                if (rel.isEmpty()) return new Proposal(ruleTool + "(**)", verb + " anywhere in its folder");
                return new Proposal(ruleTool + "(" + rel + "/**)", verb + " under `" + rel + "/`");
            }
            return new Proposal(ruleTool + "(//" + dir.replaceFirst("^/", "") + "/**)", verb + " under `" + dir + "/`");
        }
        if (tool.equals("WebFetch")) {
            String host = host(input.path("url").asText(""));
            if (!host.isEmpty()) return new Proposal("WebFetch(domain:" + host + ")", "fetching pages from " + host);
        }
        return new Proposal(tool, "any use of " + tool);
    }

    /** Plain words for a rule typed by the user. */
    public static String describe(String rule) {
        Matcher m = RULE.matcher(rule.strip());
        if (rule.startsWith("mcp__")) return "the MCP tool(s) " + rule;
        if (!m.matches()) return rule;
        String name = m.group(1), arg = m.group(2);
        if (arg == null || arg.isBlank()) return "any use of " + name;
        if (name.equals("Bash")) {
            if (arg.endsWith(":*")) return "any `" + arg.substring(0, arg.length() - 2) + "` command";
            return "the command `" + arg + "`";
        }
        if (name.equals("Edit")) return "edits to files matching `" + arg + "`";
        if (name.equals("Read")) return "reading files matching `" + arg + "`";
        if (arg.startsWith("domain:")) return "fetching pages from " + arg.substring(7);
        return name + " on `" + arg + "`";
    }

    /** Whether a rule is syntactically valid. */
    public static boolean valid(String rule) {
        String r = rule == null ? "" : rule.strip();
        return !r.isEmpty() && (r.startsWith("mcp__") ? r.matches("mcp__[A-Za-z0-9_\\-]+(__[A-Za-z0-9_\\-*]+)?")
                : RULE.matcher(r).matches());
    }
}
