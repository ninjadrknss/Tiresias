package tiresias.spec;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.traffic.Can.*;
import static tiresias.ui.Look.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.prefs.Preferences;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import tiresias.*;
import tiresias.editor.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/**
 * Helpers for the spec's JSON tree (maps, lists, numbers, IDs, point layout, Rust naming) and the shared spec types.
 */
public final class Spec {

    private Spec() {}

    // ======================================================================================
    // Model helpers (the JSON tree is the source of truth, so unknown keys and order survive)
    // ======================================================================================

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    public static List<Object> listOrEmpty(Object o) {
        List<Object> l = asList(o);
        return l == null ? List.of() : l;
    }

    public static List<Object> ensureList(Map<String, Object> m, String k) {
        List<Object> l = asList(m.get(k));
        if (l == null) {
            l = new ArrayList<>();
            m.put(k, l);
        }
        return l;
    }

    public static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object o = m.get(k);
        return o instanceof String str ? str : null;
    }

    public static String strOr(Map<String, Object> m, String k, String def) {
        String s = str(m, k);
        return s == null ? def : s;
    }

    public static BigDecimal num(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object o = m.get(k);
        return o instanceof BigDecimal b ? b : null;
    }

    public static Long longVal(Object o) {
        if (!(o instanceof BigDecimal b)) return null;
        try {
            return b.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    public static Integer tryInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (Exception e) {
            return null;
        }
    }

    public static BigDecimal parseNum(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (Exception e) {
            return null;
        }
    }

    public static String lc(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static String plain(BigDecimal b) {
        return b == null ? "" : b.stripTrailingZeros().toPlainString();
    }

    public static String fmtD(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return new BigDecimal(d).round(new MathContext(7)).stripTrailingZeros().toPlainString();
    }

    public static final Pattern HEX_ID = Pattern.compile("0[xX][0-9a-fA-F]+");
    public static final Pattern PLACEHOLDER_ANY = Pattern.compile("\\{([^}]*)}");
    public static final Pattern PLACEHOLDER_NUM = Pattern.compile("\\{(\\d+)}");
    public static final Pattern DESC_OK = Pattern.compile("[A-Za-z_\\s]*");
    public static final Pattern C_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    public static final Pattern LEGACY_DIVIDE = Pattern.compile("divide(\\d+)");

    public static Long parseId(String s) {
        if (s == null) return null;
        s = s.strip();
        if (!HEX_ID.matcher(s).matches() || s.length() > 18) return null;
        try {
            return Long.parseLong(s.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long parseLooseNumber(String q) {
        q = q.strip();
        if (HEX_ID.matcher(q).matches()) return parseId(q);
        if (q.matches("\\d{1,12}")) return Long.parseLong(q);
        return null;
    }

    public static String hex(long v) {
        return "0x" + Long.toHexString(v).toUpperCase(Locale.ROOT);
    }

    public static int sizeOf(Object p) {
        Long l = longVal(asMap(p) == null ? null : asMap(p).get("size"));
        return l == null || l < 0 ? 0 : (int) Math.min(l, 4096);
    }

    public static int[] offsets(List<Object> pts) {
        int[] o = new int[pts.size() + 1];
        int acc = 0;
        for (int i = 0; i < pts.size(); i++) {
            o[i] = acc;
            acc += sizeOf(pts.get(i));
        }
        o[pts.size()] = acc;
        return o;
    }

    public static boolean isParsed(Map<String, Object> p) {
        return p != null && !Boolean.FALSE.equals(p.get("parse"));
    }

    /** Divisor applied by the formatter: 1 when there is none, null when the formatter is unknown. */
    public static BigDecimal divisor(Map<String, Object> p) {
        Map<String, Object> f = asMap(p.get("formatter"));
        if (f != null) {
            String k = str(f, "key");
            BigDecimal a = num(f, "arg");
            if (k == null || k.isEmpty()) return BigDecimal.ONE;
            boolean div = "divide".equals(k),
                mul = "multiply".equals(k);
            if (!div && !mul) return BigDecimal.ONE; // firmware-rs cangen ignores other keys: value is used unscaled
            if (a == null) return null;
            BigDecimal u = rustArg(a);
            if (u.signum() == 0) return null;
            return div ? u : BigDecimal.ONE.divide(u, MathContext.DECIMAL64);
        }
        String fmt = str(p, "format");
        if (fmt == null || fmt.isEmpty()) return BigDecimal.ONE;
        Matcher mm = LEGACY_DIVIDE.matcher(fmt);
        return mm.matches() ? new BigDecimal(mm.group(1)) : null;
    }

    /** firmware-rs cangen uses `fmt.arg as u32`: truncates fractions, negatives become 0, saturates at u32::MAX. */
    public static BigDecimal rustArg(BigDecimal a) {
        float f = a.floatValue(); // definition-rs reads arg as an f32, then cangen casts it (arg as u32, saturating)
        if (Float.isNaN(f) || f <= 0) return BigDecimal.ZERO;
        if (Float.isInfinite(f) || f >= 4294967295f) return BigDecimal.valueOf(0xFFFFFFFFL);
        return new BigDecimal(f).setScale(0, java.math.RoundingMode.DOWN);
    }

    /**
     * Word splitting copied from the `heck` crate (what cangen's AsSnakeCase and AsPascalCase use): non-alphanumerics
     * separate words; a lowercase letter, or a digit after one, followed by an uppercase letter ends a word
     * ({@code cell2Temp} is cell2, Temp); and an uppercase run followed by a lowercase letter starts a new word before
     * its last capital ({@code HVPlate} is HV, Plate). Digits keep the case of the letters before them.
     */
    public static List<String> heckWords(String s) {
        List<String> out = new ArrayList<>();
        for (String word : s.split("[^\\p{L}\\p{N}]+")) {
            int n = word.length(),
                init = 0;
            char mode = 'B'; // B: word boundary, L: lowercase, U: uppercase
            for (int i = 0; i < n; i++) {
                char c = word.charAt(i);
                if (i + 1 == n) {
                    if (init < n) out.add(word.substring(init));
                    break;
                }
                char next = word.charAt(i + 1);
                char nextMode = Character.isLowerCase(c) ? 'L' : Character.isUpperCase(c) ? 'U' : mode;
                if (nextMode == 'L' && Character.isUpperCase(next)) {
                    out.add(word.substring(init, i + 1));
                    init = i + 1;
                    mode = 'B';
                } else if (mode == 'U' && Character.isUpperCase(c) && Character.isLowerCase(next)) {
                    if (i > init) out.add(word.substring(init, i));
                    init = i;
                    mode = 'B';
                } else mode = nextMode;
            }
        }
        return out;
    }

    /** Accessor name firmware-rs generates for a point: AsSnakeCase(name). */
    public static String rustSnake(String name) {
        List<String> w = heckWords(name);
        StringBuilder b = new StringBuilder();
        for (String x : w) {
            if (!b.isEmpty()) b.append('_');
            b.append(x.toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** Struct name firmware-rs generates for a message: AsPascalCase(desc.to_lowercase().replace(' ', "_")). */
    public static String rustStructName(String desc) {
        StringBuilder b = new StringBuilder();
        for (String x : heckWords(desc.toLowerCase(Locale.ROOT).replace(' ', '_'))) {
            b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    public static final Set<String> RUST_KEYWORDS = Set.of(
        "as",
        "break",
        "const",
        "continue",
        "crate",
        "else",
        "enum",
        "extern",
        "false",
        "fn",
        "for",
        "if",
        "impl",
        "in",
        "let",
        "loop",
        "match",
        "mod",
        "move",
        "mut",
        "pub",
        "ref",
        "return",
        "self",
        "static",
        "struct",
        "super",
        "trait",
        "true",
        "type",
        "unsafe",
        "use",
        "where",
        "while",
        "async",
        "await",
        "dyn",
        "abstract",
        "become",
        "box",
        "do",
        "final",
        "macro",
        "override",
        "priv",
        "typeof",
        "unsized",
        "virtual",
        "yield",
        "try",
        "gen"
    );
    /** Methods bitfield_struct generates on every struct, which a field accessor would clash with. */
    public static final Set<String> BITFIELD_RESERVED = Set.of("new", "from_bits", "into_bits");

    public static String formatterKey(Map<String, Object> p) {
        Map<String, Object> f = asMap(p.get("formatter"));
        if (f != null) return strOr(f, "key", "");
        return strOr(p, "format", "");
    }

    public static String formatterArg(Map<String, Object> p) {
        Map<String, Object> f = asMap(p.get("formatter"));
        return f == null ? "" : plain(num(f, "arg"));
    }

    /** Physical (post-formatter) range representable by a point, or null if unknown. */
    public static double[] physRange(Map<String, Object> p) {
        int size = sizeOf(p);
        if (size < 1 || size > 63 || Boolean.TRUE.equals(p.get("ieee754_f32"))) return null;
        BigDecimal div = divisor(p);
        if (div == null) return null;
        boolean signed = Boolean.TRUE.equals(p.get("signed"));
        double lo = signed ? -Math.pow(2, size - 1) : 0;
        double hi = signed ? Math.pow(2, size - 1) - 1 : Math.pow(2, size) - 1;
        double d = div.doubleValue();
        double a = lo / d,
            b = hi / d;
        return new double[] { Math.min(a, b), Math.max(a, b) };
    }

    public static String pointLabel(Map<String, Object> p, int i) {
        String nm = str(p, "name");
        return "Point " + (i + 1) + (nm != null && !nm.isEmpty() ? " (" + nm + ")" : "");
    }

    public static String simSummary(Object simO) {
        if (simO == null) return "";
        Map<String, Object> s = asMap(simO);
        if (s == null) return "(invalid)";
        if (s.containsKey("options")) {
            StringBuilder b = new StringBuilder("enum ");
            int n = 0;
            for (Object o : listOrEmpty(s.get("options"))) {
                List<Object> pr = asList(o);
                if (pr == null || pr.size() != 2) continue;
                if (n++ > 0) b.append(", ");
                b.append(pr.getFirst() instanceof BigDecimal v ? plain(v) : "?");
                if (pr.getLast() instanceof BigDecimal freq) b.append(" @")
                    .append(fmtD(freq.doubleValue() * 100))
                    .append('%');
            }
            return b.toString();
        }
        if (s.isEmpty()) return "(empty)";
        return (
            "sweep " +
            plain(num(s, "min")) +
            " … " +
            plain(num(s, "max")) +
            ", step " +
            plain(num(s, "inc_min")) +
            "–" +
            plain(num(s, "inc_max")) +
            (Boolean.TRUE.equals(s.get("round")) ? ", rounded" : "")
        );
    }

    public static Map<String, Object> newPoint(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("size", BigDecimal.valueOf(8));
        p.put("signed", Boolean.FALSE);
        p.put("name", name);
        p.put("c_type", "uint8_t");
        return p;
    }

    public static Map<String, Object> newField(String topic, int pointIndex) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", topic);
        f.put("unit", "");
        List<Object> v = new ArrayList<>();
        if (pointIndex > 0) v.add(BigDecimal.valueOf(pointIndex));
        f.put("values", v);
        f.put("doc", "");
        f.put("desc", "");
        return f;
    }

    /** Re-points field references after points are inserted/removed/moved. f maps old 1-based index to new ({@code <= 0} = removed). */
    public static void remapPointRefs(Map<String, Object> msg, IntUnaryOperator f) {
        for (Object o : listOrEmpty(msg.get("fields"))) {
            Map<String, Object> fd = asMap(o);
            if (fd == null) continue;
            List<Object> vals = asList(fd.get("values"));
            if (vals != null) {
                List<Object> nv = new ArrayList<>();
                for (Object v : vals) {
                    Long k = longVal(v);
                    if (k == null) {
                        nv.add(v);
                        continue;
                    }
                    int r = f.applyAsInt((int) (long) k);
                    if (r > 0) nv.add(BigDecimal.valueOf(r));
                }
                vals.clear();
                vals.addAll(nv);
            }
            String name = str(fd, "name");
            if (name != null && name.contains("{")) {
                Matcher mm = PLACEHOLDER_NUM.matcher(name);
                StringBuilder sb = new StringBuilder();
                while (mm.find()) {
                    int r = f.applyAsInt(Integer.parseInt(mm.group(1)));
                    mm.appendReplacement(sb, Matcher.quoteReplacement(r > 0 ? "{" + r + "}" : "{?}"));
                }
                mm.appendTail(sb);
                fd.put("name", sb.toString());
            }
        }
    }

    public static List<String> fieldsReferencing(Map<String, Object> msg, int pointIndex1) {
        List<String> out = new ArrayList<>();
        for (Object o : listOrEmpty(msg.get("fields"))) {
            Map<String, Object> fd = asMap(o);
            if (fd == null) continue;
            String nm = strOr(fd, "name", "");
            if (
                containsIdx(listOrEmpty(fd.get("values")), pointIndex1) || nm.contains("{" + pointIndex1 + "}")
            ) out.add(nm);
        }
        return out;
    }

    public static boolean putStr(Map<String, Object> m, String k, String v, boolean removeIfEmpty) {
        if (v == null) v = "";
        if (v.isEmpty() && removeIfEmpty) {
            if (m.containsKey(k)) {
                m.remove(k);
                return true;
            }
            return false;
        }
        if (v.equals(m.get(k))) return false;
        m.put(k, v);
        return true;
    }

    public static boolean putBool(Map<String, Object> m, String k, boolean v, boolean def) {
        if (!m.containsKey(k)) {
            if (v == def) return false;
            m.put(k, v);
            return true;
        }
        if (Boolean.valueOf(v).equals(m.get(k))) return false;
        m.put(k, v);
        return true;
    }

    public static boolean putNum(Map<String, Object> m, String k, BigDecimal v) {
        if (v == null) {
            if (m.containsKey(k)) {
                m.remove(k);
                return true;
            }
            return false;
        }
        Object cur = m.get(k);
        if (cur instanceof BigDecimal b && b.compareTo(v) == 0) return false;
        m.put(k, v);
        return true;
    }

    public static int indexOfIdentity(List<Object> l, Object o) {
        for (int i = 0; i < l.size(); i++) if (l.get(i) == o) return i;
        return -1;
    }

    // ======================================================================================
    // Files
    // ======================================================================================

    public static final class SpecFile {

        public final Path path;
        public List<Object> messages = new ArrayList<>();
        public String loadError;
        public boolean notSpec;
        public boolean dirty;
        public final List<String> parseWarnings = new ArrayList<>();

        public SpecFile(Path p) {
            path = p;
        }
    }

    // ======================================================================================
    // Validation
    // ======================================================================================

    public enum Severity {
        ERROR("Error"),
        WARN("Warning"),
        INFO("Info");

        public final String label;

        Severity(String l) {
            label = l;
        }

        public Color color() {
            return this == ERROR ? T.red() : this == WARN ? T.yellow() : T.blue();
        }

        public Color bg() {
            return T.tint(color());
        }

        public static Severity worse(Severity a, Severity b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.ordinal() <= b.ordinal() ? a : b;
        }
    }

    /** One validation finding. point/field are 0-based indices, or -1 when the issue is about the whole message or file. */
    public record Issue(
        Severity sev,
        SpecFile file,
        Map<String, Object> msg,
        String where,
        int point,
        int field,
        String text,
        Fix fix
    ) {}

    /** The kinds of problem the editor can fix by itself (see Fixes). */
    public enum FixKind {
        BYTE_ALIGN("messages that aren't byte aligned"),
        PADDING_KEYS("padding points with extra keys"),
        ID_FORMAT("badly written IDs"),
        SET_EXT("extended IDs without is_ext"),
        REMOVE_POINT_KEY("small points with endianness"),
        RENAME_DEFAULT_VALUE("default_value keys"),
        LEGACY_FORMAT("old format keys"),
        ADD_FIELD_KEY("fields missing unit or doc"),
        ADD_FIELDS("messages missing a fields list"),
        BIDIR_CASE("bidir_mode capitalization problems"),
        TOPIC_SLASHES("topics with stray slashes"),
        DEDUPE_VALUES("repeated values entries"),
        SIM_SCALE("sim frequencies that don't add up to 1");

        /** Plural description for "Fix all N …" menu items. */
        public final String what;

        FixKind(String what) {
            this.what = what;
        }
    }

    /** A fix offered for an issue: what to do (kind plus an argument some kinds need) and the menu text. */
    public record Fix(FixKind kind, String arg, String label) {}

    /** A message and the file it lives in. */
    public record SpecRef(SpecFile file, Map<String, Object> msg) {}

    /** A field (by index) of a message. */
    public record FieldRef(SpecFile file, Map<String, Object> msg, int field) {}

    public static boolean containsIdx(List<Object> l, int k) {
        for (Object o : l) {
            Long v = longVal(o);
            if (v != null && v == k) return true;
        }
        return false;
    }

    @SuppressWarnings("MagicConstant") // style is always Font.PLAIN or Font.BOLD at call sites
    /** Sorts messages by numeric CAN ID (unparseable IDs last), parsing each ID once rather than per comparison. */
    public static void sortMessagesById(List<Map<String, Object>> ms) {
        int n = ms.size();
        long[] keys = new long[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            Long v = parseId(str(ms.get(i), "id"));
            keys[i] = v == null ? Long.MAX_VALUE : v;
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(keys[a], keys[b]));
        List<Map<String, Object>> sorted = new ArrayList<>(n);
        for (Integer i : order) sorted.add(ms.get(i));
        for (int i = 0; i < n; i++) ms.set(i, sorted.get(i));
    }

    public static final String[] SEARCH_MODES = {
        "All",
        "ID",
        "Description",
        "Topic",
        "Point name",
        "Unit",
        "Doc / notes",
    };
    public static final List<String> C_TYPES = List.of(
        "",
        "bool",
        "uint8_t",
        "int8_t",
        "uint16_t",
        "int16_t",
        "uint32_t",
        "int32_t",
        "float"
    );
    /**
     * The editor shows c_type as the Rust type name; the JSON keeps the C name. Order: the type list's order.
     * (c_type is what C code generation uses; firmware-rs picks its own Rust type from size, signed and formatter.)
     */
    public static final Map<String, String> RUST_TYPES = rustTypes();

    public static Map<String, String> rustTypes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bool", "bool");
        m.put("uint8_t", "u8");
        m.put("int8_t", "i8");
        m.put("uint16_t", "u16");
        m.put("int16_t", "i16");
        m.put("uint32_t", "u32");
        m.put("int32_t", "i32");
        m.put("float", "f32");
        m.put("double", "f64");
        return Collections.unmodifiableMap(m);
    }

    /** True when cangen scales the point (a divide or multiply formatter), which makes it an f32. */
    public static boolean rustScaled(Map<String, Object> p) {
        Map<String, Object> f = p == null ? null : asMap(p.get("formatter"));
        String k = f == null ? null : str(f, "key");
        return "divide".equals(k) || "multiply".equals(k);
    }

    /**
     * The Rust type firmware-rs cangen generates for a point, in its order: ieee754_f32 or a divide/multiply
     * formatter gives f32; then c_type float gives f32 and c_type bool on a 1-bit point gives bool; anything else is
     * an integer sized from the bits (u8 up to u64, or i8/i16/i32/i64 when signed; c_type is ignored). Unparsed and
     * unnamed points are reserved bits (""); a signed size cangen can't build is "".
     */
    public static String rustGenType(Map<String, Object> p) {
        if (p == null || !isParsed(p)) return "";
        String nm = str(p, "name");
        if (nm == null || nm.isEmpty()) return "";
        int size = sizeOf(p);
        String ct = str(p, "c_type");
        if (Boolean.TRUE.equals(p.get("ieee754_f32")) || rustScaled(p) || "float".equals(ct)) return "f32";
        if ("bool".equals(ct) && size == 1) return "bool";
        if (Boolean.TRUE.equals(p.get("signed"))) return switch (size) {
            case 8 -> "i8";
            case 16 -> "i16";
            case 32 -> "i32";
            case 64 -> "i64";
            default -> "";
        };
        return size <= 8 ? "u8" : size <= 16 ? "u16" : size <= 32 ? "u32" : "u64";
    }

    /** The Rust name to show for a c_type (unknown values are shown as they are). */
    public static String rustType(String cType) {
        if (cType == null) return "";
        return RUST_TYPES.getOrDefault(cType, cType);
    }

    /** The c_type to write for what was picked or typed: a Rust name (u8) or a C name (uint8_t). */
    public static String cTypeFor(String shown) {
        String s = shown == null ? "" : shown.strip();
        for (Map.Entry<String, String> e : RUST_TYPES.entrySet()) if (e.getValue().equals(s)) return e.getKey();
        return s;
    }

    /** The type list the editor offers, in Rust names. */
    public static final List<String> RUST_TYPE_CHOICES = List.of(
        "",
        "bool",
        "u8",
        "i8",
        "u16",
        "i16",
        "u32",
        "i32",
        "f32"
    );

    public static final List<String> COMMON_UNITS = List.of(
        "",
        "V",
        "mV",
        "A",
        "mA",
        "W",
        "kW",
        "C",
        "%",
        "mph",
        "km/h",
        "rpm",
        "Nm",
        "psig",
        "bar",
        "kPa",
        "Hz",
        "s",
        "ms",
        "mg",
        "mdps",
        "Ah",
        "kWh",
        "Ohm"
    );
}
