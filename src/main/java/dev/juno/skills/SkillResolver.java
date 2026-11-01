package dev.juno.skills;

import dev.juno.model.Settings;
import dev.juno.model.SkillPack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Which skills a profile's agents get (design §5.5). Milestone 2 turns this into the session-only plugin folder. */
public final class SkillResolver {
    private final SkillLibrary library;

    public SkillResolver(SkillLibrary library) {
        this.library = library;
    }

    /** Pack id to the skills enabled for the profile; packs with none are left out. */
    public Map<String, List<String>> enabledFor(Settings settings, String profile) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SkillPack pack : settings.skillPacks) {
            List<String> skills = enabledIn(pack, profile);
            if (!skills.isEmpty()) out.put(pack.id, skills);
        }
        return out;
    }

    public List<String> enabledIn(SkillPack pack, String profile) {
        List<String> all = library.skillsOf(pack);
        if (pack.bundled) {
            Optional<List<String>> locked = BundledPacks.locked(pack.id, profile, all);
            if (locked.isPresent()) return locked.get();
        }
        if (!pack.wholeOrchestration && !pack.profiles.contains(profile)) return List.of();
        List<String> off = new ArrayList<>(pack.disabledSkills);
        off.addAll(pack.disabledPerProfile.getOrDefault(profile, List.of()));
        return all.stream().filter(s -> !off.contains(s)).toList();
    }

    /** Whether a profile has this pack at all (assigned, locked or whole-orchestration). */
    public boolean assigned(SkillPack pack, String profile) {
        if (pack.bundled && BundledPacks.lockedProfiles(pack.id).contains(profile)) return true;
        return pack.wholeOrchestration || pack.profiles.contains(profile);
    }
}
