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
    void mcpServersOnlyGetTokensFromJuno() {
        Organisation org = new Organisation("Acme");
        Map<String, String> env = IssueTrackerService.environment(org, TrackerKind.GITHUB);
        // Blank token: set explicitly to "" so a GITHUB_TOKEN / GH_TOKEN in Juno's environment is never inherited.
        assertEquals("", env.get("GITHUB_TOKEN"));
        assertEquals("", env.get("GH_TOKEN"));
        assertEquals("https://api.github.com", env.get("GITHUB_API_URL"));
        assertFalse(IssueTrackerService.hasToken(org, TrackerKind.GITHUB));

        org.tracker(TrackerKind.GITLAB).setToken("glpat-123");
        org.tracker(TrackerKind.GITLAB).setApiUrl("https://git.acme.dev");
        env = IssueTrackerService.environment(org, TrackerKind.GITLAB);
        assertEquals("glpat-123", env.get("GITLAB_TOKEN"));
        assertEquals("https://git.acme.dev", env.get("GITLAB_URL"));
        assertTrue(IssueTrackerService.hasToken(org, TrackerKind.GITLAB));
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
