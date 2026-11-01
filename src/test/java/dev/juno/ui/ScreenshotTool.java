package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Key;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.store.JunoHome;
import dev.juno.store.TokenStore;
import dev.juno.ui.settings.SettingsWindow;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Not a unit test: renders each screen to a PNG for checking the look. Always uses a scratch Juno home, a scratch
 * Claude config folder and an in-memory token store.
 * Usage: {@code java -cp ... dev.juno.ui.ScreenshotTool <output dir>}
 */
public final class ScreenshotTool {
    private static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        Files.createDirectories(out);
        Path scratch = Files.createTempDirectory("juno-shots");
        Files.createDirectories(scratch.resolve("claude"));
        Path repo = Files.createDirectories(scratch.resolve("repo"));

        TokenStore memory = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String t) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.empty(); }
        };
        JunoHome home = new JunoHome(scratch.resolve("home"));
        AppContext ctx = new AppContext(home, memory, new ClaudeCodeProvider(scratch.resolve("claude"), home));
        ctx.skills.refreshBundled(ctx.settings);
        Key k = new Key();
        k.id = "k1"; k.label = "me"; k.token = TokenStore.keyringReference("k1");
        Key bot = new Key();
        bot.id = "k2"; bot.label = "review-bot"; bot.host = "gitlab.com"; bot.kind = Key.Kind.GITLAB;
        bot.token = "env:GITLAB_BOT_TOKEN"; bot.gitName = "Review Bot"; bot.gitEmail = "bot@example.com";
        ctx.settings.keys.add(k);
        ctx.settings.keys.add(bot);
        Project p = new Project();
        p.id = "p1"; p.name = "api"; p.folder = repo.toString(); p.remote = "acme/api";
        p.buildAndTestCommands = java.util.List.of("npm ci", "npm test");
        ctx.settings.projects.add(p);
        ctx.save();

        Platform.startup(() -> {});
        run(() -> {
            Stage main = new Stage();
            MainWindow w = new MainWindow(ctx, main);
            Scene s = new Scene(w.root(), 1100, 700);
            Theme.apply(s);
            main.setScene(s);
            main.show();
            w.checkClaude();
        });
        Thread.sleep(2500);
        shot("main", Stage.getWindows().get(0));

        String[] names = {"keys", "system", "projects", "profiles", "skills", "commands"};
        for (int i = 0; i < names.length; i++) {
            int page = i;
            run(() -> SettingsWindow.show(ctx, null, SettingsWindow.Page.values()[page]));
            Thread.sleep(1200);
            if (i == 0) {
                run(() -> ((javafx.scene.control.ListView<?>) ((javafx.scene.layout.BorderPane) ((javafx.scene.control.TabPane)
                        SettingsWindow.current().getScene().getRoot()).getTabs().get(0).getContent()).getLeft().lookup(".list-view"))
                        .getSelectionModel().select(0));
                Thread.sleep(400);
            }
            if (i == 1) {
                run(() -> ctx.provider.installHook());
            }
            shot("settings-" + names[i], SettingsWindow.current());
        }
        run(() -> SettingsWindow.current().close());

        FirstRun[] fr = new FirstRun[1];
        run(() -> fr[0] = FirstRun.open(ctx, null));
        for (int i = 0; i < 6; i++) {
            int page = i;
            run(() -> fr[0].show(page));
            Thread.sleep(i == 1 ? 2500 : 500);
            shot("firstrun-" + i, fr[0].stage());
        }
        Platform.exit();
    }

    private static void run(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { l.countDown(); }
        });
        l.await(30, TimeUnit.SECONDS);
    }

    private static void shot(String name, javafx.stage.Window w) throws Exception {
        WritableImage[] img = new WritableImage[1];
        run(() -> img[0] = w.getScene().snapshot(null));
        PixelReader pr = img[0].getPixelReader();
        int width = (int) img[0].getWidth(), height = (int) img[0].getHeight();
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", out.resolve(name + ".png").toFile());
        System.out.println("wrote " + name);
    }
}
