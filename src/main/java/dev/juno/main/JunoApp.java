package dev.juno.main;

import dev.juno.main.service.BoardService;
import dev.juno.main.store.JunoStore;
import dev.juno.main.ui.BoardView;
import dev.juno.main.ui.Fx;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

/** Juno: a kanban-style orchestrator for Claude Code agents working on development tickets. */
public final class JunoApp extends Application {

    private BoardService service;

    @Override
    public void start(Stage stage) {
        service = new BoardService(JunoStore.openDefault());
        BoardView board = new BoardView(service, getHostServices());

        Scene scene = new Scene(board.root(), 1500, 860);
        scene.getStylesheets().add(Objects.requireNonNull(
                JunoApp.class.getResource("/dev/juno/main/ui/juno.css")).toExternalForm());

        stage.setTitle("Juno - agent orchestrator");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(event -> {
            int running = service.runningAgents();
            if (running > 0 && !Fx.confirm(stage, "Quit Juno?",
                    running + " agent(s) are still working. Quitting stops them; their tickets will be "
                            + "waiting for a decision (resume) when Juno next starts.")) {
                event.consume();
            }
        });
        stage.show();
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
