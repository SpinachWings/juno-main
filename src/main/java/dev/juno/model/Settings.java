package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** Everything in {@code ~/.juno/settings.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {
    public int version = 1;
    public SystemSettings system = new SystemSettings();
    public List<Key> keys = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<Profile> profiles = new ArrayList<>();
    public List<SkillPack> skillPacks = new ArrayList<>();
}
