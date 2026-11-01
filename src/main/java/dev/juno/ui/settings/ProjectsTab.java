package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.Project;
import dev.juno.model.Validation;
import dev.juno.store.SettingsStore;
import dev.juno.ui.Ui;
import java.util.Arrays;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings → Projects (design §5.3). */
public final class ProjectsTab extends BorderPane {
    private final AppContext ctx;
    private final ListView<Project> list = new ListView<>();
    private final TextField name = new TextField();
    private final TextField folder = new TextField();
    private final TextField remote = new TextField();
    private final TextArea commands = new TextArea();
    private final Button delete = Ui.button("Delete", this::deleteProject);
    private final VBox form = new VBox(8);
    private Project editing;

    public ProjectsTab(AppContext ctx) {
        this.ctx = ctx;
        setPadding(new Insets(12));

        list.getStyleClass().add("side-list");
        list.setPrefWidth(220);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Project p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.name);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> { if (p != null) edit(p.copy()); });
        Button add = Ui.button("+ Add project", () -> {
            list.getSelectionModel().clearSelection();
            Project p = new Project();
            p.id = SettingsStore.newId();
            edit(p);
        });
        VBox left = new VBox(8, add, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPadding(new Insets(0, 12, 0, 0));
        setLeft(left);

        HBox.setHgrow(folder, Priority.ALWAYS);
        folder.setPromptText("Local repository folder (must exist)");
        remote.setPromptText("owner/repo (GitHub) or group/project (GitLab), optional");
        commands.setPromptText("One command per line, e.g.\nnpm ci\nnpm test");
        commands.setPrefRowCount(5);
        commands.getStyleClass().add("mono");
        name.setPromptText("e.g. api");
        form.setPadding(new Insets(0, 4, 12, 4));
        form.getChildren().addAll(
                new Label("Name"), name,
                new Label("Local repository folder"),
                Ui.row(folder, Ui.button("Browse…", () ->
                        Ui.chooseFolder(getScene().getWindow(), "Repository folder", folder.getText())
                                .ifPresent(f -> folder.setText(f.getAbsolutePath())))),
                new Label("Remote (optional)"), remote,
                new Label("Build & test commands (optional)"),
                Ui.dim("Allowed for agents that edit files in this project, on top of their profile's tools."),
                commands,
                Ui.row(Ui.primary("Save", this::save), delete));
        ScrollPane sp = new ScrollPane(form);
        sp.setFitToWidth(true);
        setCenter(sp);
        reload(null);
    }

    private void edit(Project p) {
        editing = p;
        form.setDisable(p == null);
        if (p == null) return;
        name.setText(p.name);
        folder.setText(p.folder);
        remote.setText(p.remote);
        commands.setText(String.join("\n", p.buildAndTestCommands));
        delete.setDisable(ctx.settings.projects.stream().noneMatch(x -> x.id.equals(p.id)));
    }

    private void save() {
        Project p = editing.copy();
        p.name = name.getText().trim();
        p.folder = folder.getText().trim();
        p.remote = remote.getText().trim();
        p.buildAndTestCommands = Arrays.stream(commands.getText().split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> problems = Validation.project(p, ctx.settings.projects);
        if (!problems.isEmpty()) {
            Ui.problems(getScene().getWindow(), problems);
            return;
        }
        Project previous = ctx.settings.projects.stream().filter(x -> x.id.equals(p.id)).findFirst().orElse(null);
        if (previous == null) ctx.settings.projects.add(p);
        else ctx.settings.projects.set(ctx.settings.projects.indexOf(previous), p);
        ctx.save();
        reload(p.id);
    }

    private void deleteProject() {
        if (!Ui.confirm(getScene().getWindow(), "Delete project",
                "Delete “" + editing.name + "” from Juno? The folder itself is not touched.", "Delete")) return;
        ctx.settings.projects.removeIf(x -> x.id.equals(editing.id));
        ctx.save();
        reload(null);
    }

    private void reload(String selectId) {
        list.setItems(FXCollections.observableArrayList(ctx.settings.projects));
        Project sel = ctx.settings.projects.stream().filter(x -> x.id.equals(selectId)).findFirst()
                .orElse(ctx.settings.projects.isEmpty() ? null : ctx.settings.projects.get(0));
        if (sel != null) list.getSelectionModel().select(sel);
        else edit(null);
    }
}
