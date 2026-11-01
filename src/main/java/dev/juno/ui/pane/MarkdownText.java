package dev.juno.ui.pane;

import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Agent messages as Markdown (design §7.3), written as styled terminal text: headings, emphasis, inline code, lists,
 * quotes, tables, and highlighted code blocks with their own copy button. The text stays plain enough to copy.
 */
final class MarkdownText {
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

    private final StyledDoc doc;
    private final List<String> baseLine;
    private final List<String> inline = new ArrayList<>();
    private String prefix = "";

    private MarkdownText(StyledDoc doc, List<String> baseLine) {
        this.doc = doc;
        this.baseLine = baseLine;
    }

    static void write(StyledDoc doc, String markdown, List<String> baseLine) {
        write(doc, markdown, baseLine, List.of());
    }

    /** {@code lastExtra}: line styles added to the last top-level block only (e.g. a ❓ question). */
    static void write(StyledDoc doc, String markdown, List<String> baseLine, List<String> lastExtra) {
        Node root = PARSER.parse(markdown == null ? "" : markdown);
        MarkdownText m = new MarkdownText(doc, baseLine);
        boolean first = true;
        for (Node n = root.getFirstChild(); n != null; n = n.getNext()) {
            if (!first) m.blankLine();
            if (n.getNext() == null && !lastExtra.isEmpty()) {
                List<String> s = new ArrayList<>(baseLine);
                s.addAll(lastExtra);
                m = new MarkdownText(doc, s);
            }
            m.block(n);
            first = false;
        }
    }

    private void blankLine() {
        doc.line(baseLine).newline();
    }

    private void startLine(List<String> extra) {
        List<String> s = new ArrayList<>(baseLine);
        s.addAll(extra);
        doc.line(s);
        if (!prefix.isEmpty()) doc.add(prefix, "md-mark");
    }

    private void block(Node n) {
        switch (n) {
            case Heading h -> {
                startLine(List.of());
                inline.add(h.getLevel() <= 2 ? "md-h1" : "md-h2");
                inlines(h);
                inline.remove(inline.size() - 1);
                doc.newline();
            }
            case Paragraph p -> {
                startLine(List.of());
                inlines(p);
                doc.newline();
            }
            case FencedCodeBlock c -> code(c.getLiteral(), c.getInfo());
            case IndentedCodeBlock c -> code(c.getLiteral(), "");
            case BulletList l -> list(l, false, 1);
            case OrderedList l -> list(l, true, l.getMarkerStartNumber() == null ? 1 : l.getMarkerStartNumber());
            case BlockQuote q -> {
                String before = prefix;
                prefix = prefix + "│ ";
                boolean first = true;
                for (Node c = q.getFirstChild(); c != null; c = c.getNext()) {
                    if (!first) {
                        startLine(List.of());
                        doc.newline();
                    }
                    block(c);
                    first = false;
                }
                prefix = before;
            }
            case ThematicBreak t -> {
                startLine(List.of());
                doc.add("────────────────────", "md-mark");
                doc.newline();
            }
            case TableBlock t -> table(t);
            case HtmlBlock h -> {
                for (String line : h.getLiteral().stripTrailing().split("\n")) {
                    startLine(List.of());
                    doc.add(line, "md-code");
                    doc.newline();
                }
            }
            default -> {
                startLine(List.of());
                inlines(n);
                doc.newline();
            }
        }
    }

    private void list(Node list, boolean ordered, int start) {
        int i = start;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            String marker = ordered ? (i++) + ". " : "• ";
            String before = prefix;
            boolean first = true;
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                if (first) {
                    if (c instanceof Paragraph p) {
                        startLine(List.of());
                        doc.add(marker, "md-mark");
                        prefix = before + " ".repeat(marker.length());
                        inlines(p);
                        doc.newline();
                    } else {
                        startLine(List.of());
                        doc.add(marker, "md-mark");
                        doc.newline();
                        prefix = before + " ".repeat(marker.length());
                        block(c);
                    }
                    first = false;
                } else {
                    block(c);
                }
            }
            prefix = before;
        }
    }

    private void code(String literal, String info) {
        String lang = info == null ? "" : info.trim().split("\\s+")[0];
        String body = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        startLine(List.of("code-head"));
        doc.add(lang.isEmpty() ? "code" : lang, "dim");
        doc.add("  ");
        doc.action("⧉ copy", () -> Ui.copy(body), "copy-btn");
        doc.newline();
        boolean hash = Highlight.hashComments(lang);
        for (String line : body.split("\n", -1)) {
            startLine(List.of("code-block"));
            Highlight.write(doc, line, hash, "md-codeblock");
            doc.newline();
        }
    }

    private void table(TableBlock t) {
        List<List<String>> rows = new ArrayList<>();
        collectRows(t, rows);
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] width = new int[cols];
        for (List<String> r : rows) for (int c = 0; c < r.size(); c++) width[c] = Math.max(width[c], r.get(c).length());
        boolean header = true;
        for (List<String> r : rows) {
            startLine(List.of());
            for (int c = 0; c < cols; c++) {
                String cell = c < r.size() ? r.get(c) : "";
                doc.add(c == 0 ? "" : " │ ", "md-mark");
                doc.add(cell + " ".repeat(width[c] - cell.length()), header ? "md-strong" : "plain");
            }
            doc.newline();
            if (header) {
                startLine(List.of());
                StringBuilder sep = new StringBuilder();
                for (int c = 0; c < cols; c++) sep.append(c == 0 ? "" : "─┼─").append("─".repeat(width[c]));
                doc.add(sep.toString(), "md-mark");
                doc.newline();
                header = false;
            }
        }
    }

    private static void collectRows(Node n, List<List<String>> rows) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof TableRow) {
                List<String> cells = new ArrayList<>();
                for (Node cell = c.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) cells.add(plain(cell));
                }
                rows.add(cells);
            } else {
                collectRows(c, rows);
            }
        }
    }

    private static String plain(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) sb.append(t.getLiteral());
            else if (c instanceof Code code) sb.append(code.getLiteral());
            else sb.append(plain(c));
        }
        return sb.toString();
    }

    private void inlines(Node parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            switch (n) {
                case Text t -> doc.add(t.getLiteral(), styles());
                case Code c -> doc.add(c.getLiteral(), with("md-code"));
                case Emphasis e -> wrap(e, "md-em");
                case StrongEmphasis s -> wrap(s, "md-strong");
                case Link l -> {
                    wrap(l, "md-link");
                    String text = plain(l);
                    if (!l.getDestination().equals(text)) doc.add(" (" + l.getDestination() + ")", "dim");
                }
                case SoftLineBreak b -> {
                    doc.newline();
                    startLine(List.of());
                }
                case HardLineBreak b -> {
                    doc.newline();
                    startLine(List.of());
                }
                case HtmlInline h -> doc.add(h.getLiteral(), with("md-code"));
                default -> inlines(n);
            }
        }
    }

    private void wrap(Node n, String style) {
        inline.add(style);
        inlines(n);
        inline.remove(inline.size() - 1);
    }

    private String[] styles() {
        return inline.isEmpty() ? new String[] {"plain"} : inline.toArray(String[]::new);
    }

    private String[] with(String extra) {
        List<String> s = new ArrayList<>(inline);
        s.add(extra);
        return s.toArray(String[]::new);
    }
}
