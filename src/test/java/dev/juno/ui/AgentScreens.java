package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.LiveAgent;
import dev.juno.store.JunoHome;
import dev.juno.store.TokenStore;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Not a unit test: drives the real app with real haiku agents (low effort) against a scratch Juno home, a scratch git
 * repository and the Claude config folder in CLAUDE_CONFIG_DIR, writing a screenshot after each step.
 * Usage: {@code java -cp ... dev.juno.ui.AgentScreens <scratch dir> <output dir> <scenario>}; scenarios:
 * edit, permissions, folder, subagent, kill, crash1, crash2 (run crash1 then crash2 on the same scratch dir).
 */
public final class AgentScreens {
    private static Path out;
    private static MainWindow window;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        String scenario = args[2];
        Files.createDirectories(out);
        Path repo = scratch.resolve("repo");
        if (!Files.exists(repo.resolve(".git"))) {
            Files.createDirectories(repo);
            Files.writeString(repo.resolve("greet.py"),
                    "def greet(name):\n    return \"Hello \" + name\n\n\nprint(greet(\"world\"))\n");
            git(repo, "init", "-q");
            git(repo, "add", ".");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
        }
        TokenStore memory = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String t) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.empty(); }
        };
        JunoHome home = new JunoHome(scratch.resolve("home"));
        AppContext ctx = new AppContext(home, memory, new ClaudeCodeProvider(scratch.resolve("claude-hooks"), home));
        ctx.skills.refreshBundled(ctx.settings);
        ctx.settings.system.firstRunDone = true;
        if (ctx.settings.projects.isEmpty()) {
            Project p = new Project();
            p.id = "p1";
            p.name = "greeter";
            p.folder = repo.toString();
            ctx.settings.projects.add(p);
        }
        if (ctx.settings.profiles.stream().noneMatch(p -> p.name.equals("tester"))) {
            Profile t = ctx.settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst()
                    .orElseThrow().duplicate("tester");
            t.model = "haiku";
            t.effort = "low";
            t.allowedTools.clear();
            t.allowedTools.addAll(List.of("Read", "Grep", "Glob", "mcp__juno__request_folder"));
            t.disallowedTools.clear();
            t.disallowedTools.add("Bash(rm:*)");
            ctx.settings.profiles.add(t);
            // the bundled pack can be given to own profiles: lets /activate be checked
            ctx.settings.skillPacks.stream().filter(sp -> sp.bundled).forEach(sp -> sp.profiles.add("tester"));
        }
        ctx.save();
        ctx.runtime.start();

        Platform.startup(() -> {});
        run(() -> {
            stage = new Stage();
            window = new MainWindow(ctx, stage);
            Scene s = new Scene(window.root(), 1200, 820);
            Theme.apply(s);
            stage.setScene(s);
            stage.show();
        });
        Thread.sleep(1000);
        switch (scenario) {
            case "edit" -> {
                Agent a = agent(ctx, "edie", "tester", "p1");
                type("Edit greet.py so greet() returns \"Hi, \" + name + \"!\" instead. Use the Edit tool. Then reply "
                        + "in Markdown: a heading `Done`, one bullet saying what changed, and the new function in a "
                        + "```python code block.");
                answerUntilDone(ctx, a, "Allow once");
                shot("edit-diff");
                type("/diff");
                Thread.sleep(1500);
                shot("edit-slash-diff");
                type("/usage");
                Thread.sleep(800);
                shot("edit-usage");
            }
            case "permissions" -> {
                Agent a = agent(ctx, "perm", "tester", null);
                type("Run these four bash commands one at a time, in order, each as its own Bash call: "
                        + "`mkdir alpha`, `touch beta.txt`, `mkdir gamma`, `touch delta.txt`. Then run `rm beta.txt`. "
                        + "Then list what happened, one short line each.");
                String[] answers = {"Allow once", "Always allow for perm", "Always allow for tester", "Deny"};
                LiveAgent l = ctx.runtime.live(a.id);
                for (int i = 0; i < answers.length; i++) {
                    waitFor(() -> !l.pendingPermissions().isEmpty() || !a.turnOpen, 120);
                    if (!a.turnOpen) break;
                    Thread.sleep(700);
                    shot("perm-" + (i + 1) + "-asking");
                    click(answers[i]);
                    Thread.sleep(800);
                }
                answerUntilDone(ctx, a, "Deny");
                shot("perm-done");
                System.out.println("RULES agent=" + a.rules.stream().map(r -> r.rule).toList()
                        + " profile=" + ctx.runtime.profile("tester").allowedTools);
                type("Run `touch beta2.txt` with Bash, then say ok.");
                answerUntilDone(ctx, a, "Deny");
                shot("perm-rule-match");
            }
            case "folder" -> {
                Agent c = agent(ctx, "cody", "coder", null);
                type("/model haiku");
                type("/effort low");
                type("What's in greet.py? One sentence, no tools other than Read.");
                Thread.sleep(800);
                shot("folder-held");
                run(() -> {
                    @SuppressWarnings("unchecked")
                    ComboBox<Project> cb = (ComboBox<Project>) stage.getScene().getRoot().lookup(".card-folder .combo-box");
                    cb.getSelectionModel().select(0);
                });
                click("Use this project");
                answerUntilDone(ctx, c, "Allow once");
                shot("folder-set");
                Agent s = agent(ctx, "sally", "tester", null);
                type("You have no project. Call the mcp__juno__request_folder tool with reason \"I need the greeter "
                        + "repo to read greet.py\", then end your turn.");
                answerUntilDone(ctx, s, "Allow once");
                shot("folder-requested");
            }
            case "subagent" -> {
                Agent a = agent(ctx, "subby", "tester", "p1");
                type("/activate brainstorming");
                type("/activate brainstorming");
                type("Use the Agent tool (general-purpose sub-agent) to read greet.py and summarise it in one line. "
                        + "Wait for its result, then end your reply with a question to me as the last paragraph, "
                        + "starting with ❓.");
                answerUntilDone(ctx, a, "Allow once");
                waitFor(() -> !a.turnOpen && a.status != Agent.Status.WORKING, 120);
                answerUntilDone(ctx, a, "Allow once");
                shot("subagent");
                run(() -> window.currentPane().root().lookup(".pane-input").requestFocus());
                run(() -> ((javafx.scene.control.TextArea) window.currentPane().root().lookup(".pane-input")).setText("/he"));
                run(() -> ((javafx.scene.control.TextArea) window.currentPane().root().lookup(".pane-input")).positionCaret(3));
                Thread.sleep(800);
                shot("autocomplete");
                type("/init");
                type("/help");
                Thread.sleep(800);
                shot("help");
            }
            case "kill" -> {
                Agent a = agent(ctx, "killme", "tester", "p1");
                type("Write out the numbers from 1 to 400, one per line, in plain text, nothing else.");
                LiveAgent l = ctx.runtime.live(a.id);
                waitFor(() -> l.pid() > 0, 30);
                Thread.sleep(6000);
                long pid = l.pid();
                System.out.println("KILL pid " + pid + " turnOpen=" + a.turnOpen);
                ProcessHandle.of(pid).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
                waitFor(() -> a.status == Agent.Status.AWAITING, 30);
                Thread.sleep(800);
                shot("kill-interrupted");
                type("/resume");
                Thread.sleep(800);
                type("hello");
                Thread.sleep(500);
                shot("kill-refused-while-working");
                answerUntilDone(ctx, a, "Allow once");
                shot("kill-resumed");
            }
            case "crash1" -> {
                Agent a = agent(ctx, "crashy", "tester", "p1");
                type("Read greet.py, then write out the numbers from 1 to 300, one per line.");
                LiveAgent l = ctx.runtime.live(a.id);
                waitFor(() -> l.contextTokens > 0, 60);
                Thread.sleep(1500);
                System.out.println("CRASH now (no shutdown) turnOpen=" + a.turnOpen);
                ProcessHandle.of(l.pid()).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
                Runtime.getRuntime().halt(1);
            }
            case "crash2" -> {
                Agent a = ctx.runtime.byName("crashy").orElseThrow();
                run(() -> window.showAgent(a));
                Thread.sleep(1200);
                shot("crash-after-restart");
                type("/resume");
                answerUntilDone(ctx, a, "Allow once");
                shot("crash-resumed");
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        ctx.runtime.shutdown();
        Platform.exit();
        System.exit(0);
    }

    private static Agent agent(AppContext ctx, String name, String profile, String project) throws Exception {
        Agent a = ctx.runtime.byName(name).orElseGet(() -> ctx.runtime.create(name, profile, project, null));
        run(() -> window.showAgent(a));
        Thread.sleep(500);
        return a;
    }

    private static void type(String text) throws Exception {
        run(() -> window.currentPane().submit(text));
        Thread.sleep(300);
    }

    /** Waits for the turn to end, answering any permission prompt with {@code answer}. */
    private static void answerUntilDone(AppContext ctx, Agent a, String answer) throws Exception {
        LiveAgent l = ctx.runtime.live(a.id);
        long until = System.currentTimeMillis() + 150_000;
        Thread.sleep(1500);
        while (System.currentTimeMillis() < until && (a.turnOpen || !l.pendingPermissions().isEmpty())) {
            if (!l.pendingPermissions().isEmpty()) {
                Thread.sleep(600);
                click(answer);
            }
            Thread.sleep(500);
        }
        Thread.sleep(1200);
    }

    private static void waitFor(BooleanSupplier c, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(300);
    }

    private static void click(String text) throws Exception {
        run(() -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getText().equals(text) && !b.isDisabled()) {
                    b.fire();
                    return;
                }
            }
            System.out.println("no button " + text);
        });
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static void run(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                l.countDown();
            }
        });
        l.await(30, TimeUnit.SECONDS);
    }

    private static void shot(String name) throws Exception {
        save(name, stage);
        // Popups (autocomplete) are windows of their own.
        List<javafx.stage.Window> popups = new java.util.ArrayList<>();
        run(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.Popup && w.isShowing()).forEach(popups::add));
        for (int i = 0; i < popups.size(); i++) save(name + "-popup" + i, popups.get(i));
    }

    private static void save(String name, javafx.stage.Window w) throws Exception {
        WritableImage[] img = new WritableImage[1];
        run(() -> img[0] = w.getScene().snapshot(null));
        PixelReader pr = img[0].getPixelReader();
        int width = (int) img[0].getWidth(), height = (int) img[0].getHeight();
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", out.resolve(name + ".png").toFile());
        System.out.println("wrote " + name);
    }
}
