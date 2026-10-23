package dev.juno.main.ui;

import dev.juno.main.integration.IssueData;
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

/** Asks which GitLab/GitHub issue to import: a project/repo plus an issue number, with a browsable list. */
final class IssuePickerDialog extends Dialog<IssuePickerDialog.Selection> {

    record Selection(String location, long number) {
    }

    private final BoardService service;
    private final TrackerKind kind;
    private final ComboBox<String> location = new ComboBox<>();
    private final TextField number = new TextField();
    private final ListView<IssueData> issues = new ListView<>();
    private final Label status = new Label();
    private final ProgressIndicator busy = new ProgressIndicator(-1);

    IssuePickerDialog(Window owner, BoardService service, TrackerKind kind, String defaultLocation, Long defaultNumber) {
        this.service = service;
        this.kind = kind;
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle("Import from " + kind.displayName());
        setHeaderText("Choose a " + kind.displayName() + " issue");
        setResizable(true);

        ButtonType importType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);

        location.setEditable(true);
        location.setPromptText(kind.locationHint());
        location.setMaxWidth(Double.MAX_VALUE);
        for (Project p : service.settings().getProjects()) {
            if (!p.location(kind).isEmpty() && !location.getItems().contains(p.location(kind))) {
                location.getItems().add(p.location(kind));
            }
        }
        location.getEditor().setText(defaultLocation == null ? "" : defaultLocation);
        location.setOnAction(e -> loadIssues());

        number.setPromptText("e.g. 42");
        number.setPrefColumnCount(8);
        if (defaultNumber != null) {
            number.setText(Long.toString(defaultNumber));
        }

        Button load = new Button("Load open issues");
        load.setOnAction(e -> loadIssues());

        issues.setPlaceholder(new Label("Enter a " + kind.locationHint() + " and load its open issues,\n"
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
        form.addRow(0, new Label(kind.displayName() + " " + kind.locationHint()), locationRow);
        form.addRow(1, new Label("Issue number"), number);
        GridPane.setHgrow(locationRow, Priority.ALWAYS);

        busy.setPrefSize(16, 16);
        busy.setVisible(false);
        HBox statusRow = new HBox(8, busy, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, form, issues, statusRow);
        VBox.setVgrow(issues, Priority.ALWAYS);
        content.setPrefWidth(620);
        getDialogPane().setContent(content);

        Node importButton = getDialogPane().lookupButton(importType);
        importButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> locationText().isEmpty() || parseNumber() == null,
                location.getEditor().textProperty(), number.textProperty()));

        setResultConverter(button -> button == importType
                ? new Selection(locationText(), parseNumber())
                : null);

        setOnShown(e -> {
            if (!locationText().isEmpty()) {
                loadIssues();
            }
        });
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

    private void loadIssues() {
        String loc = locationText();
        if (loc.isEmpty()) {
            status.setText("Enter a " + kind.locationHint() + " first.");
            return;
        }
        busy.setVisible(true);
        status.setText("Loading issues from " + loc + "…");
        Fx.async(() -> service.listIssues(kind, loc), list -> {
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
