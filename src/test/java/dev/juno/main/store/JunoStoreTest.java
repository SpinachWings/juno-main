package dev.juno.main.store;

import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunoStoreTest {

    @TempDir
    Path home;

    @Test
    void freshInstallHasDefaultOrganisationAndAgent() {
        var s = new JunoStore(home).loadSettings();
        assertEquals(1, s.getOrganisations().size());
        assertEquals(1, s.getAgentProfiles().size());
        assertEquals(s.getAgentProfiles().getFirst().getId(), s.getOrganisations().getFirst().getAgentProfileId());
    }

    @Test
    void roundTripsBoardAndSettings() {
        var store = new JunoStore(home);

        var board = store.loadBoard();
        var t = new Ticket("JUNO-1", "Title", "Body\nwith lines");
        t.setStatus(TicketStatus.AWAITING_DECISION);
        t.setRef(TrackerKind.GITHUB, new ExternalRef("octo/repo", 12, "https://github.com/octo/repo/issues/12", "now"));
        board.getTickets().add(t);
        board.setNextTicketNumber(2);
        store.saveBoard(board);

        var settings = JunoSettings.createDefault();
        Organisation acme = new Organisation("Acme");
        acme.tracker(TrackerKind.GITLAB).setEnabled(true);
        acme.tracker(TrackerKind.GITLAB).setToken("secret");
        settings.getOrganisations().add(acme);
        var p = new Project();
        p.setName("Demo");
        p.setOrgId(acme.getId());
        p.setGitlabProject("group/demo");
        settings.getProjects().add(p);
        settings.getAgentProfiles().getFirst().setModel("claude-haiku-5-5");
        store.saveSettings(settings);

        var reopened = new JunoStore(home);
        var loaded = reopened.loadBoard().getTickets().getFirst();
        assertEquals("Body\nwith lines", loaded.getDescription());
        assertEquals(TicketStatus.AWAITING_DECISION, loaded.getStatus());
        assertEquals(12, loaded.getRef(TrackerKind.GITHUB).number());
        assertNull(loaded.getRef(TrackerKind.GITLAB));
        assertEquals(2, reopened.loadBoard().getNextTicketNumber());

        var s = reopened.loadSettings();
        assertEquals("claude-haiku-5-5", s.getAgentProfiles().getFirst().getModel());
        Organisation acmeLoaded = s.organisation(acme.getId()).orElseThrow();
        assertEquals("secret", acmeLoaded.tracker(TrackerKind.GITLAB).getToken());
        assertTrue(acmeLoaded.tracker(TrackerKind.GITLAB).isEnabled());
        assertFalse(acmeLoaded.tracker(TrackerKind.GITHUB).isEnabled());
        assertEquals(p.getId(), s.projectForLocation(acme.getId(), TrackerKind.GITLAB, "GROUP/demo").orElseThrow().getId());
        // Repo matching is scoped to the organisation.
        String otherOrg = s.getOrganisations().getFirst().getId();
        assertTrue(s.projectForLocation(otherOrg, TrackerKind.GITLAB, "group/demo").isEmpty());
    }

    @Test
    void migratesOldReviewColumns() throws Exception {
        Files.writeString(home.resolve("board.json"), """
                {"nextTicketNumber": 4, "tickets": [
                  {"id": "JUNO-1", "title": "a", "status": "AI_REVIEW"},
                  {"id": "JUNO-2", "title": "b", "status": "HUMAN_REVIEW"},
                  {"id": "JUNO-3", "title": "c", "status": "DONE"}]}
                """);
        var tickets = new JunoStore(home).loadBoard().getTickets();
        assertEquals(TicketStatus.REVIEW, tickets.get(0).getStatus());
        assertEquals(TicketStatus.REVIEW, tickets.get(1).getStatus());
        assertEquals(TicketStatus.DONE, tickets.get(2).getStatus());
        assertTrue(Files.exists(home.resolve("board.pre-review-columns.json")));
    }

    @Test
    void migratesSingleOrganisationSettings() throws Exception {
        Files.writeString(home.resolve("settings.json"), """
                {
                  "projects": [{"id": "p1", "name": "Web", "path": "/src/web", "color": "#123456",
                                "gitlabProject": "acme/web", "githubRepo": ""}],
                  "agent": {"cliPath": "claude", "model": "claude-opus-5-5", "permissionMode": "acceptEdits",
                            "allowedTools": "Bash(mvn test:*)", "maxTurns": 40, "apiKey": "", "baseUrl": "",
                            "extraArgs": "", "exposeTrackerMcp": true, "maxConcurrentAgents": 5},
                  "gitlab": {"apiUrl": "https://gitlab.acme.dev", "token": "glpat-1", "mcpJar": "/jars/gl.jar"},
                  "github": {"apiUrl": "https://api.github.com", "token": "", "mcpJar": "/jars/gh.jar"},
                  "javaExecutable": ""
                }
                """);
        JunoSettings s = new JunoStore(home).loadSettings();

        assertEquals(1, s.getOrganisations().size());
        Organisation org = s.getOrganisations().getFirst();
        assertEquals("Default", org.getName());
        assertTrue(org.tracker(TrackerKind.GITLAB).isEnabled());
        assertEquals("https://gitlab.acme.dev", org.tracker(TrackerKind.GITLAB).getApiUrl());
        assertEquals("glpat-1", org.tracker(TrackerKind.GITLAB).getToken());
        assertEquals("/jars/gl.jar", s.mcpJar(TrackerKind.GITLAB));
        assertEquals("/jars/gh.jar", s.mcpJar(TrackerKind.GITHUB));

        AgentProfile agent = s.getAgentProfiles().getFirst();
        assertEquals("claude-opus-5-5", agent.getModel());
        assertEquals(40, agent.getMaxTurns());
        assertTrue(agent.isExposeTrackerMcp());
        assertEquals(agent.getId(), org.getAgentProfileId());
        assertEquals(5, s.getMaxConcurrentAgents());

        Project web = s.project("p1").orElseThrow();
        assertEquals(org.getId(), web.getOrgId());
        assertTrue(Files.exists(home.resolve("settings.pre-orgs.json")));
    }
}
