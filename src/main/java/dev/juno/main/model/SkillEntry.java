package dev.juno.main.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A skill pack registered in Settings: a folder (copied into Juno's skill library) containing one or more
 * Claude skills ({@code <skill>/SKILL.md}). It applies everywhere, to one organisation or to one project, and
 * individual skills inside it can be switched off.
 */
public final class SkillEntry {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String description = "";
    private SkillScope scope = SkillScope.GLOBAL;
    /** Organisation or project id for non-global scopes. */
    private String scopeId;
    private boolean enabled = true;
    /** Folder in Juno's skill library holding the pack. */
    private String folder = "";
    /** Skill folder names inside the pack that are switched off. */
    private List<String> disabledSkills = new ArrayList<>();
    /** {@code upload} or {@code bundled:<pack>}. */
    private String source = "upload";

    public SkillEntry() {
    }

    public SkillEntry(String name, String folder, String source) {
        this.name = name;
        this.folder = folder;
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SkillScope getScope() {
        return scope == null ? SkillScope.GLOBAL : scope;
    }

    public void setScope(SkillScope scope) {
        this.scope = scope;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public List<String> getDisabledSkills() {
        if (disabledSkills == null) {
            disabledSkills = new ArrayList<>();
        }
        return disabledSkills;
    }

    public String getSource() {
        return source == null ? "upload" : source;
    }

    public boolean isBundled() {
        return getSource().startsWith("bundled:");
    }

    /** Whether this pack applies to a ticket in the given project. */
    public boolean appliesTo(Project project) {
        return switch (getScope()) {
            case GLOBAL -> true;
            case ORGANISATION -> project != null && scopeId != null && scopeId.equals(project.getOrgId());
            case PROJECT -> project != null && scopeId != null && scopeId.equals(project.getId());
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
