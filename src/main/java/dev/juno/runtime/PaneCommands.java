package dev.juno.runtime;

import dev.juno.git.Git;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.ProfileCommand;
import dev.juno.provider.AgentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a pane does with typed input (design §8): commands are handled by Juno; plain messages go to the agent (refused
 * while it works); unknown {@code /commands} are never sent.
 */
public final class PaneCommands {
    /** Commands that take a name or skill straight after them ({@code /heybob}). */
    static final List<String> ATTACHED = List.of("stoptalkingto", "getcontextfrom", "cantalkto", "activate", "assign",
            "hey");
    /** Suggested in autocomplete after /model. */
    public static final List<String> MODELS = List.of("haiku", "sonnet", "opus", "fable", "claude-haiku-5-5",
            "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1", "default");

    /** What the pane should do besides showing the agent's events. */
    public sealed interface Outcome {
        record Sent() implements Outcome {}
        record Said(String text, String level) implements Outcome {}
        record Copy(String text) implements Outcome {}
        record Clear() implements Outcome {}
        record Help(List<CommandRegistry.Command> commands) implements Outcome {}
        record Usage(List<String> rows) implements Outcome {}
    }

    private final AgentRuntime runtime;

    public PaneCommands(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /** A parsed {@code /command}: its registry or profile name and the text after it. */
    record Parsed(String name, String args) {}

    static Parsed parse(String input, List<String> knownNames) {
        String body = input.strip().substring(1);
        int space = body.indexOf(' ');
        String word = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : body.substring(space + 1).strip();
        if (knownNames.contains(word)) return new Parsed(word, rest);
        for (String a : ATTACHED) {
            if (word.startsWith(a) && word.length() > a.length()) {
                String attached = body.substring(a.length(), space < 0 ? body.length() : space);
                return new Parsed(a, (attached + " " + rest).strip());
            }
        }
        return new Parsed(word, rest);
    }

    /** Commands available in this agent's pane: any-pane ones plus its profile's (design §8). */
    public List<CommandRegistry.Command> available(Agent a) {
        List<CommandRegistry.Command> out = new ArrayList<>(CommandRegistry.ofScope(CommandRegistry.Scope.ANY_PANE));
        Profile p = runtime.profile(a.profile);
        if (p != null) {
            for (ProfileCommand c : p.commands) {
                out.add(new CommandRegistry.Command(c.name, "/" + c.name + " [details]", CommandRegistry.Scope.PROFILE,
                        AgentPrompts.firstLine(c.template, 90), "/" + c.name));
            }
        }
        return out;
    }

    public Outcome handle(Agent a, String input) {
        String text = input.strip();
        if (text.isEmpty()) return new Outcome.Said("", "dim");
        if (!text.startsWith("/")) {
            String refused = runtime.refusal(a);
            if (refused != null) return new Outcome.Said(refused, "warn");
            runtime.send(a, text, "user");
            return new Outcome.Sent();
        }
        List<String> names = new ArrayList<>(CommandRegistry.all().stream().map(CommandRegistry.Command::name).toList());
        Profile profile = runtime.profile(a.profile);
        if (profile != null) profile.commands.forEach(c -> names.add(c.name.toLowerCase(Locale.ROOT)));
        Parsed c = parse(text, names);
        String args = c.args();
        switch (c.name()) {
            case "stop" -> { return said(runtime.stop(a), "Stopping " + a.name + "…"); }
            case "resume" -> { return said(runtime.resume(a), null); }
            case "new" -> { return said(runtime.newConversation(a), "New conversation from the next message. "
                    + "The previous one is kept in " + a.name + "'s conversations."); }
            case "compact" -> { return said(runtime.compact(a), null); }
            case "clear" -> { return new Outcome.Clear(); }
            case "copy" -> {
                String last = runtime.live(a.id).lastReply;
                if (last == null || last.isBlank()) return new Outcome.Said("There's no reply to copy yet.", "warn");
                return new Outcome.Copy(last);
            }
            case "diff" -> { return diff(a); }
            case "usage" -> { return usage(a); }
            case "model" -> {
                if (args.isBlank()) return new Outcome.Said("Model: " + shown(runtime.effectiveModel(a, profile))
                        + ". /model <model> changes it from the next message; /model default uses the profile's.", "info");
                runtime.setModel(a, args.equalsIgnoreCase("default") ? "" : args);
                return new Outcome.Said("Model " + shown(runtime.effectiveModel(a, profile))
                        + " applies from the next message.", "info");
            }
            case "effort" -> {
                String e = args.toLowerCase(Locale.ROOT);
                if (e.isBlank()) return new Outcome.Said("Effort: " + shown(runtime.effectiveEffort(a, profile))
                        + ". Options: low, medium, high, xhigh, max, default.", "info");
                if (!e.equals("default") && !Profile.EFFORTS.contains(e)) {
                    return new Outcome.Said("Effort is one of low, medium, high, xhigh, max, or default.", "warn");
                }
                runtime.setEffort(a, e.equals("default") ? "" : e);
                return new Outcome.Said("Effort " + shown(runtime.effectiveEffort(a, profile))
                        + " applies from the next message.", "info");
            }
            case "activate" -> {
                if (args.isBlank()) return new Outcome.Said("Which skill? " + a.name + " has: "
                        + String.join(", ", runtime.skillNames(a)), "info");
                return new Outcome.Said(runtime.activate(a, args), "info");
            }
            case "approve" -> { return said(runtime.answer(a, null, AgentRuntime.Answer.ALLOW_ONCE, null), null); }
            case "deny" -> { return said(runtime.answer(a, null, AgentRuntime.Answer.DENY, null), null); }
            case "help" -> { return new Outcome.Help(available(a)); }
            case "hey" -> {
                String[] parts = args.split("\\s+", 2);
                if (parts[0].isBlank() || parts.length < 2 || parts[1].isBlank()) {
                    return new Outcome.Said("Usage: /hey<name> <text>", "warn");
                }
                Agent to = runtime.byName(parts[0]).orElse(null);
                if (to == null) return new Outcome.Said("There's no agent called " + parts[0] + ".", "warn");
                runtime.hey(to, parts[1]);
                return to == a ? new Outcome.Sent() : new Outcome.Said("Sent to " + to.name + ".", "dim");
            }
            case "cantalkto", "stoptalkingto", "getcontext" -> {
                // TODO(Milestone 4): links and context summaries (design §4.1, §8.2).
                return new Outcome.Said("/" + c.name() + " arrives with agent-to-agent communication (Milestone 4).",
                        "warn");
            }
            case "getcontextfrom", "assign", "status", "broadcast" -> {
                return new Outcome.Said("/" + c.name() + " works only in Juno's pane.", "warn");
            }
            default -> {
                if (profile != null) {
                    for (ProfileCommand pc : profile.commands) {
                        if (pc.name.equalsIgnoreCase(c.name())) {
                            String refused = runtime.refusal(a);
                            if (refused != null) return new Outcome.Said(refused, "warn");
                            runtime.send(a, AgentPrompts.profileCommand(runtime.settingsNow(), a,
                                    runtime.project(a), pc.template, args), "user");
                            return new Outcome.Sent();
                        }
                    }
                }
                return new Outcome.Said("`/" + c.name() + "` isn't supported in Juno — see /help.", "warn");
            }
        }
    }

    private static Outcome said(String error, String ok) {
        if (error != null) return new Outcome.Said(error, "warn");
        return ok == null ? new Outcome.Sent() : new Outcome.Said(ok, "dim");
    }

    private static String shown(String v) {
        return v == null || v.isBlank() ? "CLI default" : v;
    }

    private Outcome diff(Agent a) {
        Path folder = runtime.folder(a);
        if (runtime.project(a) == null) return new Outcome.Said(a.name + " has no project folder.", "info");
        if (!Files.isDirectory(folder) || !Git.isRepository(folder)) {
            return new Outcome.Said(folder + " isn't a git repository.", "info");
        }
        try {
            List<Git.FileDiff> diffs = Git.uncommitted(folder);
            if (diffs.isEmpty()) return new Outcome.Said("No uncommitted changes in " + folder + ".", "info");
            runtime.say(a, diffs.size() + " changed file(s) in " + folder + ":", "info");
            for (Git.FileDiff d : diffs) runtime.show(a, new AgentEvent.Patch(d.file(), d.patch()));
            return new Outcome.Sent();
        } catch (IOException e) {
            return new Outcome.Said("git diff failed: " + e.getMessage(), "bad");
        }
    }

    private Outcome usage(Agent a) {
        LiveAgent l = runtime.live(a.id);
        List<String> rows = new ArrayList<>();
        int turn = 0;
        long total = 0;
        double cost = 0;
        long ctx = 0;
        for (AgentEvent e : l.history()) {
            if (e instanceof AgentEvent.Usage u) {
                if (!u.turnTotal()) {
                    ctx = u.contextTokens();
                    total += u.input() + u.output() + u.cacheRead() + u.cacheWrite();
                } else {
                    turn++;
                    cost += u.costUsd();
                    rows.add(String.format(Locale.ROOT, "turn %-3d  ctx %7s  conversation %8s tokens  $%.4f",
                            turn, Meters.tokens(ctx), Meters.tokens(total), u.costUsd()));
                }
            }
        }
        rows.add(String.format(Locale.ROOT, "total: %d turns · %s tokens · $%.4f API-price estimate · context %s",
                turn, Meters.tokens(total), cost, Meters.context(l.contextTokens, l.contextWindow)));
        return new Outcome.Usage(rows);
    }
}
