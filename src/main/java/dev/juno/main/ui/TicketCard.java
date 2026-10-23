package dev.juno.main.ui;

import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TicketStatus;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.Optional;

/** Builds the card shown for a ticket in a board column. */
final class TicketCard {

    /** Operations a card can trigger; implemented by {@link BoardView}. */
    interface Actions {
        void start(Ticket t, Window owner);

        void stop(Ticket t, Window owner);

        void details(Ticket t, Window owner);

        void log(Ticket t, Window owner);

        void respond(Ticket t, Window owner);

        void move(Ticket t, TicketStatus target, Window owner);

        void delete(Ticket t, Window owner);
    }

    private TicketCard() {
    }

    static VBox create(Ticket ticket, BoardService service, Actions actions, HostServices hostServices) {
        VBox card = new VBox(6);
        card.getStyleClass().add("ticket-card");
        card.setMaxWidth(Double.MAX_VALUE);

        Optional<Project> project = service.projectOf(ticket);
        project.ifPresent(p -> {
            Color c = Fx.parseColor(p.getColor(), Color.GRAY);
            card.setStyle("-fx-background-color: -card-bg, " + Fx.rgba(c, 0.16) + ";"
                    + "-fx-border-color: " + Fx.toHex(c) + ";"
                    + "-fx-border-width: 0 0 0 5;");
        });

        Label meta = new Label(ticket.getId() + project.map(p -> "  ·  " + p.getName()).orElse("  ·  no project"));
        meta.getStyleClass().add("card-meta");

        Label title = new Label(ticket.getTitle().isBlank() ? "(untitled)" : ticket.getTitle());
        title.getStyleClass().add("card-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().addAll(meta, title);

        FlowPane links = new FlowPane(6, 2);
        for (TrackerKind kind : TrackerKind.values()) {
            ExternalRef ref = ticket.getRef(kind);
            if (ref != null) {
                Hyperlink link = new Hyperlink(kind.shortName() + " " + ref.display());
                link.getStyleClass().addAll("card-link", "card-link-" + kind.name().toLowerCase());
                link.setTooltip(new Tooltip(ref.url()));
                link.setOnAction(e -> {
                    if (ref.url() != null && !ref.url().isBlank()) {
                        hostServices.showDocument(ref.url());
                    }
                });
                links.getChildren().add(link);
            }
        }
        if (!links.getChildren().isEmpty()) {
            card.getChildren().add(links);
        }

        boolean running = service.isAgentRunning(ticket.getId());
        switch (ticket.getStatus()) {
            case WORKING -> {
                ProgressIndicator spinner = new ProgressIndicator(-1);
                spinner.setPrefSize(14, 14);
                Label label = new Label(running ? "Agent working…" : "Agent not running");
                label.getStyleClass().add("card-note");
                HBox row = new HBox(6, spinner, label);
                row.setAlignment(Pos.CENTER_LEFT);
                card.getChildren().add(row);
            }
            case AWAITING_DECISION -> card.getChildren().add(excerpt(ticket.getPendingQuestion(), "card-question"));
            case AI_REVIEW -> {
                if (ticket.getLastSummary() != null && !ticket.getLastSummary().isBlank()) {
                    card.getChildren().add(excerpt(ticket.getLastSummary(), "card-note"));
                }
            }
            default -> {
                // no extra content
            }
        }

        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Button details = button("Details", "Full description, project link and GitLab/GitHub sync",
                b -> actions.details(ticket, b.getScene().getWindow()));
        switch (ticket.getStatus()) {
            case PENDING -> {
                Button start = button("Start", "Hand this ticket to an agent",
                        b -> actions.start(ticket, b.getScene().getWindow()));
                start.getStyleClass().add("accent");
                buttons.getChildren().addAll(start, details);
            }
            case WORKING -> {
                Button stop = button("Stop", "Stop the agent",
                        b -> actions.stop(ticket, b.getScene().getWindow()));
                stop.setDisable(!running);
                buttons.getChildren().addAll(logButton(ticket, actions), stop, details);
            }
            case AWAITING_DECISION -> {
                Button respond = button("Respond", "Answer the agent and resume its session",
                        b -> actions.respond(ticket, b.getScene().getWindow()));
                respond.getStyleClass().add("accent");
                buttons.getChildren().addAll(respond, logButton(ticket, actions), details);
            }
            default -> buttons.getChildren().addAll(logButton(ticket, actions), details);
        }
        card.getChildren().add(buttons);

        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                actions.details(ticket, card.getScene().getWindow());
            }
        });
        card.setOnContextMenuRequested(e -> {
            contextMenu(ticket, running, actions, card).show(card, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return card;
    }

    private static ContextMenu contextMenu(Ticket ticket, boolean running, Actions actions, VBox card) {
        Menu moveTo = new Menu("Move to");
        for (TicketStatus status : TicketStatus.values()) {
            MenuItem item = new MenuItem(status.label());
            item.setDisable(running || status == TicketStatus.WORKING || status == ticket.getStatus());
            item.setOnAction(e -> actions.move(ticket, status, card.getScene().getWindow()));
            moveTo.getItems().add(item);
        }
        MenuItem details = new MenuItem("Details…");
        details.setOnAction(e -> actions.details(ticket, card.getScene().getWindow()));
        MenuItem log = new MenuItem("Agent log");
        log.setOnAction(e -> actions.log(ticket, card.getScene().getWindow()));
        MenuItem delete = new MenuItem("Delete ticket");
        delete.setDisable(running);
        delete.setOnAction(e -> actions.delete(ticket, card.getScene().getWindow()));
        return new ContextMenu(details, log, moveTo, new SeparatorMenuItem(), delete);
    }

    private static Button logButton(Ticket ticket, Actions actions) {
        return button("Log", "Show the agent's activity log", b -> actions.log(ticket, b.getScene().getWindow()));
    }

    private static Button button(String text, String tooltip, java.util.function.Consumer<Button> onClick) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> onClick.accept(b));
        return b;
    }

    private static Label excerpt(String text, String styleClass) {
        String value = text == null ? "" : text.strip();
        if (value.length() > 220) {
            value = value.substring(0, 220) + "…";
        }
        Label label = new Label(value);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
