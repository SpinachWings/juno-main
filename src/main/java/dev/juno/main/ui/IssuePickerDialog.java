package dev.juno.main.ui;

import dev.juno.main.integration.IssueData;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.service.BoardService;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;

/**
 * Asks which GitLab/GitHub issue to import: organisation and project (whose organisation's credentials are
 * used), the GitLab project / GitHub repo (pre-filled from the project) and an issue number, with a
 * browsable list of open issues.
 */
final class IssuePickerDialog extends Dialog<IssuePickerDialog.Selection> {

    record Selection(String projectId, String location, long number) {
    }

    private final BoardService service;
    private final TrackerKind kind;
    private final JunoSettings settings;
    private final ComboBox<Organisation> organisation = new ComboBox<>();
    private final ComboBox<Project> project = new ComboBox<>();
    private final ComboBox<String> location = new ComboBox<>();
    private final TextField number = new TextField();
    private final ListView<IssueData> issues = new ListView<>();
    private final Label status = new Label();
    private final ProgressIndicator busy = new ProgressIndicator(-1);

    /**
     * @param orgId           organisation to pre-select (may be null)
     * @param projectId       project to pre-select (may be null; implies its organisation)
     * @param defaultLocation location to pre-fill instead of the project's (may be null)
     * @param defaultNumber   issue number to pre-fill (may be null)
     */
    IssuePickerDialog(Window owner, BoardService service, TrackerKind kind, String orgId, String projectId,
                      String defaultLocation, Long defaultNumber) {
        this.service = service;
        this.kind = kind;
        this.settings = service.settings();
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle("Import from " + kind.displayName());
        setHeaderText("Choose a " + kind.displayName() + " issue");
        setResizable(true);

        ButtonType importType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);

        organisation.getItems().setAll(settings.getOrganisations());
        organisation.setMaxWidth(Double.MAX_VALUE);
        project.setMaxWidth(Double.MAX_VALUE);
        project.setPromptText("Choose a project");

        location.setEditable(true);
        location.setPromptText(kind.locationHint());
        location.setMaxWidth(Double.MAX_VALUE);
        location.setOnAction(e -> loadIssues());

        // Pre-selection: the project implies the organisation.
        Project initialProject = settings.project(projectId).orElse(null);
        String initialOrg = initialProject != null ? initialProject.getOrgId() : orgId;
        Organisation org = settings.organisation(initialOrg)
                .orElse(settings.getOrganisations().isEmpty() ? null : settings.getOrganisations().getFirst());
        organisation.setValue(org);
        fillProjects(org, initialProject);
        if (defaultLocation != null && !defaultLocation.isBlank()) {
            location.getEditor().setText(defaultLocation);
        }

        organisation.setOnAction(e -> {
            issues.getItems().clear();
            // Selecting the org's project (if it has exactly one) triggers loading via the project handler.
            fillProjects(organisation.getValue(), null);
        });
        project.setOnAction(e -> {
            Project p = project.getValue();
            if (p != null && !p.location(kind).isEmpty()) {
                location.getEditor().setText(p.location(kind));
                loadIssuesIfReady();
            }
        });

        number.setPromptText("e.g. 42");
        number.setPrefColumnCount(8);
        if (defaultNumber != null) {
            number.setText(Long.toString(defaultNumber));
        }

        Button load = new Button("Load open issues");
        load.setOnAction(e -> loadIssues());

        issues.setPlaceholder(new Label("Choose a project and load its open issues,\n"
                + "or type an issue number directly."));
        issues.setPrefHeight(300);
        issues.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                number.setText(Long.toString(sel.number()));
            }
        });
        issues.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && issues.getSelectionModel().getSelectedItem() != null) {
                ((Button) getDialogPane().lookupButton(importType)).fire();
            }
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        HBox locationRow = new HBox(8, location, load);
        HBox.setHgrow(location, Priority.ALWAYS);
        form.addRow(0, new Label("Organisation"), organisation);
        form.addRow(1, new Label("Import into project"), project);
        form.addRow(2, new Label(kind.displayName() + " " + kind.locationHint()), locationRow);
        form.addRow(3, new Label("Issue number"), number);
        GridPane.setHgrow(locationRow, Priority.ALWAYS);
        GridPane.setHgrow(organisation, Priority.ALWAYS);
        GridPane.setHgrow(project, Priority.ALWAYS);

        Label hint = new Label("The organisation's " + kind.displayName() + " credentials are used. If another "
                + "project in the organisation is configured for this " + kind.locationHint()
                + ", the ticket is linked to that project instead.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");

        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        HBox statusRow = new HBox(8, busy, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, form, hint, issues, statusRow);
        VBox.setVgrow(issues, Priority.ALWAYS);
        content.setPrefWidth(640);
        getDialogPane().setContent(content);

        Node importButton = getDialogPane().lookupButton(importType);
        importButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> project.getValue() == null || locationText().isEmpty() || parseNumber() == null,
                project.valueProperty(), location.getEditor().textProperty(), number.textProperty()));

        setResultConverter(button -> button == importType
                ? new Selection(project.getValue().getId(), locationText(), parseNumber())
                : null);

        setOnShown(e -> loadIssuesIfReady());
    }

    private void fillProjects(Organisation org, Project preferred) {
        project.getItems().setAll(org == null ? java.util.List.of() : settings.projectsIn(org.getId()));
        Project select = preferred != null && project.getItems().contains(preferred) ? preferred
                : project.getItems().size() == 1 ? project.getItems().getFirst() : null;
        project.setValue(select);
        location.getItems().clear();
        for (Project p : project.getItems()) {
            if (!p.location(kind).isEmpty() && !location.getItems().contains(p.location(kind))) {
                location.getItems().add(p.location(kind));
            }
        }
        location.getEditor().setText(select == null ? "" : select.location(kind));
        if (org != null && !org.tracker(kind).isEnabled()) {
            status.setText(kind.displayName() + " is not enabled for " + org.getName() + " (Settings).");
        } else if (org != null && project.getItems().isEmpty()) {
            status.setText(org.getName() + " has no projects yet. Add one in Settings.");
        } else {
            status.setText("");
        }
    }

    private String locationText() {
        String text = location.getEditor().getText();
        return text == null ? "" : text.strip();
    }

    private Long parseNumber() {
        try {
            long n = Long.parseLong(number.getText().strip().replaceFirst("^#", ""));
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void loadIssuesIfReady() {
        Organisation org = organisation.getValue();
        if (org != null && org.tracker(kind).isEnabled() && !locationText().isEmpty()) {
            loadIssues();
        }
    }

    private void loadIssues() {
        Organisation org = organisation.getValue();
        String loc = locationText();
        if (org == null) {
            status.setText("Choose an organisation first.");
            return;
        }
        if (loc.isEmpty()) {
            status.setText("Enter a " + kind.locationHint() + " first.");
            return;
        }
        busy.setVisible(true);
        status.setText("Loading issues from " + loc + " (" + org.getName() + ")…");
        String orgId = org.getId();
        Fx.async(() -> service.listIssues(orgId, kind, loc), list -> {
            if (!Objects.equals(organisation.getValue() == null ? null : organisation.getValue().getId(), orgId)) {
                return; // organisation changed while loading
            }
            busy.setVisible(false);
            issues.getItems().setAll(list);
            status.setText(list.isEmpty() ? "No open issues in " + loc + "." : list.size() + " open issue(s).");
        }, err -> {
            busy.setVisible(false);
            issues.getItems().clear();
            status.setText("Could not load issues: " + err.getMessage());
        });
    }
}
