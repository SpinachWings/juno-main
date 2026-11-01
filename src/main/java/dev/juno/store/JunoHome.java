package dev.juno.store;

import java.nio.file.Path;

/** The data folder: {@code ~/.juno}, or {@code JUNO_HOME} when set. */
public final class JunoHome {
    private final Path root;

    public JunoHome(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static JunoHome fromEnvironment() {
        String env = System.getenv("JUNO_HOME");
        if (env != null && !env.isBlank()) return new JunoHome(Path.of(env));
        return new JunoHome(Path.of(System.getProperty("user.home"), ".juno"));
    }

    public Path root() { return root; }
    public Path settingsFile() { return root.resolve("settings.json"); }
    public Path skillsDir() { return root.resolve("skills"); }
    public Path bundledSkillsDir() { return skillsDir().resolve("bundled"); }
    public Path librarySkillsDir() { return skillsDir().resolve("library"); }
    /** Where the Claude hook script appends events (read by Milestone 2). */
    public Path hookEventsDir() { return root.resolve("hook-events"); }
    public Path hookScriptsDir() { return root.resolve("hooks"); }
}
