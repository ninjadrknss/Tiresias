package tiresias.spec;

import java.util.*;
import java.util.List;

/** Diff: a line diff of two texts (a spec file on disk and what saving would write), grouped into hunks. */
public final class Diff {

    private Diff() {}

    /** One line of the diff: ' ' unchanged, '-' removed, '+' added; line numbers are 1-based, 0 when absent. */
    public record Line(char kind, String text, int oldNo, int newNo) {}

    /** A run of changes with some unchanged lines around them. */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {
        public String header() {
            return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@";
        }
    }

    /** The longest common subsequence is found on the part between the common start and end, up to this size. */
    public static final long MAX_CELLS = 6_000_000;

    public static List<Line> lines(String before, String after) {
        String[] a = split(before),
            b = split(after);
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf].equals(b[b.length - 1 - suf]))
            suf++;
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) out.add(new Line(' ', a[i], i + 1, i + 1));
        int n = a.length - pre - suf,
            m = b.length - pre - suf;
        if ((long) n * m > MAX_CELLS) {
            // too big to align line by line: show it as one replaced block
            for (int i = 0; i < n; i++) out.add(new Line('-', a[pre + i], pre + i + 1, 0));
            for (int j = 0; j < m; j++) out.add(new Line('+', b[pre + j], 0, pre + j + 1));
        } else {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) lcs[i][j] = a[pre + i].equals(b[pre + j])
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0,
                j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a[pre + i].equals(b[pre + j])) {
                    out.add(new Line(' ', a[pre + i], pre + i + 1, pre + j + 1));
                    i++;
                    j++;
                } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    out.add(new Line('+', b[pre + j], 0, pre + j + 1));
                    j++;
                } else {
                    out.add(new Line('-', a[pre + i], pre + i + 1, 0));
                    i++;
                }
            }
        }
        for (int k = 0; k < suf; k++) {
            int ai = a.length - suf + k,
                bi = b.length - suf + k;
            out.add(new Line(' ', a[ai], ai + 1, bi + 1));
        }
        return out;
    }

    /** Groups the changed lines into hunks with {@code context} unchanged lines on each side. */
    public static List<Hunk> hunks(List<Line> all, int context) {
        List<Hunk> out = new ArrayList<>();
        int i = 0;
        while (i < all.size()) {
            if (all.get(i).kind() == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - context),
                end = i;
            // extend while the next change is within 2*context unchanged lines
            while (true) {
                while (end < all.size() && all.get(end).kind() != ' ') end++;
                int gap = 0;
                while (end + gap < all.size() && all.get(end + gap).kind() == ' ') gap++;
                if (end + gap < all.size() && gap <= 2 * context) end += gap;
                else {
                    end = Math.min(all.size(), end + context);
                    break;
                }
            }
            List<Line> ls = new ArrayList<>(all.subList(start, end));
            int oldStart = 0,
                newStart = 0,
                oldCount = 0,
                newCount = 0;
            for (Line l : ls) {
                if (l.oldNo() > 0) {
                    if (oldStart == 0) oldStart = l.oldNo();
                    oldCount++;
                }
                if (l.newNo() > 0) {
                    if (newStart == 0) newStart = l.newNo();
                    newCount++;
                }
            }
            out.add(new Hunk(oldStart, oldCount, newStart, newCount, ls));
            i = end;
        }
        return out;
    }

    /** {added, removed} line counts. */
    public static int[] counts(List<Line> all) {
        int[] c = new int[2];
        for (Line l : all)
            if (l.kind() == '+') c[0]++;
            else if (l.kind() == '-') c[1]++;
        return c;
    }

    public static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String t = s.replace("\r\n", "\n"); // line endings are compared separately (ReviewDialog.endingsNote)
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return t.split("\n", -1);
    }
}
