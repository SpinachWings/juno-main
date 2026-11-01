package dev.juno.app;

import dev.juno.ui.FirstRun;
import dev.juno.ui.Theme;
import java.io.IOException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class JunoApp extends Application {
    @Override
    public void start(Stage stage) {
        AppContext ctx = AppContext.create();
        try {
            ctx.skills.refreshBundled(ctx.settings);
            ctx.save();
        } catch (IOException e) {
            System.err.println("Could not refresh the bundled skills: " + e.getMessage());
        }

        MainWindow window = new MainWindow(ctx, stage);
        Scene scene = new Scene(window.root(), 1280, 800);
        Theme.apply(scene);
        stage.setTitle("Juno");
        stage.setScene(scene);
        stage.show();
        window.checkClaude();

        if (!ctx.settings.system.firstRunDone) Platform.runLater(() -> FirstRun.run(ctx, stage));
    }
}
