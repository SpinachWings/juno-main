package dev.juno.ui.pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;

/**
 * The {@code /} list of a pane's input (design §8): commands available there, filtered as you type, each with a
 * one-line description; after a command that takes a name, a skill or an option, its choices. Tab or Enter accepts,
 * Esc closes.
 */
final class Autocomplete {
    /** One suggestion: what it inserts (the whole input up to the caret) and how it's shown. */
    record Item(String insert, String label, String description) {}

    private final TextArea input;
    private final Function<String, List<Item>> source;
    private final Popup popup = new Popup();
    private final ListView<Item> list = new ListView<>();

    Autocomplete(TextArea input, Function<String, List<Item>> source) {
        this.input = input;
        this.source = source;
        list.getStyleClass().add("autocomplete");
        list.setPrefWidth(720);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(it.label());
                name.getStyleClass().add("ac-name");
                Label desc = new Label(it.description());
                desc.getStyleClass().add("dim");
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                HBox row = new HBox(12, name, gap, desc);
                setGraphic(row);
                setText(null);
            }
        });
        list.setOnMouseClicked(e -> accept());
        popup.getContent().add(list);
        popup.setAutoHide(true);
        input.textProperty().addListener((o, a, b) -> refresh());
        input.caretPositionProperty().addListener((o, a, b) -> refresh());
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        input.focusedProperty().addListener((o, a, b) -> { if (!b) popup.hide(); });
    }

    boolean showing() {
        return popup.isShowing();
    }

    private void key(KeyEvent e) {
        if (!popup.isShowing()) return;
        KeyCode c = e.getCode();
        if (c == KeyCode.DOWN || c == KeyCode.UP) {
            int i = list.getSelectionModel().getSelectedIndex() + (c == KeyCode.DOWN ? 1 : -1);
            i = Math.max(0, Math.min(list.getItems().size() - 1, i));
            list.getSelectionModel().select(i);
            list.scrollTo(i);
            e.consume();
        } else if (c == KeyCode.TAB || (c == KeyCode.ENTER && !e.isShiftDown())) {
            Item it = list.getSelectionModel().getSelectedItem();
            String typed = input.getText().substring(0, input.getCaretPosition());
            // Enter on an exact, complete command sends it rather than re-inserting it.
            if (c == KeyCode.ENTER && it != null && it.insert().strip().equalsIgnoreCase(typed.strip())) {
                popup.hide();
                return;
            }
            accept();
            e.consume();
        } else if (c == KeyCode.ESCAPE) {
            popup.hide();
            e.consume();
        }
    }

    private void accept() {
        Item it = list.getSelectionModel().getSelectedItem();
        if (it == null) return;
        String after = input.getText().substring(input.getCaretPosition());
        input.setText(it.insert() + after);
        input.positionCaret(it.insert().length());
        popup.hide();
        javafx.application.Platform.runLater(this::refresh);
    }

    private void refresh() {
        String text = input.getText();
        int caret = Math.min(input.getCaretPosition(), text.length());
        String typed = text.substring(0, caret);
        if (!typed.startsWith("/") || typed.contains("\n")) {
            popup.hide();
            return;
        }
        List<Item> items = source.apply(typed);
        if (items.isEmpty()) {
            popup.hide();
            return;
        }
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(items.size(), 9) * 26 + 4);
        if (!popup.isShowing() && input.getScene() != null) {
            Bounds b = input.localToScreen(input.getBoundsInLocal());
            if (b == null) return;
            popup.show(input, b.getMinX(), b.getMinY() - list.getPrefHeight() - 4);
        } else if (popup.isShowing()) {
            Bounds b = input.localToScreen(input.getBoundsInLocal());
            if (b != null) {
                popup.setX(b.getMinX());
                popup.setY(b.getMinY() - list.getPrefHeight() - 4);
            }
        }
    }

    /** Filters {@code choices} by what's typed after {@code prefix}, as items inserting {@code prefix + choice + suffix}. */
    static List<Item> after(String typed, String prefix, List<String> choices, String suffix,
                            Function<String, String> describe) {
        String q = typed.substring(prefix.length()).strip().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        for (String c : choices) {
            if (c.toLowerCase(Locale.ROOT).replace(" ", "").startsWith(q.replace(" ", ""))
                    || c.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(new Item(prefix + c + suffix, c, describe.apply(c)));
            }
        }
        return out;
    }
}
