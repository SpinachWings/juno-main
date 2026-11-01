package dev.juno.provider.claude;

import dev.juno.provider.Provider;
import dev.juno.store.JunoHome;
import java.nio.file.Path;

/** Claude Code behind {@link Provider}. Milestone 1: version check and hook; the rest comes in Milestone 2. */
public final class ClaudeCodeProvider implements Provider {
    private final HookInstaller hook;

    public ClaudeCodeProvider(JunoHome home) {
        this(HookInstaller.defaultClaudeDir(), home);
    }

    public ClaudeCodeProvider(Path claudeDir, JunoHome home) {
        this.hook = new HookInstaller(claudeDir, home);
    }

    @Override public String id() { return "claude"; }

    @Override public VersionCheck checkVersion(String executable) { return ClaudeVersion.check(executable); }

    @Override public HookStatus hookStatus() { return hook.status(); }

    @Override public String installHook() { return hook.install(); }

    @Override public String uninstallHook() { return hook.uninstall(); }

    public HookInstaller hookInstaller() { return hook; }
}
