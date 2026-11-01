package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assignment and switches of one skill pack (design §5.5). The files live under {@code ~/.juno/skills}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillPack {
    public String id;
    public String name = "";
    public boolean bundled;
    /** Skill a bare {@code /activate<pack>} loads. */
    public String entrySkill = "";
    /** True = applies to the whole orchestration (every profile). */
    public boolean wholeOrchestration;
    /** Profiles it is assigned to when not whole-orchestration (user assignments; locked ones are in code). */
    public List<String> profiles = new ArrayList<>();
    /** Skills switched off for everyone. */
    public List<String> disabledSkills = new ArrayList<>();
    /** Skills switched off per profile name. */
    public Map<String, List<String>> disabledPerProfile = new HashMap<>();
}
