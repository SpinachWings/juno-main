package dev.juno.main.agent;

/**
 * How an agent run ended.
 *
 * @param kind      the classification
 * @param sessionId Claude Code session id (for resuming), may be null if the agent never started
 * @param message   final summary (COMPLETED), the agent's question (NEEDS_DECISION) or the error (FAILED)
 * @param costUsd   reported total cost, or -1 if unknown
 */
public record AgentOutcome(Kind kind, String sessionId, String message, double costUsd) {

    public enum Kind {
        COMPLETED,
        NEEDS_DECISION,
        FAILED,
        CANCELLED
    }
}
