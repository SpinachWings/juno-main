package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.comms.JunoMcpServer;
import dev.juno.comms.PermissionRules;
import dev.juno.model.Agent;
import dev.juno.model.Agent.Reason;
import dev.juno.model.Agent.Status;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.model.SkillPack;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.Provider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.AgentStore;
import dev.juno.store.AtomicFiles;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * All agents and their processes (design §3, §7, §12). Status follows the agent's output events, not message counts;
 * the Claude hook's events confirm turn ends when installed. Everything that matters after a crash (session id,
 * status, rules, held messages) is saved as it happens, and every event goes to the conversation's journal.
 *
 * <p>Listeners are called on whichever thread the change happened on.
 */
public final class AgentRuntime implements JunoMcpServer.Calls {
    /** Done returns to Idle after this long (fixed, design §3). */
    static final long DONE_TO_IDLE_MS = 15 * 60_000L;
    /** After the last background task ends, the turn counts as over if nothing else happens for this long. */
    static final long BACKGROUND_SETTLE_MS = 5_000L;
    static final long STOP_GRACE_MS = 10_000L;

    public interface Listener {
        /** The agent's saved state or meters changed. */
        default void changed(Agent a) {}

        /** A new event for the agent's pane. */
        default void event(Agent a, AgentEvent e) {}

        /** Plan limits from a rate-limit event. */
        default void rateLimit(AgentEvent.RateLimit r) {}

        /** The agent list changed (created / removed). */
        default void agentsChanged() {}
    }

    public enum Answer { ALLOW_ONCE, ALLOW_AGENT, ALLOW_PROFILE, DENY }

    private final JunoHome home;
    private final Provider provider;
    private final Supplier<Settings> settings;
    private final Runnable saveSettings;
    private final SkillLibrary skills;
    private final SkillResolver skillResolver;
    private final AgentStore store;
    private final JunoMcpServer mcp;
    private final Map<String, LiveAgent> live = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Provider.VersionCheck> versions = new ConcurrentHashMap<>();
    private final HookEvents hooks;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "juno-runtime-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile AgentEvent.RateLimit rateLimit;

    public AgentRuntime(JunoHome home, Provider provider, Supplier<Settings> settings, Runnable saveSettings,
                        SkillLibrary skills, SkillResolver skillResolver) {
        this.home = home;
        this.provider = provider;
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.skills = skills;
        this.skillResolver = skillResolver;
        this.store = new AgentStore(home);
        this.mcp = new JunoMcpServer(this);
        this.hooks = new HookEvents(home.hookEventsDir(), this::hookEvent);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Loads the agents, marks those cut off by a crash or shutdown as interrupted (§12), starts the server. */
    public void start() throws IOException {
        for (Agent a : store.loadAll()) {
            LiveAgent l = new LiveAgent(a);
            live.put(a.id, l);
            if (a.conversationId == null) a.conversationId = newConversationId();
            l.journal = new Journal(journalFile(a));
            long lastEvent = 0;
            for (Journal.Entry e : Journal.read(l.journal.file())) {
                replay(l, e.event());
                lastEvent = e.at();
            }
            boolean cutOff = a.turnOpen || a.status == Status.WORKING || a.status == Status.QUEUED
                    || (a.status == Status.AWAITING && a.reason == Reason.PERMISSION);
            if (cutOff) {
                boolean closed = a.interrupted && a.interruptReason != null && a.interruptReason.startsWith("Juno was closed");
                if (!closed) {
                    // A crash: shutdown() didn't get to record it. The last journalled event is when it stopped.
                    a.interruptReason = "Juno crashed while you were working";
                    a.interruptedAt = lastEvent > 0 ? lastEvent : System.currentTimeMillis();
                }
                a.turnOpen = false;
                a.interrupted = true;
                setStatus(l, Status.AWAITING, Reason.INTERRUPTED, "Interrupted: " + a.interruptReason + ".");
                notice(l, "⏸ Interrupted: " + (closed ? "Juno was closed" : "Juno crashed")
                        + " while " + a.name + " was working. /resume continues with a recap.", "bad");
                a.unread = true;
            }
            store.save(a);
        }
        mcp.start();
        hooks.start();
        timer.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
    }

    /** Ends every process; agents in the middle of a turn are interrupted (shown on the next start). */
    public void shutdown() {
        for (LiveAgent l : live.values()) {
            if (l.process != null) l.process.kill();
            if (l.agent.turnOpen) {
                l.agent.interrupted = true;
                l.agent.interruptReason = "Juno was closed while you were working";
                l.agent.interruptedAt = System.currentTimeMillis();
            }
            cleanRun(l);
            store.save(l.agent);
        }
        hooks.stop();
        mcp.stop();
        timer.shutdownNow();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public List<Agent> agents() {
        return live.values().stream().map(l -> l.agent).sorted((x, y) -> Long.compare(x.created, y.created)).toList();
    }

    public LiveAgent live(String agentId) {
        return live.get(agentId);
    }

    public Optional<Agent> byName(String name) {
        String n = normalise(name);
        return agents().stream().filter(a -> normalise(a.name).equals(n)).findFirst();
    }

    public AgentEvent.RateLimit rateLimit() {
        return rateLimit;
    }

    /** Names in commands ignore case and spaces (design §8). */
    public static String normalise(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public Agent create(String name, String profile, String projectId, String colour) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the agent a name.");
        if (byName(name).isPresent()) throw new IllegalArgumentException("There's already an agent called " + name + ".");
        if (normalise(name).equals("juno")) throw new IllegalArgumentException("Juno is taken.");
        if (profile(profile) == null) throw new IllegalArgumentException("No profile called " + profile + ".");
        Agent a = new Agent();
        a.id = UUID.randomUUID().toString().substring(0, 8);
        a.name = name.strip();
        a.profile = profile;
        a.projectId = projectId;
        a.colour = colour == null ? "#6FD6FF" : colour;
        a.created = System.currentTimeMillis();
        a.statusSince = a.created;
        a.conversationId = newConversationId();
        LiveAgent l = new LiveAgent(a);
        l.journal = new Journal(journalFile(a));
        live.put(a.id, l);
        store.save(a);
        listeners.forEach(Listener::agentsChanged);
        return a;
    }

    // ---------------------------------------------------------------- messages

    /** Why a plain message can't be sent right now, or null if it can (design §8). */
    public String refusal(Agent a) {
        LiveAgent l = live.get(a.id);
        if (!l.pendingPermissions().isEmpty()) {
            return a.name + " is waiting for your answer to a permission request: use its buttons, /approve or /deny.";
        }
        if (a.status == Status.WORKING || a.turnOpen) {
            return a.name + " is working. Use /hey" + normalise(a.name) + " <text> to add to its current task, "
                    + "or /stop.";
        }
        return null;
    }

    /** Sends a message the user typed (or a profile command). {@code from}: "user", or a wrapper's name. */
    public synchronized void send(Agent a, String text, String from) {
        LiveAgent l = live.get(a.id);
        Profile p = profile(a.profile);
        if (p != null && p.needsProjectFolder && project(a) == null) {
            a.heldMessage = a.heldMessage == null ? text : a.heldMessage + "\n\n" + text;
            l.folderReason = "The " + a.profile + " profile needs a project folder before it can start.";
            setStatus(l, Status.AWAITING, Reason.FOLDER, "Pick a project to start.");
            notice(l, "Held until " + a.name + " has a project: pick one below.", "warn");
            store.save(a);
            return;
        }
        if (a.pendingSkill != null) {
            text = AgentPrompts.activateSkill(a.pendingSkill, text);
            a.pendingSkill = null;
        }
        if (a.interrupted) {
            a.interrupted = false;
        }
        deliver(l, text, from);
    }

    /** Adds to the current task without interrupting it (/hey): read when the current step ends. */
    public synchronized void hey(Agent a, String text) {
        LiveAgent l = live.get(a.id);
        if (!l.running() || !a.turnOpen) {
            send(a, AgentPrompts.hey(text), "hey");
            return;
        }
        String wrapped = AgentPrompts.hey(text);
        l.process.send(provider.userMessage(wrapped));
        record(l, new AgentEvent.UserText(wrapped, "hey"));
    }

    private void deliver(LiveAgent l, String text, String from) {
        Agent a = l.agent;
        if (!ensureProcess(l)) return;
        if (a.sessionId == null && a.conversations.stream().noneMatch(c -> c.id.equals(a.conversationId))) {
            Agent.Conversation c = new Agent.Conversation();
            c.id = a.conversationId;
            c.started = System.currentTimeMillis();
            c.firstTask = AgentPrompts.firstLine(text, 200);
            a.conversations.add(c);
        }
        record(l, new AgentEvent.UserText(text, from));
        l.process.send(provider.userMessage(text));
        l.stopRequested = false;
        a.turnOpen = true;
        a.reason = null;
        setStatus(l, Status.WORKING, null, "");
        store.save(a);
    }

    /** Starts the process if needed (resuming the conversation). False if it couldn't start (status says why). */
    private boolean ensureProcess(LiveAgent l) {
        Agent a = l.agent;
        Profile p = profile(a.profile);
        Path folder = folder(a);
        String model = effectiveModel(a, p), effort = effectiveEffort(a, p);
        if (l.running() && !a.turnOpen && (!eq(model, l.launchedModel) || !eq(effort, l.launchedEffort)
                || !eq(folder.toString(), l.launchedFolder))) {
            stopProcess(l);
        }
        if (l.running()) return true;
        String exe = p.executablePath == null || p.executablePath.isBlank()
                ? settings.get().system.effectiveClaudePath() : p.executablePath.trim();
        Provider.VersionCheck v = versions.computeIfAbsent(exe, provider::checkVersion);
        if (!v.ok()) {
            versions.remove(exe);
            fail(l, v.message());
            return false;
        }
        try {
            Files.createDirectories(folder);
            Path run = home.agentRunDir(a.id);
            Files.createDirectories(run);
            Path sys = run.resolve("system-prompt.md");
            AtomicFiles.writeString(sys, AgentPrompts.systemPrompt(settings.get(), a, p, project(a), folder));
            l.token = mcp.register(a.id);
            Path mcpFile = run.resolve("mcp.json");
            AtomicFiles.writeString(mcpFile, provider.mcpConfig(mcp.url(), l.token));
            Path plugins = provider.writeSkills(run.resolve("plugins"), skillSets(a.profile));
            List<String> allowed = new ArrayList<>(p.allowedTools);
            Project project = project(a);
            if (p.editsFiles && project != null) {
                for (String cmd : project.buildAndTestCommands) {
                    if (!cmd.isBlank()) allowed.add("Bash(" + cmd.strip() + ":*)");
                }
            }
            Provider.LaunchSpec spec = new Provider.LaunchSpec(exe, folder, model, effort, p.permissionMode,
                    allowed, p.disallowedTools, p.maxTurns, p.spendLimit, sys, mcpFile, plugins, a.sessionId, a.id);
            hooks.reset(a.id);
            l.process = AgentProcess.start(provider, spec, home.logsDir(a.id).resolve(a.conversationId + ".log"),
                    e -> onEvent(l, e));
            l.launchedModel = model;
            l.launchedEffort = effort;
            l.launchedFolder = folder.toString();
            l.background = 0;
            l.lastActivity = System.currentTimeMillis();
            return true;
        } catch (IOException | RuntimeException e) {
            cleanRun(l);
            fail(l, "Couldn't start " + a.name + ": " + e.getMessage());
            return false;
        }
    }

    private void fail(LiveAgent l, String message) {
        l.agent.turnOpen = false;
        notice(l, message, "bad");
        setStatus(l, Status.AWAITING, Reason.FAILED, message);
        l.agent.unread = true;
        store.save(l.agent);
    }

    // ---------------------------------------------------------------- events

    private void onEvent(LiveAgent l, AgentEvent e) {
        Agent a = l.agent;
        l.lastActivity = System.currentTimeMillis();
        synchronized (this) {
            switch (e) {
                case AgentEvent.SessionStarted s -> {
                    if (!s.sessionId().equals(a.sessionId)) {
                        a.sessionId = s.sessionId();
                        a.conversations.stream().filter(c -> c.id.equals(a.conversationId)).findFirst()
                                .ifPresent(c -> c.sessionId = s.sessionId());
                        store.save(a);
                    }
                    return; // not shown
                }
                case AgentEvent.RateLimit r -> {
                    rateLimit = r;
                    listeners.forEach(x -> x.rateLimit(r));
                    return;
                }
                case AgentEvent.Background b -> {
                    int before = l.background;
                    l.background = b.count();
                    if (b.count() == 0 && before > 0) l.backgroundDoneAt = System.currentTimeMillis();
                    if (b.count() > 0 && !a.turnOpen) openTurn(l);
                    return;
                }
                case AgentEvent.Usage u -> usage(l, u);
                case AgentEvent.Result r -> endTurn(l, r);
                case AgentEvent.Exited x -> {
                    exited(l, x);
                    return;
                }
                case AgentEvent.Message m -> {
                    if (m.parent() == null) l.lastReply = m.text();
                    if (!a.turnOpen) openTurn(l);
                }
                case AgentEvent.ToolUse t -> {
                    if (!a.turnOpen) openTurn(l);
                }
                default -> { }
            }
        }
        record(l, e);
    }

    /** Output arriving with no turn open (e.g. after async sub-agents finish) starts one. */
    private void openTurn(LiveAgent l) {
        l.agent.turnOpen = true;
        setStatus(l, Status.WORKING, null, "");
        store.save(l.agent);
    }

    private void usage(LiveAgent l, AgentEvent.Usage u) {
        if (u.turnTotal()) {
            if (u.contextWindow() > 0) l.contextWindow = u.contextWindow();
            l.lastTurnCost = u.costUsd();
            l.conversationCost += u.costUsd();
            l.lastTurnTokens = l.turnTokens;
            l.turnTokens = 0;
        } else {
            l.contextTokens = u.contextTokens();
            l.lastInput = u.input();
            l.lastOutput = u.output();
            l.lastCacheRead = u.cacheRead();
            l.lastCacheWrite = u.cacheWrite();
            long t = u.input() + u.output() + u.cacheRead() + u.cacheWrite();
            l.turnTokens += t;
            l.conversationTokens += t;
        }
        listeners.forEach(x -> x.changed(l.agent));
    }

    private void endTurn(LiveAgent l, AgentEvent.Result r) {
        Agent a = l.agent;
        if (l.background > 0 && !r.error()) return; // still working: background tasks will continue the turn
        closeTurn(l, r);
    }

    private void closeTurn(LiveAgent l, AgentEvent.Result r) {
        Agent a = l.agent;
        a.turnOpen = false;
        if (l.stopRequested) {
            l.stopRequested = false;
            a.interrupted = true;
            a.interruptReason = "you stopped it";
            a.interruptedAt = System.currentTimeMillis();
            setStatus(l, Status.IDLE, null, "");
            notice(l, "Stopped. /resume continues where it left off.", "warn");
        } else if (r != null && r.error()) {
            String why = switch (r.kind()) {
                case "max_turns" -> "Reached the max turns (" + profile(a.profile).maxTurns + " per message).";
                case "budget" -> "Reached the spend limit for this process.";
                default -> r.text() == null || r.text().isBlank() ? "The turn failed." : AgentPrompts.firstLine(r.text(), 300);
            };
            notice(l, why + " /resume tries again.", "bad");
            a.interrupted = true;
            a.interruptReason = why;
            a.interruptedAt = System.currentTimeMillis();
            setStatus(l, Status.AWAITING, Reason.FAILED, why);
        } else if (endsWithQuestion(l.lastReply)) {
            setStatus(l, Status.AWAITING, Reason.QUESTION, "");
        } else if (a.status != Status.AWAITING || a.reason != Reason.FOLDER) {
            setStatus(l, Status.DONE, null, "");
        }
        a.unread = true;
        store.save(a);
        if (l.restartAfterTurn) {
            l.restartAfterTurn = false;
            stopProcess(l);
            if (l.afterRestart != null) {
                String text = l.afterRestart;
                l.afterRestart = null;
                deliver(l, text, "juno");
            }
        }
    }

    /** The final reply's last paragraph starts with ❓ (docs/progress.md, Milestone 0 decision 1). */
    public static boolean endsWithQuestion(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String[] paras = reply.strip().split("\n\\s*\n");
        return paras[paras.length - 1].strip().startsWith("❓");
    }

    private void exited(LiveAgent l, AgentEvent.Exited x) {
        Agent a = l.agent;
        boolean expected = "stopped".equals(x.detail()) || l.process == null;
        cancelPermissions(l, "the process ended");
        cleanRun(l);
        l.process = null;
        l.background = 0;
        if (!expected && a.turnOpen) {
            String why = x.detail() == null || x.detail().isBlank() ? "the process ended unexpectedly (exit "
                    + x.code() + ")" : launchError(x.detail());
            a.turnOpen = false;
            a.interrupted = true;
            a.interruptReason = why;
            a.interruptedAt = System.currentTimeMillis();
            record(l, new AgentEvent.Notice("Interrupted: " + why + ". /resume continues.", "bad"));
            setStatus(l, Status.AWAITING, Reason.INTERRUPTED, why);
            a.unread = true;
            store.save(a);
        } else if (!expected && x.code() != 0) {
            record(l, new AgentEvent.Notice("The process ended (exit " + x.code() + ")"
                    + (x.detail().isBlank() ? "." : ": " + AgentPrompts.firstLine(x.detail(), 300)), "warn"));
        }
        listeners.forEach(z -> z.changed(a));
    }

    /** "if a launch fails on an unknown flag, the error names the flag and the version" (design §10). */
    private String launchError(String stderr) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("unknown option '([^']+)'").matcher(stderr);
        if (m.find()) {
            String version = versions.values().stream().map(Provider.VersionCheck::version).findFirst().orElse("?");
            return "Claude Code " + version + " doesn't know the flag " + m.group(1);
        }
        return AgentPrompts.firstLine(stderr, 300);
    }

    // ---------------------------------------------------------------- commands

    /** /stop: interrupts the current turn; the conversation is kept. */
    public synchronized String stop(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.status == Status.AWAITING && a.reason == Reason.FOLDER && a.heldMessage != null) {
            a.heldMessage = null;
            setStatus(l, Status.IDLE, null, "");
            store.save(a);
            return "Dropped the held message.";
        }
        if (!l.running() || !a.turnOpen) return a.name + " isn't working.";
        l.stopRequested = true;
        cancelPermissions(l, "the user stopped you");
        l.process.send(provider.interrupt("stop-" + System.currentTimeMillis()));
        AgentProcess p = l.process;
        timer.schedule(() -> {
            synchronized (this) {
                if (l.process == p && a.turnOpen && l.stopRequested) {
                    p.kill();
                    closeTurn(l, null);
                }
            }
        }, STOP_GRACE_MS, TimeUnit.MILLISECONDS);
        return null;
    }

    /** /resume: continues an interrupted or stopped agent, with a recap of what it had done (design §12). */
    public synchronized String resume(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen) return a.name + " is working.";
        boolean failed = a.status == Status.AWAITING && a.reason == Reason.FAILED;
        if (!a.interrupted && !failed) return "There's nothing to resume: " + a.name + " wasn't interrupted.";
        String recap = AgentPrompts.recap(a, l.history(), changes(a), folder(a));
        a.interrupted = false;
        deliver(l, recap, "recap");
        return null;
    }

    /** /new: the next message starts a new conversation; this one is kept in the list (design §7.9). */
    public synchronized String newConversation(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        stopProcess(l);
        a.conversations.stream().filter(c -> c.id.equals(a.conversationId)).findFirst()
                .ifPresent(c -> c.ended = System.currentTimeMillis());
        l.journal.close();
        a.sessionId = null;
        a.conversationId = newConversationId();
        a.interrupted = false;
        l.journal = new Journal(journalFile(a));
        synchronized (l.history) {
            l.history.clear();
        }
        l.contextTokens = l.conversationTokens = l.lastTurnTokens = 0;
        l.conversationCost = l.lastTurnCost = 0;
        l.lastReply = "";
        setStatus(l, Status.IDLE, null, "");
        store.save(a);
        listeners.forEach(x -> x.agentsChanged());
        return null;
    }

    public synchronized String compact(Agent a) {
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        if (a.sessionId == null) return "There's no conversation to compact yet.";
        deliver(live.get(a.id), provider.compactMessage(), "compact");
        return null;
    }

    public synchronized void setModel(Agent a, String model) {
        a.modelOverride = model == null ? "" : model.strip();
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    public synchronized void setEffort(Agent a, String effort) {
        a.effortOverride = effort == null ? "" : effort.strip();
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    /** Toggles a skill for the next message (/activate). Returns what the pane says. */
    public synchronized String activate(Agent a, String query) {
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        String q = normalise(query).replace("-", "");
        Settings s = settings.get();
        String found = null;
        for (SkillPack pack : s.skillPacks) {
            List<String> enabled = skillResolver.enabledIn(pack, a.profile);
            if (enabled.isEmpty()) continue;
            if (normalise(pack.id).replace("-", "").equals(q) || normalise(pack.name).replace("-", "").equals(q)) {
                String entry = enabled.contains(pack.entrySkill) ? pack.entrySkill : enabled.get(0);
                found = provider.skillName(pack.id, entry);
                break;
            }
            for (String sk : enabled) {
                if (normalise(sk).replace("-", "").equals(q)) {
                    found = provider.skillName(pack.id, sk);
                    break;
                }
            }
            if (found != null) break;
        }
        if (found == null) return a.name + " has no skill called " + query + ". Its skills: " + String.join(", ", skillNames(a));
        if (found.equals(a.pendingSkill)) {
            a.pendingSkill = null;
            store.save(a);
            listeners.forEach(x -> x.changed(a));
            return "Cancelled: " + found + " won't be loaded.";
        }
        a.pendingSkill = found;
        store.save(a);
        listeners.forEach(x -> x.changed(a));
        return "Queued: " + a.name + " loads " + found + " with its next message. Repeat to cancel.";
    }

    /** Every skill the agent has, as it names them. */
    public List<String> skillNames(Agent a) {
        List<String> out = new ArrayList<>();
        for (SkillPack pack : settings.get().skillPacks) {
            for (String sk : skillResolver.enabledIn(pack, a.profile)) out.add(provider.skillName(pack.id, sk));
        }
        return out;
    }

    /** Pack ids and skill names usable with /activate. */
    public List<String> activateChoices(Agent a) {
        List<String> out = new ArrayList<>();
        for (SkillPack pack : settings.get().skillPacks) {
            List<String> enabled = skillResolver.enabledIn(pack, a.profile);
            if (enabled.isEmpty()) continue;
            out.add(pack.id);
            out.addAll(enabled);
        }
        return out;
    }

    // ---------------------------------------------------------------- folders (design §7.7)

    @Override
    public String requestFolder(String agentId, String reason) {
        LiveAgent l = live.get(agentId);
        if (l == null) return AgentPrompts.result("common", "call-failed", Map.of("reason", "this agent no longer exists"));
        synchronized (this) {
            l.folderReason = reason == null || reason.isBlank() ? "It needs a project." : reason;
            setStatus(l, Status.AWAITING, Reason.FOLDER, l.folderReason);
            l.agent.unread = true;
            notice(l, "📁 " + l.agent.name + " asks for a project: " + l.folderReason, "warn");
            store.save(l.agent);
        }
        return AgentPrompts.result("request_folder", "asked", Map.of());
    }

    /** Gives the agent a project: restarts its process in it (conversation kept) and tells it, or sends the held message. */
    public synchronized void setProject(Agent a, String projectId) {
        LiveAgent l = live.get(a.id);
        Project p = settings.get().projects.stream().filter(x -> x.id.equals(projectId)).findFirst().orElse(null);
        if (p == null) return;
        a.projectId = p.id;
        l.folderReason = null;
        notice(l, "📁 Project set: " + p.name + ".", "good");
        String text;
        if (a.heldMessage != null) {
            text = a.heldMessage;
            a.heldMessage = null;
        } else {
            text = a.sessionId != null ? AgentPrompts.folderSet(p, folder(a)) : null;
        }
        if (a.status == Status.AWAITING && a.reason == Reason.FOLDER) setStatus(l, Status.IDLE, null, "");
        store.save(a);
        if (a.turnOpen) {
            l.restartAfterTurn = true;
            l.afterRestart = text;
        } else {
            stopProcess(l);
            if (text != null) send(a, text, a.sessionId == null ? "user" : "juno");
        }
    }

    // ---------------------------------------------------------------- permissions (design §7.5)

    @Override
    public ObjectNode requestPermission(String agentId, String tool, JsonNode input, String toolUseId) {
        LiveAgent l = live.get(agentId);
        if (l == null) return deny(AgentPrompts.result("common", "call-failed", Map.of("reason", "this agent no longer exists")));
        Agent a = l.agent;
        Profile p = profile(a.profile);
        Path folder = folder(a);
        String refused = PermissionRules.refusedBy(p.disallowedTools, tool, input, folder);
        if (refused != null) {
            notice(l, "✗ " + tool + " refused: `" + refused + "` is disallowed for " + a.profile + ".", "bad");
            return deny(AgentPrompts.result("request_permission", "disallowed",
                    Map.of("rule", refused, "profile", a.profile)));
        }
        for (Agent.AgentRule r : a.rules) {
            if (PermissionRules.allows(List.of(r.rule), tool, input, folder)) {
                notice(l, "✓ allowed by rule " + r.rule, "dim");
                return allow(input);
            }
        }
        PermissionRules.Proposal proposal = PermissionRules.propose(tool, input, folder);
        String id = toolUseId == null || toolUseId.isBlank() ? UUID.randomUUID().toString() : toolUseId;
        LiveAgent.PendingPermission pending = new LiveAgent.PendingPermission(id, tool, input, proposal.rule(),
                proposal.description());
        synchronized (this) {
            synchronized (l.permissions) {
                l.permissions.put(id, pending);
            }
            record(l, new AgentEvent.Permission(id, tool, input.toString(), proposal.rule(), proposal.description(), null));
            setStatus(l, Status.AWAITING, Reason.PERMISSION, tool);
            a.unread = true;
            store.save(a);
        }
        try {
            return pending.answer.get();
        } catch (Exception e) {
            return deny(AgentPrompts.result("request_permission", "denied", Map.of()));
        } finally {
            synchronized (l.permissions) {
                l.permissions.remove(id);
            }
        }
    }

    /** Answers a pending permission request. {@code rule} is the (edited) rule for the "always" answers. */
    public synchronized String answer(Agent a, String requestId, Answer answer, String rule) {
        LiveAgent l = live.get(a.id);
        LiveAgent.PendingPermission pp;
        synchronized (l.permissions) {
            pp = requestId == null ? l.permissions.values().stream().findFirst().orElse(null)
                    : l.permissions.get(requestId);
        }
        if (pp == null) return "There's no permission request waiting.";
        Profile p = profile(a.profile);
        String r = rule == null || rule.isBlank() ? pp.proposedRule : rule.strip();
        if ((answer == Answer.ALLOW_AGENT || answer == Answer.ALLOW_PROFILE) && !PermissionRules.valid(r)) {
            return "`" + r + "` isn't a valid rule.";
        }
        if (answer != Answer.DENY) {
            String refused = PermissionRules.refusedBy(p.disallowedTools, pp.tool, pp.input, folder(a));
            if (refused != null) answer = Answer.DENY;
        }
        String outcome;
        switch (answer) {
            case ALLOW_ONCE -> outcome = "allowed once";
            case ALLOW_AGENT -> {
                a.rules.add(new Agent.AgentRule(r, PermissionRules.describe(r), AgentPrompts.firstLine(
                        pp.tool + " " + pp.input.toString(), 160)));
                outcome = "always allowed for " + a.name + ": " + r;
            }
            case ALLOW_PROFILE -> {
                if (p.builtIn) return "Built-in profiles can't be changed; duplicate it in Settings instead.";
                if (!p.allowedTools.contains(r)) p.allowedTools.add(r);
                saveSettings.run();
                outcome = "always allowed for the " + p.name + " profile (from its agents' next start): " + r;
            }
            default -> outcome = "denied";
        }
        record(l, new AgentEvent.Permission(pp.id, pp.tool, pp.input.toString(), r, PermissionRules.describe(r), outcome));
        pp.answer.complete(answer == Answer.DENY
                ? deny(AgentPrompts.result("request_permission", "denied", Map.of())) : allow(pp.input));
        if (l.pendingPermissions().size() <= 1) setStatus(l, Status.WORKING, null, "");
        store.save(a);
        return null;
    }

    private void cancelPermissions(LiveAgent l, String why) {
        for (LiveAgent.PendingPermission pp : l.pendingPermissions()) {
            record(l, new AgentEvent.Permission(pp.id, pp.tool, pp.input.toString(), pp.proposedRule,
                    pp.proposedDescription, "cancelled"));
            pp.answer.complete(deny(AgentPrompts.result("request_permission", "cancelled", Map.of("reason", why))));
        }
    }

    private static ObjectNode allow(JsonNode input) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("behavior", "allow");
        o.set("updatedInput", input);
        return o;
    }

    private static ObjectNode deny(String message) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("behavior", "deny");
        o.put("message", message);
        return o;
    }

    // ---------------------------------------------------------------- hook, timers

    /** Claude hook events (when installed): {@code Stop} confirms a turn ended; output events stay primary. */
    private void hookEvent(String agentId, JsonNode e) {
        LiveAgent l = live.get(agentId);
        if (l == null) return;
        String name = e.path("hook_event_name").asText();
        synchronized (this) {
            if (name.equals("Stop") && l.agent.turnOpen && l.background == 0 && l.pendingPermissions().isEmpty()) {
                // The stream's result normally comes first; this covers a result line that never arrives.
                timer.schedule(() -> {
                    synchronized (this) {
                        if (l.agent.turnOpen && l.background == 0 && l.pendingPermissions().isEmpty()
                                && System.currentTimeMillis() - l.lastActivity > 2_000) closeTurn(l, null);
                    }
                }, 3, TimeUnit.SECONDS);
            } else if (name.equals("Notification") && l.agent.turnOpen && l.pendingPermissions().isEmpty()
                    && e.path("message").asText().toLowerCase(Locale.ROOT).contains("permission")) {
                setStatus(l, Status.AWAITING, Reason.PERMISSION, e.path("message").asText());
            }
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            long idleLimit = Math.max(1, settings.get().system.idleTimeoutMinutes) * 60_000L;
            synchronized (this) {
                for (LiveAgent l : live.values()) {
                    Agent a = l.agent;
                    if (a.status == Status.DONE && now - a.statusSince > DONE_TO_IDLE_MS) {
                        setStatus(l, Status.IDLE, null, ""); // unread survives (design §3)
                        store.save(a);
                    }
                    if (a.turnOpen && l.background == 0 && l.backgroundDoneAt > 0
                            && now - l.backgroundDoneAt > BACKGROUND_SETTLE_MS && now - l.lastActivity > BACKGROUND_SETTLE_MS
                            && l.pendingPermissions().isEmpty()) {
                        l.backgroundDoneAt = 0;
                        closeTurn(l, null);
                    }
                    if (l.running() && !a.turnOpen && l.background == 0 && now - l.lastActivity > idleLimit) {
                        notice(l, "Process closed after " + settings.get().system.idleTimeoutMinutes
                                + " idle minutes; the next message resumes it.", "dim");
                        stopProcess(l);
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Runtime tick failed: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void stopProcess(LiveAgent l) {
        if (l.process != null) {
            AgentProcess p = l.process;
            l.process = null;
            p.close();
        }
        cleanRun(l);
    }

    /** The per-process files hold the MCP token: deleted when the process ends (design §12). */
    private void cleanRun(LiveAgent l) {
        mcp.unregister(l.token);
        l.token = null;
        Path run = home.agentRunDir(l.agent.id);
        try {
            if (Files.exists(run)) {
                try (var walk = Files.walk(run)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            System.err.println("Couldn't clean " + run + ": " + e.getMessage());
        }
    }

    private void setStatus(LiveAgent l, Status s, Reason r, String detail) {
        Agent a = l.agent;
        if (a.status != s || a.reason != r) a.statusSince = System.currentTimeMillis();
        a.status = s;
        a.reason = s == Status.AWAITING ? r : null;
        a.statusDetail = detail == null ? "" : detail;
        listeners.forEach(x -> x.changed(a));
    }

    public void markRead(Agent a) {
        if (!a.unread) return;
        a.unread = false;
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    /** Saves agent changes made elsewhere (e.g. the name) and tells listeners. */
    public void saved(Agent a) {
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    private void notice(LiveAgent l, String text, String level) {
        record(l, new AgentEvent.Notice(text, level));
    }

    /** A pane-only line (e.g. a command's answer); journalled like everything else. */
    public void say(Agent a, String text, String level) {
        notice(live.get(a.id), text, level);
    }

    private void record(LiveAgent l, AgentEvent e) {
        l.journal.append(e);
        synchronized (l.history) {
            l.history.add(e);
        }
        listeners.forEach(x -> x.event(l.agent, e));
    }

    /** Rebuilds meters and history from the journal after a restart. */
    private void replay(LiveAgent l, AgentEvent e) {
        synchronized (l.history) {
            l.history.add(e);
        }
        switch (e) {
            case AgentEvent.Usage u -> usage(l, u);
            case AgentEvent.Message m when m.parent() == null -> l.lastReply = m.text();
            default -> { }
        }
    }

    public Profile profile(String name) {
        return settings.get().profiles.stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
    }

    public Project project(Agent a) {
        if (a.projectId == null) return null;
        return settings.get().projects.stream().filter(p -> p.id.equals(a.projectId)).findFirst().orElse(null);
    }

    /**
     * The agent's working folder: the project folder, or a private empty folder (design §7.7).
     * TODO(Milestone 3): agents whose profile edits files get their own worktree (design §7.8).
     */
    public Path folder(Agent a) {
        Project p = project(a);
        return p == null ? home.agentHome(a.id) : Path.of(p.folder);
    }

    public String effectiveModel(Agent a, Profile p) {
        return a.modelOverride != null && !a.modelOverride.isBlank() ? a.modelOverride : p.model;
    }

    public String effectiveEffort(Agent a, Profile p) {
        return a.effortOverride != null && !a.effortOverride.isBlank() ? a.effortOverride : p.effort;
    }

    /** Whether the next message restarts the process (to apply /model, /effort or a project change). */
    public boolean pendingRestart(Agent a) {
        LiveAgent l = live.get(a.id);
        Profile p = profile(a.profile);
        return l.running() && (!eq(effectiveModel(a, p), l.launchedModel) || !eq(effectiveEffort(a, p), l.launchedEffort));
    }

    private List<Provider.SkillSet> skillSets(String profile) {
        List<Provider.SkillSet> out = new ArrayList<>();
        Settings s = settings.get();
        for (Map.Entry<String, List<String>> e : skillResolver.enabledFor(s, profile).entrySet()) {
            SkillPack pack = s.skillPacks.stream().filter(p -> p.id.equals(e.getKey())).findFirst().orElseThrow();
            out.add(new Provider.SkillSet(pack.id, pack.name, skills.packDir(pack).resolve("skills"), e.getValue()));
        }
        return out;
    }

    /** {@code git status --short} of the agent's folder, for the recap; empty if not a repository or clean. */
    private String changes(Agent a) {
        Path f = folder(a);
        if (!Files.isDirectory(f.resolve(".git")) && !Files.isRegularFile(f.resolve(".git"))) return "";
        try {
            String s = dev.juno.git.Git.run(f, "status", "--short").strip();
            if (s.isEmpty()) return "";
            String[] lines = s.split("\n");
            String joined = String.join(", ", java.util.Arrays.stream(lines).limit(12).map(String::strip).toList());
            return lines.length > 12 ? joined + " …" : joined;
        } catch (IOException e) {
            return "";
        }
    }

    private Path journalFile(Agent a) {
        return home.journalsDir(a.id).resolve(a.conversationId + ".jsonl");
    }

    private static String newConversationId() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    public Settings settingsNow() {
        return settings.get();
    }

    /** Shows (and journals) an event Juno made, e.g. /diff output. */
    public void show(Agent a, AgentEvent e) {
        record(live.get(a.id), e);
    }

    /** For tests: the MCP server's URL. */
    public String mcpUrl() {
        return mcp.url();
    }
}
