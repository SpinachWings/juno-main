package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** An agent profile (design §5.4). Fields locked on built-ins are re-applied from {@code BuiltInProfiles} on load. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {
    public String name = "";
    public boolean builtIn;
    public String provider = "claude";
    public String executablePath = "";
    public String model = "";
    /** low, medium, high, xhigh, max or blank for the CLI default. */
    public String effort = "";
    public String permissionMode = "default";
    public String systemPrompt = "";
    public List<String> allowedTools = new ArrayList<>();
    public List<String> disallowedTools = new ArrayList<>();
    public int maxTurns = 150;
    /** USD per process; null = none. */
    public Double spendLimit;
    public boolean needsProjectFolder;
    public boolean editsFiles;
    public List<ProfileCommand> commands = new ArrayList<>();

    public static final List<String> PERMISSION_MODES =
            List.of("default", "acceptEdits", "auto", "dontAsk", "plan", "bypassPermissions");
    public static final List<String> EFFORTS = List.of("", "low", "medium", "high", "xhigh", "max");

    /** Fully editable copy (design §5.4 "Duplicate"). */
    public Profile duplicate(String newName) {
        Profile p = new Profile();
        p.name = newName;
        p.builtIn = false;
        p.provider = provider; p.executablePath = executablePath; p.model = model; p.effort = effort;
        p.permissionMode = permissionMode; p.systemPrompt = systemPrompt;
        p.allowedTools = new ArrayList<>(allowedTools);
        p.disallowedTools = new ArrayList<>(disallowedTools);
        p.maxTurns = maxTurns; p.spendLimit = spendLimit;
        p.needsProjectFolder = needsProjectFolder; p.editsFiles = editsFiles;
        for (ProfileCommand c : commands) p.commands.add(new ProfileCommand(c.name, c.template, false));
        return p;
    }
}
