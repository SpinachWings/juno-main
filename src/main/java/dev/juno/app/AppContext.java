package dev.juno.app;

import dev.juno.model.Settings;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.AgentRuntime;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.JunoHome;
import dev.juno.store.KeyringTokenStore;
import dev.juno.store.SettingsStore;
import dev.juno.store.TokenStore;
import java.util.ArrayList;
import java.util.List;

/** The app's shared services and the live settings. UI code calls {@link #save()} after changing {@link #settings}. */
public final class AppContext {
    public final JunoHome home;
    public final SettingsStore store;
    public final TokenStore tokens;
    public final ClaudeCodeProvider provider;
    public final SkillLibrary skills;
    public final SkillResolver skillResolver;
    public final AgentRuntime runtime;
    public Settings settings;

    private final List<Runnable> listeners = new ArrayList<>();

    public AppContext(JunoHome home, TokenStore tokens, ClaudeCodeProvider provider) {
        this.home = home;
        this.store = new SettingsStore(home);
        this.tokens = tokens;
        this.provider = provider;
        this.skills = new SkillLibrary(home);
        this.skillResolver = new SkillResolver(skills);
        this.settings = store.load();
        this.runtime = new AgentRuntime(home, provider, () -> settings, this::save, skills, skillResolver);
    }

    public static AppContext create() {
        JunoHome home = JunoHome.fromEnvironment();
        return new AppContext(home, new KeyringTokenStore(), new ClaudeCodeProvider(home));
    }

    public void save() {
        store.save(settings);
        new ArrayList<>(listeners).forEach(Runnable::run);
    }

    /** Called after every save (e.g. the toolbar re-checks the Claude Code version). */
    public void onSaved(Runnable listener) {
        listeners.add(listener);
    }
}
