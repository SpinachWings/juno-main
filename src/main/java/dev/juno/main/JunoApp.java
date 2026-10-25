package dev.juno.main;

import dev.juno.main.service.BoardService;
import dev.juno.main.store.JunoStore;
import dev.juno.main.ui.BoardView;
import dev.juno.main.ui.Fx;
import dev.juno.main.ui.SpaceIcon;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Juno: a kanban-style orchestrator for Claude Code agents working on development tickets. */
public final class JunoApp extends Application {

    private BoardService service;

    /** Bundled fonts (SIL Open Font License; see the OFL-*.txt files next to them). */
    private static final String[] FONTS = {"ShareTechMono-Regular.ttf", "VT323-Regular.ttf"};

    private static void loadFonts() {
        for (String font : FONTS) {
            try (InputStream in = JunoApp.class.getResourceAsStream("/dev/juno/main/ui/fonts/" + font)) {
                if (in == null || Font.loadFont(in, 13) == null) {
                    System.err.println("Could not load font " + font + "; falling back to the system font");
                }
            } catch (IOException e) {
                System.err.println("Could not load font " + font + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void start(Stage stage) {
        loadFonts();
        service = new BoardService(JunoStore.openDefault());
        BoardView board = new BoardView(service, getHostServices());

        Scene scene = new Scene(board.root(), 1500, 860, Color.web("#0D0D10"));
        scene.getStylesheets().add(Objects.requireNonNull(
                JunoApp.class.getResource("/dev/juno/main/ui/juno.css")).toExternalForm());

        stage.setTitle("Juno - agent orchestrator");
        stage.setScene(scene);
        stage.getIcons().addAll(SpaceIcon.image(1), SpaceIcon.image(2), SpaceIcon.image(4));
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(event -> {
            int running = service.runningAgents();
            if (running > 0 && !Fx.confirm(stage, "Quit Juno?",
                    running + " agent(s) are still working. Quitting stops them; their tickets are kept "
                            + "(greyed out) with a Resume button that continues from their recorded journal.")) {
                event.consume();
            }
        });
        stage.show();
        java.util.List<String> notes = service.store().settingsNotes();
        if (!notes.isEmpty()) {
            Fx.info(stage, "Settings updated", String.join("\n", notes)
                    + "\n\nA backup of the previous settings is in settings.pre-single-host.json.");
        }
    }

    @Override
    public void stop() {
        if (service != null) {
            service.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
