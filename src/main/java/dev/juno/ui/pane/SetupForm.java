package dev.juno.ui.pane;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.ui.Ui;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

/**
 * A minimal "new agent" form: name, profile, project, colour (design §7.10).
 * TODO(Milestone 3): the in-cell form of a split, with the GitHub/GitLab key and its warning (Milestone 5).
 */
public final class SetupForm {
    /** Pre-filled colours, cycled through as agents are created; any colour can be picked. */
    static final String[] COLOURS = {"#6FD6FF", "#FF4FD8", "#7CFFB2", "#FFD166", "#B69CFF", "#FF8A5C", "#5CE1E6"};

    private final VBox root;

    public SetupForm(AppContext ctx, Consumer<Agent> created, Runnable cancel) {
        TextField name = new TextField();
        name.setPromptText("e.g. bob");
        ComboBox<Profile> profile = new ComboBox<>();
        profile.getItems().setAll(ctx.settings.profiles.stream()
                .filter(p -> !p.name.equals(dev.juno.model.BuiltInProfiles.ORCHESTRATOR)).toList());
        profile.setConverter(new StringConverter<>() {
            @Override public String toString(Profile p) { return p == null ? "" : p.name; }
            @Override public Profile fromString(String s) { return null; }
        });
        if (!profile.getItems().isEmpty()) profile.setValue(profile.getItems().get(0));
        ComboBox<Project> project = new ComboBox<>();
        project.getItems().add(null);
        project.getItems().addAll(ctx.settings.projects);
        project.setConverter(new StringConverter<>() {
            @Override public String toString(Project p) { return p == null ? "No project" : p.name; }
            @Override public Project fromString(String s) { return null; }
        });
        project.setValue(null);
        ColorPicker colour = new ColorPicker(Color.web(COLOURS[ctx.runtime.agents().size() % COLOURS.length]));
        Label problem = Ui.label("", "bad");
        Label hint = Ui.dim("");
        Runnable updateHint = () -> {
            Profile p = profile.getValue();
            hint.setText(p != null && p.needsProjectFolder && project.getValue() == null
                    ? "The " + p.name + " profile needs a project: you'll be asked for one before its first message runs."
                    : "");
        };
        profile.valueProperty().addListener((o, a, b) -> updateHint.run());
        project.valueProperty().addListener((o, a, b) -> updateHint.run());
        updateHint.run();

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.addRow(0, new Label("Name"), name);
        g.addRow(1, new Label("Profile"), profile);
        g.addRow(2, new Label("Project"), project);
        g.addRow(3, new Label("Colour"), colour);
        var create = Ui.primary("Create", () -> {
            try {
                Project p = project.getValue();
                Agent a = ctx.runtime.create(name.getText(), profile.getValue() == null ? "" : profile.getValue().name,
                        p == null ? null : p.id, hex(colour.getValue()));
                created.accept(a);
            } catch (IllegalArgumentException ex) {
                problem.setText(ex.getMessage());
            }
        });
        create.setDefaultButton(true);
        var buttons = Ui.row(create);
        if (cancel != null) buttons.getChildren().add(Ui.button("Cancel", cancel));
        root = new VBox(12, Ui.heading("New agent"), g, hint, problem, buttons);
        root.setPadding(new Insets(24));
        root.setMaxWidth(520);
        root.setAlignment(Pos.TOP_LEFT);
        root.getStyleClass().add("setup-form");
        javafx.application.Platform.runLater(name::requestFocus);
    }

    public VBox root() {
        return root;
    }

    static String hex(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
