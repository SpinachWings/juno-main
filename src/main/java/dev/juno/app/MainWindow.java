package dev.juno.app;

import dev.juno.model.Agent;
import dev.juno.provider.AgentEvent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.ui.Ui;
import dev.juno.ui.pane.AgentPane;
import dev.juno.ui.pane.SetupForm;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
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

/** The main window: toolbar, board / notes toggle and, until the board arrives (Milestone 3), one agent pane. */
public final class MainWindow {
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final VBox banners = new VBox();
    private final Stage stage;
    private final StackPane boardArea = new StackPane();
    private final ComboBox<Agent> switcher = new ComboBox<>();
    private final Label usage = new Label("usage —");
    private final Map<String, AgentPane> panes = new HashMap<>();
    private AgentPane current;
    private boolean updatingSwitcher;

    public MainWindow(AppContext ctx, Stage stage) {
        this.ctx = ctx;
        this.stage = stage;

        Label logo = new Label("JUNO");
        logo.getStyleClass().add("logo");

        // TODO(Milestone 3): "+ New agent" splits the pane last used, with the in-cell setup form (design §7.1).
        Button add = Ui.button("+ New agent", this::showSetup);
        // TODO(Milestone 3): the board and filter list replace this one-pane switcher.
        switcher.setPromptText("agents");
        switcher.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Agent a) { return a == null ? "" : (a.unread ? "● " : "") + a.name + " · " + a.statusText(); }
            @Override public Agent fromString(String s) { return null; }
        });
        switcher.valueProperty().addListener((o, a, b) -> { if (b != null && !updatingSwitcher) showAgent(b); });

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

        usage.getStyleClass().add("meter");
        usage.setTooltip(new javafx.scene.control.Tooltip("Your Claude plan's limits, from Claude Code's rate-limit "
                + "events. Shown once an agent has run."));

        Button help = Ui.button("?", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.COMMANDS));
        Ui.withTip(help, "Commands");
        Button settings = Ui.button("Settings", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.KEYS));

        HBox bar = new HBox(10, logo, add, switcher, board, notes, Ui.grow(), usage, help, settings);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");

        boardArea.getStyleClass().add("board-placeholder");
        VBox top = new VBox(bar, banners);
        root.setTop(top);
        root.setCenter(boardArea);
        ctx.onSaved(this::checkClaude);
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void rateLimit(AgentEvent.RateLimit r) { Platform.runLater(() -> showUsage(r)); }
            @Override public void changed(Agent a) { Platform.runLater(MainWindow.this::refreshSwitcher); }
            @Override public void agentsChanged() { Platform.runLater(MainWindow.this::refreshSwitcher); }
        });
        refreshSwitcher();
        List<Agent> agents = ctx.runtime.agents();
        if (agents.isEmpty()) showSetup();
        else showAgent(agents.get(agents.size() - 1));
    }

    private void showSetup() {
        boolean any = !ctx.runtime.agents().isEmpty();
        SetupForm form = new SetupForm(ctx, this::showAgent, any ? () -> {
            if (current != null) showAgent(current.agent());
        } : null);
        boardArea.getChildren().setAll(form.root());
        StackPane.setAlignment(form.root(), javafx.geometry.Pos.CENTER);
    }

    /** The pane shown now, if any (used by the screenshot tools). */
    public AgentPane currentPane() {
        return current;
    }

    public void showAgent(Agent a) {
        AgentPane pane = panes.computeIfAbsent(a.id, id -> new AgentPane(ctx, a));
        current = pane;
        boardArea.getChildren().setAll(pane.root());
        refreshSwitcher();
        pane.focusInput();
    }

    private void refreshSwitcher() {
        updatingSwitcher = true;
        try {
            switcher.getItems().setAll(ctx.runtime.agents());
            switcher.setValue(current == null ? null : current.agent());
            switcher.setVisible(!switcher.getItems().isEmpty());
            switcher.setManaged(switcher.isVisible());
        } finally {
            updatingSwitcher = false;
        }
    }

    /** {@code 5h 60% · week 68%}, reset times on hover; amber above 80 %, red above 95 % (design §7.2). */
    private void showUsage(AgentEvent.RateLimit r) {
        List<String> parts = new java.util.ArrayList<>();
        List<String> resets = new java.util.ArrayList<>();
        double worst = 0;
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm");
        if (r.fiveHour() >= 0) {
            parts.add("5h " + Math.round(r.fiveHour() * 100) + "%");
            worst = Math.max(worst, r.fiveHour());
            if (r.fiveHourReset() > 0) resets.add("5-hour limit resets " + f.format(
                    java.time.Instant.ofEpochSecond(r.fiveHourReset()).atZone(java.time.ZoneId.systemDefault())));
        }
        if (r.week() >= 0) {
            parts.add("week " + Math.round(r.week() * 100) + "%");
            worst = Math.max(worst, r.week());
            if (r.weekReset() > 0) resets.add("weekly limit resets " + f.format(
                    java.time.Instant.ofEpochSecond(r.weekReset()).atZone(java.time.ZoneId.systemDefault())));
        }
        if (parts.isEmpty()) return;
        usage.setText(String.join(" · ", parts));
        usage.getStyleClass().removeAll("warn", "bad");
        if (worst > 0.95) usage.getStyleClass().add("bad");
        else if (worst > 0.80) usage.getStyleClass().add("warn");
        usage.getTooltip().setText(String.join("\n", resets));
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
