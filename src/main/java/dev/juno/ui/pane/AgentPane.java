package dev.juno.ui.pane;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.provider.AgentEvent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.CommandRegistry;
import dev.juno.runtime.LiveAgent;
import dev.juno.runtime.Meters;
import dev.juno.runtime.PaneCommands;
import dev.juno.ui.Ui;
import dev.juno.ui.settings.SettingsWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * One agent's pane (design §7.2–§7.5): top bar, rendered output, permission and folder cards, the "can talk to" line,
 * the context meter and the input with autocomplete. It only shows the agent; the agent's events live in the runtime.
 */
public final class AgentPane {
    private final AppContext ctx;
    private final AgentRuntime runtime;
    private final PaneCommands commands;
    private final Agent agent;
    private final BorderPane root = new BorderPane();
    private final OutputView output;
    private final Label status = new Label();
    private final Label unread = new Label("●");
    private final Label name = new Label();
    private final Label where = new Label();
    private final Label modelLabel = new Label();
    private final Button resume = Ui.button("⟳ Resume", this::resume);
    private final Button stop = Ui.button("■ Stop", this::stop);
    private final Label contacts = new Label();
    private final Label context = new Label();
    private final Label turnInfo = new Label();
    private final Label pending = new Label();
    private final VBox cards = new VBox(6);
    private final TextArea input = new TextArea();
    private String shownPermission;
    private boolean folderCardShown;

    public AgentPane(AppContext ctx, Agent agent) {
        this.ctx = ctx;
        this.runtime = ctx.runtime;
        this.commands = new PaneCommands(runtime);
        this.agent = agent;
        this.output = new OutputView(agent.name, () -> ctx.runtime.folder(agent).toString());

        unread.getStyleClass().add("unread-dot");
        name.getStyleClass().add("pane-name");
        where.getStyleClass().add("dim");
        modelLabel.getStyleClass().add("dim");
        status.getStyleClass().add("status-chip");
        Button options = Ui.button("⋯", () -> {});
        options.setDisable(true);
        // TODO(Milestone 3): maximise, split and the ⋯ options menu (design §7.1, §7.10).
        Ui.withTip(options, "The options menu arrives with the board (Milestone 3).");
        HBox top = new HBox(8, status, unread, name, where, Ui.grow(), modelLabel, resume, stop, options);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("pane-top");

        contacts.getStyleClass().add("dim");
        context.getStyleClass().add("meter-text");
        turnInfo.getStyleClass().add("dim");
        pending.getStyleClass().add("warn");
        HBox meters = new HBox(14, contacts, Ui.grow(), pending, turnInfo, context);
        meters.setAlignment(Pos.CENTER_LEFT);
        meters.getStyleClass().add("pane-meters");

        input.setPromptText("Message " + agent.name + " — / for commands");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.getStyleClass().add("pane-input");
        Autocomplete ac = new Autocomplete(input, this::suggestions);
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown() && !ac.showing()) {
                e.consume();
                submit();
            }
        });
        input.focusedProperty().addListener((o, a, b) -> { if (b) runtime.markRead(agent); });
        output.onUserScroll(() -> runtime.markRead(agent));
        output.node().setOnMousePressed(e -> runtime.markRead(agent));

        VBox bottom = new VBox(6, cards, meters, input);
        bottom.getStyleClass().add("pane-bottom");
        root.setTop(top);
        root.setCenter(output.node());
        root.setBottom(bottom);
        root.getStyleClass().add("agent-pane");

        output.addAll(runtime.live(agent.id).history());
        runtime.addListener(new AgentRuntime.Listener() {
            @Override public void changed(Agent a) { if (a == agent) Platform.runLater(AgentPane.this::refresh); }

            @Override public void event(Agent a, AgentEvent e) {
                if (a == agent) Platform.runLater(() -> {
                    output.add(e);
                    refresh();
                });
            }

            @Override public void agentsChanged() {
                Platform.runLater(() -> {
                    if (runtime.live(agent.id).history().isEmpty()) output.clear();
                    refresh();
                });
            }
        });
        ctx.onSaved(() -> Platform.runLater(this::refresh));
        refresh();
    }

    public BorderPane root() {
        return root;
    }

    public Agent agent() {
        return agent;
    }

    public void focusInput() {
        input.requestFocus();
    }

    // ------------------------------------------------------------------ state → view

    private void refresh() {
        LiveAgent l = runtime.live(agent.id);
        Profile p = runtime.profile(agent.profile);
        Project project = runtime.project(agent);
        status.setText(agent.statusText());
        status.getStyleClass().removeAll("good", "warn", "bad");
        switch (agent.status) {
            case WORKING -> status.getStyleClass().add("good");
            case AWAITING -> status.getStyleClass().add(agent.reason == Agent.Reason.FAILED
                    || agent.reason == Agent.Reason.INTERRUPTED ? "bad" : "warn");
            default -> { }
        }
        tip(status, agent.statusDetail);
        unread.setVisible(agent.unread);
        name.setText(agent.name);
        where.setText(agent.profile + " · " + (project == null ? "no folder" : project.name));
        String model = p == null ? "" : runtime.effectiveModel(agent, p);
        String effort = p == null ? "" : runtime.effectiveEffort(agent, p);
        modelLabel.setText((model.isBlank() ? "default model" : model) + " · " + (effort.isBlank() ? "default effort" : effort));
        resume.setDisable(!(agent.interrupted || (agent.status == Agent.Status.AWAITING
                && agent.reason == Agent.Reason.FAILED)) || agent.turnOpen);
        stop.setDisable(!agent.turnOpen && agent.heldMessage == null);
        root.setStyle("-fx-border-color: " + agent.colour + ";");
        // TODO(Milestone 4): linked agents in their colours (design §7.4).
        contacts.setText("can talk to: Juno");

        long threshold = ctx.settings.system.freshStartThreshold;
        int pct = Meters.percent(l.contextTokens, l.contextWindow);
        context.setText("ctx " + Meters.context(l.contextTokens, l.contextWindow));
        context.getStyleClass().removeAll("warn", "bad");
        if (l.contextWindow > 0 && pct >= 90) context.getStyleClass().add("bad");
        else if (l.contextWindow > 0 && pct > threshold) context.getStyleClass().add("warn");
        tip(context, "Last call — input " + l.lastInput + " · output " + l.lastOutput + " · cache read "
                + l.lastCacheRead + " · cache write " + l.lastCacheWrite);
        turnInfo.setText("last turn " + Meters.tokens(l.lastTurnTokens) + " · conversation "
                + Meters.tokens(l.conversationTokens) + " · " + Meters.usd(l.conversationCost) + " API-price estimate");
        List<String> notes = new ArrayList<>();
        if (agent.pendingSkill != null) notes.add("next message loads " + agent.pendingSkill);
        if (runtime.pendingRestart(agent)) notes.add("model/effort apply from the next message");
        pending.setText(String.join(" · ", notes));
        refreshCards(l);
    }

    /** One tooltip per label, updated in place. */
    private static void tip(Label l, String text) {
        if (text == null || text.isBlank()) {
            l.setTooltip(null);
            return;
        }
        if (l.getTooltip() == null) {
            javafx.scene.control.Tooltip t = new javafx.scene.control.Tooltip();
            t.setWrapText(true);
            t.setMaxWidth(420);
            l.setTooltip(t);
        }
        l.getTooltip().setText(text);
    }

    private void refreshCards(LiveAgent l) {
        List<LiveAgent.PendingPermission> perms = l.pendingPermissions();
        boolean folder = agent.status == Agent.Status.AWAITING && agent.reason == Agent.Reason.FOLDER;
        String permId = perms.isEmpty() ? null : perms.get(0).id;
        if (java.util.Objects.equals(permId, shownPermission) && folder == folderCardShown) return;
        shownPermission = permId;
        folderCardShown = folder;
        cards.getChildren().clear();
        if (permId != null) cards.getChildren().add(permissionCard(perms.get(0)));
        if (folder) cards.getChildren().add(folderCard(l));
    }

    /** The four answers and the editable rule (design §7.5). */
    private VBox permissionCard(LiveAgent.PendingPermission pp) {
        Profile p = runtime.profile(agent.profile);
        Label title = new Label("🔐 " + agent.name + " wants " + pp.tool + ": " + permissionDetail(pp));
        title.setWrapText(true);
        title.getStyleClass().add("card-title");
        TextField rule = new TextField(pp.proposedRule);
        rule.getStyleClass().add("mono");
        HBox.setHgrow(rule, Priority.ALWAYS);
        Label desc = Ui.dim("= " + pp.proposedDescription);
        rule.textProperty().addListener((o, a, b) -> desc.setText(
                dev.juno.comms.PermissionRules.valid(b) ? "= " + dev.juno.comms.PermissionRules.describe(b) : "not a valid rule"));
        Button once = Ui.primary("Allow once", () -> answer(pp, AgentRuntime.Answer.ALLOW_ONCE, null));
        Button forAgent = Ui.button("Always allow for " + agent.name, () -> answer(pp, AgentRuntime.Answer.ALLOW_AGENT, rule.getText()));
        Button forProfile = Ui.button("Always allow for " + agent.profile, () -> answer(pp, AgentRuntime.Answer.ALLOW_PROFILE, rule.getText()));
        Button deny = Ui.button("Deny", () -> answer(pp, AgentRuntime.Answer.DENY, null));
        deny.getStyleClass().add("danger");
        HBox buttons = new HBox(8, once, forAgent);
        if (p != null && !p.builtIn) buttons.getChildren().add(forProfile);
        buttons.getChildren().addAll(Ui.grow(), deny);
        HBox ruleRow = new HBox(8, Ui.dim("Rule:"), rule);
        ruleRow.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, title, ruleRow, desc, buttons);
        card.getStyleClass().add("card-permission");
        return card;
    }

    private static String permissionDetail(LiveAgent.PendingPermission pp) {
        var in = pp.input;
        String s = switch (pp.tool) {
            case "Bash" -> in.path("command").asText();
            case "Edit", "Write", "MultiEdit", "Read" -> in.path("file_path").asText();
            case "WebFetch" -> in.path("url").asText();
            default -> in.toString();
        };
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private void answer(LiveAgent.PendingPermission pp, AgentRuntime.Answer a, String rule) {
        String err = runtime.answer(agent, pp.id, a, rule);
        if (err != null) runtime.say(agent, err, "warn");
        input.requestFocus();
    }

    /** Pick (or create) a project (design §7.7). */
    private VBox folderCard(LiveAgent l) {
        Label title = new Label("📁 " + (l.folderReason() == null ? agent.name + " needs a project." : l.folderReason()));
        title.setWrapText(true);
        title.getStyleClass().add("card-title");
        ComboBox<Project> pick = new ComboBox<>();
        pick.getItems().setAll(ctx.settings.projects);
        pick.setConverter(new StringConverter<>() {
            @Override public String toString(Project p) { return p == null ? "" : p.name + "  —  " + p.folder; }
            @Override public Project fromString(String s) { return null; }
        });
        pick.setPromptText(ctx.settings.projects.isEmpty() ? "No projects yet" : "Choose a project");
        Button set = Ui.primary("Use this project", () -> {
            if (pick.getValue() != null) runtime.setProject(agent, pick.getValue().id);
        });
        set.disableProperty().bind(pick.valueProperty().isNull());
        Button create = Ui.button("New project…", () -> {
            SettingsWindow.show(ctx, root.getScene().getWindow(), SettingsWindow.Page.PROJECTS);
            shownPermission = "\u0000"; // rebuild the card with the new list
            refresh();
        });
        pick.setMaxWidth(420);
        set.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        create.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(8, pick, set, create);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, title, row);
        card.getStyleClass().add("card-folder");
        return card;
    }

    // ------------------------------------------------------------------ input

    /** Types {@code text} and presses Enter (also used by the screenshot tools). */
    public void submit(String text) {
        input.setText(text);
        submit();
    }

    private void submit() {
        String text = input.getText();
        if (text.isBlank()) return;
        PaneCommands.Outcome o = commands.handle(agent, text);
        boolean keep = false;
        switch (o) {
            case PaneCommands.Outcome.Sent s -> { }
            case PaneCommands.Outcome.Said s -> {
                if (!s.text().isEmpty()) runtime.say(agent, s.text(), s.level());
                keep = !text.strip().startsWith("/") && "warn".equals(s.level());
            }
            case PaneCommands.Outcome.Copy c -> {
                Ui.copy(c.text());
                runtime.say(agent, "Copied the last reply.", "dim");
            }
            case PaneCommands.Outcome.Clear c -> output.clear();
            case PaneCommands.Outcome.Help h -> runtime.say(agent, help(h.commands()), "info");
            case PaneCommands.Outcome.Usage u -> runtime.say(agent, String.join("\n", u.rows()), "info");
        }
        // A refused plain message stays in the box so nothing is lost.
        if (!keep) input.clear();
        runtime.markRead(agent);
    }

    private static String help(List<CommandRegistry.Command> cmds) {
        StringBuilder sb = new StringBuilder("Commands here:\n");
        int w = cmds.stream().mapToInt(c -> c.usage().length()).max().orElse(10);
        for (CommandRegistry.Command c : cmds) {
            sb.append("  ").append(c.usage()).append(" ".repeat(w - c.usage().length() + 2)).append(c.description()).append('\n');
        }
        sb.append("Plain text is a message to the agent; other /commands aren't sent.");
        return sb.toString();
    }

    private List<Autocomplete.Item> suggestions(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> names = runtime.agents().stream().map(a -> a.name).toList();
        for (String c : List.of("hey", "cantalkto", "stoptalkingto")) {
            if (lower.startsWith("/" + c) && !lower.matches("/" + c + "\\s*\\S+\\s.*")) {
                String prefix = typed.substring(0, c.length() + 1);
                return Autocomplete.after(typed, prefix, names, c.equals("hey") ? " " : "", n -> runtime.byName(n)
                        .map(a -> a.profile + " · " + a.statusText()).orElse(""));
            }
        }
        if (lower.startsWith("/activate")) {
            return Autocomplete.after(typed, typed.substring(0, 9), runtime.activateChoices(agent), "", s -> "skill");
        }
        if (lower.startsWith("/model ")) return Autocomplete.after(typed, "/model ", PaneCommands.MODELS, "", s -> "");
        if (lower.startsWith("/effort ")) {
            return Autocomplete.after(typed, "/effort ", List.of("low", "medium", "high", "xhigh", "max", "default"), "", s -> "");
        }
        if (typed.contains(" ")) return List.of();
        List<Autocomplete.Item> out = new ArrayList<>();
        String q = lower.substring(1);
        for (CommandRegistry.Command c : commands.available(agent)) {
            if (!c.name().startsWith(q)) continue;
            boolean takesArgs = c.usage().contains("<") || c.usage().contains("[");
            boolean attached = c.usage().startsWith("/" + c.name() + "<");
            String insert = "/" + c.name() + (takesArgs && !attached ? " " : "");
            out.add(new Autocomplete.Item(insert, c.usage(), c.description()));
        }
        return out;
    }

    private void resume() {
        String err = runtime.resume(agent);
        if (err != null) runtime.say(agent, err, "warn");
    }

    private void stop() {
        String err = runtime.stop(agent);
        if (err != null) runtime.say(agent, err, "warn");
    }
}
