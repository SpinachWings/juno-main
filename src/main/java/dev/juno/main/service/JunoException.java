package dev.juno.main.service;

/** A user-facing failure (shown in the UI as-is). */
public class JunoException extends Exception {

    public JunoException(String message) {
        super(message);
    }

    public JunoException(String message, Throwable cause) {
        super(message, cause);
    }
}
