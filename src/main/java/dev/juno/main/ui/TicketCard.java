package dev.juno.main.ui;

import dev.juno.main.model.AgentAssignment;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.RunKind;
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

        void retryPullRequest(Ticket t, Window owner);

        void terminal(Ticket t, Window owner);

        void startReview(Ticket t, Window owner);
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
            // Project colour: a solid stripe on the left and a faint tint over the dark card.
            card.setStyle("-fx-background-color: -card-bg, " + Fx.rgba(c, 0.10) + ";"
                    + "-fx-border-color: -line -line -line " + Fx.toHex(c) + ";"
                    + "-fx-border-width: 1 1 1 4;");
        });

        String org = service.organisationOf(ticket).map(Organisation::getName).orElse(null);
        Label meta = new Label(ticket.getId() + "  ·  "
                + project.map(p -> (org == null ? "" : org + "  ·  ") + p.getName()).orElse("no project"));
        meta.getStyleClass().add("card-meta");
        meta.setWrapText(true);

        Label title = new Label(ticket.getTitle().isBlank() ? "(untitled)" : ticket.getTitle());
        title.getStyleClass().add("card-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().addAll(meta, title);

        Optional<AgentAssignment> agent = service.agentFor(ticket);
        Label agentLabel = new Label("Agent: " + agent.map(a -> a.profile().getName()).orElse("none assigned"));
        agentLabel.getStyleClass().add(agent.isPresent() ? "card-agent" : "card-agent-missing");
        agentLabel.setTooltip(new Tooltip(agent.map(AgentAssignment::describe)
                .orElse("No agent on the ticket, its project or its organisation")));
        card.getChildren().add(agentLabel);

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
        boolean delivering = service.isOpeningPullRequest(ticket.getId());
        boolean reviewing = service.isReviewRunning(ticket.getId());
        PullRequestRef pr = ticket.getPullRequest();
        switch (ticket.getStatus()) {
            case WORKING -> {
                if (!delivering) {
                    card.getChildren().add(progress(running ? "Agent working…" : "Agent not running"));
                }
            }
            case AWAITING_DECISION -> card.getChildren().add(excerpt(ticket.getPendingQuestion(), "card-question"));
            case AI_REVIEW -> {
                if (reviewing) {
                    card.getChildren().add(progress("AI review agent working…"));
                } else if (ticket.getReviewCount() > 0 && ticket.getLastReviewSummary() != null) {
                    card.getChildren().add(excerpt("AI review " + ticket.getReviewCount() + ": "
                            + ticket.getLastReviewSummary(), "card-note"));
                } else if (ticket.getLastSummary() != null && !ticket.getLastSummary().isBlank()) {
                    card.getChildren().add(excerpt(ticket.getLastSummary(), "card-note"));
                }
            }
            case PENDING -> {
                if (service.isPreparing(ticket.getId())) {
                    card.getChildren().add(progress("Preparing branch " + ticket.getBranchName() + "…"));
                }
            }
            default -> {
                // no extra content
            }
        }

        if (ticket.isBranchLocked()) {
            Label branch = new Label("Branch: " + ticket.getBranchName() + " -> " + ticket.getBaseBranch());
            branch.getStyleClass().add("card-agent");
            branch.setWrapText(true);
            card.getChildren().add(branch);
        }
        if (delivering) {
            card.getChildren().add(progress(ticket.getRunKind() == RunKind.REVIEW
                    ? "Pushing review fixes and commenting on " + (pr == null ? "the PR" : pr.display()) + "…"
                    : "Pushing branch and opening pull request…"));
        }
        if (pr != null) {
            Hyperlink prLink = new Hyperlink((pr.tracker() == TrackerKind.GITLAB ? "Merge request " : "Pull request ")
                    + pr.display().replaceFirst("^(PR|MR) ", "") + " »");
            prLink.getStyleClass().addAll("card-link", "card-pr-link");
            prLink.setTooltip(new Tooltip(pr.location() + "\n" + pr.url()));
            prLink.setOnAction(e -> hostServices.showDocument(pr.url()));
            card.getChildren().add(prLink);
        } else if (ticket.getPullRequestError() != null && ticket.getStatus() != TicketStatus.AWAITING_DECISION) {
            card.getChildren().add(excerpt("PR not opened: " + ticket.getPullRequestError(), "card-agent-missing"));
        }

        FlowPane buttons = new FlowPane(6, 6);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Button details = button("Details", "Full description, project link and GitLab/GitHub sync",
                b -> actions.details(ticket, b.getScene().getWindow()));
        Button terminal = button("Terminal", service.isTerminalOpen(ticket.getId())
                        ? "Bring this ticket's agent terminal to the front"
                        : "Open a terminal showing this ticket's agent (answer its questions there too)",
                b -> actions.terminal(ticket, b.getScene().getWindow()));
        terminal.getStyleClass().add(service.isTerminalOpen(ticket.getId()) ? "terminal-open" : "terminal-closed");
        boolean hasTerminal = ticket.isBranchLocked();
        switch (ticket.getStatus()) {
            case PENDING -> {
                Button start = button("Start", "Create the ticket's branch and hand the ticket to an agent",
                        b -> actions.start(ticket, b.getScene().getWindow()));
                start.getStyleClass().add("accent");
                start.setDisable(service.isPreparing(ticket.getId()));
                buttons.getChildren().add(start);
                if (hasTerminal) {
                    buttons.getChildren().add(terminal);
                }
                buttons.getChildren().add(details);
            }
            case WORKING -> {
                Button stop = button("Stop", "Stop the agent",
                        b -> actions.stop(ticket, b.getScene().getWindow()));
                stop.setDisable(!running);
                buttons.getChildren().addAll(terminal, logButton(ticket, actions), stop, details);
            }
            case AWAITING_DECISION -> {
                Button respond = button("Respond", "Answer the agent and resume its session",
                        b -> actions.respond(ticket, b.getScene().getWindow()));
                respond.getStyleClass().add("accent");
                buttons.getChildren().add(respond);
                if (ticket.getPullRequestError() != null && ticket.isBranchLocked()) {
                    Button retry = button("Retry", "Commit, push and " + (ticket.getRunKind() == RunKind.REVIEW
                                    ? "post the review again" : "open the pull request again"),
                            b -> actions.retryPullRequest(ticket, b.getScene().getWindow()));
                    retry.setDisable(delivering);
                    buttons.getChildren().add(retry);
                }
                if (hasTerminal) {
                    buttons.getChildren().add(terminal);
                }
                buttons.getChildren().addAll(logButton(ticket, actions), details);
            }
            case AI_REVIEW -> {
                if (reviewing) {
                    Button stop = button("Stop", "Stop the AI review agent",
                            b -> actions.stop(ticket, b.getScene().getWindow()));
                    buttons.getChildren().addAll(terminal, logButton(ticket, actions), stop, details);
                } else {
                    Button review = button("Start AI review", "Run an agent that reads the comments on the "
                                    + "pull request and linked issue, makes the changes and pushes them to the PR",
                            b -> actions.startReview(ticket, b.getScene().getWindow()));
                    review.getStyleClass().add("accent");
                    review.setDisable(pr == null || delivering || service.isPreparing(ticket.getId()));
                    buttons.getChildren().add(review);
                    if (hasTerminal) {
                        buttons.getChildren().add(terminal);
                    }
                    buttons.getChildren().addAll(logButton(ticket, actions), details);
                }
            }
            default -> {
                if (hasTerminal) {
                    buttons.getChildren().add(terminal);
                }
                buttons.getChildren().addAll(logButton(ticket, actions), details);
            }
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
        MenuItem terminal = new MenuItem("Agent terminal");
        terminal.setOnAction(e -> actions.terminal(ticket, card.getScene().getWindow()));
        MenuItem log = new MenuItem("Agent log");
        log.setOnAction(e -> actions.log(ticket, card.getScene().getWindow()));
        MenuItem delete = new MenuItem("Delete ticket");
        delete.setDisable(running);
        delete.setOnAction(e -> actions.delete(ticket, card.getScene().getWindow()));
        return new ContextMenu(details, terminal, log, moveTo, new SeparatorMenuItem(), delete);
    }

    private static HBox progress(String text) {
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(14, 14);
        Label label = new Label(text);
        label.getStyleClass().add("card-note");
        label.setWrapText(true);
        HBox row = new HBox(6, spinner, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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
