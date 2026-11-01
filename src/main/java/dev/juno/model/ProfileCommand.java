package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A profile command: name plus prompt template (design §8.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileCommand {
    public String name = "";
    public String template = "";
    public boolean builtIn;

    public ProfileCommand() {}

    public ProfileCommand(String name, String template, boolean builtIn) {
        this.name = name;
        this.template = template;
        this.builtIn = builtIn;
    }
}
