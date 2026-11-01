package dev.juno.skills;

import dev.juno.model.Settings;
import dev.juno.model.SkillPack;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The skill library under {@code ~/.juno/skills} (design §5.5). A pack is a folder {@code <pack>/skills/<skill>/SKILL.md}.
 * Bundled packs live in {@code skills/bundled} and are refreshed from the app's copy on start-up; imported packs
 * live in {@code skills/library}.
 */
public final class SkillLibrary {
    private static final String BUNDLED_RESOURCE = "/dev/juno/skills/bundled/";
    private static final long MAX_UNZIPPED_BYTES = 100L * 1024 * 1024;
    private static final Pattern NAME_LINE = Pattern.compile("(?m)^name:\\s*[\"']?([^\"'\\r\\n]+?)[\"']?\\s*$");

    private final JunoHome home;

    public SkillLibrary(JunoHome home) {
        this.home = home;
    }

    public Path packDir(SkillPack pack) {
        return (pack.bundled ? home.bundledSkillsDir() : home.librarySkillsDir()).resolve(pack.id);
    }

    /** Skill names in a pack (folders under {@code skills/} holding a SKILL.md), sorted. */
    public List<String> skillsOf(SkillPack pack) {
        Path skills = packDir(pack).resolve("skills");
        if (!Files.isDirectory(skills)) return List.of();
        try (Stream<Path> s = Files.list(skills)) {
            return s.filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                    .map(d -> d.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- bundled packs ----

    /** Copies Juno's bundled packs into {@code skills/bundled} (replacing old copies) and registers them. */
    public void refreshBundled(Settings settings) throws IOException {
        for (BundledPacks.Bundled b : BundledPacks.ALL) {
            Path target = home.bundledSkillsDir().resolve(b.id());
            deleteTree(target);
            Files.createDirectories(target);
            copyResourceTree(BUNDLED_RESOURCE + b.id(), target);
            SkillPack pack = settings.skillPacks.stream().filter(p -> p.bundled && p.id.equals(b.id()))
                    .findFirst().orElse(null);
            if (pack == null) {
                pack = new SkillPack();
                pack.id = b.id();
                settings.skillPacks.add(pack);
            }
            pack.name = b.name();
            pack.bundled = true;
            pack.entrySkill = b.entrySkill();
            pack.wholeOrchestration = false; // locked assignments are in code; user assignments are additive
        }
    }

    private static void copyResourceTree(String resourceDir, Path target) throws IOException {
        URI uri;
        try {
            var url = SkillLibrary.class.getResource(resourceDir);
            if (url == null) throw new IOException("Bundled skills are missing from the app: " + resourceDir);
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if ("jar".equals(uri.getScheme())) {
            try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
                copyTree(fs.getPath(resourceDir), target);
            }
        } else {
            copyTree(Path.of(uri), target);
        }
    }

    // ---- import ----

    /**
     * Imports a SKILL.md file, a folder of skills, or a .zip / .skill archive as a new pack.
     * A pack with a name already in use gets a numbered id.
     */
    public SkillPack importPack(Path source, Settings settings) throws IOException {
        if (!Files.exists(source)) throw new IOException("Not found: " + source);
        Path temp = Files.createTempDirectory("juno-skill-import");
        try {
            Path root;
            String packName;
            String fileName = source.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (Files.isDirectory(source)) {
                root = source;
                packName = fileName;
            } else if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
                unzip(source, temp);
                root = soleChildDir(temp);
                packName = fileName.substring(0, fileName.lastIndexOf('.'));
            } else if (lower.endsWith(".md")) {
                String name = skillName(Files.readString(source), null);
                Path one = temp.resolve(sanitise(name));
                Files.createDirectories(one);
                Files.copy(source, one.resolve("SKILL.md"));
                root = temp;
                packName = name;
            } else {
                throw new IOException("Import a SKILL.md, a folder of skills, or a .zip / .skill file.");
            }

            List<Path> skillDirs = findSkillDirs(root);
            if (skillDirs.isEmpty()) throw new IOException("No SKILL.md found in " + fileName + ".");

            String id = uniqueId(settings, slug(packName));
            Path target = home.librarySkillsDir().resolve(id);
            deleteTree(target);
            Path skillsOut = target.resolve("skills");
            Files.createDirectories(skillsOut);
            for (Path dir : skillDirs) {
                String name = skillName(Files.readString(dir.resolve("SKILL.md")), dir.getFileName().toString());
                Path out = skillsOut.resolve(sanitise(name));
                if (Files.exists(out)) throw new IOException("Two skills are both called " + name + ".");
                copyTree(dir, out);
            }

            SkillPack pack = new SkillPack();
            pack.id = id;
            pack.name = packName;
            List<String> names = skillsOfDir(skillsOut);
            pack.entrySkill = names.contains("using-" + id) ? "using-" + id : names.get(0);
            pack.wholeOrchestration = true;
            settings.skillPacks.add(pack);
            return pack;
        } finally {
            deleteTree(temp);
        }
    }

    public void remove(SkillPack pack, Settings settings) throws IOException {
        if (pack.bundled) throw new IOException("Bundled packs can't be removed.");
        deleteTree(packDir(pack));
        settings.skillPacks.remove(pack);
    }

    private static List<String> skillsOfDir(Path skills) throws IOException {
        try (Stream<Path> s = Files.list(skills)) {
            return s.map(d -> d.getFileName().toString()).sorted().toList();
        }
    }

    /** Folders that directly hold a SKILL.md: the root itself, or its children, or those under {@code skills/}. */
    static List<Path> findSkillDirs(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve("SKILL.md"))) return List.of(root);
        Path base = Files.isDirectory(root.resolve("skills")) ? root.resolve("skills") : root;
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(d -> Files.isRegularFile(d.resolve("SKILL.md"))).sorted().toList();
        }
    }

    private static Path soleChildDir(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> kids = s.toList();
            if (kids.size() == 1 && Files.isDirectory(kids.get(0)) && !Files.exists(kids.get(0).resolve("SKILL.md"))
                    && findSkillDirsQuiet(kids.get(0))) return kids.get(0);
        }
        return dir;
    }

    private static boolean findSkillDirsQuiet(Path d) {
        try {
            return !findSkillDirs(d).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    static String skillName(String skillMd, String fallback) {
        Matcher m = NAME_LINE.matcher(skillMd);
        if (m.find()) return m.group(1).trim();
        if (fallback != null) return fallback;
        throw new IllegalArgumentException("SKILL.md has no name: line.");
    }

    static String sanitise(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "-");
        return s.isBlank() || s.equals(".") || s.equals("..") ? "skill" : s;
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "pack" : s;
    }

    private static String uniqueId(Settings settings, String base) {
        List<String> used = settings.skillPacks.stream().map(p -> p.id).toList();
        String candidate = base;
        for (int i = 2; used.contains(candidate); i++) candidate = base + "-" + i;
        return candidate;
    }

    private static void unzip(Path zip, Path into) throws IOException {
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = into.resolve(e.getName()).normalize();
                if (!out.startsWith(into)) throw new IOException("The archive has an unsafe path: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var o = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNZIPPED_BYTES) throw new IOException("The archive is too large.");
                        o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = Files.newInputStream(p)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) {
            all = new ArrayList<>(walk.toList());
        }
        all.sort(Comparator.reverseOrder());
        for (Path p : all) Files.deleteIfExists(p);
    }
}
