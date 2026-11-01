package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.Key;
import dev.juno.model.McpUrls;
import dev.juno.model.Validation;
import dev.juno.store.SettingsStore;
import dev.juno.store.TokenStore;
import dev.juno.ui.Ui;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings → Keys (design §5.1). Tokens go to the OS vault; settings.json holds only a reference. */
public final class KeysTab extends BorderPane {
    private final AppContext ctx;
    private final ListView<Key> list = new ListView<>();
    private Key editing;

    private final TextField label = new TextField();
    private final ComboBox<Key.Kind> kind = new ComboBox<>(FXCollections.observableArrayList(Key.Kind.values()));
    private final TextField host = new TextField();
    private final PasswordField token = new PasswordField();
    private final Label storedNote = Ui.dim("");
    private final CheckBox useEnv = new CheckBox("Use an environment variable instead");
    private final TextField envName = new TextField();
    private final TextField gitName = new TextField();
    private final TextField gitEmail = new TextField();
    private final TextField mcpOverride = new TextField();
    private final Label mcpResolved = Ui.dim("");
    private final Label status = new Label();
    private final Button delete = Ui.button("Delete", this::deleteKey);

    public KeysTab(AppContext ctx) {
        this.ctx = ctx;
        setPadding(new Insets(12));

        list.getStyleClass().add("side-list");
        list.setPrefWidth(220);
        list.setItems(FXCollections.observableArrayList(ctx.settings.keys));
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Key k, boolean empty) {
                super.updateItem(k, empty);
                setText(empty || k == null ? null : k.label + "  ·  " + k.host);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, k) -> { if (k != null) edit(k.copy()); });
        Button add = Ui.button("+ Add key", () -> {
            list.getSelectionModel().clearSelection();
            Key k = new Key();
            k.id = SettingsStore.newId();
            edit(k);
        });
        VBox left = new VBox(8, add, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPadding(new Insets(0, 12, 0, 0));
        setLeft(left);

        kind.valueProperty().addListener((o, a, k) -> {
            if (k != null && a != null && host.getText().equals(a.defaultHost)) host.setText(k.defaultHost);
            refreshUrl();
        });
        host.textProperty().addListener((o, a, b) -> refreshUrl());
        mcpOverride.textProperty().addListener((o, a, b) -> refreshUrl());
        useEnv.selectedProperty().addListener((o, a, b) -> refreshTokenMode());
        status.setWrapText(true);

        setCenter(new ScrollPane(form()));
        ((ScrollPane) getCenter()).setFitToWidth(true);
        reloadList(null);
    }

    private Node form() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(0, 4, 12, 4));
        ColumnConstraints c0 = new ColumnConstraints(130), c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        int r = 0;
        g.add(new Label("Label"), 0, r); g.add(label, 1, r++);
        g.add(new Label("Kind"), 0, r); g.add(kind, 1, r++);
        g.add(new Label("Host"), 0, r); g.add(host, 1, r++);
        g.add(new Label("Token"), 0, r); g.add(token, 1, r++);
        g.add(storedNote, 1, r++);
        g.add(useEnv, 1, r++);
        g.add(new Label("Variable name"), 0, r); g.add(envName, 1, r++);
        g.add(Ui.subheading("Git identity (optional)"), 0, r++, 2, 1);
        g.add(new Label("Author name"), 0, r); g.add(gitName, 1, r++);
        g.add(new Label("Author email"), 0, r); g.add(gitEmail, 1, r++);
        g.add(Ui.dim("Commits made with this key use this identity, e.g. a bot account's."), 1, r++);

        VBox adv = new VBox(8, new Label("MCP URL override"), mcpOverride, mcpResolved);
        adv.setPadding(new Insets(8));
        TitledPane advanced = new TitledPane("Advanced", adv);
        advanced.setExpanded(false);
        g.add(advanced, 0, r++, 2, 1);

        Button test = Ui.button("Test", () -> Ui.info(getScene().getWindow(), "Test",
                "Testing a key against its host's MCP server arrives in Milestone 5."));
        Button save = Ui.primary("Save", this::save);
        g.add(Ui.row(save, test, delete), 1, r++);
        g.add(status, 1, r++);
        host.setPromptText("github.com");
        envName.setPromptText("e.g. GITHUB_TOKEN");
        label.setPromptText("e.g. me, or review-bot");
        return g;
    }

    private void edit(Key k) {
        editing = k;
        boolean has = k != null;
        getCenter().setDisable(!has);
        if (!has) {
            status.setText(vaultWarning().isEmpty() ? "Select a key, or add one." : "");
            return;
        }
        label.setText(k.label);
        kind.setValue(k.kind);
        host.setText(k.host);
        token.clear();
        boolean env = TokenStore.isEnvReference(k.token);
        useEnv.setSelected(env);
        envName.setText(env ? k.token.substring(4) : "");
        gitName.setText(k.gitName);
        gitEmail.setText(k.gitEmail);
        mcpOverride.setText(k.mcpUrlOverride);
        boolean saved = ctx.settings.keys.stream().anyMatch(x -> x.id.equals(k.id));
        delete.setDisable(!saved);
        storedNote.setText(!saved || k.token.isBlank() ? ""
                : env ? "Read from the environment when an agent starts."
                : "•".repeat(8) + "  stored in the OS keyring. Type a new token to replace it.");
        status.setText(vaultWarning());
        status.getStyleClass().setAll("label", "warn");
        refreshTokenMode();
        refreshUrl();
    }

    private String vaultWarning() {
        return ctx.tokens.vaultAvailable() ? ""
                : ctx.tokens.vaultProblem() + " Use “Use an environment variable instead”: Juno stores only the "
                + "variable's name, never the token.";
    }

    private void refreshTokenMode() {
        boolean env = useEnv.isSelected();
        token.setDisable(env || !ctx.tokens.vaultAvailable());
        envName.setDisable(!env);
        if (!ctx.tokens.vaultAvailable() && !env && editing != null) useEnv.setSelected(true);
    }

    private void refreshUrl() {
        if (editing == null) return;
        Key probe = new Key();
        probe.kind = kind.getValue() == null ? Key.Kind.GITHUB : kind.getValue();
        probe.host = host.getText();
        probe.mcpUrlOverride = mcpOverride.getText();
        mcpResolved.setText(McpUrls.forKey(probe).map(u -> "MCP server: " + u).orElse(McpUrls.unsupportedReason(probe)));
    }

    private void save() {
        Key k = editing.copy();
        k.label = label.getText().trim();
        k.kind = kind.getValue();
        k.host = host.getText().trim();
        k.gitName = gitName.getText().trim();
        k.gitEmail = gitEmail.getText().trim();
        k.mcpUrlOverride = mcpOverride.getText().trim();
        boolean env = useEnv.isSelected();
        boolean tokenGiven = !env && !token.getText().isBlank();
        List<String> problems = Validation.key(k, ctx.settings.keys, tokenGiven, env ? envName.getText() : "");
        if (!problems.isEmpty()) {
            Ui.problems(getScene().getWindow(), problems);
            return;
        }
        Key previous = ctx.settings.keys.stream().filter(x -> x.id.equals(k.id)).findFirst().orElse(null);
        try {
            if (env) {
                if (previous != null && !TokenStore.isEnvReference(previous.token)) ctx.tokens.delete(k.id);
                k.token = "env:" + envName.getText().trim();
            } else if (tokenGiven) {
                k.token = ctx.tokens.save(k.id, token.getText().trim());
            } else if (previous != null && TokenStore.isEnvReference(previous.token)) {
                Ui.problems(getScene().getWindow(), List.of("Enter a token to replace the environment variable."));
                return;
            }
        } catch (RuntimeException e) {
            Ui.error(getScene().getWindow(), "Couldn't store the token", e.getMessage());
            return;
        }
        token.clear();
        if (previous == null) ctx.settings.keys.add(k);
        else ctx.settings.keys.set(ctx.settings.keys.indexOf(previous), k);
        ctx.save();
        reloadList(k.id);
    }

    private void deleteKey() {
        if (editing == null) return;
        if (!Ui.confirm(getScene().getWindow(), "Delete key",
                "Delete “" + editing.label + "”? Its stored token is removed too. Agents using it lose "
                        + "host access until you pick another key.", "Delete")) return;
        ctx.tokens.delete(editing.id);
        ctx.settings.keys.removeIf(x -> x.id.equals(editing.id));
        ctx.save();
        reloadList(null);
    }

    private void reloadList(String selectId) {
        list.setItems(FXCollections.observableArrayList(ctx.settings.keys));
        Key sel = ctx.settings.keys.stream().filter(x -> x.id.equals(selectId)).findFirst()
                .orElse(ctx.settings.keys.isEmpty() ? null : ctx.settings.keys.get(0));
        if (sel != null) list.getSelectionModel().select(sel);
        else edit(null);
    }
}
