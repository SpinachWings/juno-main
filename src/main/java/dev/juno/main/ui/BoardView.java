package dev.juno.main.ui;

import dev.juno.main.model.JunoSettings;
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

    /** Project filter entry; {@code projectId == null} means all projects. */
    private record FilterItem(String projectId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final FilterItem ALL = new FilterItem(null, "All projects");

    private final BoardService service;
    private final HostServices hostServices;
    private final BorderPane root = new BorderPane();
    private final Map<TicketStatus, VBox> columnBodies = new EnumMap<>(TicketStatus.class);
    private final Map<TicketStatus, Label> columnCounts = new EnumMap<>(TicketStatus.class);
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

        projectFilter.setOnAction(e -> {
            if (!updatingFilter) {
                refresh();
            }
        });

        Button settings = new Button("Settings");
        settings.setOnAction(e -> openSettings());

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label title = new Label("Juno");
        title.getStyleClass().add("app-title");

        return new ToolBar(title, new Separator(), newTicket, importIssue, new Separator(),
                new Label("Project:"), projectFilter, spacer, agentsLabel, settings);
    }

    private Node buildColumns() {
        HBox columns = new HBox(12);
        columns.setPadding(new Insets(12));
        columns.getStyleClass().add("columns");
        for (TicketStatus status : TicketStatus.values()) {
            Label name = new Label(status.label());
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
        updateProjectFilter();
        String filterId = projectFilter.getValue() == null ? null : projectFilter.getValue().projectId();
        TicketCard.Actions actions = cardActions();
        for (TicketStatus status : TicketStatus.values()) {
            VBox body = columnBodies.get(status);
            body.getChildren().clear();
            int count = 0;
            for (Ticket t : service.column(status)) {
                if (filterId != null && !filterId.equals(t.getProjectId())) {
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

    private void updateProjectFilter() {
        List<FilterItem> items = new ArrayList<>();
        items.add(ALL);
        for (Project p : service.settings().getProjects()) {
            items.add(new FilterItem(p.getId(), p.getName()));
        }
        if (items.equals(projectFilter.getItems())) {
            if (projectFilter.getValue() == null) {
                projectFilter.setValue(ALL);
            }
            return;
        }
        FilterItem selected = projectFilter.getValue();
        updatingFilter = true;
        try {
            projectFilter.getItems().setAll(items);
            FilterItem keep = items.stream()
                    .filter(i -> selected != null && Objects.equals(i.projectId(), selected.projectId()))
                    .findFirst().orElse(ALL);
            projectFilter.setValue(keep);
        } finally {
            updatingFilter = false;
        }
    }

    // ---- actions ----------------------------------------------------------------------------

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private void status(String message) {
        statusLabel.setText(message);
    }

    private void newTicket() {
        String projectId = projectFilter.getValue() == null ? null : projectFilter.getValue().projectId();
        Ticket ticket = service.createTicket("", "", projectId);
        new TicketDetailsDialog(window(), service, ticket, true, hostServices).showAndWait();
        status(service.ticket(ticket.getId()).map(t -> "Saved " + t.getId()).orElse("Discarded new ticket"));
    }

    private void importAsNewTicket(TrackerKind kind) {
        String location = "";
        if (projectFilter.getValue() != null && projectFilter.getValue().projectId() != null) {
            location = service.settings().project(projectFilter.getValue().projectId())
                    .map(p -> p.location(kind)).orElse("");
        }
        new IssuePickerDialog(window(), service, kind, location, null).showAndWait().ifPresent(sel -> {
            status("Importing " + kind.displayName() + " " + sel.location() + "#" + sel.number() + "…");
            Fx.async(() -> service.importAsNewTicket(kind, sel.location(), sel.number()),
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
                if (Fx.confirm(owner, "Stop agent?", "Stop the agent working on " + t.getId()
                        + "? The ticket goes back to Pending; changes already made stay in the working tree.")) {
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
                        move(t, TicketStatus.PENDING, owner);
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
