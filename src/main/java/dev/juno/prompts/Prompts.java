package dev.juno.prompts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the built-in prompt templates (docs/prompts, on the classpath) and renders them (docs/prompts/README.md). */
public final class Prompts {
    private static final String BASE = "/dev/juno/prompts/";
    private static final Pattern SECTION = Pattern.compile("\\{\\{([#^])(\\w+)}}(.*?)\\{\\{/\\2}}", Pattern.DOTALL);
    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");

    private Prompts() {}

    /** The raw text of a prompt file, e.g. {@code profiles/coder.md}. */
    public static String load(String path) {
        try (InputStream in = Prompts.class.getResourceAsStream(BASE + path)) {
            if (in == null) throw new IllegalArgumentException("No built-in prompt: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every built-in prompt file, as paths relative to the prompts folder (e.g. {@code tools/report.md}). */
    public static java.util.List<String> listAll() {
        try {
            java.net.URI uri = Prompts.class.getResource(BASE).toURI();
            if ("jar".equals(uri.getScheme())) {
                try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, Map.of())) {
                    return walk(fs.getPath(BASE));
                }
            }
            return walk(java.nio.file.Path.of(uri));
        } catch (IOException | java.net.URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private static java.util.List<String> walk(java.nio.file.Path root) throws IOException {
        try (var s = java.nio.file.Files.walk(root)) {
            return s.filter(java.nio.file.Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".md")).sorted().toList();
        }
    }

    /** A file's {@code ## key} sections, in order; text before the first heading is documentation. */
    public static Map<String, String> sections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String key = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (key != null) out.put(key, body.toString().trim());
                key = line.substring(3).trim();
                body.setLength(0);
            } else if (key != null) {
                body.append(line).append('\n');
            }
        }
        if (key != null) out.put(key, body.toString().trim());
        return out;
    }

    /** Renders {@code {{x}}}, {@code {{#x}}...{{/x}}} and {@code {{^x}}...{{/x}}}. Unknown placeholders fail. */
    public static String render(String template, Map<String, ?> values) {
        String t = template;
        while (SECTION.matcher(t).find()) t = renderSections(t, values);
        Matcher v = VAR.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (v.find()) {
            String name = v.group(1);
            if (!values.containsKey(name)) throw new IllegalArgumentException("Unknown placeholder: {{" + name + "}}");
            v.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(values.get(name))));
        }
        v.appendTail(sb);
        return sb.toString();
    }

    private static String renderSections(String t, Map<String, ?> values) {
        Matcher m = SECTION.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean positive = m.group(1).equals("#");
            boolean set = truthy(values.get(m.group(2)));
            m.appendReplacement(sb, Matcher.quoteReplacement(positive == set ? m.group(3) : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return !String.valueOf(o).isBlank();
    }
}
