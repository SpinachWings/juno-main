package dev.juno.main.model;

/** How Juno reaches an MCP server, which always listens on a localhost port. */
public enum McpLaunch {
    /** Juno starts the server's jar on the port (unless it is already running there). */
    JAR("Juno starts the jar"),
    /** The server is already running on the port (started from a terminal); Juno only connects. */
    PORT("Already running on localhost");

    private final String label;

    McpLaunch(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
