package dev.juno.main.ui;

import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The ticket "menu": edit title, full description and project link, and sync with GitLab/GitHub
 * (upload ticket info, import issue info).
 */
final class TicketDetailsDialog extends Dialog<ButtonType> {

    /** Combo entry for a project or agent profile; {@code id == null} means none / inherit. */
    private record Item(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Item NO_PROJECT = new Item(null, "— none —");

    private final BoardService service;
    private final Ticket ticket;
    private final boolean isNew;
    private final HostServices hostServices;

    private final TextField title = new TextField();
    private final TextArea description = new TextArea();
    private final ComboBox<Organisation> organisation = new ComboBox<>();
    private final ComboBox<Item> project = new ComboBox<>();
    private final ComboBox<Item> agent = new ComboBox<>();
    private final Label agentHint = new Label();
    private boolean loading;
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
            } else if (!confirmProjectChange()) {
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

        organisation.getItems().setAll(service.settings().getOrganisations());
        organisation.setMaxWidth(Double.MAX_VALUE);
        organisation.setOnAction(e -> {
            if (!loading) {
                fillProjects(organisation.getValue(), null);
                fillAgents(selectedAgentId());
            }
        });
        project.setMaxWidth(Double.MAX_VALUE);
        project.setOnAction(e -> {
            if (!loading) {
                fillAgents(selectedAgentId());
            }
        });
        agent.setMaxWidth(Double.MAX_VALUE);
        agentHint.getStyleClass().add("hint");
        agentHint.setText("A ticket agent overrides the project's, which overrides the organisation's.");
        VBox agentBox = new VBox(2, agent, agentHint);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);
        form.addRow(0, new Label("Title"), title);
        form.addRow(1, new Label("Organisation"), organisation);
        form.addRow(2, new Label("Project"), project);
        form.addRow(3, new Label("Agent"), agentBox);
        Label descLabel = new Label("Description");
        GridPane.setValignment(descLabel, javafx.geometry.VPos.TOP);
        form.addRow(4, descLabel, description);
        GridPane.setVgrow(description, Priority.ALWAYS);

        VBox links = new VBox(4);
        for (TrackerKind kind : TrackerKind.values()) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            linkRows.put(kind, row);
            links.getChildren().add(row);
        }
        form.addRow(5, new Label("Linked issues"), links);

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
        Label hint = new Label("Uploads and imports use the ticket's organisation's credentials. On import, the "
                + "ticket is linked automatically to the organisation's project configured for that GitLab "
                + "project / GitHub repo.");
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
        loading = true;
        try {
            title.setText(ticket.getTitle());
            description.setText(ticket.getDescription());
            List<Organisation> orgs = service.settings().getOrganisations();
            Organisation org = service.organisationOf(ticket).orElse(orgs.isEmpty() ? null : orgs.getFirst());
            organisation.setValue(org);
            fillProjects(org, ticket.getProjectId());
            fillAgents(ticket.getAgentProfileId());
        } finally {
            loading = false;
        }
        refreshLinks();
    }

    private void fillProjects(Organisation org, String selectId) {
        boolean wasLoading = loading;
        loading = true;
        try {
            project.getItems().setAll(NO_PROJECT);
            if (org != null) {
                for (Project p : service.settings().projectsIn(org.getId())) {
                    project.getItems().add(new Item(p.getId(), p.getName()));
                }
            }
            project.setValue(project.getItems().stream()
                    .filter(i -> selectId != null && selectId.equals(i.id()))
                    .findFirst().orElse(NO_PROJECT));
        } finally {
            loading = wasLoading;
        }
    }

    /** Rebuilds the agent list; the first entry describes what the ticket would inherit. */
    private void fillAgents(String selectId) {
        boolean wasLoading = loading;
        loading = true;
        try {
            JunoSettings settings = service.settings();
            String inherited = settings.project(selectedProjectId())
                    .flatMap(settings::resolveAgent)
                    .map(a -> "Inherit: " + a.describe())
                    .orElse(selectedProjectId() == null
                            ? "Inherit (choose a project first)"
                            : "Inherit: none assigned on the project or organisation");
            agent.getItems().setAll(new Item(null, inherited));
            for (AgentProfile profile : settings.getAgentProfiles()) {
                agent.getItems().add(new Item(profile.getId(), profile.getName()));
            }
            agent.setValue(agent.getItems().stream()
                    .filter(i -> selectId != null && selectId.equals(i.id()))
                    .findFirst().orElse(agent.getItems().getFirst()));
        } finally {
            loading = wasLoading;
        }
    }

    private String selectedProjectId() {
        return project.getValue() == null ? null : project.getValue().id();
    }

    private String selectedAgentId() {
        return agent.getValue() == null ? null : agent.getValue().id();
    }

    /** Warns when moving the ticket to another organisation would drop its issue links. */
    private boolean confirmProjectChange() {
        String projectId = selectedProjectId();
        if (!Objects.equals(projectId, ticket.getProjectId()) && service.wouldDropLinks(ticket, projectId)) {
            return Fx.confirm(getDialogPane().getScene().getWindow(), "Move to another organisation?",
                    "This ticket's GitLab/GitHub links were made with its current organisation's credentials "
                            + "and will be removed. Continue?");
        }
        return true;
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

    /** Copies the form into the ticket and persists it (call {@link #confirmProjectChange()} first). */
    private void applyEdits() {
        String projectId = selectedProjectId();
        if (!Objects.equals(projectId, ticket.getProjectId())) {
            service.assignProject(ticket, projectId);
        }
        ticket.setTitle(title.getText().strip());
        ticket.setDescription(description.getText());
        ticket.setAgentProfileId(selectedAgentId());
        service.saveTicket(ticket);
        refreshLinks();
    }

    private void upload(TrackerKind kind) {
        if (title.getText().isBlank()) {
            status.setText("Add a title before uploading.");
            return;
        }
        if (selectedProjectId() == null) {
            status.setText("Choose a project first: its organisation's " + kind.displayName()
                    + " credentials are used for the upload.");
            return;
        }
        if (!confirmProjectChange()) {
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
            refreshLinks();
        }, err -> {
            setBusy(false, "Upload failed.");
            Fx.error(getDialogPane().getScene().getWindow(), "Upload to " + kind.displayName() + " failed", err);
        });
    }

    private void importFrom(TrackerKind kind) {
        ExternalRef existing = ticket.getRef(kind);
        Organisation org = organisation.getValue();
        // Only reuse the existing link if the form still points at the ticket's current organisation.
        boolean sameOrg = org != null && service.organisationOf(ticket).map(o -> o.getId().equals(org.getId()))
                .orElse(false);
        String location = existing != null && sameOrg ? existing.location() : null;
        Long number = existing != null && sameOrg ? existing.number() : null;
        new IssuePickerDialog(getDialogPane().getScene().getWindow(), service, kind,
                org == null ? null : org.getId(), selectedProjectId(), location, number)
                .showAndWait()
                .ifPresent(sel -> {
                    boolean hasContent = !title.getText().isBlank() || !description.getText().isBlank();
                    if (hasContent && !Fx.confirm(getDialogPane().getScene().getWindow(), "Replace ticket info?",
                            "Importing replaces this ticket's title and description with those of "
                                    + sel.location() + "#" + sel.number() + ".")) {
                        return;
                    }
                    // Keep the agent choice made in the form; the import sets project, title and description.
                    ticket.setAgentProfileId(selectedAgentId());
                    setBusy(true, "Importing from " + kind.displayName() + "…");
                    Fx.async(() -> {
                        service.importIssue(ticket.getId(), sel.projectId(), kind, sel.location(), sel.number());
                        return null;
                    }, ok -> {
                        loadFromTicket();
                        String linked = service.projectOf(ticket).map(p -> " into project " + p.getName()
                                + service.organisationOf(ticket).map(o -> " (" + o.getName() + ")").orElse(""))
                                .orElse("");
                        setBusy(false, "Imported " + sel.location() + "#" + sel.number() + linked + ".");
                    }, err -> {
                        setBusy(false, "Import failed.");
                        Fx.error(getDialogPane().getScene().getWindow(), "Import from " + kind.displayName() + " failed", err);
                    });
                });
    }

    private void setBusy(boolean isBusy, String message) {
        busy.setVisible(isBusy);
        actionsBox.setDisable(isBusy);
        status.setText(message);
    }
}
