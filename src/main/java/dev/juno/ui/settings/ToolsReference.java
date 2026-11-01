package dev.juno.ui.settings;

import dev.juno.model.ToolCatalog;
import dev.juno.ui.Theme;
import dev.juno.ui.Ui;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Info panel listing the tools a profile can allow or disallow, with buttons to add the selected one. */
final class ToolsReference {
    private static Stage open;

    private ToolsReference() {}

    /**
     * @param editable whether the profile being edited can change its tools (not a built-in)
     */
    static void show(Window owner, Supplier<Boolean> editable, Consumer<String> addAllowed,
                     Consumer<String> addDisallowed) {
        if (open != null) {
            open.toFront();
            return;
        }
        TableView<ToolCatalog.Tool> table = new TableView<>(FXCollections.observableArrayList(ToolCatalog.all()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Group", 130, t -> t.group().label));
        table.getColumns().add(col("Tool / rule", 240, ToolCatalog.Tool::rule));
        table.getColumns().add(col("What", 420, ToolCatalog.Tool::what));

        Button allow = Ui.button("Add to allowed", () -> {
            var t = table.getSelectionModel().getSelectedItem();
            if (t != null) addAllowed.accept(t.rule());
        });
        Button deny = Ui.button("Add to disallowed", () -> {
            var t = table.getSelectionModel().getSelectedItem();
            if (t != null) addDisallowed.accept(t.rule());
        });
        Label note = Ui.dim("");
        Runnable refresh = () -> {
            boolean can = editable.get();
            boolean picked = table.getSelectionModel().getSelectedItem() != null;
            allow.setDisable(!can || !picked);
            deny.setDisable(!can || !picked);
            note.setText(can ? "Select a row, then add it to the profile being edited."
                    : "The selected profile is built in, so its tools are locked. Duplicate it to change them.");
        };
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        VBox root = new VBox(10, Ui.dim(ToolCatalog.syntaxHelp()), table, note, Ui.row(allow, deny));
        root.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        Stage stage = new Stage();
        stage.setTitle("Juno · Available tools");
        if (owner != null) stage.initOwner(owner);
        Scene scene = new Scene(root, 860, 560);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setOnHidden(e -> open = null);
        open = stage;
        stage.show();
    }

    private static TableColumn<ToolCatalog.Tool, String> col(String title, double w,
            java.util.function.Function<ToolCatalog.Tool, String> f) {
        TableColumn<ToolCatalog.Tool, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(d -> new SimpleStringProperty(f.apply(d.getValue())));
        return c;
    }
}
