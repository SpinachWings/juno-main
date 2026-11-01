package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.model.Profile;
import dev.juno.model.ProfileCommand;
import dev.juno.prompts.Prompts;
import dev.juno.runtime.CommandRegistry;
import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Settings → Commands (design §5.6): every command, and the built-in prompts, read-only. */
public final class CommandsTab extends BorderPane {
    private record Row(String usage, String where, String what, String example) {}

    public CommandsTab(AppContext ctx) {
        setPadding(new Insets(12));
        Label intro = Ui.dim("Type / in a pane to see the commands available there. Names ignore case and spaces: "
                + "/heybob and /hey bob both work. Any other /command isn't sent to the agent.");

        TableView<Row> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("Command", 190, Row::usage));
        table.getColumns().add(column("Where", 130, Row::where));
        table.getColumns().add(column("What", 380, Row::what));
        table.getColumns().add(column("Example", 230, Row::example));
        List<Row> rows = new ArrayList<>();
        for (CommandRegistry.Command c : CommandRegistry.all())
            rows.add(new Row(c.usage(), c.scope().label, c.description(), c.example()));
        for (Profile p : ctx.settings.profiles)
            for (ProfileCommand c : p.commands)
                rows.add(new Row("/" + c.name, "Profile command (" + p.name + ")",
                        "Expands its prompt template and sends it as a message.", "/" + c.name));
        table.setItems(FXCollections.observableArrayList(rows));

        ListView<String> files = new ListView<>(FXCollections.observableArrayList(Prompts.listAll()));
        files.setPrefWidth(230);
        TextArea text = new TextArea();
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("mono");
        files.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            if (f != null) text.setText(Prompts.load(f));
        });
        files.getSelectionModel().select(0);
        HBox prompts = new HBox(8, files, text);
        HBox.setHgrow(text, Priority.ALWAYS);
        prompts.setPrefHeight(300);
        TitledPane builtIn = new TitledPane("Built-in prompts (read-only)", prompts);
        builtIn.setExpanded(false);

        VBox top = new VBox(8, intro);
        setTop(top);
        BorderPane.setMargin(table, new Insets(10, 0, 10, 0));
        setCenter(table);
        setBottom(builtIn);
    }

    private static TableColumn<Row, String> column(String title, double width, java.util.function.Function<Row, String> f) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(d -> new SimpleStringProperty(f.apply(d.getValue())));
        c.setCellFactory(v -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty ? null : s);
                setWrapText(true);
            }
        });
        return c;
    }
}
