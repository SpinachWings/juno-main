package dev.juno.main.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.RunKind;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
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
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private boolean updatingFilter;
    /** Cards by ticket id, with the state they show; a card is only rebuilt when that state changes. */
    private final Map<String, CardEntry> cards = new HashMap<>();
    /** Coalesces bursts of board changes into at most one redraw per 200 ms. */
    private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(200));

    private record CardEntry(String state, Node node) {
    }

    public BoardView(BoardService service, HostServices hostServices) {
        this.service = service;
        this.hostServices = hostServices;
        root.getStyleClass().add("board-root");
        root.setTop(buildToolbar());
        root.setCenter(buildColumns());
        refreshDelay.setOnFinished(e -> refresh());
        service.addChangeListener(() -> Fx.runLater(this::scheduleRefresh));
        // Some things on a card change without a board event (a terminal closing, an agent process ending).
        Timeline poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> scheduleRefresh()));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
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

        newTicket.getStyleClass().add("toolbar-action");
        MenuButton importIssue = new MenuButton("Import");
        importIssue.getStyleClass().add("toolbar-action");
        for (TrackerKind kind : TrackerKind.values()) {
            MenuItem item = new MenuItem("From " + kind.displayName());
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
            Label name = new Label(status.label().toUpperCase(Locale.ROOT));
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

    // ---- refresh ----------------------------------------------------------------------------

    /** Redraws soon (FX thread), merging further changes that arrive meanwhile. */
    private void scheduleRefresh() {
        if (refreshDelay.getStatus() != Animation.Status.RUNNING) {
            refreshDelay.playFromStart();
        }
    }

    /** Redraws the board from snapshots, rebuilding only the cards whose state changed. */
    private void refresh() {
        updateFilters();
        String orgId = selectedOrgId();
        String projectId = selectedProjectId();
        TicketCard.Actions actions = cardActions();
        Set<String> seen = new HashSet<>();
        for (TicketStatus status : TicketStatus.values()) {
            List<Node> nodes = new ArrayList<>();
            for (Ticket t : service.column(status)) {
                if (!visible(t, orgId, projectId)) {
                    continue;
                }
                seen.add(t.getId());
                String state = cardState(t);
                CardEntry entry = cards.get(t.getId());
                if (entry == null || !entry.state().equals(state)) {
                    entry = new CardEntry(state, TicketCard.create(t, service, actions, hostServices));
                    cards.put(t.getId(), entry);
                }
                nodes.add(entry.node());
            }
            int count = nodes.size();
            if (count == 0) {
                Label empty = new Label(status == TicketStatus.PENDING ? "No tickets yet.\nCreate or import one." : "—");
                empty.getStyleClass().add("column-empty");
                nodes.add(empty);
            }
            VBox body = columnBodies.get(status);
            if (!body.getChildren().equals(nodes)) {
                body.getChildren().setAll(nodes);
            }
            columnCounts.get(status).setText(Integer.toString(count));
        }
        cards.keySet().retainAll(seen);
        int running = service.runningAgents();
        agentsLabel.setText(running == 0 ? "No agents running" : running + " agent(s) running");
        agentsLabel.getStyleClass().setAll("label", running == 0 ? "agents-idle" : "agents-busy");
    }

    /** Everything a card shows: the ticket itself, what's running, and its project, organisation and agent. */
    private String cardState(Ticket t) {
        String id = t.getId();
        String json;
        try {
            json = service.store().mapper().writeValueAsString(t);
        } catch (JsonProcessingException e) {
            json = t.getUpdatedAt() + t.getStatus();
        }
        String project = service.projectOf(t).map(p -> p.getName() + p.getColor() + p.host()).orElse("");
        String org = service.organisationOf(t).map(Organisation::getName).orElse("");
        String agent = service.agentFor(t).map(a -> a.describe()).orElse("");
        return json + '|' + service.isAgentRunning(id) + service.isOpeningPullRequest(id) + service.isPreparing(id)
                + service.isTerminalOpen(id) + '|' + project + '|' + org + '|' + agent;
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

    /** A ticket as named in messages: its title in quotes. */
    private static String name(Ticket t) {
        return t.getTitle() == null || t.getTitle().isBlank() ? "the ticket" : "'" + t.getTitle().strip() + "'";
    }

    private void newTicket() {
        Ticket ticket = service.createTicket("", "", defaultProjectId());
        new TicketDetailsDialog(window(), service, ticket, true, hostServices).showAndWait();
    }

    private void importAsNewTicket(TrackerKind kind) {
        new IssuePickerDialog(window(), service, kind, selectedOrgId(), defaultProjectId(), null, null)
                .showAndWait().ifPresent(sel -> {
            Fx.async(() -> service.importAsNewTicket(sel.projectId(), kind, sel.location(), sel.number()),
                    t -> { },
                    err -> Fx.error(window(), "Import failed", err));
        });
    }

    private void openSettings() {
        JunoSettings working = service.store().copy(service.settings(), JunoSettings.class);
        new SettingsDialog(window(), service, working).showAndWait().ifPresent(service::updateSettings);
    }

    private TicketCard.Actions cardActions() {
        return new TicketCard.Actions() {
            @Override
            public void start(Ticket t, Window owner) {
                Fx.async(() -> {
                    service.startWork(t.getId());
                    return null;
                }, ok -> { }, err -> Fx.error(owner, "Could not start " + name(t), err));
            }

            @Override
            public void stop(Ticket t, Window owner) {
                RunKind kind = t.getRunKind();
                boolean conflicts = kind == RunKind.CONFLICTS;
                if (Fx.confirm(owner, "Stop agent?", "Stop the " + (kind == RunKind.REVIEW
                        ? "AI review agent" : conflicts ? "conflict-resolving agent" : "agent")
                        + " working on " + name(t) + "? The ticket goes back to " + kind.homeStatus().label()
                        + (conflicts ? "; the unfinished merge is abandoned and the branch is left as it was."
                        : "; commits and changes already made stay on its branch."))) {
                    service.stopWork(t.getId());
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
                new DecisionDialog(owner, t, service).showAndWait().ifPresent(decision -> {
                    if (decision.backToPending()) {
                        move(t, t.getRunKind().homeStatus(), owner);
                        return;
                    }
                    if (decision.approveDesign()) {
                        Fx.async(() -> {
                            service.approveDesign(t.getId());
                            return null;
                        }, ok -> { },
                                err -> Fx.error(owner, "Could not start " + name(t), err));
                        return;
                    }
                    Fx.async(() -> {
                        service.respond(t.getId(), decision.answer());
                        return null;
                    }, ok -> { },
                            err -> Fx.error(owner, "Could not resume " + name(t), err));
                });
            }

            @Override
            public void move(Ticket t, TicketStatus target, Window owner) {
                try {
                    service.move(t.getId(), target);
                } catch (Exception e) {
                    Fx.error(owner, "Cannot move " + name(t), e);
                }
            }

            @Override
            public void retryPullRequest(Ticket t, Window owner) {
                Fx.async(() -> {
                    service.deliver(t.getId());
                    return null;
                }, ok -> { },
                        err -> Fx.error(owner, "Retry failed", err));
            }

            @Override
            public void terminal(Ticket t, Window owner) {
                Fx.async(() -> service.showTerminal(t.getId()), message -> { },
                        err -> Fx.error(owner, "Could not open the terminal", err));
            }

            @Override
            public void startReview(Ticket t, Window owner) {
                Fx.async(() -> service.startReview(t.getId()),
                        count -> { },
                        err -> Fx.error(owner, "Could not start the AI review", err));
            }

            @Override
            public void resolveConflicts(Ticket t, Window owner) {
                Fx.async(() -> service.resolveConflicts(t.getId()),
                        nothing -> nothing.ifPresent(message -> Fx.info(owner, "No merge conflicts", message)),
                        err -> Fx.error(owner, "Could not resolve the conflicts", err));
            }

            @Override
            public void resume(Ticket t, Window owner) {
                Fx.async(() -> {
                    service.resumeInterrupted(t.getId());
                    return null;
                }, ok -> { }, err -> Fx.error(owner, "Could not resume " + name(t), err));
            }

            @Override
            public void moveToDone(Ticket t, Window owner) {
                if (Fx.confirm(owner, "Move " + name(t) + " to Done?", "Are you sure? " + name(t)
                        + " will leave Review" + (t.getPullRequest() == null ? "."
                        : ". Its " + t.getPullRequest().tracker().pullRequestNoun() + " is not merged or closed by Juno."))) {
                    move(t, TicketStatus.DONE, owner);
                }
            }

            @Override
            public void delete(Ticket t, Window owner) {
                if (Fx.confirm(owner, "Delete " + name(t) + "?",
                        "This removes the ticket from Juno with everything its agents left behind: the working "
                                + "copy (including any uncommitted changes), Juno's logs and run records, the "
                                + "agents' Claude Code session history and any open terminals. Its branch, pull "
                                + "request and linked issue on GitHub/GitLab are kept.")) {
                    service.deleteTicket(t.getId());
                }
            }
        };
    }
}
