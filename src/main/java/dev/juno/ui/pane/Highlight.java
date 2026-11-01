package dev.juno.ui.pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small, language-agnostic syntax highlighter: comments, strings, numbers and common keywords. */
final class Highlight {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "def",
            "default", "do", "elif", "else", "enum", "export", "extends", "false", "final", "finally", "fn", "for",
            "from", "func", "function", "if", "impl", "implements", "import", "in", "interface", "is", "let", "match",
            "mut", "new", "nil", "None", "not", "null", "or", "package", "private", "protected", "pub", "public",
            "record", "return", "sealed", "self", "static", "struct", "super", "switch", "this", "throw", "throws",
            "trait", "True", "False", "true", "try", "type", "use", "val", "var", "void", "while", "with", "yield");
    private static final Pattern TOKEN = Pattern.compile(
            "(?<comment>//.*|#.*|--.*|/\\*.*?\\*/)|(?<string>\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`[^`]*`)"
                    + "|(?<number>\\b\\d[\\d_.xXa-fA-F]*\\b)|(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)");

    record Token(int start, int end, String style) {}

    private Highlight() {}

    /** Style ranges for one line of code ({@code hashComments} turns on {@code #} comments). */
    static List<Token> line(String line, boolean hashComments) {
        List<Token> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(line);
        while (m.find()) {
            if (m.group("comment") != null) {
                String c = m.group("comment");
                if (c.startsWith("#") && !hashComments) continue;
                if (c.startsWith("--") && !hashComments) continue;
                out.add(new Token(m.start(), m.end(), "hl-comment"));
            } else if (m.group("string") != null) {
                out.add(new Token(m.start(), m.end(), "hl-string"));
            } else if (m.group("number") != null) {
                out.add(new Token(m.start(), m.end(), "hl-number"));
            } else if (KEYWORDS.contains(m.group("word"))) {
                out.add(new Token(m.start(), m.end(), "hl-keyword"));
            }
        }
        return out;
    }

    static boolean hashComments(String fileOrLanguage) {
        String f = fileOrLanguage == null ? "" : fileOrLanguage.toLowerCase();
        return f.endsWith(".py") || f.endsWith(".sh") || f.endsWith(".rb") || f.endsWith(".yml") || f.endsWith(".yaml")
                || f.endsWith(".toml") || f.endsWith(".ps1") || f.equals("python") || f.equals("bash") || f.equals("sh")
                || f.equals("shell") || f.equals("yaml") || f.equals("ruby") || f.equals("toml") || f.equals("powershell");
    }

    /** Writes one line of code into {@code doc} with highlighting, on top of {@code base} styles. */
    static void write(StyledDoc doc, String line, boolean hashComments, String... base) {
        int pos = 0;
        for (Token t : line(line, hashComments)) {
            if (t.start() > pos) doc.add(line.substring(pos, t.start()), base);
            String[] styles = java.util.Arrays.copyOf(base, base.length + 1);
            styles[base.length] = t.style();
            doc.add(line.substring(t.start(), t.end()), styles);
            pos = t.end();
        }
        if (pos < line.length()) doc.add(line.substring(pos), base);
    }
}
