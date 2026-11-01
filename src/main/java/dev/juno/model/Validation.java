package dev.juno.model;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Save-time checks for the Settings tabs. Each returns human-readable problems; empty = valid. */
public final class Validation {
    private static final Pattern REMOTE = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+(:\\d+)?");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z][a-z0-9]*");

    private Validation() {}

    public static List<String> project(Project p, List<Project> others) {
        List<String> errors = new ArrayList<>();
        if (p.name == null || p.name.isBlank()) errors.add("Give the project a name.");
        else if (others.stream().anyMatch(o -> o != p && !o.id.equals(p.id) && o.name.equalsIgnoreCase(p.name.trim())))
            errors.add("Another project is already called " + p.name.trim() + ".");
        if (p.folder == null || p.folder.isBlank()) errors.add("Choose the repository folder.");
        else {
            try {
                if (!Files.isDirectory(Path.of(p.folder.trim()))) errors.add("The folder doesn't exist: " + p.folder.trim());
            } catch (InvalidPathException e) {
                errors.add("The folder path isn't valid: " + p.folder.trim());
            }
        }
        if (p.remote != null && !p.remote.isBlank() && !REMOTE.matcher(p.remote.trim()).matches())
            errors.add("The remote must look like owner/repo (GitHub) or group/project (GitLab).");
        return errors;
    }

    public static List<String> key(Key k, List<Key> others, boolean tokenGiven, String envName) {
        List<String> errors = new ArrayList<>();
        if (k.label == null || k.label.isBlank()) errors.add("Give the key a label.");
        else if (others.stream().anyMatch(o -> !o.id.equals(k.id) && o.label.equalsIgnoreCase(k.label.trim())))
            errors.add("Another key is already called " + k.label.trim() + ".");
        if (k.host == null || !HOST.matcher(k.host.trim()).matches()) errors.add("Enter a host such as " + k.kind.defaultHost + ".");
        if (envName != null && !envName.isBlank() && !ENV_NAME.matcher(envName.trim()).matches())
            errors.add("Environment variable names use letters, digits and underscores.");
        boolean hasToken = tokenGiven || (envName != null && !envName.isBlank())
                || (k.token != null && !k.token.isBlank());
        if (!hasToken) errors.add("Enter a token (or the name of an environment variable that holds it).");
        boolean oneIdentity = (k.gitName == null || k.gitName.isBlank()) == (k.gitEmail == null || k.gitEmail.isBlank());
        if (!oneIdentity) errors.add("Set both the git author name and email, or neither.");
        if (k.mcpUrlOverride != null && !k.mcpUrlOverride.isBlank() && !k.mcpUrlOverride.trim().startsWith("http"))
            errors.add("The MCP URL override must start with http:// or https://.");
        return errors;
    }

    public static List<String> profile(Profile p, List<Profile> others) {
        List<String> errors = new ArrayList<>();
        if (p.name == null || p.name.isBlank()) errors.add("Give the profile a name.");
        else if (others.stream().anyMatch(o -> o != p && o.name.equalsIgnoreCase(p.name.trim())))
            errors.add("Another profile is already called " + p.name.trim() + ".");
        if (p.maxTurns < 1) errors.add("Max turns must be at least 1.");
        if (p.spendLimit != null && p.spendLimit <= 0) errors.add("The spend limit must be above 0, or blank for none.");
        for (ProfileCommand c : p.commands) {
            if (!COMMAND_NAME.matcher(c.name == null ? "" : c.name).matches())
                errors.add("Command names are lower-case letters and digits, e.g. checkforcomments.");
            else if (p.commands.stream().filter(o -> o.name.equals(c.name)).count() > 1)
                errors.add("The command /" + c.name + " appears twice.");
        }
        return errors;
    }

    public static List<String> system(SystemSettings s) {
        List<String> errors = new ArrayList<>();
        if (s.freshStartThreshold < 1 || s.freshStartThreshold > 100) errors.add("Fresh-start threshold is 1–100 %.");
        if (s.reportLength < 100) errors.add("Report length must be at least 100 characters.");
        if (s.hopLimit < 1) errors.add("Hop limit must be at least 1.");
        if (s.pipelineWaitLimitMinutes < 1) errors.add("Pipeline wait limit must be at least 1 minute.");
        if (s.maxAgentsWorking < 1) errors.add("Max agents working at once must be at least 1.");
        if (s.idleTimeoutMinutes < 1) errors.add("Idle process timeout must be at least 1 minute.");
        return errors;
    }
}
