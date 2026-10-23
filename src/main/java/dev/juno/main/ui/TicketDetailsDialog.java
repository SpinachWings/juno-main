package dev.juno.main.ui;

import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.Project;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The ticket "menu": edit title, full description and project link, and sync with GitLab/GitHub
 * (upload ticket info, import issue info).
 */
final class TicketDetailsDialog extends Dialog<ButtonType> {

    private record ProjectItem(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final ProjectItem NO_PROJECT = new ProjectItem(null, "— none —");

    private final BoardService service;
    private final Ticket ticket;
    private final boolean isNew;
    private final HostServices hostServices;

    private final TextField title = new TextField();
    private final TextArea description = new TextArea();
    private final ComboBox<ProjectItem> project = new ComboBox<>();
    private final Map<TrackerKind, HBox> linkRows = new EnumMap<>(TrackerKind.class);
    private final VBox actionsBox = new VBox(8);
    private final Label status = new Label();
    private final ProgressIndicator busy = new ProgressIndicator(-1);

    TicketDetailsDialog(Window owner, BoardService service, Ticket ticket, boolean isNew, HostServices hostServices) {
        this.service = service;
        this.ticket = ticket;
        this.isNew = isNew;
        this.hostServices = hostServices;

        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle(isNew ? "New ticket" : ticket.getId());
        setHeaderText(isNew ? "New ticket " + ticket.getId()
                : ticket.getId() + "  ·  " + ticket.getStatus().label());
        setResizable(true);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().getStyleClass().add("ticket-dialog");
        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefSize(960, 640);

        loadFromTicket();

        Button saveButton = (Button) getDialogPane().lookupButton(save);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (title.getText().isBlank()) {
                status.setText("A title is required.");
                e.consume();
            }
        });

        setResultConverter(button -> {
            if (button == save) {
                applyEdits();
            } else if (this.isNew && ticket.getRef(TrackerKind.GITLAB) == null
                    && ticket.getRef(TrackerKind.GITHUB) == null && ticket.getTitle().isBlank()) {
                service.deleteTicket(ticket.getId());
            }
            return button;
        });
        setOnShown(e -> title.requestFocus());
    }

    private BorderPane buildContent() {
        title.setPromptText("Short summary of the work");
        description.setPromptText("Full description, acceptance criteria, notes for the agent…");
        description.setWrapText(true);
        description.setPrefRowCount(20);

        project.getItems().add(NO_PROJECT);
        for (Project p : service.settings().getProjects()) {
            project.getItems().add(new ProjectItem(p.getId(), p.getName()));
        }
        project.setMaxWidth(Double.MAX_VALUE);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);
        form.addRow(0, new Label("Title"), title);
        form.addRow(1, new Label("Project"), project);
        Label descLabel = new Label("Description");
        GridPane.setValignment(descLabel, javafx.geometry.VPos.TOP);
        form.addRow(2, descLabel, description);
        GridPane.setVgrow(description, Priority.ALWAYS);

        VBox links = new VBox(4);
        for (TrackerKind kind : TrackerKind.values()) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            linkRows.put(kind, row);
            links.getChildren().add(row);
        }
        form.addRow(3, new Label("Linked issues"), links);

        // The sync "menu".
        Label menuTitle = new Label("Sync");
        menuTitle.getStyleClass().add("section-title");
        actionsBox.getChildren().add(menuTitle);
        for (TrackerKind kind : TrackerKind.values()) {
            actionsBox.getChildren().add(actionButton("Upload ticket info to " + kind.displayName(),
                    () -> upload(kind)));
        }
        for (TrackerKind kind : TrackerKind.values()) {
            actionsBox.getChildren().add(actionButton("Import ticket info from " + kind.displayName(),
                    () -> importFrom(kind)));
        }
        Label hint = new Label("Linking to a project happens automatically on import when the GitLab project "
                + "or GitHub repo matches one configured in Settings.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");
        actionsBox.getChildren().add(hint);
        actionsBox.setPadding(new Insets(0, 0, 0, 16));
        actionsBox.setPrefWidth(260);
        actionsBox.setMinWidth(240);

        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        status.setWrapText(true);
        status.getStyleClass().add("dialog-status");
        HBox statusRow = new HBox(8, busy, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setPadding(new Insets(10, 0, 0, 0));

        BorderPane content = new BorderPane();
        content.setCenter(form);
        content.setRight(actionsBox);
        content.setBottom(statusRow);
        return content;
    }

    private Button actionButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void loadFromTicket() {
        title.setText(ticket.getTitle());
        description.setText(ticket.getDescription());
        project.setValue(project.getItems().stream()
                .filter(i -> Objects.equals(i.id(), ticket.getProjectId()))
                .findFirst().orElse(NO_PROJECT));
        refreshLinks();
    }

    private void refreshLinks() {
        for (TrackerKind kind : TrackerKind.values()) {
            HBox row = linkRows.get(kind);
            row.getChildren().clear();
            Label name = new Label(kind.displayName() + ":");
            name.setMinWidth(55);
            row.getChildren().add(name);
            ExternalRef ref = ticket.getRef(kind);
            if (ref == null) {
                Label none = new Label("not linked");
                none.getStyleClass().add("hint");
                row.getChildren().add(none);
            } else {
                Hyperlink link = new Hyperlink(ref.display());
                link.setOnAction(e -> {
                    if (ref.url() != null && !ref.url().isBlank()) {
                        hostServices.showDocument(ref.url());
                    }
                });
                Button unlink = new Button("Unlink");
                unlink.getStyleClass().add("small");
                unlink.setOnAction(e -> {
                    service.unlink(ticket.getId(), kind);
                    refreshLinks();
                });
                row.getChildren().addAll(link, unlink);
            }
        }
    }

    /** Copies the form into the ticket and persists it. */
    private void applyEdits() {
        ticket.setTitle(title.getText().strip());
        ticket.setDescription(description.getText());
        ProjectItem selected = project.getValue();
        ticket.setProjectId(selected == null ? null : selected.id());
        service.saveTicket(ticket);
    }

    private void upload(TrackerKind kind) {
        if (title.getText().isBlank()) {
            status.setText("Add a title before uploading.");
            return;
        }
        applyEdits();
        ExternalRef existing = ticket.getRef(kind);
        String location;
        if (existing != null) {
            if (!Fx.confirm(getDialogPane().getScene().getWindow(), "Update " + kind.displayName() + " issue?",
                    "Overwrite the title and description of " + existing.display() + " with this ticket's?")) {
                return;
            }
            location = existing.location();
        } else {
            TextInputDialog ask = new TextInputDialog(service.defaultLocation(ticket, kind));
            ask.initOwner(getDialogPane().getScene().getWindow());
            ask.setTitle("Upload to " + kind.displayName());
            ask.setHeaderText("Create a new " + kind.displayName() + " issue from " + ticket.getId());
            ask.setContentText(kind.displayName() + " " + kind.locationHint() + ":");
            Optional<String> answer = ask.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
            if (answer.isEmpty()) {
                return;
            }
            location = answer.get();
        }
        setBusy(true, "Uploading to " + kind.displayName() + "…");
        Fx.async(() -> service.upload(ticket.getId(), kind, location), ref -> {
            setBusy(false, (existing != null ? "Updated " : "Created ") + kind.displayName() + " issue " + ref.display());
            loadProjectOnly();
            refreshLinks();
        }, err -> {
            setBusy(false, "Upload failed.");
            Fx.error(getDialogPane().getScene().getWindow(), "Upload to " + kind.displayName() + " failed", err);
        });
    }

    private void importFrom(TrackerKind kind) {
        ExternalRef existing = ticket.getRef(kind);
        String location = service.defaultLocation(ticket, kind);
        Long number = existing == null ? null : existing.number();
        new IssuePickerDialog(getDialogPane().getScene().getWindow(), service, kind, location, number)
                .showAndWait()
                .ifPresent(sel -> {
                    boolean hasContent = !title.getText().isBlank() || !description.getText().isBlank();
                    if (hasContent && !Fx.confirm(getDialogPane().getScene().getWindow(), "Replace ticket info?",
                            "Importing replaces this ticket's title and description with those of "
                                    + sel.location() + "#" + sel.number() + ".")) {
                        return;
                    }
                    // Keep any project choice made in the form unless the import links one.
                    ProjectItem chosen = project.getValue();
                    ticket.setProjectId(chosen == null ? null : chosen.id());
                    setBusy(true, "Importing from " + kind.displayName() + "…");
                    Fx.async(() -> {
                        service.importIssue(ticket.getId(), kind, sel.location(), sel.number());
                        return null;
                    }, ok -> {
                        loadFromTicket();
                        String linked = service.projectOf(ticket).map(p -> " and linked to project " + p.getName())
                                .orElse("");
                        setBusy(false, "Imported " + sel.location() + "#" + sel.number() + linked + ".");
                    }, err -> {
                        setBusy(false, "Import failed.");
                        Fx.error(getDialogPane().getScene().getWindow(), "Import from " + kind.displayName() + " failed", err);
                    });
                });
    }

    private void loadProjectOnly() {
        project.setValue(project.getItems().stream()
                .filter(i -> Objects.equals(i.id(), ticket.getProjectId()))
                .findFirst().orElse(NO_PROJECT));
    }

    private void setBusy(boolean isBusy, String message) {
        busy.setVisible(isBusy);
        actionsBox.setDisable(isBusy);
        status.setText(message);
    }
}
