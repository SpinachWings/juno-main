package dev.juno.model;

import java.util.Optional;

/** The host's MCP server URL for a key (design §5.1 table). */
public final class McpUrls {
    private McpUrls() {}

    /** The override if set, else the URL derived from the host; empty when the host isn't supported. */
    public static Optional<String> forKey(Key key) {
        if (key.mcpUrlOverride != null && !key.mcpUrlOverride.isBlank()) return Optional.of(key.mcpUrlOverride.trim());
        String host = key.host == null ? "" : key.host.trim().toLowerCase();
        if (host.isEmpty()) return Optional.empty();
        if (key.kind == Key.Kind.GITHUB) {
            if (host.equals("github.com")) return Optional.of("https://api.githubcopilot.com/mcp/");
            if (host.endsWith(".ghe.com")) {
                String name = host.substring(0, host.length() - ".ghe.com".length());
                return Optional.of("https://copilot-api." + name + ".ghe.com/mcp");
            }
            return Optional.empty(); // GitHub Enterprise Server: not supported in this version.
        }
        return Optional.of("https://" + host + "/api/v4/mcp");
    }

    /** Why a key has no URL, for the Keys tab. */
    public static String unsupportedReason(Key key) {
        return key.kind == Key.Kind.GITHUB
                ? "GitHub Enterprise Server isn't supported in this version. Use github.com or a <name>.ghe.com host, "
                + "or set an MCP URL override."
                : "Enter a host.";
    }
}
