package dev.juno.main.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Upgrades settings written before organisations existed (one global GitLab/GitHub connection and one agent)
 * into the organisation model: a "Default" organisation holding the old connections, a "Default agent"
 * profile assigned to it, and every existing project placed in that organisation.
 */
final class SettingsMigration {

    private SettingsMigration() {
    }

    static boolean isLegacy(ObjectNode root) {
        return !root.has("organisations") && (root.has("agent") || root.has("gitlab") || root.has("github"));
    }

    static void migrate(ObjectNode root) {
        String orgId = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();

        // Agent: the old single agent becomes the default profile; the concurrency limit becomes global.
        ObjectNode agent = root.has("agent") && root.get("agent").isObject()
                ? (ObjectNode) root.remove("agent")
                : root.objectNode();
        JsonNode limit = agent.remove("maxConcurrentAgents");
        if (limit != null && limit.canConvertToInt()) {
            root.put("maxConcurrentAgents", limit.asInt());
        }
        agent.put("id", agentId);
        agent.put("name", "Default agent");
        root.putArray("agentProfiles").add(agent);

        // Trackers: connection details move to the organisation; jar paths stay global.
        ObjectNode org = root.objectNode();
        org.put("id", orgId);
        org.put("name", "Default");
        org.put("agentProfileId", agentId);
        for (String kind : new String[] {"gitlab", "github"}) {
            JsonNode old = root.remove(kind);
            ObjectNode tracker = org.putObject(kind);
            tracker.put("enabled", old != null);
            if (old != null) {
                tracker.put("apiUrl", old.path("apiUrl").asText(""));
                tracker.put("token", old.path("token").asText(""));
                String jar = old.path("mcpJar").asText("");
                if (!jar.isBlank()) {
                    root.put(kind + "McpJar", jar);
                }
            }
        }
        root.putArray("organisations").add(org);

        JsonNode projects = root.get("projects");
        if (projects instanceof ArrayNode list) {
            for (JsonNode p : list) {
                if (p instanceof ObjectNode project && !project.hasNonNull("orgId")) {
                    project.put("orgId", orgId);
                }
            }
        }
    }
}
