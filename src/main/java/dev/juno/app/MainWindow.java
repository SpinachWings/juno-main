package dev.juno.app;

import dev.juno.ui.Ui;
import dev.juno.ui.settings.SettingsWindow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** The main window shell: toolbar, board / notes toggle, and (from Milestone 3) the board. */
public final class MainWindow {
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final VBox banners = new VBox();
    private final Stage stage;

    public MainWindow(AppContext ctx, Stage stage) {
        this.ctx = ctx;
        this.stage = stage;

        Label logo = new Label("JUNO");
        logo.getStyleClass().add("logo");

        // TODO(Milestone 3): "+ Add agent" opens the in-cell setup form (design §7.10).
        Button add = Ui.button("+ Add agent", () -> Ui.info(stage, "Add agent",
                "Agents and the board arrive in Milestones 2 and 3."));

        ToggleGroup view = new ToggleGroup();
        ToggleButton board = new ToggleButton("Board");
        // TODO(Milestone 6): the notepad.
        ToggleButton notes = new ToggleButton("Notes");
        board.setToggleGroup(view);
        notes.setToggleGroup(view);
        board.setSelected(true);
        notes.setDisable(true);
        Ui.withTip(notes, "The notepad arrives in Milestone 6.");
        view.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });

        // TODO(Milestone 2): usage-limit meter (design §7.2).
        Label usage = new Label("usage —");
        usage.getStyleClass().add("meter");
        Ui.withTip(usage, "Usage-limit meter arrives in Milestone 2.");

        Button help = Ui.button("?", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.COMMANDS));
        Ui.withTip(help, "Commands");
        Button settings = Ui.button("Settings", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.KEYS));

        HBox bar = new HBox(10, logo, add, board, notes, Ui.grow(), usage, help, settings);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");

        Label stub = new Label("The board — Milestone 3");
        stub.getStyleClass().add("heading");
        Label hint = Ui.dim("Open Settings to set up keys, projects, profiles and skills.");
        VBox centre = new VBox(6, stub, hint);
        centre.setAlignment(Pos.CENTER);
        StackPane board0 = new StackPane(centre);
        board0.getStyleClass().add("board-placeholder");

        VBox top = new VBox(bar, banners);
        root.setTop(top);
        root.setCenter(board0);
        ctx.onSaved(this::checkClaude);
    }

    public BorderPane root() {
        return root;
    }

    /** Checks the Claude Code version and shows a banner when it is missing or too old (design §10). */
    public void checkClaude() {
        String exe = ctx.settings.system.effectiveClaudePath();
        Ui.background(() -> ctx.provider.checkVersion(exe), v -> {
            banners.getChildren().clear();
            if (!v.ok()) {
                Label l = new Label("⚠ " + v.message());
                l.setWrapText(true);
                l.getStyleClass().addAll("banner-bad", "bad");
                l.setMaxWidth(Double.MAX_VALUE);
                banners.getChildren().add(l);
                banners.setPadding(new Insets(0));
            }
        });
    }
}
