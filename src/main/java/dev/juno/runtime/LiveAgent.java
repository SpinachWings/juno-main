package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.model.Agent;
import dev.juno.provider.AgentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** What the runtime knows about an agent beyond its saved {@link Agent}: the process, pending requests, meters. */
public final class LiveAgent {
    public final Agent agent;
    AgentProcess process;
    String token;
    Journal journal;
    /** Launch settings of the running process, to tell when /model, /effort or a project change needs a restart. */
    String launchedModel, launchedEffort, launchedFolder;
    int background;
    boolean stopRequested;
    boolean restartAfterTurn;
    long lastActivity = System.currentTimeMillis();
    long backgroundDoneAt;
    String folderReason;
    /** Text to send once the process is back after a project change. */
    String afterRestart;
    final Map<String, PendingPermission> permissions = new LinkedHashMap<>();
    final List<AgentEvent> history = new ArrayList<>();

    // Meters (design §7.4)
    public long contextTokens;
    public long contextWindow;
    public long lastTurnTokens;
    public long conversationTokens;
    public double lastTurnCost;
    public double conversationCost;
    public long lastInput, lastOutput, lastCacheRead, lastCacheWrite;
    long turnTokens;
    public String lastReply = "";

    LiveAgent(Agent agent) {
        this.agent = agent;
    }

    public boolean running() {
        return process != null && process.alive();
    }

    /** The process id, or -1 when no process runs. */
    public long pid() {
        return running() ? process.pid() : -1;
    }

    public List<PendingPermission> pendingPermissions() {
        synchronized (permissions) {
            return List.copyOf(permissions.values());
        }
    }

    /** The events of the current conversation so far (for a pane being built). */
    public List<AgentEvent> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public String folderReason() {
        return folderReason;
    }

    /** A permission prompt waiting for the user (design §7.5). */
    public static final class PendingPermission {
        public final String id;
        public final String tool;
        public final JsonNode input;
        public final String proposedRule;
        public final String proposedDescription;
        final CompletableFuture<ObjectNode> answer = new CompletableFuture<>();

        PendingPermission(String id, String tool, JsonNode input, String rule, String description) {
            this.id = id;
            this.tool = tool;
            this.input = input;
            this.proposedRule = rule;
            this.proposedDescription = description;
        }
    }
}
