package dev.juno.main.service;

import dev.juno.main.console.AgentConsoles;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;

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
     * Brings the terminal for the ticket's current (or last) run to the front, opening one if it isn't running.
     * Work runs and AI review runs have separate terminals.
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
            throw new JunoException("Could not open a terminal: " + e.getMessage(), e);
        }
    }

    public boolean isTerminalOpen(String ticketId) {
        RunKind kind;
        synchronized (state) {
            kind = state.find(ticketId).map(Ticket::getRunKind).orElse(RunKind.WORK);
        }
        return consoles != null && consoles.isOpen(AgentConsoles.key(ticketId, consoleVariant(kind)));
    }

    void close() {
        if (consoles != null) {
            consoles.close();
        }
    }
}
