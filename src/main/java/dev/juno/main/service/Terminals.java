package dev.juno.main.service;

import dev.juno.main.console.AgentConsoles;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.RunKind;

import java.io.IOException;

/** The agent terminals: one per ticket run (AI reviews and conflict resolutions get their own). */
final class Terminals {

    private final BoardState state;
    /** Terminal consoles; null if the local console server could not start. */
    private final AgentConsoles consoles;

    Terminals(BoardState state, AgentConsoles consoles) {
        this.state = state;
        this.consoles = consoles;
    }

    static String consoleVariant(RunKind kind) {
        return kind.consoleVariant();
    }

    void openTerminalIfEnabled(String ticketId, RunKind kind) {
        JunoSettings s = state.settings;
        if (consoles == null || !s.isOpenTerminals()) {
            return;
        }
        try {
            consoles.ensureOpen(ticketId, consoleVariant(kind), s.getTerminalCommand());
        } catch (IOException e) {
            state.log(ticketId, "[juno] could not open a terminal: " + e.getMessage());
        }
    }

    /**
     * Brings the ticket's open terminal to the front (the one for its current or last run if several are open),
     * opening one only if none is.
     */
    public String showTerminal(String ticketId) throws JunoException {
        if (consoles == null) {
            throw new JunoException("The agent console is unavailable (see Juno's startup output)");
        }
        RunKind kind;
        synchronized (state) {
            kind = state.require(ticketId).getRunKind();
        }
        try {
            return consoles.show(ticketId, consoleVariant(kind), state.settings.getTerminalCommand());
        } catch (IOException e) {
            throw new JunoException(e.getMessage(), e);
        }
    }

    /** Whether any of the ticket's terminals is open. */
    public boolean isTerminalOpen(String ticketId) {
        return consoles != null && consoles.isAnyOpen(ticketId);
    }

    /** A deleted ticket's terminals close themselves; this forgets them. */
    void forget(String ticketId) {
        if (consoles != null) {
            consoles.forget(ticketId);
        }
    }

    void close() {
        if (consoles != null) {
            consoles.close();
        }
    }
}
