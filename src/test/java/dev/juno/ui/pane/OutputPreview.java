package dev.juno.ui.pane;

import dev.juno.provider.AgentEvent;
import dev.juno.ui.Theme;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/** Not a unit test: renders made-up events into an output view and saves a PNG. Args: output file. */
public final class OutputPreview {
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {});
        CountDownLatch done = new CountDownLatch(1);
        Stage[] st = new Stage[1];
        Platform.runLater(() -> {
            OutputView v = new OutputView("bob", () -> "C:/work/repo");
            Map<String, String> todos = new LinkedHashMap<>();
            todos.put("Read the code", "completed");
            todos.put("Fix the bug", "in_progress");
            todos.put("Run the tests", "pending");
            v.addAll(List.of(
                    new AgentEvent.UserText("Fix the greeting and add a test.", "user"),
                    new AgentEvent.Thinking("", 120, null),
                    new AgentEvent.Todos(todos, null),
                    new AgentEvent.ToolUse("t1", "Bash", "{\"command\":\"git status --short\"}", null),
                    new AgentEvent.ToolResult("t1", " M greet.py\n?? notes.txt", false, null),
                    new AgentEvent.Diff("t2", "C:/work/repo/src/greet.py", "def greet(name):\n    return \"Hello \" + name\n",
                            "def greet(name):\n    return \"Hi, \" + name + \"!\"\n", false, null),
                    new AgentEvent.SubAgent("s1", "Review the change", "code-reviewer", "Review greet.py for bugs.", null),
                    new AgentEvent.ToolUse("t3", "Read", "{\"file_path\":\"C:/work/repo/src/greet.py\"}", "s1"),
                    new AgentEvent.ToolResult("t3", "1\tdef greet(name):\n2\t    return 1", false, "s1"),
                    new AgentEvent.Message("Looks **fine**; one nit.", "s1"),
                    new AgentEvent.SubAgentDone("s1", "completed"),
                    new AgentEvent.Diff("t4", "C:/work/repo/test_greet.py", "", "import greet\n\ndef test_hi():\n    assert greet.greet(\"x\") == \"Hi, x!\"  # check\n", true, null),
                    new AgentEvent.Permission("p1", "Bash", "{\"command\":\"npm install left-pad\"}", "Bash(npm install:*)", "any `npm install` command", null),
                    new AgentEvent.Message("## Done\n\nChanged **greet** to say `Hi`.\n\n1. one\n2. two\n\n> quoted\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```python\nprint(\"hi\")  # x\n```\n\n❓ Should I also update the README?", null),
                    new AgentEvent.Usage(10, 20, 30000, 400, 30430, 0, 0, false),
                    new AgentEvent.Usage(10, 20, 30000, 400, 0, 200000, 0.0123, true),
                    new AgentEvent.Notice("Stopped. /resume continues where it left off.", "warn"),
                    new AgentEvent.Notice("bad notice", "bad"), new AgentEvent.Notice("good notice", "good"),
                    new AgentEvent.Patch("greet.py", "@@ -1 +1 @@\n-a\n+b")));
            Scene s = new Scene(new javafx.scene.layout.StackPane(v.node()), 1000, 900);
            Theme.apply(s);
            st[0] = new Stage();
            st[0].setScene(s);
            st[0].show();
            done.countDown();
        });
        done.await();
        Thread.sleep(1500);
        CountDownLatch shot = new CountDownLatch(1);
        Platform.runLater(() -> {
            WritableImage img = st[0].getScene().snapshot(null);
            BufferedImage bi = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bi.getHeight(); y++) for (int x = 0; x < bi.getWidth(); x++) bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
            try {
                ImageIO.write(bi, "png", Path.of(args[0]).toFile());
            } catch (Exception e) {
                e.printStackTrace();
            }
            shot.countDown();
        });
        shot.await();
        Platform.exit();
        System.exit(0);
    }
}
