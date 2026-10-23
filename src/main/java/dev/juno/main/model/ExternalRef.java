package dev.juno.main.model;

/**
 * Link between a Juno ticket and an issue in GitLab or GitHub.
 *
 * @param location GitLab project path ({@code group/project}) or GitHub repo ({@code owner/repo})
 * @param number   GitLab IID or GitHub issue number
 * @param url      browser URL of the issue
 * @param syncedAt ISO-8601 instant of the last import/upload
 */
public record ExternalRef(String location, long number, String url, String syncedAt) {

    /** e.g. {@code group/project#12}. */
    public String display() {
        return location + "#" + number;
    }
}
