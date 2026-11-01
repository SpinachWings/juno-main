package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.SystemSettings;
import dev.juno.model.Validation;
import dev.juno.provider.Provider;
import dev.juno.ui.Ui;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings → System settings (design §5.2). */
public final class SystemTab extends ScrollPane {
    private final AppContext ctx;
    private final Label hookStatus = new Label();
    private final Label hookMessage = Ui.dim("");
    private final Label claudeResult = new Label();
    private final TextField claudePath = new TextField();
    private final CheckBox asks = new CheckBox("Juno asks before assigning");
    private final CheckBox tell = new CheckBox("Tell Juno about direct tasks");
    private final Spinner<Integer> fresh = spinner(1, 100);
    private final Spinner<Integer> report = spinner(100, 20000);
    private final Spinner<Integer> hops = spinner(1, 100);
    private final Spinner<Integer> pipeline = spinner(1, 1440);
    private final Spinner<Integer> maxWorking = spinner(1, 50);
    private final Spinner<Integer> idle = spinner(1, 1440);
    private final TextField notes = new TextField();
    private final Button install = Ui.button("Install", this::installHook);
    private final Button uninstall = Ui.button("Uninstall", this::uninstallHook);

    public SystemTab(AppContext ctx) {
        this.ctx = ctx;
        setFitToWidth(true);
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        setContent(box);

        box.getChildren().add(Ui.subheading("Claude hook"));
        box.getChildren().add(Ui.dim("Makes “Awaiting decision” and “Done” exact. It never blocks or fails "
                + "Claude Code, only reports for agents Juno started, and only adds its own entries to "
                + "~/.claude/settings.json (a timestamped backup is taken before every change)."));
        box.getChildren().add(Ui.row(new Label("Status:"), hookStatus, install, uninstall));
        box.getChildren().add(hookMessage);

        box.getChildren().add(Ui.subheading("Claude Code"));
        claudePath.setPromptText("claude (found on PATH)");
        HBox.setHgrow(claudePath, Priority.ALWAYS);
        box.getChildren().add(Ui.row(new Label("Path"), claudePath, Ui.button("Check", this::checkClaude)));
        claudeResult.setWrapText(true);
        box.getChildren().add(claudeResult);

        box.getChildren().add(Ui.subheading("Delegation"));
        box.getChildren().addAll(asks, tell);
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int r = 0;
        r = row(g, r, "Fresh-start threshold (%)", fresh,
                "Juno proposes a fresh start for an agent whose context passes this share of its window.");
        r = row(g, r, "Report length (characters)", report, "Reports to Juno are cut to this length.");
        r = row(g, r, "Hop limit", hops, "Agent-to-agent messages in a chain before it stops without a message from you.");
        r = row(g, r, "Pipeline wait limit (min)", pipeline, "How long a review waits for a PR's pipelines.");
        r = row(g, r, "Max agents working at once", maxWorking, "Further agents queue. Juno and Waiting agents don't count.");
        r = row(g, r, "Idle process timeout (min)", idle, "An idle agent's process is closed; its next message resumes it.");
        box.getChildren().add(g);

        box.getChildren().add(Ui.subheading("Notes folder"));
        HBox.setHgrow(notes, Priority.ALWAYS);
        notes.setPromptText("Where your notes live (not inside a project folder)");
        box.getChildren().add(Ui.row(notes, Ui.button("Browse…", () ->
                Ui.chooseFolder(getScene().getWindow(), "Notes folder", notes.getText())
                        .ifPresent(f -> notes.setText(f.getAbsolutePath())))));
        // TODO(Milestone 6): refuse a notes folder inside a project folder, or the reverse (design §9).

        box.getChildren().add(Ui.subheading("Storage"));
        Button remove = new Button("Remove worktree");
        Button clean = new Button("Clean up old data");
        remove.setDisable(true);
        clean.setDisable(true);
        box.getChildren().add(Ui.dim("Space used per agent and in total, with Remove worktree and Clean up old data, "
                + "arrives in Milestone 3 together with worktrees."));
        box.getChildren().add(Ui.row(remove, clean));

        box.getChildren().add(Ui.primary("Save", this::save));
        load();
        refreshHook();
    }

    private static Spinner<Integer> spinner(int min, int max) {
        Spinner<Integer> s = new Spinner<>(min, max, min);
        s.setEditable(true);
        s.setPrefWidth(110);
        // Commit typed text when focus leaves, so Save sees it.
        s.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                try {
                    s.getValueFactory().setValue(Integer.parseInt(s.getEditor().getText().trim()));
                } catch (NumberFormatException e) {
                    s.getEditor().setText(String.valueOf(s.getValue()));
                }
            }
        });
        return s;
    }

    private static int row(GridPane g, int r, String text, Node field, String tip) {
        Label l = new Label(text);
        Ui.withTip(l, tip);
        g.add(l, 0, r);
        g.add(field, 1, r);
        return r + 1;
    }

    private void load() {
        SystemSettings s = ctx.settings.system;
        claudePath.setText(s.claudePath);
        asks.setSelected(s.junoAsksBeforeAssigning);
        tell.setSelected(s.tellJunoAboutDirectTasks);
        fresh.getValueFactory().setValue(s.freshStartThreshold);
        report.getValueFactory().setValue(s.reportLength);
        hops.getValueFactory().setValue(s.hopLimit);
        pipeline.getValueFactory().setValue(s.pipelineWaitLimitMinutes);
        maxWorking.getValueFactory().setValue(s.maxAgentsWorking);
        idle.getValueFactory().setValue(s.idleTimeoutMinutes);
        notes.setText(s.notesFolder);
    }

    private void save() {
        SystemSettings s = new SystemSettings();
        s.claudePath = claudePath.getText().trim();
        s.junoAsksBeforeAssigning = asks.isSelected();
        s.tellJunoAboutDirectTasks = tell.isSelected();
        s.freshStartThreshold = fresh.getValue();
        s.reportLength = report.getValue();
        s.hopLimit = hops.getValue();
        s.pipelineWaitLimitMinutes = pipeline.getValue();
        s.maxAgentsWorking = maxWorking.getValue();
        s.idleTimeoutMinutes = idle.getValue();
        s.notesFolder = notes.getText().trim();
        s.firstRunDone = ctx.settings.system.firstRunDone;
        List<String> problems = Validation.system(s);
        if (!problems.isEmpty()) {
            Ui.problems(getScene().getWindow(), problems);
            return;
        }
        boolean pathChanged = !s.claudePath.equals(ctx.settings.system.claudePath);
        ctx.settings.system = s;
        ctx.save();
        if (pathChanged) checkClaude();
    }

    // ---- hook ----

    private void refreshHook() {
        Provider.HookStatus st = ctx.provider.hookStatus();
        hookStatus.getStyleClass().setAll("label", switch (st) {
            case INSTALLED -> "good";
            case OUTDATED -> "warn";
            case NOT_INSTALLED -> "dim";
        });
        hookStatus.setText(switch (st) {
            case INSTALLED -> "installed";
            case OUTDATED -> "outdated";
            case NOT_INSTALLED -> "not installed";
        });
        install.setText(st == Provider.HookStatus.OUTDATED ? "Update" : "Install");
        install.setDisable(st == Provider.HookStatus.INSTALLED);
        uninstall.setDisable(st == Provider.HookStatus.NOT_INSTALLED);
    }

    private void installHook() {
        runHook(ctx.provider::installHook);
    }

    private void uninstallHook() {
        runHook(ctx.provider::uninstallHook);
    }

    private void runHook(java.util.function.Supplier<String> action) {
        try {
            hookMessage.setText(action.get());
            hookMessage.getStyleClass().setAll("label", "dim");
        } catch (RuntimeException e) {
            hookMessage.setText(e.getMessage());
            hookMessage.getStyleClass().setAll("label", "bad");
        }
        refreshHook();
    }

    /** Re-checks the version of the Claude Code path currently typed. */
    public void checkClaude() {
        String exe = claudePath.getText().isBlank() ? "claude" : claudePath.getText().trim();
        claudeResult.setText("Checking…");
        claudeResult.getStyleClass().setAll("label", "dim");
        Ui.background(() -> ctx.provider.checkVersion(exe), v -> {
            claudeResult.setText(v.message());
            claudeResult.getStyleClass().setAll("label", v.ok() ? "good" : "bad");
        });
    }
}
