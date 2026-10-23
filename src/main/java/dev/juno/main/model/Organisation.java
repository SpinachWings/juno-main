package dev.juno.main.model;

import java.util.UUID;

/**
 * A credentials boundary: owns GitLab/GitHub connections and a set of projects. It does not have to match a
 * GitLab group or GitHub organisation; it is whatever its tokens can reach.
 */
public final class Organisation {

    private String id = UUID.randomUUID().toString();
    private String name = "New organisation";
    private TrackerSettings gitlab = new TrackerSettings(false, "https://gitlab.com");
    private TrackerSettings github = new TrackerSettings(false, "https://api.github.com");
    /** Default agent profile for this organisation's projects and tickets; null = none. */
    private String agentProfileId;

    public Organisation() {
    }

    public Organisation(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TrackerSettings tracker(TrackerKind kind) {
        return kind == TrackerKind.GITLAB ? gitlab : github;
    }

    public void setTracker(TrackerKind kind, TrackerSettings settings) {
        if (kind == TrackerKind.GITLAB) {
            gitlab = settings;
        } else {
            github = settings;
        }
    }

    public String getAgentProfileId() {
        return agentProfileId;
    }

    public void setAgentProfileId(String agentProfileId) {
        this.agentProfileId = agentProfileId;
    }

    @Override
    public String toString() {
        return name;
    }
}
