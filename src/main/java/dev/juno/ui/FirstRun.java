package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.model.Project;
import dev.juno.model.Validation;
import dev.juno.provider.Provider;
import dev.juno.store.SettingsStore;
import dev.juno.ui.settings.SettingsWindow;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** The skippable first-run setup (design §5.1.2). */
public final class FirstRun {
    private final AppContext ctx;
    private final Stage stage = new Stage();
    private final StackPane steps = new StackPane();
    private final List<Node> pages;
    private final Button back = Ui.button("Back", this::back);
    private final Button next = Ui.primary("Next", this::next);
    private final Button skip = Ui.button("Skip setup", this::finish);
    private int index;

    private final TextField claudePath = new TextField();
    private final Label claudeResult = new Label();
    private final Label hookStatus = new Label();
    private final Label hookMessage = Ui.dim("");
    private final TextField notes = new TextField();
    private final TextField projectName = new TextField();
    private final TextField projectFolder = new TextField();
    private final TextField projectRemote = new TextField();
    private final TextArea projectCommands = new TextArea();

    private FirstRun(AppContext ctx, Window owner) {
        this.ctx = ctx;
        stage.setTitle("Welcome to Juno");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        pages = List.of(welcome(), claudePage(), hookPage(), notesPage(), projectPage(), keysPage());

        HBox buttons = new HBox(8, skip, Ui.grow(), back, next);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 16, 14, 16));
        BorderPane root = new BorderPane(steps);
        root.setBottom(buttons);
        steps.setPadding(new Insets(16));
        Scene scene = new Scene(root, 640, 520);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> markDone());
        show(0);
    }

    /** Shows the setup and returns when it is closed. */
    public static void run(AppContext ctx, Window owner) {
        new FirstRun(ctx, owner).stage.showAndWait();
    }

    /** Opens the setup without waiting (for screenshots and tests). */
    public static FirstRun open(AppContext ctx, Window owner) {
        FirstRun f = new FirstRun(ctx, owner);
        f.stage.show();
        return f;
    }

    public Stage stage() {
        return stage;
    }

    public void show(int i) {
        index = i;
        steps.getChildren().setAll(pages.get(i));
        back.setDisable(i == 0);
        next.setText(i == pages.size() - 1 ? "Finish" : "Next");
        skip.setVisible(i < pages.size() - 1);
    }

    private void back() {
        show(index - 1);
    }

    private void next() {
        if (index == 1) saveClaudePath();
        if (index == 3) ctx.settings.system.notesFolder = notes.getText().trim();
        if (index == 4 && !saveProject()) return;
        if (index == pages.size() - 1) {
            finish();
            return;
        }
        ctx.save();
        show(index + 1);
    }

    private void finish() {
        markDone();
        stage.close();
    }

    private void markDone() {
        ctx.settings.system.firstRunDone = true;
        ctx.save();
    }

    private VBox page(String title, Node... content) {
        VBox v = new VBox(12);
        v.getChildren().add(label(title));
        v.getChildren().addAll(content);
        return v;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("step-title");
        return l;
    }

    private Node welcome() {
        return page("Welcome to Juno",
                Ui.dim("Juno runs coding agents side by side on a board, with one orchestrator, Juno, who hands out "
                        + "tasks and collects reports."),
                Ui.dim("A short setup: check Claude Code, install the Claude hook, pick a notes folder, add a first "
                        + "project, and add your GitHub / GitLab keys. Every step can be skipped and everything can "
                        + "be changed later in Settings."));
    }

    private Node claudePage() {
        claudePath.setText(ctx.settings.system.claudePath);
        claudePath.setPromptText("claude (found on PATH)");
        HBox.setHgrow(claudePath, Priority.ALWAYS);
        claudeResult.setWrapText(true);
        Runnable check = this::checkClaude;
        javafx.application.Platform.runLater(check);
        return page("Claude Code",
                Ui.dim("Juno drives the Claude Code command line. Leave the path blank to use `claude` from your PATH."),
                Ui.row(claudePath, Ui.button("Check", check)), claudeResult);
    }

    private void checkClaude() {
        String exe = claudePath.getText().isBlank() ? "claude" : claudePath.getText().trim();
        claudeResult.setText("Checking…");
        claudeResult.getStyleClass().setAll("label", "dim");
        Ui.background(() -> ctx.provider.checkVersion(exe), v -> {
            claudeResult.setText(v.message());
            claudeResult.getStyleClass().setAll("label", v.ok() ? "good" : "bad");
        });
    }

    private void saveClaudePath() {
        ctx.settings.system.claudePath = claudePath.getText().trim();
    }

    private Node hookPage() {
        Button install = Ui.button("Install hook", () -> {
            try {
                hookMessage.setText(ctx.provider.installHook());
            } catch (RuntimeException e) {
                hookMessage.setText(e.getMessage());
            }
            refreshHook();
        });
        refreshHook();
        return page("Claude hook",
                Ui.dim("The hook makes “Awaiting decision” and “Done” exact. It only adds its own "
                        + "entries to ~/.claude/settings.json, never blocks Claude Code, and a backup is taken first. "
                        + "You can uninstall it any time in System settings."),
                Ui.row(new Label("Status:"), hookStatus, install), hookMessage);
    }

    private void refreshHook() {
        Provider.HookStatus st = ctx.provider.hookStatus();
        hookStatus.setText(st.name().toLowerCase().replace('_', ' '));
        hookStatus.getStyleClass().setAll("label", st == Provider.HookStatus.INSTALLED ? "good" : "warn");
    }

    private Node notesPage() {
        notes.setText(ctx.settings.system.notesFolder);
        notes.setPromptText("Where your notes live (not inside a project folder)");
        HBox.setHgrow(notes, Priority.ALWAYS);
        return page("Notes folder",
                Ui.dim("Your notes are plain Markdown files in a folder you choose. Agents never get this folder."),
                Ui.row(notes, Ui.button("Browse…", () ->
                        Ui.chooseFolder(stage, "Notes folder", notes.getText())
                                .ifPresent(f -> notes.setText(f.getAbsolutePath())))));
    }

    private Node projectPage() {
        HBox.setHgrow(projectFolder, Priority.ALWAYS);
        projectName.setPromptText("e.g. api");
        projectFolder.setPromptText("Local repository folder");
        projectRemote.setPromptText("owner/repo or group/project, optional");
        projectCommands.setPromptText("Build & test commands, one per line, optional");
        projectCommands.setPrefRowCount(4);
        return page("A first project",
                Ui.dim("A project is a local repository, optionally with a GitHub or GitLab remote. Leave it blank to add "
                        + "projects later."),
                new Label("Name"), projectName, new Label("Folder"),
                Ui.row(projectFolder, Ui.button("Browse…", () ->
                        Ui.chooseFolder(stage, "Repository folder", projectFolder.getText())
                                .ifPresent(f -> projectFolder.setText(f.getAbsolutePath())))),
                new Label("Remote"), projectRemote, new Label("Build & test commands"), projectCommands);
    }

    private boolean saveProject() {
        if (projectName.getText().isBlank() && projectFolder.getText().isBlank()) return true;
        Project p = new Project();
        p.id = SettingsStore.newId();
        p.name = projectName.getText().trim();
        p.folder = projectFolder.getText().trim();
        p.remote = projectRemote.getText().trim();
        p.buildAndTestCommands = java.util.Arrays.stream(projectCommands.getText().split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> problems = Validation.project(p, ctx.settings.projects);
        if (!problems.isEmpty()) {
            Ui.problems(stage, problems);
            return false;
        }
        ctx.settings.projects.add(p);
        projectName.clear();
        projectFolder.clear();
        projectRemote.clear();
        projectCommands.clear();
        return true;
    }

    private Node keysPage() {
        return page("GitHub / GitLab keys",
                Ui.dim("Agents reach GitHub and GitLab only through the key you give each one. Add your own and any bot "
                        + "accounts' keys per host now, then pick them when you create agents. Tokens are kept in your "
                        + "OS keyring, never in a file."),
                Ui.button("Open Keys settings…", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.KEYS)),
                Ui.dim("Creating an agent for a project whose host has no key shows a warning in its setup form."));
    }
}
