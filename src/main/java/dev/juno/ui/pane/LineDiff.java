package dev.juno.ui.pane;

import java.util.ArrayList;
import java.util.List;

/** Line diff (longest common subsequence) for showing edits; falls back to all-removed/all-added when large. */
final class LineDiff {
    enum Kind { SAME, DEL, ADD }

    record Line(Kind kind, String text) {}

    private static final int MAX_CELLS = 4_000_000;

    private LineDiff() {}

    static List<Line> diff(String before, String after) {
        String[] a = split(before), b = split(after);
        List<Line> out = new ArrayList<>();
        if ((long) a.length * b.length > MAX_CELLS) {
            for (String s : a) out.add(new Line(Kind.DEL, s));
            for (String s : b) out.add(new Line(Kind.ADD, s));
            return out;
        }
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                out.add(new Line(Kind.SAME, a[i]));
                i++;
                j++;
            } else if (j < b.length && (i == a.length || lcs[i][j + 1] > lcs[i + 1][j])) {
                out.add(new Line(Kind.ADD, b[j++]));
            } else {
                out.add(new Line(Kind.DEL, a[i++]));
            }
        }
        return out;
    }

    /** Keeps changed lines plus {@code context} unchanged lines around them; gaps become null. */
    static List<Line> withContext(List<Line> lines, int context) {
        boolean[] keep = new boolean[lines.size()];
        for (int k = 0; k < lines.size(); k++) {
            if (lines.get(k).kind() != Kind.SAME) {
                for (int c = Math.max(0, k - context); c <= Math.min(lines.size() - 1, k + context); c++) keep[c] = true;
            }
        }
        List<Line> out = new ArrayList<>();
        boolean gap = false;
        for (int k = 0; k < lines.size(); k++) {
            if (keep[k]) {
                if (gap && !out.isEmpty()) out.add(null);
                out.add(lines.get(k));
                gap = false;
            } else {
                gap = true;
            }
        }
        return out;
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String t = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        return t.split("\n", -1);
    }
}
