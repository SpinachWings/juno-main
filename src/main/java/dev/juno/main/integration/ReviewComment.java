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
                            String diffHunk, String createdAt, boolean resolved) {

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
                c.path("resolved").asBoolean(false));
    }

    public boolean postedByJuno() {
        return body != null && body.contains(JUNO_MARKER);
    }
}
