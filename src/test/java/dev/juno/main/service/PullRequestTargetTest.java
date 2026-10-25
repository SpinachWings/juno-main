package dev.juno.main.service;

import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestTargetTest {

    @Test
    void prefersLinkedTrackerThenGitHubThenGitLab() throws Exception {
        Organisation org = new Organisation("Acme");
        org.tracker(TrackerKind.GITHUB).setEnabled(true);
        org.tracker(TrackerKind.GITLAB).setEnabled(true);
        Project project = new Project();
        project.setGithubRepo("acme/web");
        project.setGitlabProject("acme/web");
        Ticket ticket = new Ticket("JUNO-1", "t", "");

        assertEquals(TrackerKind.GITHUB, BoardService.pullRequestTracker(ticket, project, org));

        ticket.setRef(TrackerKind.GITLAB, new ExternalRef("acme/web", 3, "", ""));
        assertEquals(TrackerKind.GITLAB, BoardService.pullRequestTracker(ticket, project, org));

        org.tracker(TrackerKind.GITLAB).setEnabled(false);
        assertEquals(TrackerKind.GITHUB, BoardService.pullRequestTracker(ticket, project, org));

        project.setGithubRepo("");
        boolean failed = false;
        try {
            BoardService.pullRequestTracker(ticket, project, org);
        } catch (JunoException e) {
            failed = true;
        }
        assertTrue(failed);
    }
}
