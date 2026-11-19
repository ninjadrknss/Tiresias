package tiresias.spec;

import static tiresias.spec.Spec.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/**
 * Fixes: the changes behind the editor's Fix menu items, plus automatic padding. Each one edits the JSON tree of
 * the issue's message in place and checks its condition again first, so an issue that's already fixed (or out of
 * date) does nothing. The Validator attaches a {@link Fix} to the issues these can solve.
 */
public final class Fixes {

    private Fixes() {}

    /** Applies the issue's fix. Returns true if the JSON changed. */
    public static boolean apply(Issue is) {
        Fix fx = is.fix();
        Map<String, Object> m = is.msg();
        if (fx == null || m == null) return false;
        Map<String, Object> p = point(m, is.point()),
            f = field(m, is.field());
        return switch (fx.kind()) {
            case BYTE_ALIGN -> padToByte(m) > 0;
            case PADDING_KEYS -> p != null && !isParsed(p) && p.keySet().removeIf(k -> !PAD_KEYS.contains(k));
            case ID_FORMAT -> {
                String now = str(m, "id"),
                    want = normalId(now);
                if (want == null || want.equals(now)) yield false;
                m.put("id", want);
                yield true;
            }
            case SET_EXT -> {
                if (Boolean.TRUE.equals(m.get("is_ext"))) yield false;
                if (m.containsKey("is_ext")) m.put("is_ext", Boolean.TRUE);
                else insertAfter(m, "id", "is_ext", Boolean.TRUE);
                yield true;
            }
            case REMOVE_POINT_KEY -> p != null && p.remove(fx.arg()) != null;
            case RENAME_DEFAULT_VALUE -> p != null &&
                p.containsKey("default_value") &&
                !p.containsKey("default") &&
                renameKey(p, "default_value", "default", p.get("default_value"));
            case LEGACY_FORMAT -> {
                Matcher mm = p == null ? null : LEGACY_DIVIDE.matcher(strOr(p, "format", ""));
                if (mm == null || !mm.matches() || p.containsKey("formatter")) yield false;
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("key", "divide");
                fm.put("arg", new BigDecimal(mm.group(1)));
                yield renameKey(p, "format", "formatter", fm);
            }
            case ADD_FIELD_KEY -> {
                if (f == null || f.containsKey(fx.arg())) yield false;
                // Keep the spec's usual order: name, unit, doc, then the rest.
                String after = fx.arg().equals("doc") && f.containsKey("unit") ? "unit" : "name";
                insertAfter(f, after, fx.arg(), "");
                yield true;
            }
            case ADD_FIELDS -> {
                if (m.containsKey("fields")) yield false;
                m.put("fields", new ArrayList<>());
                yield true;
            }
            case BIDIR_CASE -> {
                String b = str(m, "bidir_mode");
                if (b == null || b.equals(fx.arg()) || !b.equalsIgnoreCase(fx.arg())) yield false;
                m.put("bidir_mode", fx.arg());
                yield true;
            }
            case TOPIC_SLASHES -> {
                String nm = str(f, "name"),
                    want = nm == null ? null : cleanTopic(nm);
                if (want == null || want.isEmpty() || want.equals(nm)) yield false;
                f.put("name", want);
                yield true;
            }
            case DEDUPE_VALUES -> {
                List<Object> vals = f == null ? null : asList(f.get("values"));
                if (vals == null) yield false;
                Set<Long> seen = new HashSet<>();
                yield vals.removeIf(v -> {
                    Long k = longVal(v);
                    return k != null && !seen.add(k);
                });
            }
            case SIM_SCALE -> p != null && scaleSimFrequencies(asMap(p.get("sim")));
        };
    }

    // ---------- padding

    public static final Set<String> PAD_KEYS = Set.of("size", "parse");

    /** A padding point with nothing but size and parse: the kind the editor may resize or remove. */
    public static boolean isPurePadding(Object o) {
        Map<String, Object> p = asMap(o);
        return p != null && !isParsed(p) && PAD_KEYS.containsAll(p.keySet()) && sizeOf(p) > 0;
    }

    /** The message's last point if it's pure padding, else null. */
    public static Map<String, Object> trailingPadding(List<Object> pts) {
        return pts.isEmpty() || !isPurePadding(pts.getLast()) ? null : asMap(pts.getLast());
    }

    /**
     * Byte-aligns the message by growing its trailing padding point, or adding one when the last point isn't pure
     * padding. Returns the bits added (0 if it was already aligned).
     */
    public static int padToByte(Map<String, Object> m) {
        List<Object> pts = asList(m.get("points"));
        if (pts == null || pts.isEmpty()) return 0;
        int total = offsets(pts)[pts.size()],
            add = (8 - (total % 8)) % 8;
        if (add == 0) return 0;
        Map<String, Object> pad = trailingPadding(pts);
        if (pad != null) pad.put("size", BigDecimal.valueOf(sizeOf(pad) + add));
        else pts.add(padding(add));
        return add;
    }

    /**
     * After a points edit changed the message's total from {@code before} bits, resizes, adds or removes the
     * trailing padding point so the message is the smallest whole number of bytes that holds its points: growing a
     * point uses up padding (or adds a byte), and shrinking or removing one trims the padding (removing the 17th bit
     * of a 32-bit message drops it to 16 bits). It acts when the edit left the message unaligned, or changed its size
     * while the padding holds a whole spare byte. A message of nothing but padding is left alone. Returns a status
     * line, or null if nothing changed.
     */
    public static String keepAligned(Map<String, Object> m, int before) {
        List<Object> pts = asList(m.get("points"));
        if (pts == null || pts.isEmpty()) return null;
        int total = offsets(pts)[pts.size()];
        Map<String, Object> pad = trailingPadding(pts);
        int old = pad == null ? 0 : sizeOf(pad),
            rest = total - old,
            target = roundUpToByte(rest),
            want = target - rest;
        boolean spareByte = total != before && old >= 8;
        if (rest == 0 || (total % 8 == 0 && !spareByte) || want == old) return null;
        if (want == 0) {
            pts.removeLast();
            return "Removed the " + old + "-bit padding point; the message is now " + rest + " bits";
        }
        if (pad != null) pad.put("size", BigDecimal.valueOf(want));
        else pts.add(padding(want));
        return (
            (pad == null
                ? "Added a " + want + "-bit padding point"
                : "Padding resized from " + old + " to " + want + " bits") +
            "; the message is now " +
            target +
            " bits"
        );
    }

    public static int roundUpToByte(int bits) {
        return ((bits + 7) / 8) * 8;
    }

    public static Map<String, Object> padding(int bits) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("size", BigDecimal.valueOf(bits));
        p.put("parse", Boolean.FALSE);
        return p;
    }

    // ---------- helpers

    /** The lowercase "0x…" form of a hex ID (with or without its prefix), or null if it isn't one. */
    public static String normalId(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (HEX_ID.matcher(s).matches()) return "0x" + s.substring(2).toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-fA-F]+") ? "0x" + s.toLowerCase(Locale.ROOT) : null;
    }

    /** A topic without leading or trailing slashes and with no empty levels. */
    public static String cleanTopic(String name) {
        return name.replaceAll("/{2,}", "/").replaceAll("^/+|/+$", "");
    }

    /** Scales [value, frequency] options so the frequencies add up to exactly 1. */
    public static boolean scaleSimFrequencies(Map<String, Object> sim) {
        List<Object> opts = sim == null ? null : asList(sim.get("options"));
        if (opts == null || opts.isEmpty()) return false;
        List<List<Object>> pairs = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Object o : opts) {
            List<Object> pr = asList(o);
            if (
                pr == null || pr.size() != 2 || !(pr.getLast() instanceof BigDecimal fq) || fq.signum() < 0
            ) return false;
            pairs.add(pr);
            sum = sum.add(fq);
        }
        if (sum.signum() == 0 || sum.compareTo(BigDecimal.ONE) == 0) return false;
        MathContext mc = new MathContext(6);
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < pairs.size(); i++) {
            BigDecimal fq = (BigDecimal) pairs.get(i).getLast();
            BigDecimal v =
                i == pairs.size() - 1
                    ? BigDecimal.ONE.subtract(used)
                    : fq.divide(sum, mc).setScale(6, java.math.RoundingMode.HALF_EVEN);
            if (v.signum() < 0) v = BigDecimal.ZERO;
            v = v.stripTrailingZeros();
            if (v.scale() < 0) v = v.setScale(0);
            pairs.get(i).set(1, v);
            used = used.add(v);
        }
        return true;
    }

    public static Map<String, Object> point(Map<String, Object> m, int i) {
        List<Object> pts = asList(m.get("points"));
        return pts == null || i < 0 || i >= pts.size() ? null : asMap(pts.get(i));
    }

    public static Map<String, Object> field(Map<String, Object> m, int j) {
        List<Object> fds = asList(m.get("fields"));
        return fds == null || j < 0 || j >= fds.size() ? null : asMap(fds.get(j));
    }

    /** Replaces a key with another in the same position. */
    public static boolean renameKey(Map<String, Object> p, String from, String to, Object value) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : p.entrySet())
            if (e.getKey().equals(from)) rebuilt.put(to, value);
            else rebuilt.put(e.getKey(), e.getValue());
        p.clear();
        p.putAll(rebuilt);
        return true;
    }

    /** Puts key right after {@code after} (or first, if that key is missing), keeping the rest in order. */
    public static void insertAfter(Map<String, Object> f, String after, String key, Object value) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        if (!f.containsKey(after)) rebuilt.put(key, value);
        for (Map.Entry<String, Object> e : f.entrySet()) {
            rebuilt.put(e.getKey(), e.getValue());
            if (e.getKey().equals(after)) rebuilt.put(key, value);
        }
        f.clear();
        f.putAll(rebuilt);
    }
}
