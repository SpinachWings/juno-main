package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.Profile;
import dev.juno.model.SkillPack;
import dev.juno.skills.BundledPacks;
import dev.juno.ui.Ui;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

/** Settings → Skills (design §5.5). Changes apply and save immediately. */
public final class SkillsTab extends BorderPane {
    private static final String LOCK = "🔒";

    private final AppContext ctx;
    private final Runnable onChanged;
    private final ListView<SkillPack> list = new ListView<>();
    private final VBox detail = new VBox(10);
    private final Label message = Ui.dim("");

    public SkillsTab(AppContext ctx, Runnable onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
        setPadding(new Insets(12));

        list.getStyleClass().add("side-list");
        list.setPrefWidth(220);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(SkillPack p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : (p.bundled ? LOCK + " " : "   ") + p.name);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> show(p));
        Button importFile = Ui.button("Import file…", this::importFile);
        Button importFolder = Ui.button("Import folder…", this::importFolder);
        VBox left = new VBox(8, importFile, importFolder, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPadding(new Insets(0, 12, 0, 0));
        setLeft(left);

        detail.setPadding(new Insets(0, 4, 12, 4));
        ScrollPane sp = new ScrollPane(detail);
        sp.setFitToWidth(true);
        setCenter(sp);
        reload(null);
    }

    /** Re-reads profiles and packs (called when the tab is shown, since profiles change elsewhere). */
    public void refresh() {
        SkillPack sel = list.getSelectionModel().getSelectedItem();
        reload(sel == null ? null : sel.id);
    }

    private void reload(String selectId) {
        list.setItems(FXCollections.observableArrayList(ctx.settings.skillPacks));
        SkillPack sel = ctx.settings.skillPacks.stream().filter(p -> p.id.equals(selectId)).findFirst()
                .orElse(ctx.settings.skillPacks.isEmpty() ? null : ctx.settings.skillPacks.get(0));
        if (sel != null) list.getSelectionModel().select(sel);
        else show(null);
    }

    private void changed() {
        ctx.save();
        onChanged.run();
    }

    private void importFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import skills");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Skill files", "*.md", "*.zip", "*.skill"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) importFrom(f);
    }

    private void importFolder() {
        Ui.chooseFolder(getScene().getWindow(), "Import a skill folder", "").ifPresent(this::importFrom);
    }

    private void importFrom(File f) {
        try {
            SkillPack pack = ctx.skills.importPack(f.toPath(), ctx.settings);
            changed();
            reload(pack.id);
            message.setText("Imported “" + pack.name + "” with " + ctx.skills.skillsOf(pack).size() + " skill(s).");
        } catch (IOException | RuntimeException e) {
            Ui.error(getScene().getWindow(), "Couldn't import", e.getMessage());
        }
    }

    private void show(SkillPack pack) {
        detail.getChildren().clear();
        if (pack == null) {
            detail.getChildren().addAll(Ui.dim("Import a SKILL.md, a folder of skills, or a .zip / .skill file."), message);
            return;
        }
        detail.getChildren().add(Ui.heading(pack.name));
        if (pack.bundled) {
            detail.getChildren().add(Ui.dim(LOCK + " Bundled with Juno (refreshed from Juno's copy on start-up). Its built-in "
                    + "assignments are locked. You can also assign it to your own profiles."));
        } else {
            detail.getChildren().add(Ui.dim("Entry skill for /activate" + pack.name.toLowerCase().replace(" ", "") + ": "
                    + pack.entrySkill));
        }

        // Assignment
        CheckBox whole = new CheckBox("Whole orchestration (every profile)");
        whole.setSelected(pack.wholeOrchestration);
        whole.setDisable(pack.bundled);
        whole.setOnAction(e -> { pack.wholeOrchestration = whole.isSelected(); changed(); show(pack); });
        detail.getChildren().add(whole);
        if (!pack.wholeOrchestration) {
            FlowPane flow = new FlowPane(12, 6);
            for (Profile p : ctx.settings.profiles) {
                boolean locked = pack.bundled && BundledPacks.lockedProfiles(pack.id).contains(p.name);
                CheckBox cb = new CheckBox((locked ? LOCK + " " : "") + p.name);
                cb.setSelected(locked || pack.profiles.contains(p.name));
                cb.setDisable(locked);
                cb.setOnAction(e -> {
                    if (cb.isSelected()) pack.profiles.add(p.name); else pack.profiles.remove(p.name);
                    changed();
                    show(pack);
                });
                flow.getChildren().add(cb);
            }
            detail.getChildren().addAll(Ui.subheading("Assigned to"), flow);
        }

        // Skills grid: everyone + one column per profile that has the pack
        List<Profile> columns = ctx.settings.profiles.stream().filter(p -> ctx.skillResolver.assigned(pack, p.name)).toList();
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(6);
        // The global switch is only for imported packs; a bundled pack's skills are fixed per profile.
        int first = pack.bundled ? 1 : 2;
        g.add(new Label("Skill"), 0, 0);
        if (!pack.bundled)
            g.add(Ui.withTip(new Label("Enabled"), "Global switch: off turns the skill off for every profile."), 1, 0);
        for (int c = 0; c < columns.size(); c++) g.add(new Label(columns.get(c).name), first + c, 0);
        List<String> skills = ctx.skills.skillsOf(pack);
        for (int r = 0; r < skills.size(); r++) {
            String skill = skills.get(r);
            g.add(new Label(skill), 0, r + 1);
            CheckBox all = new CheckBox();
            boolean lockedAll = pack.bundled;
            all.setSelected(!pack.disabledSkills.contains(skill));
            all.setDisable(lockedAll);
            all.setOnAction(e -> {
                if (all.isSelected()) pack.disabledSkills.remove(skill); else pack.disabledSkills.add(skill);
                changed();
                show(pack);
            });
            if (!pack.bundled) g.add(all, 1, r + 1);
            for (int c = 0; c < columns.size(); c++) {
                Profile p = columns.get(c);
                boolean locked = pack.bundled && BundledPacks.lockedProfiles(pack.id).contains(p.name);
                CheckBox cb = new CheckBox();
                cb.setSelected(ctx.skillResolver.enabledIn(pack, p.name).contains(skill));
                cb.setDisable(locked || pack.disabledSkills.contains(skill));
                cb.setOnAction(e -> {
                    List<String> off = new ArrayList<>(pack.disabledPerProfile.getOrDefault(p.name, List.of()));
                    if (cb.isSelected()) off.remove(skill); else off.add(skill);
                    pack.disabledPerProfile.put(p.name, off);
                    changed();
                });
                g.add(cb, first + c, r + 1);
            }
        }
        detail.getChildren().addAll(Ui.subheading("Skills"), g);

        if (!pack.bundled) {
            detail.getChildren().add(Ui.button("Remove pack", () -> {
                if (!Ui.confirm(getScene().getWindow(), "Remove pack",
                        "Remove “" + pack.name + "” from the library?", "Remove")) return;
                try {
                    ctx.skills.remove(pack, ctx.settings);
                    changed();
                    reload(null);
                } catch (IOException e) {
                    Ui.error(getScene().getWindow(), "Couldn't remove", e.getMessage());
                }
            }));
        }
        detail.getChildren().add(message);
    }
}
