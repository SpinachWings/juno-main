package dev.juno.main.service;

import dev.juno.main.integration.IssueTrackerService;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestTargetTest {

    @Test
    void projectsHaveExactlyOneHost() {
        Project project = new Project();
        assertNull(project.host());
        project.setRepository(TrackerKind.GITHUB, " acme/web ");
        assertEquals(TrackerKind.GITHUB, project.host());
        assertEquals("acme/web", project.getGithubRepo());
        project.setRepository(TrackerKind.GITLAB, "acme/web");
        assertEquals(TrackerKind.GITLAB, project.host());
        assertEquals("", project.getGithubRepo());
        assertFalse(project.hasBothHosts());
    }

    @Test
    void settingsWithBothHostsKeepTheEnabledOne() {
        JunoSettings settings = new JunoSettings();
        Organisation org = new Organisation("Acme");
        org.tracker(TrackerKind.GITLAB).setEnabled(true);
        settings.getOrganisations().add(org);
        Project p = new Project();
        p.setOrgId(org.getId());
        p.setGithubRepo("acme/web");
        p.setGitlabProject("acme/web-gl");
        settings.getProjects().add(p);

        List<String> notes = settings.normalise();
        assertEquals(1, notes.size());
        assertEquals(TrackerKind.GITLAB, p.host());
        assertFalse(p.hasBothHosts());
        assertTrue(settings.normalise().isEmpty());
    }

    @Test
    void pullRequestsGoToTheProjectsHost() throws Exception {
        Organisation org = new Organisation("Acme");
        org.tracker(TrackerKind.GITHUB).setEnabled(true);
        Project project = new Project();
        project.setRepository(TrackerKind.GITHUB, "acme/web");
        Ticket ticket = new Ticket("a", "t", "");
        assertEquals(TrackerKind.GITHUB, BoardService.pullRequestTracker(ticket, project, org));

        project.setRepository(TrackerKind.GITLAB, "acme/web");
        assertFails(() -> BoardService.pullRequestTracker(ticket, project, org)); // GitLab not enabled
        project.setRepository(null, "");
        assertFails(() -> BoardService.pullRequestTracker(ticket, project, org)); // no repository
    }

    @Test
    void credentialsTravelInRequestHeadersFromJunosSettingsOnly() {
        Organisation org = new Organisation("Acme");
        // No token: no Authorization header, so the server can read public data only.
        assertFalse(IssueTrackerService.headers(org, TrackerKind.GITHUB).containsKey("Authorization"));
        assertFalse(IssueTrackerService.hasToken(org, TrackerKind.GITHUB));

        org.tracker(TrackerKind.GITLAB).setToken("glpat-123");
        org.tracker(TrackerKind.GITLAB).setApiUrl("https://git.acme.dev");
        Map<String, String> headers = IssueTrackerService.headers(org, TrackerKind.GITLAB);
        assertEquals("Bearer glpat-123", headers.get("Authorization"));
        assertEquals("https://git.acme.dev", headers.get("X-Api-Url"));
        assertTrue(IssueTrackerService.hasToken(org, TrackerKind.GITLAB));
    }

    @Test
    void mcpPortsDefaultTo9950And9951() {
        JunoSettings s = new JunoSettings();
        assertEquals(9950, s.mcpPort(TrackerKind.GITHUB));
        assertEquals(9951, s.mcpPort(TrackerKind.GITLAB));
        assertEquals(dev.juno.main.model.McpLaunch.JAR, s.mcpLaunch(TrackerKind.GITHUB));
        assertEquals("http://127.0.0.1:9951/mcp",
                dev.juno.main.integration.McpServers.endpoint(s, TrackerKind.GITLAB).toString());
        // Nothing machine-specific is stored by default: blank jars and design doc folder resolve at run time.
        assertEquals("", s.mcpJar(TrackerKind.GITHUB));
        assertEquals("", s.getDesignDocFolder());
        assertTrue(s.resolvedMcpJar(TrackerKind.GITHUB).endsWith("juno-github-mcp.jar"));
        s.setMcpJar(TrackerKind.GITLAB, JunoSettings.defaultJar("juno-gitlab-mcp"));
        s.setDesignDocFolder(JunoSettings.defaultDesignDocFolder());
        assertTrue(s.clearAutomaticDefaults());
        assertEquals("", s.mcpJar(TrackerKind.GITLAB));
        assertEquals("", s.getDesignDocFolder());
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void assertFails(Call call) {
        try {
            call.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("expected a failure");
    }
}
