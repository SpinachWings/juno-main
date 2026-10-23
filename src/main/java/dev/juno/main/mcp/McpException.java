package dev.juno.main.mcp;

/** Any failure talking to an MCP server, including tool errors. */
public class McpException extends Exception {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
