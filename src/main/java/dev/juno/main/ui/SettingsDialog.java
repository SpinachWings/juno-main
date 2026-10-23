package dev.juno.main.ui;

import dev.juno.main.model.AgentSettings;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.Project;
import dev.juno.main.model.TrackerKind;
import dev.juno.main.model.TrackerSettings;
import dev.juno.main.service.BoardService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/** Projects (paths, colours, trackers), agent (Claude instance/model) and integration settings. */
final class SettingsDialog extends Dialog<JunoSettings> {

    private static final Color[] PALETTE = {
            Color.web("#4C8BF5"), Color.web("#E8710A"), Color.web("#1E8E3E"), Color.web("#A142F4"),
            Color.web("#D93025"), Color.web("#12B5CB"), Color.web("#F9AB00"), Color.web("#E52592")};

    private final BoardService service;
    private final JunoSettings working;

    // projects
    private final ListView<Project> projectList = new ListView<>();
    private final TextField projectName = new TextField();
    private final TextField projectPath = new TextField();
    private final ColorPicker projectColor = new ColorPicker();
    private final TextField projectGitlab = new TextField();
    private final TextField projectGithub = new TextField();
    private final GridPane projectForm = new GridPane();
    private boolean loadingProject;

    // agent
    private final TextField cliPath = new TextField();
    private final ComboBox<String> model = new ComboBox<>();
    private final ComboBox<String> permissionMode = new ComboBox<>();
    private final TextField allowedTools = new TextField();
    private final Spinner<Integer> maxTurns = new Spinner<>(0, 1000, 0, 5);
    private final Spinner<Integer> maxAgents = new Spinner<>(1, 32, 3);
    private final PasswordField apiKey = new PasswordField();
    private final TextField baseUrl = new TextField();
    private final TextField extraArgs = new TextField();
    private final CheckBox exposeMcp = new CheckBox("Give agents the GitLab/GitHub MCP tools (read issues, add comments)");

    // integrations
    private final Map<TrackerKind, TextField> trackerUrl = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, PasswordField> trackerToken = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, TextField> trackerJar = new EnumMap<>(TrackerKind.class);
    private final TextField javaExecutable = new TextField();

    SettingsDialog(Window owner, BoardService service, JunoSettings working) {
        this.service = service;
        this.working = working;
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle("Juno settings");
        setHeaderText(null);
        setResizable(true);

        TabPane tabs = new TabPane(
                new Tab("Projects", buildProjectsTab()),
                new Tab("Agent", scroll(buildAgentTab())),
                new Tab("Integrations", scroll(buildIntegrationsTab())));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getDialogPane().setContent(tabs);
        getDialogPane().setPrefSize(900, 600);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        setResultConverter(button -> {
            if (button != save) {
                return null;
            }
            commitAgentAndIntegrations();
            return working;
        });
    }

    // ---- projects ---------------------------------------------------------------------------

    private Node buildProjectsTab() {
        projectList.getItems().setAll(working.getProjects());
        projectList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Project p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(p.getName());
                    setGraphic(new Circle(6, Fx.parseColor(p.getColor(), Color.GRAY)));
                }
            }
        });
        projectList.setPrefWidth(240);
        projectList.setPlaceholder(new Label("No projects yet"));

        Button add = new Button("Add");
        add.setOnAction(e -> {
            Project p = new Project();
            p.setColor(Fx.toHex(PALETTE[working.getProjects().size() % PALETTE.length]));
            working.getProjects().add(p);
            projectList.getItems().add(p);
            projectList.getSelectionModel().select(p);
            projectName.requestFocus();
            projectName.selectAll();
        });
        Button remove = new Button("Remove");
        remove.setOnAction(e -> {
            Project p = projectList.getSelectionModel().getSelectedItem();
            if (p != null && Fx.confirm(getDialogPane().getScene().getWindow(), "Remove " + p.getName() + "?",
                    "Tickets linked to this project will become unlinked. Files on disk are not touched.")) {
                working.getProjects().remove(p);
                projectList.getItems().remove(p);
            }
        });
        remove.disableProperty().bind(projectList.getSelectionModel().selectedItemProperty().isNull());
        HBox listButtons = new HBox(6, add, remove);
        VBox left = new VBox(8, projectList, listButtons);
        VBox.setVgrow(projectList, Priority.ALWAYS);

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Project directory");
            File current = new File(projectPath.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File dir = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (dir != null) {
                projectPath.setText(dir.getAbsolutePath());
            }
        });
        HBox pathRow = new HBox(6, projectPath, browse);
        HBox.setHgrow(projectPath, Priority.ALWAYS);

        projectGitlab.setPromptText("group/project (optional)");
        projectGithub.setPromptText("owner/repo (optional)");
        projectPath.setPromptText("Local checkout the agents work in");

        formGrid(projectForm);
        int row = 0;
        projectForm.addRow(row++, new Label("Name"), projectName);
        projectForm.addRow(row++, new Label("Directory"), pathRow);
        projectForm.addRow(row++, new Label("Colour"), projectColor);
        projectForm.addRow(row++, new Label("GitLab project"), projectGitlab);
        projectForm.addRow(row++, new Label("GitHub repo"), projectGithub);
        projectForm.add(hint("Tickets linked to this project are colour coded with this colour. Imported issues "
                + "from the GitLab project / GitHub repo above are linked to this project automatically, and "
                + "uploads default to them."), 0, row, 2, 1);

        bindProjectField(projectName, Project::setName);
        bindProjectField(projectPath, Project::setPath);
        bindProjectField(projectGitlab, Project::setGitlabProject);
        bindProjectField(projectGithub, Project::setGithubRepo);
        projectColor.valueProperty().addListener((obs, old, value) -> {
            Project p = projectList.getSelectionModel().getSelectedItem();
            if (!loadingProject && p != null && value != null) {
                p.setColor(Fx.toHex(value));
                projectList.refresh();
            }
        });

        projectList.getSelectionModel().selectedItemProperty().addListener((obs, old, p) -> loadProject(p));
        if (!projectList.getItems().isEmpty()) {
            projectList.getSelectionModel().selectFirst();
        } else {
            loadProject(null);
        }

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(projectForm);
        BorderPane.setMargin(projectForm, new Insets(0, 0, 0, 16));
        pane.setPadding(new Insets(12));
        return pane;
    }

    private void bindProjectField(TextField field, java.util.function.BiConsumer<Project, String> setter) {
        field.textProperty().addListener((obs, old, value) -> {
            Project p = projectList.getSelectionModel().getSelectedItem();
            if (!loadingProject && p != null) {
                setter.accept(p, value.strip());
                projectList.refresh();
            }
        });
    }

    private void loadProject(Project p) {
        loadingProject = true;
        try {
            projectForm.setDisable(p == null);
            projectName.setText(p == null ? "" : p.getName());
            projectPath.setText(p == null ? "" : p.getPath());
            projectColor.setValue(p == null ? Color.GRAY : Fx.parseColor(p.getColor(), Color.GRAY));
            projectGitlab.setText(p == null ? "" : p.getGitlabProject());
            projectGithub.setText(p == null ? "" : p.getGithubRepo());
        } finally {
            loadingProject = false;
        }
    }

    // ---- agent ------------------------------------------------------------------------------

    private Node buildAgentTab() {
        AgentSettings a = working.getAgent();
        cliPath.setText(a.getCliPath());
        model.setEditable(true);
        model.getItems().setAll(AgentSettings.SUGGESTED_MODELS);
        model.getEditor().setText(a.getModel());
        model.setMaxWidth(Double.MAX_VALUE);
        permissionMode.getItems().setAll(AgentSettings.PERMISSION_MODES);
        permissionMode.setValue(a.getPermissionMode());
        allowedTools.setText(a.getAllowedTools());
        allowedTools.setPromptText("e.g. Bash(mvn test:*) Bash(git status) mcp__juno-github");
        maxTurns.getValueFactory().setValue(a.getMaxTurns());
        maxAgents.getValueFactory().setValue(Math.max(1, a.getMaxConcurrentAgents()));
        apiKey.setText(a.getApiKey());
        apiKey.setPromptText("Blank = use the CLI's own login / environment");
        baseUrl.setText(a.getBaseUrl());
        baseUrl.setPromptText("Blank = Anthropic default");
        extraArgs.setText(a.getExtraArgs());
        exposeMcp.setSelected(a.isExposeTrackerMcp());

        Button browse = fileBrowse(cliPath, "Claude Code executable");
        HBox cliRow = new HBox(6, cliPath, browse);
        HBox.setHgrow(cliPath, Priority.ALWAYS);

        GridPane grid = new GridPane();
        formGrid(grid);
        int row = 0;
        grid.add(sectionTitle("Claude instance"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Claude Code CLI"), cliRow);
        grid.addRow(row++, new Label("API key"), apiKey);
        grid.addRow(row++, new Label("Base URL"), baseUrl);
        grid.add(hint("Agents run as 'claude -p' processes in the ticket's project directory. Leave the key blank "
                + "to use your existing Claude login; set a base URL to route through a gateway."), 0, row++, 2, 1);
        grid.add(sectionTitle("Model and behaviour"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Model"), model);
        grid.addRow(row++, new Label("Permission mode"), permissionMode);
        grid.addRow(row++, new Label("Allowed tools"), allowedTools);
        grid.addRow(row++, new Label("Max turns (0 = no limit)"), maxTurns);
        grid.addRow(row++, new Label("Max concurrent agents"), maxAgents);
        grid.addRow(row++, new Label("Extra CLI arguments"), extraArgs);
        grid.add(exposeMcp, 1, row++);
        grid.add(hint("In headless mode, tools that need approval are refused unless the permission mode or the "
                + "allowed-tools list permits them. 'acceptEdits' lets agents edit files; add Bash rules (e.g. your "
                + "build/test commands) so they can verify their work. MCP tools must be allowed explicitly "
                + "(e.g. mcp__juno-github)."), 0, row, 2, 1);
        return grid;
    }

    // ---- integrations -----------------------------------------------------------------------

    private Node buildIntegrationsTab() {
        GridPane grid = new GridPane();
        formGrid(grid);
        int row = 0;
        for (TrackerKind kind : TrackerKind.values()) {
            TrackerSettings t = working.tracker(kind);
            TextField url = new TextField(t.getApiUrl());
            PasswordField token = new PasswordField();
            token.setText(t.getToken());
            token.setPromptText("Blank = inherit " + (kind == TrackerKind.GITLAB ? "GITLAB_TOKEN" : "GITHUB_TOKEN")
                    + " from the environment");
            TextField jar = new TextField(t.getMcpJar());
            trackerUrl.put(kind, url);
            trackerToken.put(kind, token);
            trackerJar.put(kind, jar);

            Button browse = fileBrowse(jar, kind.displayName() + " MCP server jar");
            HBox jarRow = new HBox(6, jar, browse);
            HBox.setHgrow(jar, Priority.ALWAYS);

            Button test = new Button("Test MCP server");
            Label result = new Label();
            result.setWrapText(true);
            test.setOnAction(e -> {
                commitAgentAndIntegrations();
                JunoSettings candidate = service.store().copy(working, JunoSettings.class);
                result.setText("Starting " + kind.displayName() + " MCP server…");
                test.setDisable(true);
                Fx.async(() -> service.testTracker(candidate, kind), tools -> {
                    test.setDisable(false);
                    result.setText("OK - tools: " + String.join(", ", tools));
                }, err -> {
                    test.setDisable(false);
                    result.setText("Failed: " + err.getMessage());
                });
            });
            HBox testRow = new HBox(8, test, result);
            testRow.setAlignment(Pos.CENTER_LEFT);

            grid.add(sectionTitle(kind.displayName()), 0, row++, 2, 1);
            grid.addRow(row++, new Label(kind == TrackerKind.GITLAB ? "Instance URL" : "API URL"), url);
            grid.addRow(row++, new Label("Access token"), token);
            grid.addRow(row++, new Label("MCP server jar"), jarRow);
            grid.add(testRow, 1, row++);
        }
        javaExecutable.setText(working.getJavaExecutable());
        javaExecutable.setPromptText("Blank = the Java runtime running Juno");
        Button browseJava = fileBrowse(javaExecutable, "Java executable");
        HBox javaRow = new HBox(6, javaExecutable, browseJava);
        HBox.setHgrow(javaExecutable, Priority.ALWAYS);
        grid.add(sectionTitle("Runtime"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Java for MCP servers"), javaRow);
        grid.add(hint("Tokens are stored in plain text in ~/.juno/settings.json. Prefer leaving them blank and "
                + "setting GITLAB_TOKEN / GITHUB_TOKEN in your environment."), 0, row, 2, 1);
        return grid;
    }

    private void commitAgentAndIntegrations() {
        AgentSettings a = working.getAgent();
        a.setCliPath(cliPath.getText().strip());
        String modelText = model.getEditor().getText();
        a.setModel(modelText == null || modelText.isBlank() ? model.getValue() : modelText.strip());
        a.setPermissionMode(permissionMode.getValue());
        a.setAllowedTools(allowedTools.getText().strip());
        a.setMaxTurns(maxTurns.getValue());
        a.setMaxConcurrentAgents(maxAgents.getValue());
        a.setApiKey(apiKey.getText().strip());
        a.setBaseUrl(baseUrl.getText().strip());
        a.setExtraArgs(extraArgs.getText().strip());
        a.setExposeTrackerMcp(exposeMcp.isSelected());
        for (TrackerKind kind : TrackerKind.values()) {
            TrackerSettings t = working.tracker(kind);
            t.setApiUrl(trackerUrl.get(kind).getText().strip());
            t.setToken(trackerToken.get(kind).getText().strip());
            t.setMcpJar(trackerJar.get(kind).getText().strip());
        }
        working.setJavaExecutable(javaExecutable.getText().strip());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Button fileBrowse(TextInputControl target, String title) {
        Button b = new Button("Browse…");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            File current = new File(target.getText());
            if (current.getParentFile() != null && current.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(current.getParentFile());
            }
            File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (file != null) {
                target.setText(file.getAbsolutePath());
            }
        });
        return b;
    }

    private static void formGrid(GridPane grid) {
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(160);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, fields);
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("hint");
        return l;
    }
}
