package dev.juno.ui.pane;

import dev.juno.provider.AgentEvent;
import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * The rendered output of one agent (design §7.3): one read-only rich text area, so selection drags across blocks and
 * copies as plain text. Toggles and copy buttons are clickable ranges. Only the blocks from the first changed one
 * onwards are re-rendered, so a selection higher up survives new output.
 */
public final class OutputView {
    private record Action(int start, int end, Runnable run) {}

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final VirtualizedScrollPane<StyleClassedTextArea> scroll = new VirtualizedScrollPane<>(area);
    private final OutputModel model;
    private final List<Integer> offsets = new ArrayList<>();
    private final List<Integer> paragraphs = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private final PauseTransition throttle = new PauseTransition(Duration.millis(60));
    private boolean scheduled;
    private Runnable onUserScroll = () -> {};

    /** {@code folder} gives the agent's current folder, so paths inside it are shown relative to it. */
    public OutputView(String agentName, java.util.function.Supplier<String> folder) {
        model = new OutputModel(agentName, folder, this::requestRender);
        // Indentation (sub-agents, block bodies) as a paragraph graphic: wrapped lines stay indented, copies stay clean.
        area.setParagraphGraphicFactory(i -> {
            int depth = 0;
            if (i < area.getParagraphs().size()) {
                for (String c : area.getParagraph(i).getParagraphStyle()) {
                    if (c.startsWith("ind")) depth = c.charAt(3) - '0';
                }
            }
            javafx.scene.layout.HBox box = new javafx.scene.layout.HBox();
            for (int d = 0; d < depth; d++) {
                javafx.scene.layout.Region r = new javafx.scene.layout.Region();
                r.getStyleClass().add("indent-guide");
                r.setMinWidth(16);
                r.setPrefWidth(16);
                box.getChildren().add(r);
            }
            return box;
        });
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("output");
        scroll.getStyleClass().add("output-scroll");
        throttle.setOnFinished(e -> render());
        area.setOnMouseClicked(e -> {
            if (!e.isStillSincePress() || e.getClickCount() != 1) return;
            Action a = actionAt(e.getX(), e.getY());
            if (a != null) {
                area.deselect();
                a.run().run();
            }
        });
        area.setOnMouseMoved(e -> area.setCursor(actionAt(e.getX(), e.getY()) != null ? Cursor.HAND : Cursor.TEXT));
        area.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, e -> onUserScroll.run());
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> area.copy());
        MenuItem copyAll = new MenuItem("Copy all");
        copyAll.setOnAction(e -> Ui.copy(area.getText()));
        MenuItem selectAll = new MenuItem("Select all");
        selectAll.setOnAction(e -> area.selectAll());
        area.setContextMenu(new ContextMenu(copy, copyAll, selectAll));
    }

    public javafx.scene.Node node() {
        return scroll;
    }

    public void onUserScroll(Runnable r) {
        onUserScroll = r;
    }

    /** Called on the FX thread. */
    public void add(AgentEvent e) {
        model.add(e);
        requestRender();
    }

    public void addAll(List<AgentEvent> events) {
        for (AgentEvent e : events) model.add(e);
        requestRender();
    }

    /** /clear: empties the pane (the journal and log are kept). */
    public void clear() {
        model.clear();
        requestRender();
    }

    public String text() {
        return area.getText();
    }

    private void requestRender() {
        if (scheduled) return;
        scheduled = true;
        throttle.playFromStart();
    }

    private Action actionAt(double x, double y) {
        var hit = area.hit(x, y);
        var idx = hit.getCharacterIndex();
        if (idx.isEmpty()) return null;
        int i = idx.getAsInt();
        for (Action a : actions) if (i >= a.start() && i < a.end()) return a;
        return null;
    }

    void render() {
        scheduled = false;
        int from = model.takeDirty();
        if (from < 0) return;
        boolean atBottom = atBottom();
        int start = from < offsets.size() ? offsets.get(from) : area.getLength();
        int par = from < paragraphs.size() ? paragraphs.get(from) : Math.max(0, area.getParagraphs().size() - 1);
        if (from >= offsets.size()) {
            // Appending: the area's text ends with a newline, so the new text starts at the last (empty) paragraph.
            start = area.getLength();
        }
        while (offsets.size() > from) offsets.remove(offsets.size() - 1);
        while (paragraphs.size() > from) paragraphs.remove(paragraphs.size() - 1);
        final int s0 = start;
        actions.removeIf(a -> a.start() >= s0);

        StyledDoc doc = new StyledDoc();
        for (int i = from; i < model.blocks.size(); i++) {
            offsets.add(start + doc.length());
            paragraphs.add(par + doc.lineStyles.size());
            model.blocks.get(i).render(doc, 0);
        }
        area.replaceText(start, area.getLength(), doc.text.toString());
        if (doc.length() > 0) area.setStyleSpans(start, spans(doc));
        for (int i = 0; i < doc.lineStyles.size(); i++) {
            int p = par + i;
            if (p < area.getParagraphs().size()) area.setParagraphStyle(p, doc.lineStyles.get(i));
        }
        // The empty last paragraph after the final newline keeps whatever style was there before.
        int last = area.getParagraphs().size() - 1;
        if (last >= 0 && area.getParagraph(last).length() == 0) area.setParagraphStyle(last, Collections.emptyList());
        for (StyledDoc.Action a : doc.actions) actions.add(new Action(a.start() + start, a.end() + start, a.run()));
        if (atBottom) Platform.runLater(this::scrollToEnd);
    }

    private static org.fxmisc.richtext.model.StyleSpans<Collection<String>> spans(StyledDoc doc) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int pos = 0;
        for (StyledDoc.Span s : doc.spans) {
            if (s.start() > pos) b.add(Collections.emptyList(), s.start() - pos);
            b.add(s.styles(), s.end() - s.start());
            pos = s.end();
        }
        if (pos < doc.length()) b.add(Collections.emptyList(), doc.length() - pos);
        return b.create();
    }

    private boolean atBottom() {
        double y = scroll.estimatedScrollYProperty().getValue();
        double total = scroll.totalHeightEstimateProperty().getValue();
        double h = scroll.getHeight();
        return total <= h + 2 || y + h >= total - 40;
    }

    public void scrollToEnd() {
        int last = area.getParagraphs().size() - 1;
        if (last >= 0) area.showParagraphAtBottom(last);
    }
}
