package dev.juno.provider;

/**
 * Everything CLI-specific (design §10). Milestone 1 has the version check and the hook; the command line, event
 * parsing, resume, MCP config and skills arrive in Milestone 2.
 */
public interface Provider {
    String id();

    /** Runs the executable's version command. */
    VersionCheck checkVersion(String executable);

    HookStatus hookStatus();

    /** Installs or updates the hook; returns a one-line description of what changed. */
    String installHook();

    String uninstallHook();

    enum HookStatus { INSTALLED, OUTDATED, NOT_INSTALLED }

    /** Result of a version check; {@code message} is ready to show. */
    record VersionCheck(boolean ok, String version, String message) {}
}
