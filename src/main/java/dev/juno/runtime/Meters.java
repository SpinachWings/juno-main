package dev.juno.runtime;

import java.util.Locale;

/** Number formats for the context and usage meters (design §7.2, §7.4). */
public final class Meters {
    private Meters() {}

    public static String tokens(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return Math.round(n / 1000.0) + "k";
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }

    /** {@code 46k / 200k · 23%}; the window is left out when unknown. */
    public static String context(long tokens, long window) {
        if (window <= 0) return tokens(tokens);
        return tokens(tokens) + " / " + tokens(window) + " · " + percent(tokens, window) + "%";
    }

    public static int percent(long part, long whole) {
        return whole <= 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    public static String usd(double v) {
        return String.format(Locale.ROOT, v < 0.01 ? "$%.4f" : "$%.2f", v);
    }
}
