package dev.juno.main.model;

/** Resolves secret settings that may be stored as {@code env:VARIABLE} references instead of literal values. */
public final class Secrets {

    private Secrets() {
    }

    /** Returns the literal value, the referenced environment variable's value, or null if blank/unset. */
    public static String resolve(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("env:")) {
            String fromEnv = System.getenv(v.substring(4).trim());
            return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
        }
        return v;
    }
}
