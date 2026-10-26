package dev.juno.main.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Small JavaFX helpers: background work, alerts and colour handling. */
public final class Fx {

    private static final ExecutorService BACKGROUND = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "juno-background");
        t.setDaemon(true);
        return t;
    });

    private Fx() {
    }

    /** Runs {@code work} off the FX thread and delivers the result (or error) back on it. */
    public static <T> void async(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        BACKGROUND.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    public static void runLater(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    public static void error(Window owner, String header, Throwable e) {
        error(owner, header, e.getMessage() == null ? e.toString() : e.getMessage());
    }

    public static void error(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        inheritStyles(alert, owner);
        alert.setTitle("Juno");
        alert.setHeaderText(header);
        alert.setResizable(true);
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefColumnCount(80);
        // Roomy enough to read a multi-line problem list or a git/MCP error without scrolling; resizable for more.
        int lines = Math.max((int) message.lines().count() + 1, message.length() / 80 + 1);
        text.setPrefRowCount(Math.min(24, Math.max(8, lines + 2)));
        alert.getDialogPane().setContent(text);
        alert.getDialogPane().setPrefWidth(760);
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        alert.showAndWait();
    }

    public static void info(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        inheritStyles(alert, owner);
        alert.setTitle("Juno");
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    public static boolean confirm(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        inheritStyles(alert, owner);
        alert.setTitle("Juno");
        alert.setHeaderText(header);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Dialogs get their own scene; give them the main window's stylesheets. */
    public static void inheritStyles(Dialog<?> dialog, Window owner) {
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            // When the owner is itself a dialog, its styles live on its root (the DialogPane).
            if (owner.getScene().getRoot() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getRoot().getStylesheets());
            }
        }
    }

    public static Color parseColor(String hex, Color fallback) {
        try {
            return hex == null || hex.isBlank() ? fallback : Color.web(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String toHex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    public static String rgba(Color c, double alpha) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255), alpha);
    }
}
