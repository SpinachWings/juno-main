package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** A local repository, optionally with one remote (design §5.3). Projects hold no keys. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Project {
    public String id;
    public String name = "";
    public String folder = "";
    /** GitHub {@code owner/repo} or GitLab {@code group/project}; blank for none. */
    public String remote = "";
    public List<String> buildAndTestCommands = new ArrayList<>();

    public Project copy() {
        Project p = new Project();
        p.id = id; p.name = name; p.folder = folder; p.remote = remote;
        p.buildAndTestCommands = new ArrayList<>(buildAndTestCommands);
        return p;
    }
}
