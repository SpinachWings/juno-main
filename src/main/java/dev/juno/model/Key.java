package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A GitHub or GitLab key (design §5.1). The token itself is never here, only a reference to it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Key {
    public enum Kind {
        GITHUB("github.com"), GITLAB("gitlab.com");

        public final String defaultHost;

        Kind(String defaultHost) {
            this.defaultHost = defaultHost;
        }
    }

    public String id;
    public String label = "";
    public Kind kind = Kind.GITHUB;
    public String host = Kind.GITHUB.defaultHost;
    /** {@code keyring:juno/key/<id>} or {@code env:VARIABLE}. Never the token. */
    public String token = "";
    public String gitName = "";
    public String gitEmail = "";
    public String mcpUrlOverride = "";

    public Key copy() {
        Key k = new Key();
        k.id = id; k.label = label; k.kind = kind; k.host = host; k.token = token;
        k.gitName = gitName; k.gitEmail = gitEmail; k.mcpUrlOverride = mcpUrlOverride;
        return k;
    }
}
