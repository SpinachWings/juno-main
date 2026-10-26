package dev.juno.main.skills;

import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.SkillEntry;
import dev.juno.main.model.SkillScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLibraryTest {

    @TempDir
    Path tmp;

    private static final String SKILL = """
            ---
            name: tidy-code
            description: "Keeps code tidy"
            ---
            # Tidy
            """;

    @Test
    void importsMarkdownFolderAndZip() throws Exception {
        var lib = new SkillLibrary(tmp.resolve("home"));

        Path md = tmp.resolve("tidy.md");
        Files.writeString(md, SKILL);
        SkillEntry fromMd = lib.importSkill(md, SkillScope.GLOBAL, null);
        assertEquals("tidy-code", fromMd.getName());
        assertEquals("Keeps code tidy", fromMd.getDescription());

        Path pack = tmp.resolve("pack");
        Files.createDirectories(pack.resolve("a"));
        Files.createDirectories(pack.resolve("b/refs"));
        Files.writeString(pack.resolve("a/SKILL.md"), SKILL.replace("tidy-code", "alpha"));
        Files.writeString(pack.resolve("b/SKILL.md"), SKILL.replace("tidy-code", "beta"));
        Files.writeString(pack.resolve("b/refs/notes.md"), "notes");
        SkillEntry fromFolder = lib.importSkill(pack, SkillScope.PROJECT, "p1");
        assertEquals(2, SkillLibrary.scan(Path.of(fromFolder.getFolder())).size());
        assertEquals("p1", fromFolder.getScopeId());

        Path zip = tmp.resolve("z.skill");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("zipped/SKILL.md"));
            out.write(SKILL.replace("tidy-code", "zipped").getBytes());
            out.closeEntry();
        }
        assertEquals("zipped", lib.importSkill(zip, SkillScope.GLOBAL, null).getName());

        Path evil = tmp.resolve("evil.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(evil))) {
            out.putNextEntry(new ZipEntry("../../escape/SKILL.md"));
            out.write(SKILL.getBytes());
            out.closeEntry();
        }
        boolean refused = false;
        try {
            lib.importSkill(evil, SkillScope.GLOBAL, null);
        } catch (IOException e) {
            refused = e.getMessage().contains("Unsafe path");
        }
        assertTrue(refused);

        Path notSkill = tmp.resolve("empty");
        Files.createDirectories(notSkill);
        boolean rejected = false;
        try {
            lib.importSkill(notSkill, SkillScope.GLOBAL, null);
        } catch (IOException e) {
            rejected = e.getMessage().contains("No SKILL.md");
        }
        assertTrue(rejected);
    }

    @Test
    void scopesAndMaterialisation() throws Exception {
        var lib = new SkillLibrary(tmp.resolve("home"));
        JunoSettings s = new JunoSettings();
        Organisation acme = new Organisation("Acme");
        Organisation other = new Organisation("Other");
        s.getOrganisations().addAll(List.of(acme, other));
        Project web = new Project();
        web.setOrgId(acme.getId());
        Project api = new Project();
        api.setOrgId(other.getId());
        s.getProjects().addAll(List.of(web, api));

        Path pack = tmp.resolve("pack");
        Files.createDirectories(pack.resolve("a"));
        Files.createDirectories(pack.resolve("b"));
        Files.writeString(pack.resolve("a/SKILL.md"), SKILL.replace("tidy-code", "alpha"));
        Files.writeString(pack.resolve("b/SKILL.md"), SKILL.replace("tidy-code", "beta"));
        SkillEntry global = lib.importSkill(pack, SkillScope.GLOBAL, null);
        global.getDisabledSkills().add("b");
        SkillEntry orgOnly = lib.importSkill(pack, SkillScope.ORGANISATION, acme.getId());
        orgOnly.setName("Acme pack");
        SkillEntry projectOnly = lib.importSkill(pack, SkillScope.PROJECT, api.getId());
        SkillEntry off = lib.importSkill(pack, SkillScope.GLOBAL, null);
        off.setEnabled(false);
        s.getSkills().addAll(List.of(global, orgOnly, projectOnly, off));

        assertEquals(List.of(global, orgOnly), s.skillsFor(web));
        assertEquals(List.of(global, projectOnly), s.skillsFor(api));

        Path worktree = tmp.resolve("wt");
        Files.createDirectories(worktree.resolve(".claude/skills/juno-stale"));
        Files.createDirectories(worktree.resolve(".claude/skills/repo-own"));
        List<String> names = SkillLibrary.materialise(worktree, s.skillsFor(web));
        assertEquals(List.of("alpha", "alpha", "beta"), names);
        Path dir = worktree.resolve(".claude/skills");
        assertFalse(Files.exists(dir.resolve("juno-stale")));          // Juno's old copies are replaced
        assertTrue(Files.exists(dir.resolve("repo-own")));              // the repository's own skills are kept
        assertTrue(Files.exists(dir.resolve("juno-pack-a/SKILL.md")));
        assertFalse(Files.exists(dir.resolve("juno-pack-b")));          // switched off in the global pack
        assertTrue(Files.exists(dir.resolve("juno-acme-pack-b/SKILL.md")));
    }

    @Test
    void installsBundledPacksOnce() {
        var lib = new SkillLibrary(tmp.resolve("home"));
        JunoSettings s = new JunoSettings();
        assertTrue(lib.installBundled(s));
        List<String> sources = s.getSkills().stream().map(SkillEntry::getSource).toList();
        assertTrue(sources.containsAll(List.of("bundled:superpowers", "bundled:juno-github", "bundled:juno-gitlab")),
                sources.toString());
        SkillEntry superpowers = s.getSkills().stream()
                .filter(e -> e.getSource().equals("bundled:superpowers")).findFirst().orElseThrow();
        assertEquals(SkillScope.GLOBAL, superpowers.getScope());
        assertTrue(superpowers.getDisabledSkills().contains("finishing-a-development-branch"));
        int count = SkillLibrary.scan(Path.of(superpowers.getFolder())).size();
        assertEquals(15, count);

        // Removing a bundled pack keeps it removed.
        s.getSkills().remove(superpowers);
        lib.installBundled(s);
        assertFalse(s.getSkills().stream().anyMatch(e -> e.getSource().equals("bundled:superpowers")));
    }
}
