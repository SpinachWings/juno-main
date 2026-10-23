package dev.juno.main.store;

import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JunoStoreTest {

    @TempDir
    Path home;

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

        var settings = new JunoSettings();
        var p = new Project();
        p.setName("Demo");
        p.setGitlabProject("group/demo");
        settings.getProjects().add(p);
        settings.getAgent().setModel("claude-haiku-5-5");
        settings.tracker(TrackerKind.GITLAB).setToken("secret");
        store.saveSettings(settings);

        var reopened = new JunoStore(home);
        var loaded = reopened.loadBoard().getTickets().getFirst();
        assertEquals("Body\nwith lines", loaded.getDescription());
        assertEquals(TicketStatus.AWAITING_DECISION, loaded.getStatus());
        assertEquals(12, loaded.getRef(TrackerKind.GITHUB).number());
        assertNull(loaded.getRef(TrackerKind.GITLAB));
        assertEquals(2, reopened.loadBoard().getNextTicketNumber());

        var s = reopened.loadSettings();
        assertEquals("claude-haiku-5-5", s.getAgent().getModel());
        assertEquals("secret", s.tracker(TrackerKind.GITLAB).getToken());
        assertEquals(p.getId(), s.projectForLocation(TrackerKind.GITLAB, "GROUP/demo").orElseThrow().getId());
    }
}
