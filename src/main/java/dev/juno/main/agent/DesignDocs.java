package dev.juno.main.agent;

import dev.juno.main.model.Ticket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Design doc helpers: pulling the doc out of an agent reply, recognising approval, exporting to .txt. */
public final class DesignDocs {

    public static final String OPEN = "<design-doc>";
    public static final String CLOSE = "</design-doc>";
    private static final Pattern BLOCK = Pattern.compile("(?s)" + Pattern.quote(OPEN) + "\\s*(.*?)\\s*"
            + Pattern.quote(CLOSE));

    private DesignDocs() {
    }

    /** The last {@code <design-doc>…</design-doc>} block in the text, or null. */
    public static String extract(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = BLOCK.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1).strip();
        }
        return last == null || last.isEmpty() ? null : last;
    }

    /** Whether a reply to the design review means "approved, go ahead". */
    public static boolean isApproval(String answer) {
        if (answer == null) {
            return false;
        }
        String a = answer.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        return a.equals("/approve") || a.equals("approve") || a.equals("approved");
    }

    /**
     * Writes the ticket's design doc to {@code <folder>/<title>-design.txt} and returns the file. Re-exporting
     * overwrites the ticket's own earlier export; another ticket's file with the same name is never overwritten.
     */
    public static Path export(Ticket ticket, String projectName, Path folder) throws IOException {
        if (ticket.getDesignDoc().isBlank()) {
            throw new IOException("'" + ticket.getTitle() + "' has no design doc yet");
        }
        Files.createDirectories(folder);
        String slug = ticket.getTitle().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 50) {
            slug = slug.substring(0, 50).replaceAll("-+$", "");
        }
        String stem = slug.isEmpty() ? "ticket" : slug;
        Path previous = ticket.getDesignDocFile() == null ? null : Path.of(ticket.getDesignDocFile()).toAbsolutePath();
        Path file = folder.resolve(stem + "-design.txt");
        for (int n = 2; Files.exists(file) && !file.toAbsolutePath().equals(previous); n++) {
            file = folder.resolve(stem + "-" + n + "-design.txt");
        }
        String header = "Design doc: " + ticket.getTitle() + System.lineSeparator()
                + "Project: " + (projectName == null ? "-" : projectName) + System.lineSeparator()
                + "Status: " + (ticket.isDesignDocApproved() ? "approved" : "draft")
                + ("user".equals(ticket.getDesignDocSource()) ? " (written by a person)" : " (written with the agent)")
                + System.lineSeparator()
                + "Exported: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + System.lineSeparator() + "=".repeat(72) + System.lineSeparator() + System.lineSeparator();
        String body = ticket.getDesignDoc().strip().replace("\r\n", "\n").replace("\n", System.lineSeparator());
        Files.writeString(file, header + body + System.lineSeparator(), StandardCharsets.UTF_8);
        return file;
    }
}
