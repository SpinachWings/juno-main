package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.Profile;
import dev.juno.model.ProfileCommand;
import dev.juno.model.Validation;
import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings → Agent profiles (design §5.4). Locked settings of built-ins show a lock and can't be edited. */
public final class ProfilesTab extends BorderPane {
    private static final String LOCK = "🔒";

    private final AppContext ctx;
    private final ListView<Profile> list = new ListView<>();
    private Profile editing;
    private boolean editingIsNew;

    private final TextField name = new TextField();
    private final Label provider = new Label("Claude Code");
    private final TextField exe = new TextField();
    private final ComboBox<String> model = new ComboBox<>(FXCollections.observableArrayList(
            "", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"));
    private final ComboBox<String> effort = new ComboBox<>(FXCollections.observableArrayList(Profile.EFFORTS));
    private final ComboBox<String> permission = new ComboBox<>(FXCollections.observableArrayList(Profile.PERMISSION_MODES));
    private final TextArea prompt = new TextArea();
    private final TextArea allowed = new TextArea();
    private final TextArea disallowed = new TextArea();
    private final TextField maxTurns = new TextField();
    private final TextField spend = new TextField();
    private final CheckBox needsFolder = new CheckBox("Its agents won't start without a project");
    private final CheckBox editsFiles = new CheckBox("Its agents get their own worktree");
    private final ListView<ProfileCommand> commandList = new ListView<>();
    private final TextField commandName = new TextField();
    private final TextArea commandTemplate = new TextArea();
    private final Label skillsSummary = Ui.dim("");
    private final Button duplicate = Ui.button("Duplicate", this::duplicate);
    private final Button delete = Ui.button("Delete", this::deleteProfile);
    private final Button save = Ui.primary("Save", this::save);
    private final Button addCommand = Ui.button("+ Add command", this::addCommand);
    private final Button removeCommand = Ui.button("Remove command", this::removeCommand);
    private final VBox form = new VBox(8);
    private final List<ProfileCommand> workingCommands = new ArrayList<>();

    public ProfilesTab(AppContext ctx) {
        this.ctx = ctx;
        setPadding(new Insets(12));

        list.getStyleClass().add("side-list");
        list.setPrefWidth(220);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Profile p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : (p.builtIn ? LOCK + " " : "   ") + p.name);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null) edit(p, false);
        });
        VBox left = new VBox(8, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPadding(new Insets(0, 12, 0, 0));
        setLeft(left);

        model.setEditable(true);
        model.setMaxWidth(Double.MAX_VALUE);
        effort.setMaxWidth(Double.MAX_VALUE);
        permission.setMaxWidth(Double.MAX_VALUE);
        effort.setPromptText("CLI default");
        exe.setPromptText("blank = the Claude Code path in System settings");
        spend.setPromptText("USD per process, blank = none");
        for (TextArea a : List.of(prompt, allowed, disallowed, commandTemplate)) {
            a.getStyleClass().add("mono");
            a.setWrapText(true);
        }
        prompt.setPrefRowCount(10);
        allowed.setPrefRowCount(5);
        disallowed.setPrefRowCount(3);
        commandTemplate.setPrefRowCount(6);
        commandList.setPrefHeight(90);
        commandList.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ProfileCommand c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : (c.builtIn ? LOCK + " " : "   ") + "/" + c.name);
            }
        });
        commandList.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> showCommand(c));
        commandName.textProperty().addListener((o, a, b) -> {
            ProfileCommand c = commandList.getSelectionModel().getSelectedItem();
            if (c != null && !c.builtIn && !b.equals(c.name)) {
                c.name = b;
                commandList.refresh();
            }
        });
        commandTemplate.textProperty().addListener((o, a, b) -> {
            ProfileCommand c = commandList.getSelectionModel().getSelectedItem();
            if (c != null && !c.builtIn) c.template = b;
        });

        form.setPadding(new Insets(0, 4, 12, 4));
        form.getChildren().addAll(
                field("Name", name, true), field("Provider", provider, true), field("Executable path", exe, false),
                field("Model", model, false), field("Effort", effort, false),
                field("Permission mode", permission, true), field("System prompt", prompt, true),
                field("Allowed tools (one per line)", allowed, true),
                field("Disallowed tools (one per line)", disallowed, true),
                field("Max turns (per message)", maxTurns, false), field("Spend limit", spend, false),
                field("Needs a project folder", needsFolder, true), field("Edits files", editsFiles, true),
                section("Profile commands", commandList, Ui.row(addCommand, removeCommand), commandName,
                        commandTemplate),
                section("Skills", skillsSummary),
                Ui.row(save, duplicate, delete));
        ScrollPane sp = new ScrollPane(form);
        sp.setFitToWidth(true);
        setCenter(sp);
        reload(ctx.settings.profiles.get(0).name);
    }

    private Node field(String title, Node control, boolean lockedOnBuiltIn) {
        Label l = new Label(title);
        control.setUserData(lockedOnBuiltIn ? "locks" : "free");
        Label lock = new Label(LOCK);
        lock.getStyleClass().add("lock");
        lock.setUserData("lockIcon");
        lock.setVisible(false);
        lock.setManaged(false);
        VBox v = new VBox(4, Ui.row(l, lockedOnBuiltIn ? lock : new Label()), control);
        if (control instanceof ComboBox<?> || control instanceof TextField) v.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    private Node section(String title, Node... content) {
        VBox v = new VBox(6, Ui.subheading(title));
        v.getChildren().addAll(content);
        return v;
    }

    private List<Node> lockable() {
        return List.of(name, permission, prompt, allowed, disallowed, needsFolder, editsFiles);
    }

    private void edit(Profile p, boolean isNew) {
        editing = p;
        editingIsNew = isNew;
        boolean locked = p.builtIn;
        name.setText(p.name);
        exe.setText(p.executablePath);
        model.setValue(p.model);
        effort.setValue(p.effort);
        permission.setValue(p.permissionMode);
        prompt.setText(p.systemPrompt);
        allowed.setText(String.join("\n", p.allowedTools));
        disallowed.setText(String.join("\n", p.disallowedTools));
        maxTurns.setText(String.valueOf(p.maxTurns));
        spend.setText(p.spendLimit == null ? "" : String.valueOf(p.spendLimit));
        needsFolder.setSelected(p.needsProjectFolder);
        editsFiles.setSelected(p.editsFiles);
        for (Node n : lockable()) {
            boolean readOnlyLike = n instanceof TextInputControl;
            if (readOnlyLike) ((TextInputControl) n).setEditable(!locked);
            n.setDisable(locked && !readOnlyLike);
        }
        form.lookupAll(".lock").forEach(n -> { n.setVisible(locked); n.setManaged(locked); });
        // Duplicate is the way to change anything locked.
        workingCommands.clear();
        for (ProfileCommand c : p.commands) workingCommands.add(new ProfileCommand(c.name, c.template, c.builtIn));
        commandList.setItems(FXCollections.observableArrayList(workingCommands));
        if (!workingCommands.isEmpty()) commandList.getSelectionModel().select(0);
        else showCommand(null);
        duplicate.setDisable(isNew);
        delete.setDisable(locked || isNew);
        skillsSummary.setText(skillsText(p));
    }

    private String skillsText(Profile p) {
        Map<String, List<String>> enabled = ctx.skillResolver.enabledFor(ctx.settings, p.name);
        if (enabled.isEmpty()) return "No skills assigned. Assign packs in the Skills tab.";
        StringBuilder sb = new StringBuilder("Assigned in the Skills tab:");
        enabled.forEach((pack, skills) -> sb.append("\n  ").append(pack).append(": ").append(String.join(", ", skills)));
        return sb.toString();
    }

    private void showCommand(ProfileCommand c) {
        boolean has = c != null;
        commandName.setDisable(!has);
        commandTemplate.setDisable(!has);
        commandName.setText(has ? c.name : "");
        commandTemplate.setText(has ? c.template : "");
        boolean locked = has && c.builtIn;
        commandName.setEditable(!locked);
        commandTemplate.setEditable(!locked);
        removeCommand.setDisable(!has || locked);
    }

    private void addCommand() {
        ProfileCommand c = new ProfileCommand("newcommand", "Prompt template. Use {{args}} for what's typed after the command.", false);
        workingCommands.add(c);
        commandList.getItems().add(c);
        commandList.getSelectionModel().select(c);
    }

    private void removeCommand() {
        ProfileCommand c = commandList.getSelectionModel().getSelectedItem();
        if (c == null || c.builtIn) return;
        workingCommands.remove(c);
        commandList.getItems().remove(c);
    }

    private void duplicate() {
        String base = editing.name + " copy";
        String n = base;
        for (int i = 2; nameTaken(n); i++) n = base + " " + i;
        Profile copy = editing.duplicate(n);
        edit(copy, true);
    }

    private boolean nameTaken(String n) {
        return ctx.settings.profiles.stream().anyMatch(p -> p.name.equalsIgnoreCase(n));
    }

    private void save() {
        Profile p = editing.duplicate(editing.name);
        p.builtIn = editing.builtIn;
        p.commands = new ArrayList<>(workingCommands.stream()
                .map(c -> new ProfileCommand(c.name, c.template, c.builtIn)).toList());
        p.executablePath = exe.getText().trim();
        p.model = model.getEditor().getText() == null ? "" : model.getEditor().getText().trim();
        p.effort = effort.getValue() == null ? "" : effort.getValue();
        try {
            p.maxTurns = Integer.parseInt(maxTurns.getText().trim());
        } catch (NumberFormatException e) {
            Ui.problems(getScene().getWindow(), List.of("Max turns must be a whole number."));
            return;
        }
        String sp = spend.getText().trim();
        try {
            p.spendLimit = sp.isEmpty() ? null : Double.parseDouble(sp);
        } catch (NumberFormatException e) {
            Ui.problems(getScene().getWindow(), List.of("The spend limit must be a number, or blank for none."));
            return;
        }
        if (!p.builtIn) {
            p.name = name.getText().trim();
            p.permissionMode = permission.getValue();
            p.systemPrompt = prompt.getText();
            p.allowedTools = lines(allowed);
            p.disallowedTools = lines(disallowed);
            p.needsProjectFolder = needsFolder.isSelected();
            p.editsFiles = editsFiles.isSelected();
        } else {
            p.name = editing.name;
            p.permissionMode = editing.permissionMode;
            p.systemPrompt = editing.systemPrompt;
            p.allowedTools = editing.allowedTools;
            p.disallowedTools = editing.disallowedTools;
        }
        List<Profile> others = ctx.settings.profiles.stream().filter(x -> editingIsNew || !x.name.equals(editing.name)).toList();
        List<String> problems = Validation.profile(p, others);
        if (!problems.isEmpty()) {
            Ui.problems(getScene().getWindow(), problems);
            return;
        }
        String oldName = editing.name;
        if (editingIsNew) {
            ctx.settings.profiles.add(p);
        } else {
            int i = indexOf(oldName);
            ctx.settings.profiles.set(i, p);
            if (!oldName.equals(p.name)) renameInSkills(oldName, p.name);
        }
        ctx.save();
        reload(p.name);
    }

    private void renameInSkills(String from, String to) {
        ctx.settings.skillPacks.forEach(pack -> {
            pack.profiles.replaceAll(x -> x.equals(from) ? to : x);
            List<String> off = pack.disabledPerProfile.remove(from);
            if (off != null) pack.disabledPerProfile.put(to, off);
        });
    }

    private void deleteProfile() {
        if (editing.builtIn) return;
        if (!Ui.confirm(getScene().getWindow(), "Delete profile",
                "Delete the profile “" + editing.name + "”? Its skill assignments are removed too.", "Delete")) return;
        ctx.settings.profiles.removeIf(x -> x.name.equals(editing.name));
        ctx.settings.skillPacks.forEach(pack -> {
            pack.profiles.remove(editing.name);
            pack.disabledPerProfile.remove(editing.name);
        });
        ctx.save();
        reload(ctx.settings.profiles.get(0).name);
    }

    private int indexOf(String n) {
        for (int i = 0; i < ctx.settings.profiles.size(); i++) if (ctx.settings.profiles.get(i).name.equals(n)) return i;
        throw new IllegalStateException(n);
    }

    private static List<String> lines(TextArea a) {
        return Arrays.stream(a.getText().split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void reload(String selectName) {
        list.setItems(FXCollections.observableArrayList(ctx.settings.profiles));
        ctx.settings.profiles.stream().filter(p -> p.name.equals(selectName)).findFirst().ifPresent(p -> {
            list.getSelectionModel().select(p);
            edit(p, false);
        });
    }

    /** Re-reads the skills summary after the Skills tab changes. */
    public void refreshSkills() {
        if (editing != null) skillsSummary.setText(skillsText(editing));
    }
}
