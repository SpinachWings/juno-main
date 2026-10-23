package dev.juno.main.integration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** An issue as returned by either MCP server's normalised issue shape. */
public record IssueData(long number, String title, String body, String state, String url,
                        List<String> labels, List<Comment> comments) {

    public record Comment(String author, String body, String createdAt) {
    }

    public static IssueData fromJson(JsonNode node) {
        List<String> labels = new ArrayList<>();
        node.path("labels").forEach(l -> labels.add(l.asText()));
        List<Comment> comments = new ArrayList<>();
        node.path("comments").forEach(c -> comments.add(new Comment(
                c.path("author").asText(""), c.path("body").asText(""), c.path("createdAt").asText(""))));
        return new IssueData(
                node.path("number").asLong(),
                node.path("title").asText(""),
                node.path("body").asText(""),
                node.path("state").asText(""),
                node.path("url").asText(""),
                List.copyOf(labels),
                List.copyOf(comments));
    }

    @Override
    public String toString() {
        return "#" + number + "  " + title + ("closed".equals(state) ? "  (closed)" : "");
    }
}
