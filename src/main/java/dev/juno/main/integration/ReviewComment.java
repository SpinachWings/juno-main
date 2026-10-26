package dev.juno.main.integration;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A comment on a pull/merge request (or on a linked issue).
 *
 * @param kind      {@code conversation}, {@code review}, {@code inline} or {@code issue}
 * @param path      file for inline comments, else null
 * @param line      line for inline comments, else 0
 * @param diffHunk  diff context for inline comments (GitHub), else null
 * @param resolved  whether the thread is resolved (GitLab)
 */
public record ReviewComment(String kind, long id, String author, String body, String path, int line,
                            String diffHunk, String createdAt, boolean resolved, boolean flaggedByServer) {

    public ReviewComment(String kind, long id, String author, String body, String path, int line, String diffHunk,
                         String createdAt, boolean resolved) {
        this(kind, id, author, body, path, line, diffHunk, createdAt, resolved, false);
    }

    /** Marker Juno puts in the comments it posts, so they are not treated as feedback. */
    public static final String JUNO_MARKER = "<!-- juno -->";

    public static ReviewComment fromJson(JsonNode c) {
        return new ReviewComment(
                c.path("kind").asText("conversation"),
                c.path("id").asLong(),
                c.path("author").asText(""),
                c.path("body").asText(""),
                c.hasNonNull("path") ? c.get("path").asText() : null,
                c.path("line").asInt(0),
                c.hasNonNull("diffHunk") ? c.get("diffHunk").asText() : null,
                c.path("createdAt").asText(""),
                c.path("resolved").asBoolean(false),
                c.path("postedByJuno").asBoolean(false));
    }

    /** Juno's own summaries (AI reviews, conflict resolutions) are never review feedback. */
    public boolean postedByJuno() {
        return flaggedByServer || isJunoText(body);
    }

    /**
     * Whether text was written by Juno: it carries {@link #JUNO_MARKER}, or starts with one of Juno's comment
     * headings (in case the marker was edited out).
     */
    public static boolean isJunoText(String body) {
        if (body == null) {
            return false;
        }
        String text = body.strip();
        return text.contains(JUNO_MARKER) || text.startsWith("### AI review ")
                || text.startsWith("### Merge conflicts resolved");
    }
}
