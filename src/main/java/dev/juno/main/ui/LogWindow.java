package dev.juno.main.ui;

import dev.juno.main.model.Ticket;
import dev.juno.main.service.BoardService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.Map;

/** Live view of an agent's activity log (one window per ticket). */
final class LogWindow {

    private static final Map<String, Stage> OPEN = new HashMap<>();
    private static final int MAX_CHARS = 2_000_000;

    private LogWindow() {
    }

    static void show(Window owner, BoardService service, Ticket ticket) {
        Stage existing = OPEN.get(ticket.getId());
        if (existing != null) {
            existing.toFront();
            existing.requestFocus();
            return;
        }

        TextArea text = new TextArea(service.readLog(ticket.getId()));
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("log-text");
        text.positionCaret(text.getLength());

        CheckBox follow = new CheckBox("Follow output");
        follow.setSelected(true);
        Button clearView = new Button("Clear view");
        clearView.setOnAction(e -> text.clear());
        HBox bar = new HBox(10, follow, clearView);
        bar.getStyleClass().add("log-bar");

        BorderPane root = new BorderPane(text);
        root.setBottom(bar);

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Agent log - " + ticket.getTitle());
        Scene scene = new Scene(root, 900, 600);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);

        BoardService.LogListener listener = (ticketId, line) -> {
            if (ticketId.equals(ticket.getId())) {
                Platform.runLater(() -> {
                    if (text.getLength() > MAX_CHARS) {
                        text.deleteText(0, text.getLength() - MAX_CHARS / 2);
                    }
                    double scroll = text.getScrollTop();
                    text.appendText(line + "\n");
                    if (!follow.isSelected()) {
                        text.setScrollTop(scroll);
                    }
                });
            }
        };
        service.addLogListener(listener);
        stage.setOnHidden(e -> {
            service.removeLogListener(listener);
            OPEN.remove(ticket.getId());
        });
        OPEN.put(ticket.getId(), stage);
        stage.show();
    }
}
