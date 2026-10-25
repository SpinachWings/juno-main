package dev.juno.main.ui;

import dev.juno.main.model.AgentProfile;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.McpLaunch;
import dev.juno.main.model.Organisation;
import dev.juno.main.model.Project;
import dev.juno.main.model.SkillEntry;
import dev.juno.main.model.SkillScope;
import dev.juno.main.skills.SkillLibrary;
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
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Settings, edited on a working copy that replaces the live settings on Save:
 * <ul>
 *   <li><b>Organisations &amp; projects</b>: a tree of organisations (GitLab/GitHub integrations, default
 *       agent) and their projects (directory, colour, repos, agent override, extra allowed tools)</li>
 *   <li><b>Agents</b>: named agent profiles (Claude instance, model, permissions)</li>
 *   <li><b>General</b>: MCP server jars, Java runtime, concurrency limit</li>
 * </ul>
 */
final class SettingsDialog extends Dialog<JunoSettings> {

    private static final Color[] PALETTE = {
            Color.web("#4C8BF5"), Color.web("#E8710A"), Color.web("#1E8E3E"), Color.web("#A142F4"),
            Color.web("#D93025"), Color.web("#12B5CB"), Color.web("#F9AB00"), Color.web("#E52592")};

    /** Combo entry for an agent profile; {@code id == null} means none / inherit. */
    private record Item(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final BoardService service;
    private final JunoSettings working;
    private boolean loading;

    // ---- organisations & projects ----
    private final TreeView<Object> tree = new TreeView<>();
    private final BorderPane detail = new BorderPane();

    private final TextField orgName = new TextField();
    private final ComboBox<Item> orgAgent = new ComboBox<>();
    private final Map<TrackerKind, CheckBox> trackerEnabled = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, TextField> trackerUrl = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, PasswordField> trackerToken = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, Label> trackerResult = new EnumMap<>(TrackerKind.class);
    private final Label orgProjectsLabel = new Label();
    private Node orgForm;

    private final TextField projectName = new TextField();
    private final ComboBox<Organisation> projectOrg = new ComboBox<>();
    private final TextField projectPath = new TextField();
    private final ColorPicker projectColor = new ColorPicker();
    /** Where the project's repository lives: GitHub or GitLab (one or the other, never both). */
    private final ComboBox<TrackerKind> projectHost = new ComboBox<>();
    private final TextField projectRepo = new TextField();
    private final ComboBox<Item> projectAgent = new ComboBox<>();
    private final TextField projectTools = new TextField();
    private Node projectForm;

    // ---- agents ----
    private final ListView<AgentProfile> agentList = new ListView<>();
    private final TextField agentName = new TextField();
    private final TextField cliPath = new TextField();
    private final PasswordField apiKey = new PasswordField();
    private final TextField baseUrl = new TextField();
    private final ComboBox<String> model = new ComboBox<>();
    private final ComboBox<String> permissionMode = new ComboBox<>();
    private final TextField allowedTools = new TextField();
    private final Spinner<Integer> maxTurns = new Spinner<>(0, 1000, 0, 5);
    private final TextField extraArgs = new TextField();
    private final CheckBox exposeMcp =
            new CheckBox("Give agents their organisation's GitLab/GitHub MCP tools (read issues and review comments)");
    private final Label agentUsage = new Label();
    private final GridPane agentForm = new GridPane();

    // ---- general ----
    private final Map<TrackerKind, TextField> mcpJar = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, ComboBox<McpLaunch>> mcpLaunch = new EnumMap<>(TrackerKind.class);
    private final Map<TrackerKind, TextField> mcpPort = new EnumMap<>(TrackerKind.class);
    private final TextField javaExecutable = new TextField();
    private final Spinner<Integer> maxAgents = new Spinner<>(1, 32, 3);
    private final TextField designDocFolder = new TextField();

    // skills
    private final ListView<SkillEntry> skillList = new ListView<>();
    private final TextField skillName = new TextField();
    private final Label skillDescription = new Label();
    private final ComboBox<ScopeItem> skillScope = new ComboBox<>();
    private final CheckBox skillEnabled = new CheckBox("Enabled");
    private final VBox skillContents = new VBox(8);
    private final GridPane skillForm = new GridPane();
    private final List<SkillEntry> importedThisSession = new ArrayList<>();
    private final List<SkillEntry> removedThisSession = new ArrayList<>();
    private final CheckBox openTerminals =
            new CheckBox("Open a terminal window with a live console when an agent starts");
    private final TextField terminalCommand = new TextField();

    SettingsDialog(Window owner, BoardService service, JunoSettings working) {
        this.service = service;
        this.working = working;
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle("Juno settings");
        setHeaderText(null);
        setResizable(true);

        Tab orgTab = new Tab("Organisations & projects", buildOrganisationsTab());
        Tab agentTab = new Tab("Agents", buildAgentsTab());
        Tab skillsTab = new Tab("Skills", buildSkillsTab());
        Tab generalTab = new Tab("General", scroll(buildGeneralTab()));
        TabPane tabs = new TabPane(orgTab, agentTab, skillsTab, generalTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Agent names may have changed on the Agents tab; refresh the agent pickers.
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == orgTab) {
                showSelection();
            } else if (tab == skillsTab) {
                loadSkill(skillList.getSelectionModel().getSelectedItem());
            }
        });
        getDialogPane().setContent(tabs);
        getDialogPane().setPrefSize(980, 660);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            List<String> problems = validate();
            if (!problems.isEmpty()) {
                e.consume(); // keep the dialog open
                Fx.error(getDialogPane().getScene().getWindow(), "Settings need fixing",
                        String.join("\n", problems));
            }
        });
        setResultConverter(button -> {
            if (button != save) {
                // Discard the files of skills uploaded in this dialog.
                importedThisSession.forEach(e -> service.skillLibrary().deleteUpload(e));
                return null;
            }
            removedThisSession.forEach(e -> service.skillLibrary().deleteUpload(e));
            commitGeneral();
            working.normalise();
            return working;
        });
    }

    /** Problems that would stop projects from working; Save is refused until they are fixed. */
    private List<String> validate() {
        List<String> problems = new ArrayList<>();
        java.util.Set<Integer> ports = new java.util.HashSet<>();
        for (TrackerKind kind : TrackerKind.values()) {
            String text = mcpPort.get(kind).getText().strip();
            int port = text.isEmpty() ? -1 : Integer.parseInt(text);
            if (port < 1024 || port > 65535) {
                problems.add("The " + kind.displayName() + " MCP port must be between 1024 and 65535.");
            } else if (!ports.add(port)) {
                problems.add("The GitHub and GitLab MCP servers need different ports.");
            }
            if (mcpLaunch.get(kind).getValue() == McpLaunch.JAR && mcpJar.get(kind).getText().isBlank()) {
                problems.add("Set the " + kind.displayName() + " MCP jar, or choose 'Already running on localhost'.");
            }
        }
        java.util.regex.Pattern githubRepo = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
        java.util.regex.Pattern gitlabProject = java.util.regex.Pattern.compile("([^/\\s]+/)+[^/\\s]+|\\d+");
        for (Project p : working.getProjects()) {
            String name = "Project '" + p.getName() + "'";
            if (p.getName() == null || p.getName().isBlank()) {
                problems.add("A project has no name.");
            }
            if (p.hasBothHosts()) {
                problems.add(name + " has both a GitHub repo and a GitLab project; keep one.");
                continue;
            }
            TrackerKind host = p.host();
            if (host == null) {
                continue; // allowed while setting up; Juno explains what's missing when a ticket is started
            }
            String location = p.location(host);
            boolean valid = (host == TrackerKind.GITHUB ? githubRepo : gitlabProject).matcher(location).matches();
            if (!valid) {
                problems.add(name + ": '" + location + "' isn't a valid " + host.displayName() + " "
                        + (host == TrackerKind.GITHUB ? "repository (owner/repo)." : "project (group/project or ID)."));
            }
            working.organisation(p.getOrgId()).filter(o -> !o.tracker(host).isEnabled())
                    .ifPresent(o -> problems.add(name + " is hosted on " + host.displayName() + ", but "
                            + host.displayName() + " isn't enabled for organisation '" + o.getName() + "'."));
        }
        return problems;
    }

    // =========================================================================================
    // Organisations & projects
    // =========================================================================================

    private Node buildOrganisationsTab() {
        tree.setShowRoot(false);
        tree.setPrefWidth(260);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("tree-org");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else if (item instanceof Organisation o) {
                    setText(o.getName());
                    setGraphic(null);
                    getStyleClass().add("tree-org");
                } else if (item instanceof Project p) {
                    setText(p.getName());
                    setGraphic(new Circle(6, Fx.parseColor(p.getColor(), Color.GRAY)));
                } else {
                    setText(String.valueOf(item));
                    setGraphic(null);
                }
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> showSelection());

        Button addOrg = new Button("Add organisation");
        addOrg.setOnAction(e -> {
            Organisation org = new Organisation();
            if (!working.getAgentProfiles().isEmpty()) {
                org.setAgentProfileId(working.getAgentProfiles().getFirst().getId());
            }
            working.getOrganisations().add(org);
            rebuildTree(org);
            orgName.requestFocus();
            orgName.selectAll();
        });
        Button addProject = new Button("Add project");
        addProject.setOnAction(e -> addProject());
        Button remove = new Button("Remove");
        remove.setOnAction(e -> removeSelected());
        remove.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull());

        HBox buttons = new HBox(6, addOrg, addProject, remove);
        VBox left = new VBox(8, tree, buttons);
        VBox.setVgrow(tree, Priority.ALWAYS);

        orgForm = scroll(buildOrgForm());
        projectForm = scroll(buildProjectForm());

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(detail);
        BorderPane.setMargin(detail, new Insets(0, 0, 0, 12));
        pane.setPadding(new Insets(12));

        Organisation first = working.getOrganisations().isEmpty() ? null : working.getOrganisations().getFirst();
        rebuildTree(first);
        return pane;
    }

    private Node buildOrgForm() {
        GridPane grid = new GridPane();
        formGrid(grid);
        int row = 0;
        grid.add(sectionTitle("Organisation"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Name"), orgName);
        orgAgent.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row++, new Label("Default agent"), orgAgent);
        grid.add(hint("Used for this organisation's tickets unless their project or the ticket itself assigns "
                + "a different agent."), 1, row++);
        orgProjectsLabel.getStyleClass().add("hint");
        orgProjectsLabel.setWrapText(true);
        grid.addRow(row++, new Label("Projects"), orgProjectsLabel);

        bind(orgName, Organisation.class, (o, v) -> {
            o.setName(v);
            tree.refresh();
        });
        orgAgent.setOnAction(e -> {
            if (!loading && selected() instanceof Organisation o && orgAgent.getValue() != null) {
                o.setAgentProfileId(orgAgent.getValue().id());
            }
        });

        for (TrackerKind kind : TrackerKind.values()) {
            CheckBox enabled = new CheckBox("Enable " + kind.displayName() + " for this organisation");
            TextField url = new TextField();
            PasswordField token = new PasswordField();
            token.setPromptText("Access token (or env:VARIABLE)");
            Label result = new Label();
            result.setWrapText(true);
            Button test = new Button("Test connection");
            trackerEnabled.put(kind, enabled);
            trackerUrl.put(kind, url);
            trackerToken.put(kind, token);
            trackerResult.put(kind, result);

            enabled.selectedProperty().addListener((obs, old, on) -> {
                if (!loading && selected() instanceof Organisation o) {
                    o.tracker(kind).setEnabled(on);
                }
                url.setDisable(!on);
                token.setDisable(!on);
                test.setDisable(!on);
            });
            bind(url, Organisation.class, (o, v) -> o.tracker(kind).setApiUrl(v));
            bind(token, Organisation.class, (o, v) -> o.tracker(kind).setToken(v));
            test.setOnAction(e -> testTracker(kind));

            HBox testRow = new HBox(8, test, result);
            testRow.setAlignment(Pos.CENTER_LEFT);
            grid.add(sectionTitle(kind.displayName() + " integration"), 0, row++, 2, 1);
            grid.add(enabled, 1, row++);
            grid.addRow(row++, new Label(kind == TrackerKind.GITLAB ? "Instance URL" : "API URL"), url);
            grid.addRow(row++, new Label("Access token"), token);
            grid.add(testRow, 1, row++);
        }
        grid.add(hint("Each organisation runs its own GitLab/GitHub MCP server with only its own token, and agents "
                + "only ever receive their ticket's organisation's servers. Tokens typed here are stored in plain "
                + "text in ~/.juno/settings.json; 'env:ACME_GITLAB_TOKEN' reads one from an environment variable "
                + "instead."), 0, row, 2, 1);
        return grid;
    }

    private Node buildProjectForm() {
        GridPane grid = new GridPane();
        formGrid(grid);

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

        projectPath.setPromptText("Local checkout the agents work in");
        projectHost.getItems().setAll(TrackerKind.GITHUB, TrackerKind.GITLAB);
        projectHost.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(TrackerKind kind) {
                return kind == null ? "" : kind.displayName();
            }

            @Override
            public TrackerKind fromString(String text) {
                return null;
            }
        });
        projectHost.setMaxWidth(Double.MAX_VALUE);
        projectTools.setPromptText("e.g. Bash(mvn test:*) Bash(npm run lint)");
        projectOrg.setMaxWidth(Double.MAX_VALUE);
        projectAgent.setMaxWidth(Double.MAX_VALUE);

        int row = 0;
        grid.add(sectionTitle("Project"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Name"), projectName);
        grid.addRow(row++, new Label("Organisation"), projectOrg);
        grid.addRow(row++, new Label("Directory"), pathRow);
        grid.addRow(row++, new Label("Colour"), projectColor);
        grid.addRow(row++, new Label("Hosted on"), projectHost);
        grid.addRow(row++, new Label("Repository"), projectRepo);
        grid.add(hint("A project lives on GitHub or on GitLab, not both. Agents' pull/merge requests are opened "
                + "there, issues imported from it are linked to this project automatically, and uploads go to it, "
                + "all with the organisation's credentials for that host. Tickets are tinted with the project's "
                + "colour."), 1, row++);
        grid.add(sectionTitle("Agent"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Agent"), projectAgent);
        grid.addRow(row++, new Label("Extra allowed tools"), projectTools);
        grid.add(hint("Overrides the organisation's agent for this project's tickets; a ticket can override it "
                + "again. Extra allowed tools are added to whichever agent runs here, e.g. this codebase's build "
                + "and test commands."), 1, row);

        bind(projectName, Project.class, (p, v) -> {
            p.setName(v);
            tree.refresh();
        });
        bind(projectPath, Project.class, (p, v) -> p.setPath(v));
        bind(projectRepo, Project.class, (p, v) -> p.setRepository(projectHost.getValue(), v));
        projectHost.valueProperty().addListener((obs, old, kind) -> {
            projectRepo.setPromptText(kind == TrackerKind.GITLAB ? "group/project, e.g. acme/web"
                    : "owner/repo, e.g. acme/web");
            if (!loading && selected() instanceof Project p && kind != null) {
                p.setRepository(kind, projectRepo.getText());
            }
        });
        bind(projectTools, Project.class, (p, v) -> p.setExtraAllowedTools(v));
        projectColor.valueProperty().addListener((obs, old, value) -> {
            if (!loading && selected() instanceof Project p && value != null) {
                p.setColor(Fx.toHex(value));
                tree.refresh();
            }
        });
        projectAgent.setOnAction(e -> {
            if (!loading && selected() instanceof Project p && projectAgent.getValue() != null) {
                p.setAgentProfileId(projectAgent.getValue().id());
            }
        });
        projectOrg.setOnAction(e -> {
            if (!loading && selected() instanceof Project p && projectOrg.getValue() != null
                    && !projectOrg.getValue().getId().equals(p.getOrgId())) {
                p.setOrgId(projectOrg.getValue().getId());
                rebuildTree(p); // moves it under its new organisation and reloads the form
            }
        });
        return grid;
    }

    private Object selected() {
        TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void rebuildTree(Object select) {
        TreeItem<Object> root = new TreeItem<>("root");
        TreeItem<Object> toSelect = null;
        for (Organisation org : working.getOrganisations()) {
            TreeItem<Object> orgItem = new TreeItem<>(org);
            orgItem.setExpanded(true);
            if (org == select) {
                toSelect = orgItem;
            }
            for (Project p : working.projectsIn(org.getId())) {
                TreeItem<Object> projectItem = new TreeItem<>(p);
                orgItem.getChildren().add(projectItem);
                if (p == select) {
                    toSelect = projectItem;
                }
            }
            root.getChildren().add(orgItem);
        }
        tree.setRoot(root);
        if (toSelect != null) {
            tree.getSelectionModel().select(toSelect);
        } else if (!root.getChildren().isEmpty()) {
            tree.getSelectionModel().select(root.getChildren().getFirst());
        } else {
            tree.getSelectionModel().clearSelection();
        }
        showSelection();
    }

    /** Shows the form for the selected tree node, loading its values. */
    private void showSelection() {
        Object item = selected();
        loading = true;
        try {
            if (item instanceof Organisation org) {
                orgName.setText(org.getName());
                fillAgentChoices(orgAgent, "— none —", org.getAgentProfileId());
                for (TrackerKind kind : TrackerKind.values()) {
                    TrackerSettings t = org.tracker(kind);
                    trackerEnabled.get(kind).setSelected(t.isEnabled());
                    trackerUrl.get(kind).setText(t.getApiUrl());
                    trackerToken.get(kind).setText(t.getToken());
                    trackerResult.get(kind).setText("");
                    boolean on = t.isEnabled();
                    trackerUrl.get(kind).setDisable(!on);
                    trackerToken.get(kind).setDisable(!on);
                }
                List<String> names = working.projectsIn(org.getId()).stream().map(Project::getName).toList();
                orgProjectsLabel.setText(names.isEmpty() ? "None yet: use Add project." : String.join(", ", names));
                detail.setCenter(orgForm);
            } else if (item instanceof Project p) {
                projectName.setText(p.getName());
                projectOrg.getItems().setAll(working.getOrganisations());
                projectOrg.setValue(working.organisation(p.getOrgId()).orElse(null));
                projectPath.setText(p.getPath());
                projectColor.setValue(Fx.parseColor(p.getColor(), Color.GRAY));
                TrackerKind host = p.host();
                if (host == null) {
                    // Default to whichever host the organisation has enabled (GitHub if both or neither).
                    host = working.organisation(p.getOrgId())
                            .filter(o -> o.tracker(TrackerKind.GITLAB).isEnabled()
                                    && !o.tracker(TrackerKind.GITHUB).isEnabled())
                            .map(o -> TrackerKind.GITLAB).orElse(TrackerKind.GITHUB);
                }
                projectHost.setValue(host);
                projectRepo.setText(p.location(host));
                projectTools.setText(p.getExtraAllowedTools());
                String inherited = working.organisation(p.getOrgId())
                        .map(o -> "Inherit from organisation: " + working.agentProfile(o.getAgentProfileId())
                                .map(AgentProfile::getName).orElse("none assigned"))
                        .orElse("Inherit from organisation");
                fillAgentChoices(projectAgent, inherited, p.getAgentProfileId());
                detail.setCenter(projectForm);
            } else {
                Label empty = new Label("Add an organisation to get started. Organisations hold GitLab/GitHub "
                        + "credentials and a default agent; projects belong to an organisation.");
                empty.setWrapText(true);
                empty.getStyleClass().add("hint");
                detail.setCenter(empty);
            }
        } finally {
            loading = false;
        }
    }

    private void fillAgentChoices(ComboBox<Item> combo, String noneLabel, String selectedId) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(null, noneLabel));
        for (AgentProfile a : working.getAgentProfiles()) {
            items.add(new Item(a.getId(), a.getName()));
        }
        combo.getItems().setAll(items);
        combo.setValue(items.stream().filter(i -> Objects.equals(i.id(), selectedId)).findFirst()
                .orElse(items.getFirst()));
    }

    private void addProject() {
        Object item = selected();
        Organisation org = item instanceof Organisation o ? o
                : item instanceof Project p ? working.organisation(p.getOrgId()).orElse(null)
                : null;
        if (org == null) {
            if (working.getOrganisations().isEmpty()) {
                Fx.info(getDialogPane().getScene().getWindow(), "No organisation",
                        "Add an organisation first; projects belong to an organisation.");
                return;
            }
            org = working.getOrganisations().getFirst();
        }
        Project project = new Project();
        project.setOrgId(org.getId());
        project.setColor(Fx.toHex(PALETTE[working.getProjects().size() % PALETTE.length]));
        working.getProjects().add(project);
        rebuildTree(project);
        projectName.requestFocus();
        projectName.selectAll();
    }

    private void removeSelected() {
        Object item = selected();
        Window window = getDialogPane().getScene().getWindow();
        if (item instanceof Organisation org) {
            List<Project> projects = working.projectsIn(org.getId());
            if (Fx.confirm(window, "Remove " + org.getName() + "?",
                    "This removes the organisation" + (projects.isEmpty() ? "" : " and its " + projects.size()
                            + " project(s)") + ". Their tickets stay on the board but become unlinked. "
                            + "Nothing on disk, GitLab or GitHub is touched.")) {
                working.getProjects().removeAll(projects);
                working.getOrganisations().remove(org);
                rebuildTree(null);
            }
        } else if (item instanceof Project p) {
            if (Fx.confirm(window, "Remove " + p.getName() + "?",
                    "Tickets linked to this project stay on the board but become unlinked. Files on disk are "
                            + "not touched.")) {
                Organisation org = working.organisation(p.getOrgId()).orElse(null);
                working.getProjects().remove(p);
                rebuildTree(org);
            }
        }
    }

    private void testTracker(TrackerKind kind) {
        if (!(selected() instanceof Organisation org)) {
            return;
        }
        Label result = trackerResult.get(kind);
        commitGeneral();
        JunoSettings candidate = service.store().copy(working, JunoSettings.class);
        String orgId = org.getId();
        result.setText("Starting " + kind.displayName() + " MCP server for " + org.getName() + "…");
        Fx.async(() -> service.testTracker(candidate, orgId, kind),
                tools -> result.setText("OK - tools: " + String.join(", ", tools)),
                err -> result.setText("Failed: " + err.getMessage()));
    }

    // =========================================================================================
    // Agents
    // =========================================================================================

    private Node buildAgentsTab() {
        agentList.getItems().setAll(working.getAgentProfiles());
        agentList.setPrefWidth(240);
        agentList.setPlaceholder(new Label("No agents yet"));

        Button add = new Button("Add");
        add.setOnAction(e -> {
            AgentProfile a = new AgentProfile("New agent");
            working.getAgentProfiles().add(a);
            agentList.getItems().add(a);
            agentList.getSelectionModel().select(a);
            agentName.requestFocus();
            agentName.selectAll();
        });
        Button duplicate = new Button("Duplicate");
        duplicate.setOnAction(e -> {
            AgentProfile source = agentList.getSelectionModel().getSelectedItem();
            if (source != null) {
                AgentProfile copy = source.duplicate(source.getName() + " (copy)");
                working.getAgentProfiles().add(copy);
                agentList.getItems().add(copy);
                agentList.getSelectionModel().select(copy);
            }
        });
        duplicate.disableProperty().bind(agentList.getSelectionModel().selectedItemProperty().isNull());
        Button remove = new Button("Remove");
        remove.setOnAction(e -> removeAgent());
        remove.disableProperty().bind(agentList.getSelectionModel().selectedItemProperty().isNull());
        VBox left = new VBox(8, agentList, new HBox(6, add, duplicate, remove));
        VBox.setVgrow(agentList, Priority.ALWAYS);

        model.setEditable(true);
        model.getItems().setAll(AgentProfile.SUGGESTED_MODELS);
        model.setMaxWidth(Double.MAX_VALUE);
        permissionMode.getItems().setAll(AgentProfile.PERMISSION_MODES);
        apiKey.setPromptText("Blank = the CLI's own login; or a key; or env:VARIABLE");
        baseUrl.setPromptText("Blank = Anthropic default");
        allowedTools.setPromptText("e.g. Bash(git status) Bash(git diff:*)");
        agentUsage.setWrapText(true);
        agentUsage.getStyleClass().add("hint");

        Button browseCli = fileBrowse(cliPath, "Claude Code executable");
        HBox cliRow = new HBox(6, cliPath, browseCli);
        HBox.setHgrow(cliPath, Priority.ALWAYS);

        formGrid(agentForm);
        int row = 0;
        agentForm.add(sectionTitle("Agent profile"), 0, row++, 2, 1);
        agentForm.addRow(row++, new Label("Name"), agentName);
        agentForm.addRow(row++, new Label("Used by"), agentUsage);
        agentForm.add(sectionTitle("Claude instance"), 0, row++, 2, 1);
        agentForm.addRow(row++, new Label("Claude Code CLI"), cliRow);
        agentForm.addRow(row++, new Label("API key"), apiKey);
        agentForm.addRow(row++, new Label("Base URL"), baseUrl);
        agentForm.add(hint("Agents run as 'claude -p' processes in the ticket's project directory. Leave the key "
                + "blank to use your existing Claude login; set a base URL to route through a gateway."), 1, row++);
        agentForm.add(sectionTitle("Model and behaviour"), 0, row++, 2, 1);
        agentForm.addRow(row++, new Label("Model"), model);
        agentForm.addRow(row++, new Label("Permission mode"), permissionMode);
        agentForm.addRow(row++, new Label("Allowed tools"), allowedTools);
        agentForm.addRow(row++, new Label("Max turns (0 = no limit)"), maxTurns);
        agentForm.addRow(row++, new Label("Extra CLI arguments"), extraArgs);
        agentForm.add(exposeMcp, 1, row++);
        agentForm.add(hint("In headless mode, tools that need approval are refused unless the permission mode or "
                + "allowed tools permit them. 'acceptEdits' lets agents edit files; add Bash rules so they can "
                + "build and test (projects can add their own). MCP tools must be allowed explicitly, e.g. "
                + "mcp__juno-github."), 1, row);

        bindAgent(agentName, (a, v) -> {
            a.setName(v);
            agentList.refresh();
        });
        bindAgent(cliPath, AgentProfile::setCliPath);
        bindAgent(apiKey, AgentProfile::setApiKey);
        bindAgent(baseUrl, AgentProfile::setBaseUrl);
        bindAgent(model.getEditor(), AgentProfile::setModel);
        bindAgent(allowedTools, AgentProfile::setAllowedTools);
        bindAgent(extraArgs, AgentProfile::setExtraArgs);
        model.valueProperty().addListener((obs, old, v) -> {
            AgentProfile a = agentList.getSelectionModel().getSelectedItem();
            if (!loading && a != null && v != null) {
                a.setModel(v.strip());
            }
        });
        permissionMode.valueProperty().addListener((obs, old, v) -> withAgent(a -> a.setPermissionMode(v)));
        maxTurns.valueProperty().addListener((obs, old, v) -> withAgent(a -> a.setMaxTurns(v)));
        exposeMcp.selectedProperty().addListener((obs, old, v) -> withAgent(a -> a.setExposeTrackerMcp(v)));

        agentList.getSelectionModel().selectedItemProperty().addListener((obs, old, a) -> loadAgent(a));
        if (!agentList.getItems().isEmpty()) {
            agentList.getSelectionModel().selectFirst();
        } else {
            loadAgent(null);
        }

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(scroll(agentForm));
        BorderPane.setMargin(pane.getCenter(), new Insets(0, 0, 0, 12));
        pane.setPadding(new Insets(12));
        return pane;
    }

    private void loadAgent(AgentProfile a) {
        loading = true;
        try {
            agentForm.setDisable(a == null);
            agentName.setText(a == null ? "" : a.getName());
            cliPath.setText(a == null ? "" : a.getCliPath());
            apiKey.setText(a == null ? "" : a.getApiKey());
            baseUrl.setText(a == null ? "" : a.getBaseUrl());
            model.getEditor().setText(a == null ? "" : a.getModel());
            permissionMode.setValue(a == null ? null : a.getPermissionMode());
            allowedTools.setText(a == null ? "" : a.getAllowedTools());
            maxTurns.getValueFactory().setValue(a == null ? 0 : a.getMaxTurns());
            extraArgs.setText(a == null ? "" : a.getExtraArgs());
            exposeMcp.setSelected(a != null && a.isExposeTrackerMcp());
            agentUsage.setText(a == null ? "" : usageOf(a));
        } finally {
            loading = false;
        }
    }

    /** Where a profile is assigned in settings (ticket-level assignments live on the board). */
    private String usageOf(AgentProfile a) {
        List<String> uses = new ArrayList<>();
        for (Organisation o : working.getOrganisations()) {
            if (a.getId().equals(o.getAgentProfileId())) {
                uses.add("organisation " + o.getName());
            }
        }
        for (Project p : working.getProjects()) {
            if (a.getId().equals(p.getAgentProfileId())) {
                uses.add("project " + p.getName());
            }
        }
        return uses.isEmpty() ? "Not assigned to any organisation or project (tickets may still use it)."
                : String.join(", ", uses);
    }

    private void removeAgent() {
        AgentProfile a = agentList.getSelectionModel().getSelectedItem();
        if (a == null || !Fx.confirm(getDialogPane().getScene().getWindow(), "Remove agent " + a.getName() + "?",
                "Used by: " + usageOf(a) + "\n\nWherever it was assigned, tickets fall back to the next level "
                        + "(ticket → project → organisation).")) {
            return;
        }
        for (Organisation o : working.getOrganisations()) {
            if (a.getId().equals(o.getAgentProfileId())) {
                o.setAgentProfileId(null);
            }
        }
        for (Project p : working.getProjects()) {
            if (a.getId().equals(p.getAgentProfileId())) {
                p.setAgentProfileId(null);
            }
        }
        working.getAgentProfiles().remove(a);
        agentList.getItems().remove(a);
    }

    private void withAgent(Consumer<AgentProfile> change) {
        AgentProfile a = agentList.getSelectionModel().getSelectedItem();
        if (!loading && a != null) {
            change.accept(a);
        }
    }

    private void bindAgent(TextInputControl field, BiConsumer<AgentProfile, String> setter) {
        field.textProperty().addListener((obs, old, value) -> withAgent(a -> setter.accept(a, value.strip())));
    }

    // =========================================================================================
    // General
    // =========================================================================================

    private Node buildGeneralTab() {
        GridPane grid = new GridPane();
        formGrid(grid);
        int row = 0;
        grid.add(sectionTitle("MCP servers"), 0, row++, 2, 1);
        for (TrackerKind kind : TrackerKind.values()) {
            ComboBox<McpLaunch> launch = new ComboBox<>();
            launch.getItems().setAll(McpLaunch.values());
            launch.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(McpLaunch l) {
                    return l == null ? "" : l.label();
                }

                @Override
                public McpLaunch fromString(String text) {
                    return null;
                }
            });
            launch.setValue(working.mcpLaunch(kind));
            TextField port = new TextField(Integer.toString(working.mcpPort(kind)));
            port.setPrefColumnCount(6);
            port.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                    change -> change.getControlNewText().matches("\\d{0,5}") ? change : null));
            TextField jar = new TextField(working.mcpJar(kind));
            Button browse = fileBrowse(jar, kind.displayName() + " MCP server jar");
            jar.disableProperty().bind(launch.valueProperty().isEqualTo(McpLaunch.PORT));
            browse.disableProperty().bind(launch.valueProperty().isEqualTo(McpLaunch.PORT));
            mcpLaunch.put(kind, launch);
            mcpPort.put(kind, port);
            mcpJar.put(kind, jar);
            HBox launchRow = new HBox(8, launch, new Label("Port"), port);
            launchRow.setAlignment(Pos.CENTER_LEFT);
            HBox jarRow = new HBox(6, jar, browse);
            HBox.setHgrow(jar, Priority.ALWAYS);
            grid.addRow(row++, new Label(kind.displayName() + " MCP server"), launchRow);
            grid.addRow(row++, new Label(kind.displayName() + " MCP jar"), jarRow);
        }
        javaExecutable.setText(working.getJavaExecutable());
        javaExecutable.setPromptText("Blank = the Java runtime running Juno");
        HBox javaRow = new HBox(6, javaExecutable, fileBrowse(javaExecutable, "Java executable"));
        HBox.setHgrow(javaExecutable, Priority.ALWAYS);
        grid.addRow(row++, new Label("Java for MCP servers"), javaRow);
        grid.add(hint("Each server listens on its port on this computer (defaults: GitHub "
                + JunoSettings.DEFAULT_GITHUB_MCP_PORT + ", GitLab " + JunoSettings.DEFAULT_GITLAB_MCP_PORT + "). "
                + "'Juno starts the jar' runs it for you (and builds the jar first if it's missing); 'Already "
                + "running on localhost' connects to a server you started yourself, e.g. with run.sh / run.cmd or "
                + "'java -jar <jar> --port <port>'. The servers store no tokens: Juno sends each organisation's "
                + "token with every call, so one server serves all organisations."), 1, row++);
        grid.add(sectionTitle("Agents"), 0, row++, 2, 1);
        maxAgents.getValueFactory().setValue(Math.max(1, working.getMaxConcurrentAgents()));
        grid.addRow(row++, new Label("Max concurrent agents"), maxAgents);
        grid.add(sectionTitle("Agent terminals"), 0, row++, 2, 1);
        openTerminals.setSelected(working.isOpenTerminals());
        grid.add(openTerminals, 1, row++);
        terminalCommand.setText(working.getTerminalCommand());
        terminalCommand.setPromptText("Automatic (Windows Terminal / cmd, macOS Terminal, Linux terminal emulator)");
        grid.addRow(row++, new Label("Terminal command"), terminalCommand);
        grid.add(hint("Each ticket's terminal streams its agent's activity, and you can answer the agent's "
                + "questions there as well as on the board. A custom command can use {title} and {script}, e.g. "
                + "'wezterm start -- {script}' or 'gnome-terminal --title \"{title}\" -- {script}'."), 1, row++);

        grid.add(sectionTitle("Design docs"), 0, row++, 2, 1);
        designDocFolder.setText(working.getDesignDocFolder());
        Button browseDocs = new Button("Browse…");
        browseDocs.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Design doc folder");
            File current = new File(designDocFolder.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File dir = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (dir != null) {
                designDocFolder.setText(dir.getAbsolutePath());
            }
        });
        HBox docsRow = new HBox(6, designDocFolder, browseDocs);
        HBox.setHgrow(designDocFolder, Priority.ALWAYS);
        grid.addRow(row++, new Label("Design doc folder"), docsRow);
        grid.add(hint("Design docs exported from a ticket's terminal (/export) or its Details are written here as "
                + "<TICKET>-<title>-design.txt."), 1, row);
        return grid;
    }

    // =========================================================================================
    // Skills
    // =========================================================================================

    /** Where a skill pack applies. */
    private record ScopeItem(SkillScope scope, String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private Node buildSkillsTab() {
        skillList.getItems().setAll(working.getSkills());
        skillList.setPrefWidth(300);
        skillList.setPlaceholder(new Label("No skills yet"));
        skillList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(SkillEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setText(null);
                } else {
                    setText(e.getName() + "\n" + scopeLabel(e) + (e.isEnabled() ? "" : "  (off)"));
                }
            }
        });

        Button addFile = new Button("Add skill file…");
        addFile.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Skill file");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Skill (SKILL.md, .md, .zip, .skill)", "*.md", "*.zip", "*.skill"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (file != null) {
                importSkill(file.toPath());
            }
        });
        Button addFolder = new Button("Add skill folder…");
        addFolder.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Folder containing SKILL.md (or several skill folders)");
            File dir = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (dir != null) {
                importSkill(dir.toPath());
            }
        });
        Button remove = new Button("Remove");
        remove.disableProperty().bind(skillList.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            SkillEntry entry = skillList.getSelectionModel().getSelectedItem();
            if (entry != null && Fx.confirm(getDialogPane().getScene().getWindow(), "Remove " + entry.getName() + "?",
                    entry.isBundled() ? "This bundled pack won't be added again automatically."
                            : "Its files are deleted from Juno's skill library when you save.")) {
                working.getSkills().remove(entry);
                skillList.getItems().remove(entry);
                if (!importedThisSession.remove(entry)) {
                    removedThisSession.add(entry);
                } else {
                    service.skillLibrary().deleteUpload(entry);
                }
            }
        });
        FlowPane buttons = new FlowPane(6, 6, addFile, addFolder, remove);
        VBox left = new VBox(8, skillList, buttons);
        VBox.setVgrow(skillList, Priority.ALWAYS);

        skillScope.setMaxWidth(Double.MAX_VALUE);
        skillScope.setOnAction(e -> {
            SkillEntry entry = skillList.getSelectionModel().getSelectedItem();
            ScopeItem item = skillScope.getValue();
            if (!loading && entry != null && item != null) {
                entry.setScope(item.scope());
                entry.setScopeId(item.id());
                skillList.refresh();
            }
        });
        skillEnabled.selectedProperty().addListener((obs, old, on) -> {
            SkillEntry entry = skillList.getSelectionModel().getSelectedItem();
            if (!loading && entry != null) {
                entry.setEnabled(on);
                skillList.refresh();
            }
        });
        skillName.textProperty().addListener((obs, old, v) -> {
            SkillEntry entry = skillList.getSelectionModel().getSelectedItem();
            if (!loading && entry != null) {
                entry.setName(v.strip());
                skillList.refresh();
            }
        });
        skillDescription.setWrapText(true);
        skillDescription.getStyleClass().add("hint");

        formGrid(skillForm);
        int row = 0;
        skillForm.add(sectionTitle("Skill pack"), 0, row++, 2, 1);
        skillForm.addRow(row++, new Label("Name"), skillName);
        skillForm.addRow(row++, new Label("Applies to"), skillScope);
        skillForm.add(skillEnabled, 1, row++);
        skillForm.addRow(row++, new Label("Description"), skillDescription);
        skillForm.add(sectionTitle("Skills in this pack"), 0, row++, 2, 1);
        skillForm.add(skillContents, 0, row++, 2, 1);
        skillForm.add(hint("Agents get the skills that apply to their ticket: everywhere (the whole "
                + "orchestration), its organisation, and its project. They are copied into the ticket's worktree "
                + "(.claude/skills/juno-*) for each run and never committed. Juno's own rules (never merge, never "
                + "push) always take precedence over a skill."), 0, row, 2, 1);

        skillList.getSelectionModel().selectedItemProperty().addListener((obs, old, e) -> loadSkill(e));
        if (!skillList.getItems().isEmpty()) {
            skillList.getSelectionModel().selectFirst();
        } else {
            loadSkill(null);
        }

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(scroll(skillForm));
        BorderPane.setMargin(pane.getCenter(), new Insets(0, 0, 0, 12));
        pane.setPadding(new Insets(12));
        return pane;
    }

    private void importSkill(java.nio.file.Path source) {
        ScopeItem scope = skillScope.getValue();
        try {
            SkillEntry entry = service.skillLibrary().importSkill(source,
                    scope == null ? SkillScope.GLOBAL : scope.scope(), scope == null ? null : scope.id());
            working.getSkills().add(entry);
            importedThisSession.add(entry);
            skillList.getItems().add(entry);
            skillList.getSelectionModel().select(entry);
        } catch (java.io.IOException ex) {
            Fx.error(getDialogPane().getScene().getWindow(), "Could not add the skill", ex);
        }
    }

    private List<ScopeItem> scopeItems() {
        List<ScopeItem> items = new ArrayList<>();
        items.add(new ScopeItem(SkillScope.GLOBAL, null, "Everywhere (whole orchestration)"));
        for (Organisation o : working.getOrganisations()) {
            items.add(new ScopeItem(SkillScope.ORGANISATION, o.getId(), "Organisation: " + o.getName()));
        }
        for (Project p : working.getProjects()) {
            String org = working.organisation(p.getOrgId()).map(Organisation::getName).orElse("?");
            items.add(new ScopeItem(SkillScope.PROJECT, p.getId(), "Project: " + org + " / " + p.getName()));
        }
        return items;
    }

    private String scopeLabel(SkillEntry e) {
        return scopeItems().stream()
                .filter(i -> i.scope() == e.getScope() && Objects.equals(i.id(), e.getScopeId()))
                .map(ScopeItem::label).findFirst()
                .orElse(e.getScope() == SkillScope.GLOBAL ? "Everywhere" : "Unassigned (its target was removed)");
    }

    private void loadSkill(SkillEntry e) {
        loading = true;
        try {
            skillForm.setDisable(e == null);
            List<ScopeItem> items = scopeItems();
            skillScope.getItems().setAll(items);
            skillName.setText(e == null ? "" : e.getName());
            skillEnabled.setSelected(e != null && e.isEnabled());
            skillDescription.setText(e == null ? "" : e.getDescription()
                    + (e.isBundled() ? "\n(Bundled with Juno.)" : ""));
            skillScope.setValue(e == null ? items.getFirst() : items.stream()
                    .filter(i -> i.scope() == e.getScope() && Objects.equals(i.id(), e.getScopeId()))
                    .findFirst().orElse(items.getFirst()));
            skillContents.getChildren().clear();
            if (e != null) {
                for (SkillLibrary.SkillInfo info : SkillLibrary.scan(java.nio.file.Path.of(e.getFolder()))) {
                    CheckBox box = new CheckBox(info.name());
                    box.setSelected(!e.getDisabledSkills().contains(info.folderName()));
                    box.setTooltip(new javafx.scene.control.Tooltip(info.description()));
                    box.selectedProperty().addListener((obs, old, on) -> {
                        if (on) {
                            e.getDisabledSkills().remove(info.folderName());
                        } else if (!e.getDisabledSkills().contains(info.folderName())) {
                            e.getDisabledSkills().add(info.folderName());
                        }
                    });
                    Label desc = new Label(info.description());
                    desc.setWrapText(true);
                    desc.getStyleClass().add("hint");
                    skillContents.getChildren().add(new VBox(1, box, desc));
                }
            }
        } finally {
            loading = false;
        }
    }

    private void commitGeneral() {
        for (TrackerKind kind : TrackerKind.values()) {
            working.setMcpJar(kind, mcpJar.get(kind).getText().strip());
            working.setMcpLaunch(kind, mcpLaunch.get(kind).getValue());
            working.setMcpPort(kind, Integer.parseInt(mcpPort.get(kind).getText().strip()));
        }
        working.setJavaExecutable(javaExecutable.getText().strip());
        working.setMaxConcurrentAgents(maxAgents.getValue());
        working.setOpenTerminals(openTerminals.isSelected());
        working.setTerminalCommand(terminalCommand.getText().strip());
        working.setDesignDocFolder(designDocFolder.getText().strip());
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    /** Writes a text field into the selected tree object (organisation or project) as it changes. */
    private <T> void bind(TextInputControl field, Class<T> type, BiConsumer<T, String> setter) {
        field.textProperty().addListener((obs, old, value) -> {
            Object item = selected();
            if (!loading && type.isInstance(item)) {
                setter.accept(type.cast(item), value.strip());
            }
        });
    }

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
        grid.setPadding(new Insets(4, 12, 12, 12));
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
