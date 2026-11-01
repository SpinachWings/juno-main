package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.ui.Theme;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/** The Settings window with its six tabs (design §5). */
public final class SettingsWindow {
    public enum Page { KEYS, SYSTEM, PROJECTS, PROFILES, SKILLS, COMMANDS }

    private static SettingsWindow open;

    private final Stage stage = new Stage();
    private final TabPane tabs = new TabPane();

    private SettingsWindow(AppContext ctx, Window owner) {
        stage.setTitle("Juno · Settings");
        if (owner != null) stage.initOwner(owner);
        ProfilesTab profiles = new ProfilesTab(ctx);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("Keys", new KeysTab(ctx)),
                tab("System settings", new SystemTab(ctx)),
                tab("Projects", new ProjectsTab(ctx)),
                tab("Agent profiles", profiles),
                tab("Skills", new SkillsTab(ctx, profiles::refreshSkills)),
                tab("Commands", new CommandsTab(ctx)));
        Scene scene = new Scene(tabs, 1020, 760);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setOnHidden(e -> open = null);
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        return new Tab(title, content);
    }

    /** The open Settings window, or null. */
    public static Stage current() {
        return open == null ? null : open.stage;
    }

    /** Opens the window (one at a time) on the given page. */
    public static void show(AppContext ctx, Window owner, Page page) {
        if (open == null) open = new SettingsWindow(ctx, owner);
        open.tabs.getSelectionModel().select(page.ordinal());
        open.stage.show();
        open.stage.toFront();
    }
}
