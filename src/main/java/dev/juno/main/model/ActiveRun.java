package dev.juno.main.model;

/**
 * An agent run that has been started and not yet finished. It is persisted with the ticket as soon as the run
 * starts (and updated when Claude reports its session id), so a run cut short by a crash, shutdown or restart
 * can be recognised and resumed.
 *
 * @param kind        what the run is for
 * @param mode        {@code fresh}, {@code resume} (answering a decision), {@code recovery-resume} or
 *                    {@code recovery-fresh}
 * @param sessionId   Claude Code session id, once known
 * @param startedAt   ISO-8601
 * @param journal     path of this run's journal ({@code ~/.juno/runs/<ticket>/<run>.jsonl})
 * @param recoveredFrom journal of the interrupted run this one recovers, or null
 * @param pid         operating-system process id of the agent, or 0
 */
public record ActiveRun(RunKind kind, String mode, String sessionId, String startedAt, String journal,
                        String recoveredFrom, long pid) {

    public ActiveRun withSessionId(String id) {
        return new ActiveRun(kind, mode, id, startedAt, journal, recoveredFrom, pid);
    }

    public ActiveRun withPid(long processId) {
        return new ActiveRun(kind, mode, sessionId, startedAt, journal, recoveredFrom, processId);
    }
}
