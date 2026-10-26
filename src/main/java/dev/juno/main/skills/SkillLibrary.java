package dev.juno.main.skills;

import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.SkillEntry;
import dev.juno.main.model.SkillScope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Juno's skill library ({@code ~/.juno/skills}). Skill packs are copied in when uploaded in Settings, and the
 * packs that apply to a ticket are copied into its worktree's {@code .claude/skills/} before each agent run, where
 * Claude Code picks them up. Those copies are excluded from git so they are never committed.
 *
 * <p>A pack is a folder holding one skill ({@code SKILL.md} at its root) or several ({@code <name>/SKILL.md}).
 * Uploads can be a {@code SKILL.md}/{@code .md} file, a folder, or a {@code .zip}/{@code .skill} archive.
 */
public final class SkillLibrary {

    /** A skill found inside a pack. */
    public record SkillInfo(String folderName, String name, String description, Path dir) {
    }

    /** Packs shipped with Juno and added at the orchestration (global) level on first start. */
    public record Bundled(String pack, String displayName, List<String> disabledByDefault) {
    }

    public static final List<Bundled> BUNDLED = List.of(
            // These two superpowers skills conflict with how Juno runs agents (Juno creates the worktree, pushes and
            // opens the PR; nothing merges without a human), so they start switched off.
            new Bundled("superpowers", "superpowers",
                    List.of("finishing-a-development-branch", "using-git-worktrees")));

    /**
     * Packs Juno used to ship and no longer does (the GitHub/GitLab tool guides: agents don't get those tools).
     * They are removed from the settings and the library on start-up.
     */
    static final List<String> RETIRED = List.of("juno-github", "juno-gitlab");

    /** Folder prefix for skills Juno copies into a worktree. */
    public static final String WORKTREE_PREFIX = "juno-";

    private static final String RESOURCE_ROOT = "/dev/juno/main/bundled-skills/";

    private final Path root;

    public SkillLibrary(Path junoHome) {
        this.root = junoHome.resolve("skills");
    }

    public Path root() {
        return root;
    }

    // ---- reading packs ----------------------------------------------------------------------

    /** The skills in a pack folder (the folder itself if it has a SKILL.md, else sub-folders up to 3 deep). */
    public static List<SkillInfo> scan(Path pack) {
        List<SkillInfo> found = new ArrayList<>();
        if (pack == null || !Files.isDirectory(pack)) {
            return found;
        }
        if (Files.isRegularFile(pack.resolve("SKILL.md"))) {
            found.add(read(pack));
            return found;
        }
        try (Stream<Path> files = Files.walk(pack, 4)) {
            files.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .map(Path::getParent)
                    .sorted()
                    .forEach(dir -> {
                        // skip skills nested inside another skill's folder
                        if (found.stream().noneMatch(s -> dir.startsWith(s.dir()))) {
                            found.add(read(dir));
                        }
                    });
        } catch (IOException e) {
            // unreadable pack: report as empty
        }
        return found;
    }

    private static SkillInfo read(Path dir) {
        String name = dir.getFileName().toString();
        String description = "";
        try (BufferedReader in = Files.newBufferedReader(dir.resolve("SKILL.md"), StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line != null && line.strip().equals("---")) {
                while ((line = in.readLine()) != null && !line.strip().equals("---")) {
                    if (line.startsWith("name:")) {
                        name = unquote(line.substring(5));
                    } else if (line.startsWith("description:")) {
                        description = unquote(line.substring(12));
                    }
                }
            }
        } catch (IOException ignored) {
            // keep defaults
        }
        return new SkillInfo(dir.getFileName().toString(), name, description, dir);
    }

    private static String unquote(String s) {
        String v = s.strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ---- importing --------------------------------------------------------------------------

    /**
     * Copies an uploaded skill (SKILL.md / .md file, folder, or .zip / .skill archive) into the library and
     * returns a new entry for it.
     */
    public SkillEntry importSkill(Path source, SkillScope scope, String scopeId) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IOException("Not found: " + source);
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String base = slug(stripExtension(source.getFileName().toString()));
        Path target = root.resolve("uploads").resolve(base + "-" + id);
        Files.createDirectories(target);
        try {
            String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isDirectory(source)) {
                copyTree(source, target);
            } else if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
                unzip(source, target);
            } else if (lower.endsWith(".md")) {
                Path skillDir = lower.equals("skill.md") && source.getParent() != null
                        ? target.resolve(slug(source.getParent().getFileName().toString()))
                        : target.resolve(base);
                Files.createDirectories(skillDir);
                Files.copy(source, skillDir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new IOException("Unsupported skill file: choose a SKILL.md (.md), a folder, or a .zip/.skill");
            }
            List<SkillInfo> skills = scan(target);
            if (skills.isEmpty()) {
                throw new IOException("No SKILL.md found in " + source.getFileName()
                        + ". A skill is a folder containing a SKILL.md file.");
            }
            SkillEntry entry = new SkillEntry(skills.size() == 1 ? skills.getFirst().name() : base,
                    target.toString(), "upload");
            entry.setDescription(describe(skills));
            entry.setScope(scope);
            entry.setScopeId(scope == SkillScope.GLOBAL ? null : scopeId);
            return entry;
        } catch (IOException e) {
            deleteTree(target);
            throw e;
        }
    }

    public static String describe(List<SkillInfo> skills) {
        if (skills.size() == 1) {
            return skills.getFirst().description();
        }
        return skills.size() + " skills: " + String.join(", ", skills.stream().map(SkillInfo::name).toList());
    }

    /** Deletes an uploaded pack's files (bundled packs are left alone). */
    public void deleteUpload(SkillEntry entry) {
        Path folder = Path.of(entry.getFolder()).toAbsolutePath().normalize();
        if (!entry.isBundled() && folder.startsWith(root.toAbsolutePath().normalize())) {
            deleteTree(folder);
        }
    }

    // ---- bundled packs ----------------------------------------------------------------------

    /**
     * Refreshes the bundled packs in the library from Juno's resources and, the first time each one is seen,
     * registers it at the global level. Returns true if settings changed.
     */
    public boolean installBundled(JunoSettings settings) {
        boolean changed = false;
        for (String retired : RETIRED) {
            changed |= settings.getSkills().removeIf(e -> e.getSource().equals("bundled:" + retired));
            changed |= settings.getBundledSkillsOffered().remove(retired);
            deleteTree(root.resolve("bundled").resolve(retired));
        }
        List<String> index = readIndex();
        for (Bundled b : BUNDLED) {
            Path target = root.resolve("bundled").resolve(b.pack());
            List<String> files = index.stream().filter(f -> f.startsWith(b.pack() + "/")).toList();
            if (files.isEmpty()) {
                continue;
            }
            try {
                deleteTree(target);
                for (String file : files) {
                    Path out = target.resolve(file.substring(b.pack().length() + 1));
                    Files.createDirectories(out.getParent());
                    try (InputStream in = SkillLibrary.class.getResourceAsStream(RESOURCE_ROOT + file)) {
                        if (in != null) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not install bundled skills '" + b.pack() + "': " + e.getMessage());
                continue;
            }
            String source = "bundled:" + b.pack();
            boolean present = settings.getSkills().stream().anyMatch(e -> e.getSource().equals(source));
            if (!present && !settings.getBundledSkillsOffered().contains(b.pack())) {
                SkillEntry entry = new SkillEntry(b.displayName(), target.toString(), source);
                entry.setDescription(describe(scan(target)));
                entry.setScope(SkillScope.GLOBAL);
                entry.getDisabledSkills().addAll(b.disabledByDefault());
                settings.getSkills().add(entry);
                settings.getBundledSkillsOffered().add(b.pack());
                changed = true;
            } else if (present) {
                // keep the folder path current (e.g. Juno home moved)
                settings.getSkills().stream().filter(e -> e.getSource().equals(source))
                        .forEach(e -> e.setFolder(target.toString()));
            }
        }
        return changed;
    }

    private static List<String> readIndex() {
        List<String> files = new ArrayList<>();
        try (InputStream in = SkillLibrary.class.getResourceAsStream(RESOURCE_ROOT + "index.txt")) {
            if (in == null) {
                return files;
            }
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.contains(".."))
                    .forEach(files::add);
        } catch (IOException e) {
            // no bundled skills
        }
        return files;
    }

    // ---- per-run materialisation -------------------------------------------------------------

    /**
     * Replaces Juno's skill copies in {@code <worktree>/.claude/skills/} with the given packs' enabled skills.
     *
     * @return the names of the skills made available
     */
    public static List<String> materialise(Path worktree, List<SkillEntry> packs) throws IOException {
        Path dir = worktree.resolve(".claude").resolve("skills");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> existing = Files.list(dir)) {
                for (Path p : existing.filter(p -> p.getFileName().toString().startsWith(WORKTREE_PREFIX)).toList()) {
                    deleteTree(p);
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (SkillEntry pack : packs) {
            for (SkillInfo skill : scan(Path.of(pack.getFolder()))) {
                if (pack.getDisabledSkills().contains(skill.folderName())) {
                    continue;
                }
                Path target = dir.resolve(WORKTREE_PREFIX + slug(pack.getName()) + "-" + slug(skill.folderName()));
                Files.createDirectories(target);
                copyTree(skill.dir(), target);
                names.add(skill.name());
            }
        }
        return names;
    }

    // ---- file helpers -----------------------------------------------------------------------

    static void unzip(Path zip, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("Unsafe path in archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else if (!e.getName().startsWith("__MACOSX/")) {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(from) && (name.equals(".git") || name.equals("node_modules"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            System.err.println("Could not delete " + path + ": " + e.getMessage());
        }
    }

    static String slug(String s) {
        String v = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return v.isEmpty() ? "skill" : v.length() > 40 ? v.substring(0, 40) : v;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
