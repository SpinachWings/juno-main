package dev.juno.app;

import javafx.application.Application;

/** Plain main class, so the app starts from the classpath without a module-path launcher. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(JunoApp.class, args);
    }
}
