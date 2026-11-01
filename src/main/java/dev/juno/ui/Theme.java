package dev.juno.ui;

import java.io.InputStream;
import javafx.scene.Scene;
import javafx.scene.text.Font;

/** Bundled fonts and the one stylesheet (design §2). */
public final class Theme {
    private static boolean fontsLoaded;

    private Theme() {}

    public static synchronized void loadFonts() {
        if (fontsLoaded) return;
        for (String f : new String[] {"ShareTechMono-Regular.ttf", "VT323-Regular.ttf"}) {
            try (InputStream in = Theme.class.getResourceAsStream("/dev/juno/fonts/" + f)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (Exception ignored) {
                // Falls back to the system monospace font.
            }
        }
        fontsLoaded = true;
    }

    public static void apply(Scene scene) {
        loadFonts();
        scene.getStylesheets().add(Theme.class.getResource("/dev/juno/ui/juno.css").toExternalForm());
    }
}
