package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * An agent (design §3): saved in {@code ~/.juno/agents/<id>/agent.json} as things happen (§12). Runtime-only state
 * (the process, pending requests) lives in {@code runtime}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent {
    public enum Status {
        IDLE("Idle"), QUEUED("Queued"), WORKING("Working"), WAITING("Waiting ⏳"), AWAITING("Awaiting decision"),
        DONE("Done");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** Why an agent is Awaiting decision (design §3). */
    public enum Reason {
        QUESTION("❓", "question"), PERMISSION("🔐", "permission"), FOLDER("📁", "folder"), KEY("🔑", "key"),
        FAILED("⚠", "failed"), INTERRUPTED("⏸", "interrupted");

        public final String icon;
        public final String label;

        Reason(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    public String id;
    public String name = "";
    public String colour = "#6FD6FF";
    public String profile = "";
    /** Project id; null for none (design §7.7). */
    public String projectId;
    public Status status = Status.IDLE;
    public Reason reason;
    /** One line shown with the status (e.g. the failure). */
    public String statusDetail = "";
    public long statusSince;
    public boolean unread;
    /** Claude Code's conversation id; null until the first message of a conversation. */
    public String sessionId;
    /** Juno's id for the current conversation (names its journal). */
    public String conversationId;
    public List<Conversation> conversations = new ArrayList<>();
    /** Per-agent overrides (same as /model, /effort); blank = the profile's. */
    public String modelOverride = "";
    public String effortOverride = "";
    public List<AgentRule> rules = new ArrayList<>();
    /** A skill to load with the next message (/activate), as the agent names it. */
    public String pendingSkill;
    /** A message held until the agent has a folder (design §7.7). */
    public String heldMessage;
    /** Set while a turn runs, so a crash can be told from an idle shutdown. */
    public boolean turnOpen;
    /** Stopped by the user mid-turn (or interrupted); Resume sends the recap. */
    public boolean interrupted;
    public String interruptReason = "";
    public long interruptedAt;
    public long created;

    /** An earlier conversation (design §7.9; the list UI comes in Milestone 3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Conversation {
        public String id;
        public String sessionId;
        public long started;
        public long ended;
        public String firstTask = "";
    }

    /** "Always allow for this agent" (design §7.5). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentRule {
        public String rule = "";
        public String description = "";
        public long added;
        /** What it was first allowed for, e.g. the command. */
        public String why = "";

        public AgentRule() {}

        public AgentRule(String rule, String description, String why) {
            this.rule = rule;
            this.description = description;
            this.why = why;
            this.added = System.currentTimeMillis();
        }
    }

    public String statusText() {
        if (status == Status.AWAITING && reason != null) return reason.icon + " " + status.label;
        return status.label;
    }
}
