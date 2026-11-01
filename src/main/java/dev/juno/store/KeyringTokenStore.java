package dev.juno.store;

import com.github.javakeyring.Keyring;
import java.util.Optional;

/** java-keyring backed store (Windows Credential Manager, macOS Keychain, Linux Secret Service). */
public final class KeyringTokenStore implements TokenStore {
    private static final String SERVICE = "juno";
    private final Keyring keyring;
    private final String problem;

    public KeyringTokenStore() {
        Keyring k = null;
        String why = null;
        try {
            k = Keyring.create();
        } catch (Throwable t) {
            why = "No OS keyring is available (" + t.getClass().getSimpleName() + ").";
        }
        this.keyring = k;
        this.problem = why;
    }

    @Override public boolean vaultAvailable() { return keyring != null; }

    @Override public String vaultProblem() { return problem == null ? "" : problem; }

    private static String account(String id) { return "key/" + id; }

    @Override
    public String save(String id, String token) {
        if (keyring == null) throw new IllegalStateException(problem);
        try {
            keyring.setPassword(SERVICE, account(id), token);
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the token to the OS keyring: " + e.getMessage(), e);
        }
        return TokenStore.keyringReference(id);
    }

    @Override
    public void delete(String id) {
        if (keyring == null) return;
        try {
            keyring.deletePassword(SERVICE, account(id));
        } catch (Exception ignored) {
            // Entry already gone.
        }
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        if (TokenStore.isEnvReference(reference)) {
            String v = System.getenv(reference.substring(4).trim());
            return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
        }
        String prefix = "keyring:juno/";
        if (keyring == null || !reference.startsWith(prefix)) return Optional.empty();
        try {
            return Optional.ofNullable(keyring.getPassword(SERVICE, reference.substring(prefix.length())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
