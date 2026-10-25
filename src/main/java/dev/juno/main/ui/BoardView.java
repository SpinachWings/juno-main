package dev.juno.main.ui;

import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Main window content: toolbar plus one column per {@link TicketStatus}. */
public final class BoardView {

    /** Organisation or project filter entry; {@code id == null} means "all". */
    private record FilterItem(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final FilterItem ALL_ORGS = new FilterItem(null, "All organisations");
    private static final FilterItem ALL_PROJECTS = new FilterItem(null, "All projects");

    private final BoardService service;
    private final HostServices hostServices;
    private final BorderPane root = new BorderPane();
    private final Map<TicketStatus, VBox> columnBodies = new EnumMap<>(TicketStatus.class);
    private final Map<TicketStatus, Label> columnCounts = new EnumMap<>(TicketStatus.class);
    private final ComboBox<FilterItem> orgFilter = new ComboBox<>();
    private final ComboBox<FilterItem> projectFilter = new ComboBox<>();
    private final Label agentsLabel = new Label();
    private final Label statusLabel = new Label("Ready");
    private boolean updatingFilter;

    public BoardView(BoardService service, HostServices hostServices) {
        this.service = service;
        this.hostServices = hostServices;
        root.getStyleClass().add("board-root");
        root.setTop(buildToolbar());
        root.setCenter(buildColumns());
        root.setBottom(buildStatusBar());
        service.addChangeListener(() -> Fx.runLater(this::refresh));
        refresh();
    }

    public BorderPane root() {
        return root;
    }

    // ---- layout -----------------------------------------------------------------------------

    private ToolBar buildToolbar() {
        Button newTicket = new Button("+ New ticket");
        newTicket.getStyleClass().add("accent");
        newTicket.setOnAction(e -> newTicket());

        MenuButton importIssue = new MenuButton("Import issue");
        for (TrackerKind kind : TrackerKind.values()) {
            MenuItem item = new MenuItem("From " + kind.displayName() + "…");
            item.setOnAction(e -> importAsNewTicket(kind));
            importIssue.getItems().add(item);
        }

        orgFilter.setOnAction(e -> {
            if (!updatingFilter) {
                refresh();
            }
        });
        projectFilter.setOnAction(e -> {
            if (!updatingFilter) {
                refresh();
            }
        });

        Button settings = new Button("Settings");
        settings.setOnAction(e -> openSettings());

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label titleText = new Label("Juno");
        titleText.getStyleClass().add("app-title");
        HBox title = new HBox(8, SpaceIcon.create(), titleText);
        title.setAlignment(Pos.CENTER_LEFT);
        title.getStyleClass().add("app-brand");

        return new ToolBar(title, new Separator(), newTicket, importIssue, new Separator(),
                new Label("Organisation:"), orgFilter, new Label("Project:"), projectFilter,
                spacer, agentsLabel, settings);
    }

    private Node buildColumns() {
        HBox columns = new HBox(12);
        columns.setPadding(new Insets(12));
        columns.getStyleClass().add("columns");
        for (TicketStatus status : TicketStatus.values()) {
            Label name = new Label(status.label().toUpperCase(java.util.Locale.ROOT));
            name.getStyleClass().add("column-title");
            Label count = new Label("0");
            count.getStyleClass().add("column-count");
            columnCounts.put(status, count);
            HBox header = new HBox(8, name, count);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getStyleClass().add("column-header");

            VBox body = new VBox(10);
            body.setPadding(new Insets(4, 6, 12, 6));
            body.getStyleClass().add("column-body");
            columnBodies.put(status, body);

            ScrollPane scroll = new ScrollPane(body);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.getStyleClass().add("column-scroll");
            VBox.setVgrow(scroll, Priority.ALWAYS);

            VBox column = new VBox(6, header, scroll);
            column.getStyleClass().addAll("column", "column-" + status.name().toLowerCase().replace('_', '-'));
            column.setMinWidth(230);
            column.setPrefWidth(270);
            column.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
            columns.getChildren().add(column);
        }
        ScrollPane outer = new ScrollPane(columns);
        outer.setFitToHeight(true);
        outer.setFitToWidth(true);
        outer.getStyleClass().add("board-scroll");
        return outer;
    }

    private Node buildStatusBar() {
        HBox bar = new HBox(statusLabel);
        bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(4, 12, 4, 12));
        return bar;
    }

    // ---- refresh ----------------------------------------------------------------------------

    private void refresh() {
        updateFilters();
        String orgId = selectedOrgId();
        String projectId = selectedProjectId();
        TicketCard.Actions actions = cardActions();
        for (TicketStatus status : TicketStatus.values()) {
            VBox body = columnBodies.get(status);
            body.getChildren().clear();
            int count = 0;
            for (Ticket t : service.column(status)) {
                if (!visible(t, orgId, projectId)) {
                    continue;
                }
                body.getChildren().add(TicketCard.create(t, service, actions, hostServices));
                count++;
            }
            if (count == 0) {
                Label empty = new Label(status == TicketStatus.PENDING ? "No tickets yet.\nCreate or import one." : "—");
                empty.getStyleClass().add("column-empty");
                body.getChildren().add(empty);
            }
            columnCounts.get(status).setText(Integer.toString(count));
        }
        int running = service.runningAgents();
        agentsLabel.setText(running == 0 ? "No agents running" : running + " agent(s) running");
        agentsLabel.getStyleClass().setAll("label", running == 0 ? "agents-idle" : "agents-busy");
    }

    private boolean visible(Ticket t, String orgId, String projectId) {
        if (projectId != null) {
            return projectId.equals(t.getProjectId());
        }
        if (orgId != null) {
            return service.organisationOf(t).map(o -> o.getId().equals(orgId)).orElse(false);
        }
        return true;
    }

    private String selectedOrgId() {
        return orgFilter.getValue() == null ? null : orgFilter.getValue().id();
    }

    private String selectedProjectId() {
        return projectFilter.getValue() == null ? null : projectFilter.getValue().id();
    }

    /**
     * Rebuilds both filter lists, keeping selections where still valid. With "All organisations" the project
     * list holds every project from every organisation (labelled with its organisation).
     */
    private void updateFilters() {
        JunoSettings s = service.settings();
        String selectedOrg = selectedOrgId();
        String selectedProject = selectedProjectId();
        updatingFilter = true;
        try {
            List<FilterItem> orgs = new ArrayList<>();
            orgs.add(ALL_ORGS);
            for (Organisation o : s.getOrganisations()) {
                orgs.add(new FilterItem(o.getId(), o.getName()));
            }
            if (!orgs.equals(orgFilter.getItems())) {
                orgFilter.getItems().setAll(orgs);
            }
            FilterItem org = find(orgs, selectedOrg, ALL_ORGS);
            if (!Objects.equals(org, orgFilter.getValue())) {
                orgFilter.setValue(org);
            }

            List<FilterItem> projects = new ArrayList<>();
            projects.add(ALL_PROJECTS);
            if (org.id() == null) {
                for (Project p : s.getProjects()) {
                    String orgName = s.organisationOf(p).map(Organisation::getName).orElse("?");
                    projects.add(new FilterItem(p.getId(), p.getName() + "  (" + orgName + ")"));
                }
            } else {
                for (Project p : s.projectsIn(org.id())) {
                    projects.add(new FilterItem(p.getId(), p.getName()));
                }
            }
            if (!projects.equals(projectFilter.getItems())) {
                projectFilter.getItems().setAll(projects);
            }
            FilterItem project = find(projects, selectedProject, ALL_PROJECTS);
            if (!Objects.equals(project, projectFilter.getValue())) {
                projectFilter.setValue(project);
            }
        } finally {
            updatingFilter = false;
        }
    }

    private static FilterItem find(List<FilterItem> items, String id, FilterItem fallback) {
        return items.stream().filter(i -> id != null && id.equals(i.id())).findFirst().orElse(fallback);
    }

    /** Project to pre-select for new/imported tickets: the filtered project, else the org's only project. */
    private String defaultProjectId() {
        if (selectedProjectId() != null) {
            return selectedProjectId();
        }
        if (selectedOrgId() != null) {
            List<Project> inOrg = service.settings().projectsIn(selectedOrgId());
            if (inOrg.size() == 1) {
                return inOrg.getFirst().getId();
            }
        }
        return null;
    }

    // ---- actions ----------------------------------------------------------------------------

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private void status(String message) {
        statusLabel.setText(message);
    }

    private void newTicket() {
        Ticket ticket = service.createTicket("", "", defaultProjectId());
        new TicketDetailsDialog(window(), service, ticket, true, hostServices).showAndWait();
        status(service.ticket(ticket.getId()).map(t -> "Saved " + t.getId()).orElse("Discarded new ticket"));
    }

    private void importAsNewTicket(TrackerKind kind) {
        new IssuePickerDialog(window(), service, kind, selectedOrgId(), defaultProjectId(), null, null)
                .showAndWait().ifPresent(sel -> {
            status("Importing " + kind.displayName() + " " + sel.location() + "#" + sel.number() + "…");
            Fx.async(() -> service.importAsNewTicket(sel.projectId(), kind, sel.location(), sel.number()),
                    t -> status("Imported " + kind.displayName() + " issue as " + t.getId()),
                    err -> {
                        status("Import failed");
                        Fx.error(window(), "Import failed", err);
                    });
        });
    }

    private void openSettings() {
        JunoSettings working = service.store().copy(service.settings(), JunoSettings.class);
        new SettingsDialog(window(), service, working).showAndWait().ifPresent(updated -> {
            service.updateSettings(updated);
            status("Settings saved");
        });
    }

    private TicketCard.Actions cardActions() {
        return new TicketCard.Actions() {
            @Override
            public void start(Ticket t, Window owner) {
                status("Starting agent for " + t.getId() + "…");
                Fx.async(() -> {
                    service.startWork(t.getId());
                    return null;
                }, ok -> status("Agent started on " + t.getId()), err -> {
                    status("Could not start " + t.getId());
                    Fx.error(owner, "Could not start " + t.getId(), err);
                });
            }

            @Override
            public void stop(Ticket t, Window owner) {
                boolean review = t.getRunKind() == dev.juno.main.model.RunKind.REVIEW;
                if (Fx.confirm(owner, "Stop agent?", "Stop the " + (review ? "AI review agent" : "agent")
                        + " working on " + t.getId() + "? The ticket goes back to " + (review ? "AI review" : "Pending")
                        + "; commits and changes already made stay on its branch.")) {
                    service.stopWork(t.getId());
                    status("Stopping agent on " + t.getId());
                }
            }

            @Override
            public void details(Ticket t, Window owner) {
                new TicketDetailsDialog(owner, service, t, false, hostServices).showAndWait();
            }

            @Override
            public void log(Ticket t, Window owner) {
                LogWindow.show(owner, service, t);
            }

            @Override
            public void respond(Ticket t, Window owner) {
                new DecisionDialog(owner, t).showAndWait().ifPresent(decision -> {
                    if (decision.backToPending()) {
                        move(t, t.getRunKind() == dev.juno.main.model.RunKind.REVIEW
                                ? TicketStatus.REVIEW : TicketStatus.PENDING, owner);
                        return;
                    }
                    Fx.async(() -> {
                        service.respond(t.getId(), decision.answer());
                        return null;
                    }, ok -> status("Resumed agent on " + t.getId()),
                            err -> Fx.error(owner, "Could not resume " + t.getId(), err));
                });
            }

            @Override
            public void move(Ticket t, TicketStatus target, Window owner) {
                try {
                    service.move(t.getId(), target);
                    status("Moved " + t.getId() + " to " + target.label());
                } catch (Exception e) {
                    Fx.error(owner, "Cannot move " + t.getId(), e);
                }
            }

            @Override
            public void retryPullRequest(Ticket t, Window owner) {
                status("Retrying " + t.getId() + "…");
                Fx.async(() -> {
                    service.deliver(t.getId());
                    return null;
                }, ok -> status(t.getPullRequestError() == null
                        ? t.getId() + " delivered" + (t.getPullRequest() == null ? "" : ": " + t.getPullRequest().display())
                        : t.getId() + " still failing; see the card"),
                        err -> Fx.error(owner, "Retry failed", err));
            }

            @Override
            public void terminal(Ticket t, Window owner) {
                Fx.async(() -> service.showTerminal(t.getId()), BoardView.this::status,
                        err -> Fx.error(owner, "Could not open the terminal", err));
            }

            @Override
            public void startReview(Ticket t, Window owner) {
                status("Reading review comments for " + t.getId() + "…");
                Fx.async(() -> service.startReview(t.getId()),
                        count -> status(count == 0
                                ? "No new comments on " + t.getId() + "'s pull request; AI review "
                                        + t.getReviewCount() + " completed immediately"
                                : "AI review started on " + t.getId() + " with " + count + " comment(s)"),
                        err -> {
                            status("AI review not started");
                            Fx.error(owner, "Could not start the AI review", err);
                        });
            }

            @Override
            public void resume(Ticket t, Window owner) {
                status("Resuming " + t.getId() + "…");
                Fx.async(() -> {
                    service.resumeInterrupted(t.getId());
                    return null;
                }, ok -> status("Resumed " + t.getId()), err -> {
                    status("Could not resume " + t.getId());
                    Fx.error(owner, "Could not resume " + t.getId(), err);
                });
            }

            @Override
            public void moveToDone(Ticket t, Window owner) {
                if (Fx.confirm(owner, "Move " + t.getId() + " to Done?", "Are you sure? " + t.getId()
                        + " will leave Review" + (t.getPullRequest() == null ? "."
                        : ". Its " + t.getPullRequest().tracker().pullRequestNoun() + " is not merged or closed by Juno."))) {
                    move(t, TicketStatus.DONE, owner);
                }
            }

            @Override
            public void delete(Ticket t, Window owner) {
                if (Fx.confirm(owner, "Delete " + t.getId() + "?",
                        "This removes the ticket from Juno only; linked GitLab/GitHub issues are not touched.")) {
                    service.deleteTicket(t.getId());
                    status("Deleted " + t.getId());
                }
            }
        };
    }
}
