package dev.juno.store;

import java.util.Optional;

/**
 * Where key tokens live (design §5.1). Tokens never go into settings.json: only a reference does, either
 * {@code keyring:juno/key/<id>} (OS vault) or {@code env:VARIABLE}.
 */
public interface TokenStore {
    /** Whether an OS vault is usable on this machine. */
    boolean vaultAvailable();

    /** Why not, for the Keys tab; empty when available. */
    String vaultProblem();

    /** Stores a token for key {@code id}; returns the reference to save in settings. */
    String save(String id, String token);

    /** Deletes the vault entry of key {@code id} (no-op if there is none). */
    void delete(String id);

    /** Reads the token a reference points to; empty if not available. Only called when an agent starts. */
    Optional<String> resolve(String reference);

    static String keyringReference(String id) {
        return "keyring:juno/key/" + id;
    }

    static boolean isEnvReference(String reference) {
        return reference != null && reference.startsWith("env:");
    }

    /** Masked display of a stored token, never the full token. */
    static String mask(String token) {
        if (token == null || token.isEmpty()) return "";
        String tail = token.length() > 8 ? token.substring(token.length() - 4) : "";
        return "••••••••" + tail;
    }
}
