package dev.juno.main.ui;

import dev.juno.main.model.AgentProfile;
import dev.juno.main.git.GitWorkspace;
import dev.juno.main.model.ExternalRef;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.PullRequestRef;
import dev.juno.main.model.Ticket;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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

    private static final Item NO_PROJECT = new Item(null, "— choose a project (required to run) —");

    private final BoardService service;
    private final Ticket ticket;
    private final boolean isNew;
    private final HostServices hostServices;

    private final TextField title = new TextField();
    private final TextArea description = new TextArea();
    private final TextArea instructions = new TextArea();
    private final CheckBox generateDesign = new CheckBox("Generate design doc (for more complex features)");
    private final TextArea designDoc = new TextArea();
    private final Label designStatus = new Label();
    private final Button exportDesign = new Button("Export .txt");
    private final TextField baseBranch = new TextField();
    private final TextField branchName = new TextField();
    private final Label branchHint = new Label();
    private final HBox pullRequestRow = new HBox(6);
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
        setTitle(isNew ? "New ticket" : ticket.getTitle().isBlank() ? "Ticket" : ticket.getTitle());
        setHeaderText(isNew ? "New ticket" : (ticket.getTitle().isBlank() ? "Ticket" : ticket.getTitle())
                + "  ·  " + ticket.getStatus().label());
        setResizable(true);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().getStyleClass().add("ticket-dialog");
        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefSize(980, 800);

        loadFromTicket();

        Button saveButton = (Button) getDialogPane().lookupButton(save);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String branchProblem = branchProblem();
            if (title.getText().isBlank()) {
                status.setText("A title is required.");
                e.consume();
            } else if (branchProblem != null) {
                status.setText(branchProblem);
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
        description.setPromptText("Full description, acceptance criteria, notes for the agent");
        description.setWrapText(true);
        description.setPrefRowCount(14);

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
            refreshSyncButtons();
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

        baseBranch.setPromptText("main");
        baseBranch.setPrefColumnCount(14);
        branchName.setPromptText(GitWorkspace.suggestBranchName(ticket.getTitle()));
        title.textProperty().addListener((obs, old, value) ->
                branchName.setPromptText(GitWorkspace.suggestBranchName(value)));
        HBox.setHgrow(branchName, Priority.ALWAYS);
        Label branchLabel = new Label("Branch name");
        HBox branchRow = new HBox(8, baseBranch, branchLabel, branchName);
        branchRow.setAlignment(Pos.CENTER_LEFT);
        branchHint.getStyleClass().add("hint");
        branchHint.setWrapText(true);
        form.addRow(4, new Label("Base branch"), new VBox(2, branchRow, branchHint));

        Label descLabel = new Label("Description");
        GridPane.setValignment(descLabel, javafx.geometry.VPos.TOP);
        form.addRow(5, descLabel, description);
        GridPane.setVgrow(description, Priority.ALWAYS);

        instructions.setPromptText("Instructions for the agent when it picks up this ticket (not synced to "
                + "GitLab/GitHub), e.g. approach, files to look at, things to avoid");
        instructions.setWrapText(true);
        instructions.setPrefRowCount(5);
        Label instructionsLabel = new Label("Initial\ninstructions");
        GridPane.setValignment(instructionsLabel, javafx.geometry.VPos.TOP);
        form.addRow(6, instructionsLabel, instructions);

        designDoc.setPromptText("Leave empty to write the design doc with the agent (a back-and-forth conversation "
                + "in the terminal or Awaiting decision), or paste / write your own here to use it as-is.");
        designDoc.setWrapText(true);
        designDoc.setPrefRowCount(6);
        designStatus.getStyleClass().add("hint");
        designStatus.setWrapText(true);
        exportDesign.setOnAction(e -> exportDesignDoc());
        HBox designActions = new HBox(8, exportDesign, designStatus);
        designActions.setAlignment(Pos.CENTER_LEFT);
        generateDesign.selectedProperty().addListener((obs, old, on) -> {
            designDoc.setDisable(!on && designDoc.getText().isBlank());
        });
        VBox designBox = new VBox(6, generateDesign, designDoc, designActions);
        Label designLabel = new Label("Design doc");
        GridPane.setValignment(designLabel, javafx.geometry.VPos.TOP);
        form.addRow(7, designLabel, designBox);

        VBox links = new VBox(4);
        for (TrackerKind kind : TrackerKind.values()) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            linkRows.put(kind, row);
            links.getChildren().add(row);
        }
        pullRequestRow.setAlignment(Pos.CENTER_LEFT);
        links.getChildren().add(pullRequestRow);
        form.addRow(8, new Label("Linked issues"), links);

        // The sync "menu": filled by refreshSyncButtons() for the selected project's host.
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

    /**
     * The sync menu offers the selected project's repository host only: a project lives on GitHub or GitLab,
     * never both.
     */
    private void refreshSyncButtons() {
        actionsBox.getChildren().clear();
        Label menuTitle = new Label("Sync");
        menuTitle.getStyleClass().add("section-title");
        actionsBox.getChildren().add(menuTitle);
        Project selectedProject = service.settings().project(selectedProjectId()).orElse(null);
        TrackerKind host = selectedProject == null ? null : selectedProject.host();
        String text;
        if (selectedProject == null) {
            text = "Choose a project to sync this ticket with its GitHub repo or GitLab project.";
        } else if (host == null) {
            text = "Project '" + selectedProject.getName() + "' has no GitHub repo or GitLab project yet. Set one "
                    + "in Settings → Organisations & projects.";
        } else {
            actionsBox.getChildren().add(actionButton("Upload ticket info to " + host.displayName(),
                    () -> upload(host)));
            actionsBox.getChildren().add(actionButton("Import ticket info from " + host.displayName(),
                    () -> importFrom(host)));
            text = "Syncs with " + host.displayName() + " " + selectedProject.location(host) + " using the "
                    + "organisation's credentials.";
        }
        Label hint = new Label(text);
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");
        actionsBox.getChildren().add(hint);
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
            instructions.setText(ticket.getInitialInstructions());
            generateDesign.setSelected(ticket.isGenerateDesignDoc());
            designDoc.setText(ticket.getDesignDoc());
            designDoc.setDisable(!ticket.isGenerateDesignDoc() && ticket.getDesignDoc().isBlank());
            refreshDesignStatus();
            baseBranch.setText(ticket.getBaseBranch());
            branchName.setText(ticket.getBranchName());
            boolean locked = ticket.isBranchLocked();
            baseBranch.setDisable(locked);
            branchName.setDisable(locked);
            branchHint.setText(locked
                    ? "Fixed: work has started on this branch (worktree " + ticket.getWorktreePath() + ")."
                    : "The agent works on a new branch created from the base branch; when it finishes, Juno "
                            + "opens a pull request into the base branch. Leave the name blank to use the "
                            + "suggestion.");
            List<Organisation> orgs = service.settings().getOrganisations();
            Organisation org = service.organisationOf(ticket).orElse(orgs.isEmpty() ? null : orgs.getFirst());
            organisation.setValue(org);
            fillProjects(org, ticket.getProjectId());
            fillAgents(ticket.getAgentProfileId());
        } finally {
            loading = false;
        }
        refreshLinks();
        refreshSyncButtons();
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
        pullRequestRow.getChildren().clear();
        Label prName = new Label("Pull request:");
        prName.setMinWidth(55);
        pullRequestRow.getChildren().add(prName);
        PullRequestRef pr = ticket.getPullRequest();
        if (pr != null) {
            Hyperlink link = new Hyperlink(pr.display() + " (" + pr.location() + ")");
            link.setOnAction(e -> hostServices.showDocument(pr.url()));
            pullRequestRow.getChildren().add(link);
        } else if (ticket.getPullRequestError() != null) {
            Label error = new Label(ticket.getPullRequestError());
            error.setWrapText(true);
            error.getStyleClass().add("card-agent-missing");
            pullRequestRow.getChildren().add(error);
        } else {
            Label none = new Label(ticket.isBranchLocked() ? "opened when the agent finishes" : "none yet");
            none.getStyleClass().add("hint");
            pullRequestRow.getChildren().add(none);
        }
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

    private void refreshDesignStatus() {
        String text;
        if (ticket.getDesignDoc().isBlank()) {
            text = generateDesign.isSelected() ? "Start will open a design conversation with the agent first."
                    : "No design doc.";
        } else if (ticket.isDesignDocApproved()) {
            text = "user".equals(ticket.getDesignDocSource()) ? "Your design doc: used as-is for implementation."
                    : "Approved: implementation follows this design.";
        } else {
            text = "Draft from the agent, awaiting your approval (Respond on the card, or /approve in the terminal).";
        }
        if (ticket.getDesignDocFile() != null) {
            text += "\nLast exported to " + ticket.getDesignDocFile();
        }
        designStatus.setText(text);
        exportDesign.setDisable(ticket.getDesignDoc().isBlank() && designDoc.getText().isBlank());
    }

    private void exportDesignDoc() {
        if (!confirmProjectChange()) {
            return;
        }
        applyEdits();
        setBusy(true, "Exporting design doc");
        Fx.async(() -> service.exportDesignDoc(ticket.getId()), path -> {
            setBusy(false, "Design doc saved to " + path);
            refreshDesignStatus();
        }, err -> {
            setBusy(false, "Export failed.");
            Fx.error(getDialogPane().getScene().getWindow(), "Could not export the design doc", err);
        });
    }

    /** Quick sanity check of the branch fields (git validates them fully when work starts). */
    private String branchProblem() {
        if (ticket.isBranchLocked()) {
            return null;
        }
        for (String[] field : new String[][] {{"Base branch", baseBranch.getText()},
                {"Branch name", branchName.getText()}}) {
            String v = field[1] == null ? "" : field[1].strip();
            if (!v.isEmpty() && (v.matches(".*[\\s~^:?*\\[\\\\].*") || v.contains("..") || v.startsWith("-")
                    || v.startsWith("/") || v.endsWith("/") || v.endsWith(".lock") || v.endsWith("."))) {
                return field[0] + " '" + v + "' is not a valid git branch name.";
            }
        }
        String base = baseBranch.getText() == null || baseBranch.getText().isBlank() ? "main" : baseBranch.getText().strip();
        if (base.equals(branchName.getText() == null ? "" : branchName.getText().strip())) {
            return "The branch name must differ from the base branch.";
        }
        return null;
    }

    /** Copies the form into the ticket and persists it (call {@link #confirmProjectChange()} first). */
    private void applyEdits() {
        String projectId = selectedProjectId();
        if (!Objects.equals(projectId, ticket.getProjectId())) {
            service.assignProject(ticket, projectId);
        }
        ticket.setTitle(title.getText().strip());
        ticket.setDescription(description.getText());
        ticket.setInitialInstructions(instructions.getText());
        ticket.setGenerateDesignDoc(generateDesign.isSelected());
        String doc = designDoc.getText() == null ? "" : designDoc.getText();
        if (!doc.strip().equals(ticket.getDesignDoc().strip())) {
            // A doc typed or edited here is the user's own: it is used as-is (approved).
            ticket.setDesignDoc(doc);
            ticket.setDesignDocSource(doc.isBlank() ? null : "user");
            ticket.setDesignDocApproved(!doc.isBlank());
        }
        if (!ticket.isBranchLocked()) {
            ticket.setBaseBranch(baseBranch.getText());
            ticket.setBranchName(branchName.getText());
        }
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
            Fx.inheritStyles(ask, getDialogPane().getScene().getWindow());
            ask.setTitle("Upload to " + kind.displayName());
            ask.setHeaderText("Create a new " + kind.displayName() + " issue from '" + ticket.getTitle() + "'");
            ask.setContentText(kind.displayName() + " " + kind.locationHint() + ":");
            Optional<String> answer = ask.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
            if (answer.isEmpty()) {
                return;
            }
            location = answer.get();
        }
        setBusy(true, "Uploading to " + kind.displayName());
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
                    setBusy(true, "Importing from " + kind.displayName());
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
