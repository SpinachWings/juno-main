package dev.juno.main.service;

import dev.juno.main.git.GitWorkspace;
import dev.juno.main.integration.IssueTrackerService;
import dev.juno.main.mcp.McpException;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a ticket can run before anything is touched: project, folder, repository host, integration and token,
 * base branch, agent and capacity, and (on GitHub/GitLab) that the repository and base branch exist. Also
 * picks branch names no other open ticket uses.
 */
final class Preflight {

    private final BoardState state;

    Preflight(BoardState state) {
        this.state = state;
    }

    /**
     * Checks everything a run needs before anything is touched, reporting every missing piece at once: a title,
     * a project (required) with a git folder and exactly one repository host, the organisation's integration and
     * token for that host, a base branch and an agent. With {@code checkRemote}, it then asks the host that the
     * repository exists (and the token can see it) and that the base branch exists there.
     */
    void check(String ticketId, boolean checkRemote) throws JunoException {
        Project project;
        Organisation org;
        String base;
        List<String> problems = new ArrayList<>();
        synchronized (state) {
            Ticket t = state.require(ticketId);
            project = state.settings.project(t.getProjectId()).orElse(null);
            if (project == null) {
                throw new JunoException(BoardState.name(t) + " has no project. Tickets must have a project to run: open the "
                        + "ticket's Details and choose one.");
            }
            org = state.settings.organisationOf(project).orElse(null);
            base = t.getBaseBranch() == null ? "" : t.getBaseBranch().strip();
            String where = "Settings → Organisations & projects";
            if (t.getTitle() == null || t.getTitle().isBlank()) {
                problems.add("The ticket has no title. Give it one in Details.");
            }
            String path = project.getPath() == null ? "" : project.getPath().strip();
            if (path.isEmpty()) {
                problems.add("Project '" + project.getName() + "' has no local folder. Set it in " + where + ".");
            } else if (!Files.isDirectory(Path.of(path))) {
                problems.add("Project '" + project.getName() + "' points to " + path + ", which doesn't exist. "
                        + "Fix the folder in " + where + ".");
            } else if (!Files.exists(Path.of(path).resolve(".git"))) {
                problems.add("Project '" + project.getName() + "' folder " + path + " is not a git repository.");
            }
            TrackerKind host = project.host();
            if (org == null) {
                problems.add("Project '" + project.getName() + "' isn't in an organisation. Move it into one in "
                        + where + ".");
            }
            if (project.hasBothHosts()) {
                problems.add("Project '" + project.getName() + "' has both a GitHub repo and a GitLab project. A "
                        + "project lives on one host only: remove one in " + where + ".");
            } else if (host == null) {
                problems.add("Project '" + project.getName() + "' has no repository. Set its GitHub repo or "
                        + "GitLab project in " + where + ".");
            } else if (org != null) {
                if (!org.tracker(host).isEnabled()) {
                    problems.add(host.displayName() + " isn't enabled for organisation '" + org.getName()
                            + "'. Enable it and add a token in " + where + ".");
                } else if (!IssueTrackerService.hasToken(org, host)) {
                    String token = org.tracker(host).getToken() == null ? "" : org.tracker(host).getToken().strip();
                    problems.add("Organisation '" + org.getName() + "' has no " + host.displayName() + " token"
                            + (token.startsWith("env:") ? " (the environment variable " + token.substring(4)
                            + " it refers to isn't set)" : "") + ". Add one in " + where + ".");
                }
            }
            if (base.isEmpty()) {
                problems.add("The ticket has no base branch. Set one in Details (for example main).");
            }
            String branch = t.getBranchName() == null ? "" : t.getBranchName().strip();
            if (!branch.isEmpty()) {
                if (branch.equals(base)) {
                    problems.add("The ticket's branch and base branch are both '" + base + "'. Use a separate "
                            + "branch for the work.");
                }
                branchOwner(t, branch).ifPresent(o -> problems.add("Branch '" + branch + "' is already used by "
                        + BoardState.name(o) + ". Choose another branch name in Details."));
            }
            if (state.settings.resolveAgent(t).isEmpty()) {
                problems.add("No agent is assigned to the ticket, project '" + project.getName() + "'"
                        + (org == null ? "" : " or organisation '" + org.getName() + "'")
                        + ". Assign one in the ticket's Details or in Settings.");
            }
        }
        if (!problems.isEmpty()) {
            throw new JunoException(problems.size() == 1 ? problems.getFirst()
                    : "This ticket can't run yet:\n- " + String.join("\n- ", problems));
        }
        if (!checkRemote) {
            return;
        }
        TrackerKind host = project.host();
        String location = project.location(host);
        String noun = host == TrackerKind.GITLAB ? "project" : "repository";
        IssueTrackerService.RepositoryCheck check;
        try {
            check = state.trackers.checkRepository(org, host, location, base);
        } catch (McpException e) {
            throw new JunoException("Couldn't check the " + host.displayName() + " " + noun + " '" + location
                    + "': " + e.getMessage(), e);
        }
        if (!check.exists()) {
            throw new JunoException("The " + host.displayName() + " " + noun + " '" + location + "' doesn't exist, "
                    + "or organisation '" + org.getName() + "''s token can't access it. Check project '"
                    + project.getName() + "' and the token in Settings → Organisations & projects.");
        }
        if (Boolean.FALSE.equals(check.branchExists())) {
            throw new JunoException("The base branch '" + base + "' doesn't exist in " + host.displayName() + " "
                    + noun + " '" + location + "'"
                    + (check.defaultBranch().isBlank() ? "" : " (its default branch is '" + check.defaultBranch() + "')")
                    + ". Change the base branch in the ticket's Details, or push it first.");
        }
    }

    void checkCapacity() throws JunoException {
        int max = Math.max(1, state.settings.getMaxConcurrentAgents());
        if (state.runner.runningCount() >= max) {
            throw new JunoException(max + " agent(s) already running (limit set in Settings → General)");
        }
    }

    /** A branch name from the ticket's title that no other open ticket uses. Caller holds the lock. */
    String uniqueBranchName(Ticket t) {
        String base = GitWorkspace.suggestBranchName(t.getTitle());
        String name = base;
        for (int n = 2; branchOwner(t, name).isPresent(); n++) {
            name = base + "-" + n;
        }
        return name;
    }

    /** Another ticket (not in Done) already using the branch. Caller holds the lock. */
    Optional<Ticket> branchOwner(Ticket self, String branch) {
        return state.board.getTickets().stream()
                .filter(o -> o != self && o.getStatus() != TicketStatus.DONE)
                .filter(o -> o.getBranchName() != null && o.getBranchName().equalsIgnoreCase(branch))
                .findFirst();
    }
}
