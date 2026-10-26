package dev.juno.main.service;

import dev.juno.main.integration.IssueData;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;

import java.time.Instant;
import java.util.List;

/** Copies ticket info to and from GitHub/GitLab issues with the ticket's organisation's credentials. */
final class TrackerSync {

    private final BoardState state;
    private final BoardService service;

    TrackerSync(BoardState state, BoardService service) {
        this.state = state;
        this.service = service;
    }

    /** Suggested GitLab project / GitHub repo for a ticket: its existing link, else its project's setting. */
    public String defaultLocation(Ticket ticket, TrackerKind kind) {
        synchronized (state) {
            ExternalRef ref = ticket.getIssue();
            if (ref != null && ref.tracker() == kind) {
                return ref.location();
            }
            return state.projectOf(ticket).map(p -> p.location(kind)).orElse("");
    
        }
    }

    public List<IssueData> listIssues(String orgId, TrackerKind kind, String location) throws JunoException {
        Organisation org = state.requireOrganisation(orgId);
        try {
            return state.trackers.listIssues(org, kind, location, "open", 100);
        } catch (McpException e) {
            throw new JunoException(e.getMessage(), e);
        }
    }

    /**
     * Replaces the ticket's title and description with the remote issue's and links the issue. The issue is
     * read with the chosen project's organisation's credentials. The ticket is linked to the project in that
     * organisation whose GitLab project / GitHub repo matches the location, falling back to the chosen one.
     */
    public void importIssue(String ticketId, String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        Project chosen;
        Organisation org;
        synchronized (state) {
            chosen = state.settings.project(projectId)
                    .orElseThrow(() -> new JunoException("Choose the project to import into"));
            org = state.settings.organisationOf(chosen)
                    .orElseThrow(() -> new JunoException("Project '" + chosen.getName() + "' has no organisation"));
        }
        IssueData issue;
        try {
            issue = state.trackers.getIssue(org, kind, location, number, false);
        } catch (McpException e) {
            throw new JunoException("Import from " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        synchronized (state) {
            Ticket t = state.require(ticketId);
            Project target = state.settings.projectForLocation(org.getId(), kind, location).orElse(chosen);
            String previousOrg = state.organisationOf(t).map(Organisation::getId).orElse(null);
            t.setProjectId(target.getId());
            t.setTitle(issue.title());
            t.setDescription(issue.body());
            // Replaces any previous link (one issue per ticket; a link made with another organisation goes too).
            t.setIssue(new ExternalRef(kind, location.trim(), issue.number(), issue.url(), Instant.now().toString()));
            t.touch();
            state.persist();
        }
        state.fireChanged();
    }

    /** Creates a new Pending ticket from a remote issue. */
    public Ticket importAsNewTicket(String projectId, TrackerKind kind, String location, long number)
            throws JunoException {
        Ticket ticket = service.createTicket("Importing " + kind.shortName() + " #" + number + "…", "", projectId);
        try {
            importIssue(ticket.getId(), projectId, kind, location, number);
        } catch (JunoException e) {
            service.deleteTicket(ticket.getId());
            throw e;
        }
        return ticket;
    }

    /**
     * Uploads the ticket's title and description with its organisation's credentials: updates the linked
     * issue if there is one, otherwise creates an issue in {@code location} and links it.
     */
    public ExternalRef upload(String ticketId, TrackerKind kind, String location) throws JunoException {
        String title;
        String body;
        ExternalRef existing;
        Organisation org;
        synchronized (state) {
            Ticket t = state.require(ticketId);
            title = t.getTitle();
            body = t.getDescription();
            existing = t.getIssue() != null && t.getIssue().tracker() == kind ? t.getIssue() : null;
            org = state.organisationOf(t).orElseThrow(() -> new JunoException(
                    BoardState.name(t) + " has no project. Choose a project first: its organisation's "
                            + kind.displayName() + " credentials are used for the upload."));
            Project p = state.projectOf(t).orElse(null);
            if (p != null && p.host() != null && p.host() != kind) {
                throw new JunoException("Project '" + p.getName() + "' is on " + p.host().displayName()
                        + ", so its tickets sync with " + p.host().displayName() + " only.");
            }
        }
        if (title == null || title.isBlank()) {
            throw new JunoException("The ticket needs a title before it can be uploaded");
        }
        IssueData issue;
        String target = existing != null ? existing.location() : location;
        try {
            issue = existing != null
                    ? state.trackers.updateIssue(org, kind, existing.location(), existing.number(), title, body)
                    : state.trackers.createIssue(org, kind, location, title, body);
        } catch (McpException e) {
            throw new JunoException("Upload to " + kind.displayName() + " failed: " + e.getMessage(), e);
        }
        ExternalRef ref = new ExternalRef(kind, target.trim(), issue.number(), issue.url(), Instant.now().toString());
        synchronized (state) {
            Ticket t = state.require(ticketId);
            t.setIssue(ref);
            t.touch();
            state.persist();
        }
        state.fireChanged();
        return ref;
    }

    public void unlink(String ticketId) {
        synchronized (state) {
            state.find(ticketId).ifPresent(t -> {
                t.setIssue(null);
                t.touch();
            });
            state.persist();
        }
        state.fireChanged();
    }
}
