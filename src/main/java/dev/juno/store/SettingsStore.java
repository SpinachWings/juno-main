package dev.juno.store;

import dev.juno.model.BuiltInProfiles;
import dev.juno.model.Profile;
import dev.juno.model.Settings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Loads and saves {@code settings.json}. Built-in profiles are re-applied from code on every load. */
public final class SettingsStore {
    private final JunoHome home;

    public SettingsStore(JunoHome home) {
        this.home = home;
    }

    public Settings load() {
        Settings s;
        try {
            s = Files.exists(home.settingsFile())
                    ? Json.MAPPER.readValue(home.settingsFile().toFile(), Settings.class)
                    : new Settings();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + home.settingsFile() + ": " + e.getMessage(), e);
        }
        normalise(s);
        return s;
    }

    public void save(Settings s) {
        normalise(s);
        try {
            AtomicFiles.write(home.settingsFile(), Json.MAPPER.writeValueAsBytes(s));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + home.settingsFile() + ": " + e.getMessage(), e);
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Fills missing ids and puts the built-in profiles first, with their locked fields from code. */
    static void normalise(Settings s) {
        s.keys.forEach(k -> { if (k.id == null || k.id.isBlank()) k.id = newId(); });
        s.projects.forEach(p -> { if (p.id == null || p.id.isBlank()) p.id = newId(); });

        List<Profile> merged = new ArrayList<>();
        for (Profile builtIn : BuiltInProfiles.create()) {
            Profile saved = s.profiles.stream()
                    .filter(p -> p.builtIn && p.name.equals(builtIn.name)).findFirst().orElse(null);
            if (saved != null) {
                builtIn.executablePath = saved.executablePath;
                builtIn.model = saved.model;
                builtIn.effort = saved.effort;
                builtIn.maxTurns = saved.maxTurns;
                builtIn.spendLimit = saved.spendLimit;
                saved.commands.stream().filter(c -> !c.builtIn).forEach(builtIn.commands::add);
            }
            merged.add(builtIn);
        }
        for (Profile p : s.profiles) {
            if (!p.builtIn && !BuiltInProfiles.isBuiltIn(p.name)) merged.add(p);
        }
        s.profiles = merged;
    }
}
