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
/*
 * Tiresias — browse, search, edit and validate Calypso / CANGEN JSON spec files, and foresee what will
 * break the firmware build before it happens (named for the prophet who warns Odysseus of the dangers ahead).
 *
 * Pure Java 21, no dependencies. Runs anywhere a JDK/JRE with Swing runs.
 *
 *   Run straight from source (JDK 21+):   java Tiresias.java [folder-or-files...]
 *   Or the prebuilt jar:                  java -jar Tiresias.jar [folder-or-files...]
 *   Headless / CI validation:             java -jar Tiresias.jar --validate <folder> [--strict] [--info]
 *        (exit code 1 when errors are found; --strict also fails on warnings)
 */

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.*;
import javax.swing.table.*;
import javax.swing.tree.*;

public class Tiresias {

    static final System.Logger LOG = System.getLogger("Tiresias");

    // ======================================================================================
    // Entry point
    // ======================================================================================

    public static void main(String[] args) {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        a.removeIf(x -> x.startsWith("-psn_")); // macOS Finder may pass a process serial number
        if (a.contains("--askpass")) {
            // SSH_ASKPASS helper for live capture: hand ssh the password
            String pw = System.getenv("TIRESIAS_SSH_PW");
            System.out.println(pw == null ? "" : pw);
            System.exit(0);
            return;
        }
        if (a.remove("--candump")) {
            System.exit(Cli.candump(a));
            return;
        }
        if (a.remove("--export-icons")) {
            System.exit(Icons.export(a));
            return;
        }
        boolean validate = a.remove("--validate") | a.remove("-v");
        if (validate) {
            boolean strict = a.remove("--strict");
            boolean info = a.remove("--info");
            System.exit(Cli.run(a, strict, info));
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available. For command-line validation use: --validate <folder|files...>");
            System.exit(2);
        }
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        // Start slow one-time work in parallel with building the window: spec files, the font system, Swing classes.
        List<Path> initial = App.initialPaths(a);
        CompletableFuture<Loader.Loaded> preload = initial.isEmpty()
            ? null
            : CompletableFuture.supplyAsync(() -> Loader.loadAll(initial, List.of()));
        Thread.ofPlatform().daemon().start(Theme::initFonts);
        Thread.ofPlatform().daemon().start(Tiresias::preloadClasses);
        SwingUtilities.invokeLater(() -> {
            Theme.apply(Settings.NODE.get("theme", "One Dark"));
            App app = new App();
            app.setVisible(true);
            app.openInitial(initial, preload);
        });
    }

    /** Loads (and statically initializes) the heavier Swing classes on a spare core while the main thread starts up. */
    static void preloadClasses() {
        for (String c : List.of(
            "javax.swing.JTable",
            "javax.swing.JTree",
            "javax.swing.JTabbedPane",
            "javax.swing.JSplitPane",
            "javax.swing.JComboBox",
            "javax.swing.JTextArea",
            "javax.swing.plaf.metal.MetalLookAndFeel",
            "javax.swing.plaf.basic.BasicTableUI",
            "javax.swing.plaf.basic.BasicTreeUI",
            "javax.swing.plaf.basic.BasicTabbedPaneUI",
            "javax.swing.text.html.HTMLEditorKit",
            "javax.swing.plaf.basic.BasicHTML",
            "javax.swing.table.DefaultTableCellRenderer"
        )) {
            try {
                Class.forName(c, true, Tiresias.class.getClassLoader());
            } catch (Throwable ignored) {}
        }
        try {
            new javax.swing.text.html.HTMLEditorKit().getStyleSheet();
        } catch (Throwable ignored) {} // parses Swing's default HTML CSS
    }

    static void setNativeLookAndFeel() throws Exception {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(i.getName())) {
                        UIManager.setLookAndFeel(i.getClassName());
                        return;
                    }
                }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (UnsupportedLookAndFeelException ignored) {}
    }

    // ======================================================================================
    // Minimal JSON (order-preserving, numbers kept as BigDecimal so files round-trip cleanly)
    // ======================================================================================

    static final class JsonException extends RuntimeException {

        JsonException(String m) {
            super(m);
        }
    }

    static final class Json {

        static Object parse(String s) {
            return new Parser(s).parseAll();
        }

        static final class Parser {

            final String s;
            int i;
            final List<String> warnings = new ArrayList<>();

            Parser(String s) {
                this.s = s;
            }

            Object parseAll() {
                ws();
                Object v = value();
                ws();
                if (i < s.length()) throw err("Unexpected trailing content");
                return v;
            }

            JsonException err(String m) {
                return new JsonException(m + " (" + pos(i) + ")");
            }

            String pos(int at) {
                int line = 1,
                    col = 1;
                for (int k = 0; k < at && k < s.length(); k++) {
                    if (s.charAt(k) == '\n') {
                        line++;
                        col = 1;
                    } else col++;
                }
                return "line " + line + ", column " + col;
            }

            char peek() {
                return i < s.length() ? s.charAt(i) : '\0';
            }

            void ws() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                    else break;
                }
            }

            Object value() {
                if (i >= s.length()) throw err("Unexpected end of input");
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> obj();
                    case '[' -> arr();
                    case '"' -> str();
                    case 't' -> {
                        lit("true");
                        yield Boolean.TRUE;
                    }
                    case 'f' -> {
                        lit("false");
                        yield Boolean.FALSE;
                    }
                    case 'n' -> {
                        lit("null");
                        yield null;
                    }
                    default -> {
                        if (c == '-' || (c >= '0' && c <= '9')) yield num();
                        throw err("Unexpected character '" + c + "'");
                    }
                };
            }

            void lit(String w) {
                if (!s.startsWith(w, i)) throw err("Expected '" + w + "'");
                i += w.length();
            }

            Map<String, Object> obj() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (peek() == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    if (peek() != '"') throw err("Expected a quoted key");
                    int keyAt = i;
                    String k = str();
                    ws();
                    if (peek() != ':') throw err("Expected ':' after key \"" + k + "\"");
                    i++;
                    ws();
                    if (m.containsKey(k)) warnings.add(
                        "Duplicate key \"" + k + "\" (" + pos(keyAt) + "); the last value wins"
                    );
                    m.put(k, value());
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    if (c == '}') {
                        i++;
                        return m;
                    }
                    throw err("Expected ',' or '}'");
                }
            }

            List<Object> arr() {
                List<Object> l = new ArrayList<>();
                i++;
                ws();
                if (peek() == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    if (c == ']') {
                        i++;
                        return l;
                    }
                    throw err("Expected ',' or ']'");
                }
            }

            String str() {
                StringBuilder b = new StringBuilder();
                i++;
                while (true) {
                    if (i >= s.length()) throw err("Unterminated string");
                    char c = s.charAt(i++);
                    if (c == '"') return b.toString();
                    if (c == '\\') {
                        if (i >= s.length()) throw err("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"', '\\', '/' -> b.append(e);
                            case 'b' -> b.append('\b');
                            case 'f' -> b.append('\f');
                            case 'n' -> b.append('\n');
                            case 'r' -> b.append('\r');
                            case 't' -> b.append('\t');
                            case 'u' -> {
                                if (i + 4 > s.length()) throw err("Bad \\u escape");
                                try {
                                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                } catch (NumberFormatException ex) {
                                    throw err("Bad \\u escape");
                                }
                                i += 4;
                            }
                            default -> throw err("Bad escape \\" + e);
                        }
                    } else {
                        if (c < 0x20) throw err("Control character in string");
                        b.append(c);
                    }
                }
            }

            BigDecimal num() {
                int st = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                try {
                    return new BigDecimal(s.substring(st, i));
                } catch (NumberFormatException ex) {
                    i = st;
                    throw err("Invalid number");
                }
            }
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0);
            sb.append('\n');
            return sb.toString();
        }

        static String writeCompact(Object v) {
            String s = write(v);
            return s.replaceAll("\\s*\\n\\s*", " ").strip();
        }

        static void indent(StringBuilder sb, int n) {
            sb.repeat("  ", n);
        }

        static void write(StringBuilder sb, Object v, int ind) {
            switch (v) {
                case null -> sb.append("null");
                case String str -> quote(sb, str);
                case Boolean bool -> sb.append(bool);
                case BigDecimal b -> sb.append(b.toPlainString());
                case Number num -> sb.append(num);
                case Map<?, ?> m -> {
                    if (m.isEmpty()) {
                        sb.append("{}");
                        return;
                    }
                    sb.append("{\n");
                    int n = 0;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        indent(sb, ind + 1);
                        quote(sb, String.valueOf(e.getKey()));
                        sb.append(": ");
                        write(sb, e.getValue(), ind + 1);
                        if (++n < m.size()) sb.append(',');
                        sb.append('\n');
                    }
                    indent(sb, ind);
                    sb.append('}');
                }
                case List<?> l -> {
                    if (l.isEmpty()) {
                        sb.append("[]");
                        return;
                    }
                    sb.append("[\n");
                    for (int k = 0; k < l.size(); k++) {
                        indent(sb, ind + 1);
                        write(sb, l.get(k), ind + 1);
                        if (k < l.size() - 1) sb.append(',');
                        sb.append('\n');
                    }
                    indent(sb, ind);
                    sb.append(']');
                }
                default -> quote(sb, String.valueOf(v));
            }
        }

        static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> sb.append(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
                }
            }
            sb.append('"');
        }

        @SuppressWarnings("unchecked")
        static <T> T deepCopy(T v) {
            return (T) parse(write(v));
        }
    }

    // ======================================================================================
    // Model helpers (the JSON tree is the source of truth, so unknown keys and order survive)
    // ======================================================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    static List<Object> listOrEmpty(Object o) {
        List<Object> l = asList(o);
        return l == null ? List.of() : l;
    }

    static List<Object> ensureList(Map<String, Object> m, String k) {
        List<Object> l = asList(m.get(k));
        if (l == null) {
            l = new ArrayList<>();
            m.put(k, l);
        }
        return l;
    }

    static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object o = m.get(k);
        return o instanceof String str ? str : null;
    }

    static String strOr(Map<String, Object> m, String k, String def) {
        String s = str(m, k);
        return s == null ? def : s;
    }

    static BigDecimal num(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object o = m.get(k);
        return o instanceof BigDecimal b ? b : null;
    }

    static Long longVal(Object o) {
        if (!(o instanceof BigDecimal b)) return null;
        try {
            return b.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    static Integer tryInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (Exception e) {
            return null;
        }
    }

    static BigDecimal parseNum(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (Exception e) {
            return null;
        }
    }

    static String lc(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    static String plain(BigDecimal b) {
        return b == null ? "" : b.stripTrailingZeros().toPlainString();
    }

    static String fmtD(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return new BigDecimal(d).round(new MathContext(7)).stripTrailingZeros().toPlainString();
    }

    static final Pattern HEX_ID = Pattern.compile("0[xX][0-9a-fA-F]+");
    static final Pattern PLACEHOLDER_ANY = Pattern.compile("\\{([^}]*)}");
    static final Pattern PLACEHOLDER_NUM = Pattern.compile("\\{(\\d+)}");
    static final Pattern DESC_OK = Pattern.compile("[A-Za-z_\\s]*");
    static final Pattern C_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern LEGACY_DIVIDE = Pattern.compile("divide(\\d+)");

    static Long parseId(String s) {
        if (s == null) return null;
        s = s.strip();
        if (!HEX_ID.matcher(s).matches() || s.length() > 18) return null;
        try {
            return Long.parseLong(s.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long parseLooseNumber(String q) {
        q = q.strip();
        if (HEX_ID.matcher(q).matches()) return parseId(q);
        if (q.matches("\\d{1,12}")) return Long.parseLong(q);
        return null;
    }

    static String hex(long v) {
        return "0x" + Long.toHexString(v).toUpperCase(Locale.ROOT);
    }

    static int sizeOf(Object p) {
        Long l = longVal(asMap(p) == null ? null : asMap(p).get("size"));
        return l == null || l < 0 ? 0 : (int) Math.min(l, 4096);
    }

    static int[] offsets(List<Object> pts) {
        int[] o = new int[pts.size() + 1];
        int acc = 0;
        for (int i = 0; i < pts.size(); i++) {
            o[i] = acc;
            acc += sizeOf(pts.get(i));
        }
        o[pts.size()] = acc;
        return o;
    }

    static boolean isParsed(Map<String, Object> p) {
        return p != null && !Boolean.FALSE.equals(p.get("parse"));
    }

    /** Divisor applied by the formatter: 1 when there is none, null when the formatter is unknown. */
    static BigDecimal divisor(Map<String, Object> p) {
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
    static BigDecimal rustArg(BigDecimal a) {
        if (a.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal t = a.setScale(0, java.math.RoundingMode.DOWN);
        BigDecimal max = BigDecimal.valueOf(0xFFFFFFFFL);
        return t.compareTo(max) > 0 ? max : t;
    }

    /** Word splitting like the `heck` crate: non-alphanumerics separate words, and so do lower→Upper and ACRONYMWord boundaries. */
    static List<String> heckWords(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (!cur.isEmpty()) {
                char prev = cur.charAt(cur.length() - 1);
                boolean split =
                    Character.isUpperCase(c) &&
                    (Character.isLowerCase(prev) ||
                        (Character.isUpperCase(prev) && i + 1 < n && Character.isLowerCase(s.charAt(i + 1))));
                if (split) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Accessor name firmware-rs generates for a point: AsSnakeCase(name). */
    static String rustSnake(String name) {
        List<String> w = heckWords(name);
        StringBuilder b = new StringBuilder();
        for (String x : w) {
            if (!b.isEmpty()) b.append('_');
            b.append(x.toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** Struct name firmware-rs generates for a message: AsPascalCase(desc.to_lowercase().replace(' ', "_")). */
    static String rustStructName(String desc) {
        StringBuilder b = new StringBuilder();
        for (String x : heckWords(desc.toLowerCase(Locale.ROOT).replace(' ', '_'))) {
            b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    static final Set<String> RUST_KEYWORDS = Set.of(
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
    static final Set<String> BITFIELD_RESERVED = Set.of("new", "from_bits", "into_bits");

    static String formatterKey(Map<String, Object> p) {
        Map<String, Object> f = asMap(p.get("formatter"));
        if (f != null) return strOr(f, "key", "");
        return strOr(p, "format", "");
    }

    static String formatterArg(Map<String, Object> p) {
        Map<String, Object> f = asMap(p.get("formatter"));
        return f == null ? "" : plain(num(f, "arg"));
    }

    /** Physical (post-formatter) range representable by a point, or null if unknown. */
    static double[] physRange(Map<String, Object> p) {
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

    static String pointLabel(Map<String, Object> p, int i) {
        String nm = str(p, "name");
        return "Point " + (i + 1) + (nm != null && !nm.isEmpty() ? " (" + nm + ")" : "");
    }

    static String simSummary(Object simO) {
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

    static Map<String, Object> newPoint(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("size", BigDecimal.valueOf(8));
        p.put("signed", Boolean.FALSE);
        p.put("name", name);
        p.put("c_type", "uint8_t");
        return p;
    }

    static Map<String, Object> newField(String topic, int pointIndex) {
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
    static void remapPointRefs(Map<String, Object> msg, IntUnaryOperator f) {
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

    static List<String> fieldsReferencing(Map<String, Object> msg, int pointIndex1) {
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

    static boolean putStr(Map<String, Object> m, String k, String v, boolean removeIfEmpty) {
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

    static boolean putBool(Map<String, Object> m, String k, boolean v, boolean def) {
        if (!m.containsKey(k)) {
            if (v == def) return false;
            m.put(k, v);
            return true;
        }
        if (Boolean.valueOf(v).equals(m.get(k))) return false;
        m.put(k, v);
        return true;
    }

    static boolean putNum(Map<String, Object> m, String k, BigDecimal v) {
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

    static int indexOfIdentity(List<Object> l, Object o) {
        for (int i = 0; i < l.size(); i++) if (l.get(i) == o) return i;
        return -1;
    }

    static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ======================================================================================
    // Files
    // ======================================================================================

    static final class SpecFile {

        final Path path;
        List<Object> messages = new ArrayList<>();
        String loadError;
        boolean notSpec;
        boolean dirty;
        final List<String> parseWarnings = new ArrayList<>();

        SpecFile(Path p) {
            path = p;
        }
    }

    static final class Loader {

        static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", "build", "dist", "__pycache__");

        static List<Path> collect(List<Path> inputs) throws IOException {
            List<Path> out = new ArrayList<>();
            for (Path in : inputs) {
                if (Files.isDirectory(in)) {
                    Files.walkFileTree(
                        in,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                                if (
                                    !d.equals(in) && (n.startsWith(".") || SKIP_DIRS.contains(n))
                                ) return FileVisitResult.SKIP_SUBTREE;
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                                if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) out.add(f);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path f, IOException e) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    );
                } else if (Files.isRegularFile(in)) out.add(in);
                else throw new IOException("Not found: " + in);
            }
            List<Path> norm = new ArrayList<>();
            for (Path p : out) {
                Path n = p.toAbsolutePath().normalize();
                if (!norm.contains(n)) norm.add(n);
            }
            Collections.sort(norm);
            return norm;
        }

        static SpecFile load(Path p) {
            SpecFile f = new SpecFile(p);
            try {
                String t = Files.readString(p);
                if (t.startsWith("\uFEFF")) t = t.substring(1);
                Json.Parser ps = new Json.Parser(t);
                Object root = ps.parseAll();
                f.parseWarnings.addAll(ps.warnings);
                List<Object> l = asList(root);
                if (l == null) {
                    f.notSpec = true;
                    f.loadError = "Root is not a JSON array of messages";
                } else f.messages = l;
            } catch (JsonException e) {
                f.loadError = "JSON syntax error: " + e.getMessage();
            } catch (IOException e) {
                f.loadError = "Cannot read file: " + e.getMessage();
            }
            return f;
        }

        static void save(SpecFile f) throws IOException {
            String text = Json.write(f.messages);
            Path tmp = f.path.resolveSibling(f.path.getFileName() + ".tmp");
            Files.writeString(tmp, text);
            try {
                Files.move(tmp, f.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f.path, StandardCopyOption.REPLACE_EXISTING);
            }
            f.dirty = false;
        }

        /** Everything opening files produces. Built off the Swing thread so the window stays responsive. */
        record Loaded(
            List<SpecFile> added,
            List<SpecFile> all,
            int skipped,
            String error,
            List<Issue> issues,
            Map<SpecFile, String> snapshots,
            Map<Map<String, Object>, Family> families
        ) {
            static Loaded failed(String error) {
                return new Loaded(List.of(), List.of(), 0, error, List.of(), Map.of(), Map.of());
            }
        }

        /** Collects, parses (in parallel), snapshots, validates and groups families for inputs plus already-open files. */
        static Loaded loadAll(List<Path> inputs, List<SpecFile> already) {
            List<Path> paths;
            try {
                paths = collect(inputs);
            } catch (IOException e) {
                return Loaded.failed(e.getMessage());
            }
            Set<Path> open = new HashSet<>();
            for (SpecFile f : already) open.add(f.path);
            List<SpecFile> loaded = paths
                .parallelStream()
                .filter(p -> !open.contains(p))
                .map(Loader::load)
                .toList();
            List<SpecFile> added = loaded
                .stream()
                .filter(f -> !f.notSpec)
                .toList();
            List<SpecFile> all = new ArrayList<>(already);
            all.addAll(added);
            Map<SpecFile, String> snaps = new IdentityHashMap<>();
            for (SpecFile f : added) if (f.loadError == null) snaps.put(f, Json.write(f.messages));
            Map<Map<String, Object>, Family> fams = new IdentityHashMap<>();
            findFamilies(all, null, fams);
            return new Loaded(added, all, loaded.size() - added.size(), null, Validator.validate(all), snaps, fams);
        }

        static Path commonParent(List<Path> paths) {
            Path common = null;
            for (Path p : paths) {
                Path d = p.toAbsolutePath().getParent();
                if (d == null) continue;
                if (common == null) {
                    common = d;
                    continue;
                }
                while (common != null && !d.startsWith(common)) common = common.getParent();
            }
            return common;
        }
    }

    // ======================================================================================
    // Validation
    // ======================================================================================

    enum Severity {
        ERROR("Error"),
        WARN("Warning"),
        INFO("Info");

        final String label;

        Severity(String l) {
            label = l;
        }

        Color color() {
            return this == ERROR ? T.red() : this == WARN ? T.yellow() : T.blue();
        }

        Color bg() {
            return T.tint(color());
        }

        static Severity worse(Severity a, Severity b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.ordinal() <= b.ordinal() ? a : b;
        }
    }

    /** One validation finding. point/field are 0-based indices, or -1 when the issue is about the whole message or file. */
    record Issue(
        Severity sev,
        SpecFile file,
        Map<String, Object> msg,
        String where,
        int point,
        int field,
        String text
    ) {}

    /** A message and the file it lives in. */
    record SpecRef(SpecFile file, Map<String, Object> msg) {}

    /** A field (by index) of a message. */
    record FieldRef(SpecFile file, Map<String, Object> msg, int field) {}

    static final class Validator {

        static final Set<String> MSG_KEYS = set(
            "id",
            "desc",
            "points",
            "fields",
            "is_ext",
            "key",
            "bidir_mode",
            "clients",
            "sim_freq"
        );
        static final Set<String> FIELD_KEYS = set("name", "unit", "doc", "desc", "values");
        static final Set<String> POINT_KEYS = set(
            "size",
            "name",
            "c_type",
            "parse",
            "signed",
            "endianness",
            "formatter",
            "default",
            "ieee754_f32",
            "sim"
        );
        static final Set<String> META_KEYS = set("desc", "fields");
        static final Set<String> META_FIELD_KEYS = set("name", "unit", "doc", "desc");

        static String unknownKey(String kind, String k) {
            String hint = k.equals("default_value")
                ? " The key is \"default\" (the README's default_value is out of date)."
                : k.equals("format")
                  ? " Use \"formatter\": {\"key\": \"divide\", \"arg\": N} instead."
                  : " Typo?";
            return (
                "Unknown " + kind + " key \"" + k + "\". definition-rs rejects unknown keys, so the build fails." + hint
            );
        }

        static final Set<String> SWEEP_KEYS = set("min", "max", "inc_min", "inc_max", "round");
        static final Set<Integer> SUPPORTED_CLIENTS = Set.of(1882);
        static final Map<String, Integer> CTYPE_BITS = new LinkedHashMap<>();

        static {
            CTYPE_BITS.put("bool", 1);
            CTYPE_BITS.put("uint8_t", 8);
            CTYPE_BITS.put("int8_t", 8);
            CTYPE_BITS.put("uint16_t", 16);
            CTYPE_BITS.put("int16_t", 16);
            CTYPE_BITS.put("uint32_t", 32);
            CTYPE_BITS.put("int32_t", 32);
            CTYPE_BITS.put("float", null);
            CTYPE_BITS.put("double", null);
        }

        static Set<String> set(String... s) {
            return Set.of(s);
        }

        final List<Issue> out = new ArrayList<>();
        SpecFile cf;
        Map<String, Object> cm;
        boolean curEncodable, curBroadcast;

        void add(Severity s, String where, int pt, int fd, String text) {
            out.add(new Issue(s, cf, cm, where, pt, fd, text));
        }

        void msgIssue(Severity s, String text) {
            add(s, "Message", -1, -1, text);
        }

        static List<Issue> validate(List<SpecFile> files) {
            Validator v = new Validator();
            v.run(files);
            return v.out;
        }

        void run(List<SpecFile> files) {
            Map<String, List<SpecRef>> ids = new LinkedHashMap<>();
            Map<String, List<SpecRef>> structs = new LinkedHashMap<>();
            Map<String, List<FieldRef>> topics = new LinkedHashMap<>();
            for (SpecFile f : files) {
                cf = f;
                cm = null;
                if (f.loadError != null) {
                    add(Severity.ERROR, "File", -1, -1, f.loadError);
                    continue;
                }
                for (String w : f.parseWarnings) add(Severity.WARN, "File", -1, -1, w);
                Map<String, Integer> nodes = new HashMap<>();
                for (int i = 0; i < f.messages.size(); i++) {
                    Map<String, Object> m = asMap(f.messages.get(i));
                    if (m == null) {
                        cm = null;
                        add(Severity.ERROR, "Entry " + (i + 1), -1, -1, "Top-level entry is not a message object");
                        continue;
                    }
                    cm = m;
                    if (!m.containsKey("id") && !m.containsKey("points")) {
                        checkMeta(m);
                        continue;
                    }
                    checkMessage(m);
                    Long id = parseId(str(m, "id"));
                    if (id != null) ids.computeIfAbsent(
                        (Boolean.TRUE.equals(m.get("is_ext")) ? "ext:" : "std:") + id,
                        k -> new ArrayList<>()
                    ).add(new SpecRef(f, m));
                    String d = str(m, "desc");
                    if (d != null && !rustStructName(d).isEmpty()) structs
                        .computeIfAbsent(rustStructName(d), k -> new ArrayList<>())
                        .add(new SpecRef(f, m));
                    List<Object> fds = listOrEmpty(m.get("fields"));
                    for (int j = 0; j < fds.size(); j++) {
                        String nm = str(asMap(fds.get(j)), "name");
                        if (nm == null || nm.isEmpty()) continue;
                        if (!nm.contains("{")) topics
                            .computeIfAbsent(nm, k -> new ArrayList<>())
                            .add(new FieldRef(f, m, j));
                        String node = nm.split("/")[0];
                        if (!node.equals("Calypso") && !node.isEmpty()) nodes.merge(node, 1, Integer::sum);
                    }
                }
                if (nodes.size() > 1) {
                    String major = Collections.max(nodes.entrySet(), Map.Entry.comparingByValue()).getKey();
                    for (Object o : f.messages) {
                        Map<String, Object> m = asMap(o);
                        if (m == null) continue;
                        cm = m;
                        List<Object> fds = listOrEmpty(m.get("fields"));
                        for (int j = 0; j < fds.size(); j++) {
                            String nm = str(asMap(fds.get(j)), "name");
                            if (nm == null || nm.isEmpty()) continue;
                            String node = nm.split("/")[0];
                            if (!node.equals("Calypso") && !node.equals(major)) add(
                                Severity.INFO,
                                "Field " + (j + 1),
                                -1,
                                j,
                                "Topic node \"" +
                                    node +
                                    "\" differs from this file's usual node \"" +
                                    major +
                                    "\" (the first level should be the node sending the message)"
                            );
                        }
                    }
                }
            }
            for (Map.Entry<String, List<SpecRef>> e : structs.entrySet()) {
                if (e.getValue().size() < 2) continue;
                for (SpecRef a : e.getValue()) {
                    cf = a.file();
                    cm = a.msg();
                    String others = others(
                        e.getValue(),
                        a,
                        b -> strOr(b.msg(), "id", "?") + " \"" + strOr(b.msg(), "desc", "?") + "\""
                    );
                    msgIssue(
                        Severity.ERROR,
                        "desc generates the Rust struct " +
                            e.getKey() +
                            ", which " +
                            others +
                            " also generates. firmware-rs won't compile with two structs of the same name"
                    );
                }
            }
            for (Map.Entry<String, List<SpecRef>> e : ids.entrySet()) {
                if (e.getValue().size() < 2) continue;
                for (SpecRef a : e.getValue()) {
                    cf = a.file();
                    cm = a.msg();
                    String others = others(
                        e.getValue(),
                        a,
                        b -> b.file().path.getFileName() + " \"" + strOr(b.msg(), "desc", "?") + "\""
                    );
                    msgIssue(
                        Severity.ERROR,
                        "Duplicate CAN ID " + hex(parseId(str(a.msg(), "id"))) + ", also used by " + others
                    );
                }
            }
            for (Map.Entry<String, List<FieldRef>> e : topics.entrySet()) {
                if (e.getValue().size() < 2) continue;
                for (FieldRef a : e.getValue()) {
                    cf = a.file();
                    cm = a.msg();
                    int j = a.field();
                    add(
                        Severity.WARN,
                        "Field " + (j + 1),
                        -1,
                        j,
                        "Topic \"" + e.getKey() + "\" is published by " + e.getValue().size() + " fields"
                    );
                }
            }
        }

        /** A meta message (definition-rs MetaMsg): just desc and value-less fields; indexed but never received. */
        void checkMeta(Map<String, Object> m) {
            String where = "Meta entry";
            add(
                Severity.INFO,
                where,
                -1,
                -1,
                "No id or points, so this is a meta message: its topics are indexed but it isn't a CAN frame"
            );
            for (String k : m.keySet())
                if (!META_KEYS.contains(k)) add(
                    Severity.ERROR,
                    where,
                    -1,
                    -1,
                    "Unknown key \"" +
                        k +
                        "\". This entry is neither a valid CAN message (needs id and points) nor a valid meta message (only desc and fields)"
                );
            if (!(m.get("desc") instanceof String)) add(
                Severity.ERROR,
                where,
                -1,
                -1,
                "Meta messages need a \"desc\" string"
            );
            List<Object> fds = asList(m.get("fields"));
            if (fds == null) {
                add(Severity.ERROR, where, -1, -1, "Meta messages need a \"fields\" list");
                return;
            }
            for (int j = 0; j < fds.size(); j++) {
                Map<String, Object> f = asMap(fds.get(j));
                String fw = "Field " + (j + 1);
                if (f == null) {
                    add(Severity.ERROR, fw, -1, j, "Field is not an object");
                    continue;
                }
                for (String k : f.keySet())
                    if (!META_FIELD_KEYS.contains(k)) add(
                        Severity.ERROR,
                        fw,
                        -1,
                        j,
                        "Unknown key \"" +
                            k +
                            "\" in a meta field" +
                            (k.equals("values") ? " (meta fields have no values)" : "")
                    );
                for (String k : new String[] { "name", "unit", "doc" })
                    if (!(f.get(k) instanceof String)) add(
                        Severity.ERROR,
                        fw,
                        -1,
                        j,
                        "Meta fields need a \"" + k + "\" string"
                    );
                if (f.containsKey("desc") && !(f.get("desc") instanceof String)) add(
                    Severity.ERROR,
                    fw,
                    -1,
                    j,
                    "desc must be a string"
                );
            }
        }

        static String others(List<SpecRef> refs, SpecRef self, Function<SpecRef, String> label) {
            return String.join(
                "; ",
                refs
                    .stream()
                    .filter(b -> b != self)
                    .map(label)
                    .toList()
            );
        }

        void checkMessage(Map<String, Object> m) {
            for (String k : m.keySet()) if (!MSG_KEYS.contains(k)) msgIssue(Severity.ERROR, unknownKey("message", k));

            Long id = null;
            Object idO = m.get("id");
            if (!(idO instanceof String rawId)) msgIssue(
                Severity.ERROR,
                "Missing \"id\" (a hex string such as \"0x1A0\")"
            );
            else {
                String s = rawId.strip();
                if (!HEX_ID.matcher(s).matches()) msgIssue(
                    Severity.ERROR,
                    "id \"" + s + "\" must be a hexadecimal string such as \"0x1A0\""
                );
                else {
                    id = parseId(s);
                    if (s.startsWith("0X")) msgIssue(
                        Severity.ERROR,
                        "id uses an uppercase \"0X\" prefix. firmware-rs cangen only strips a lowercase \"0x\", so parsing fails and the build panics"
                    );
                    if (!rawId.equals(s)) msgIssue(
                        Severity.ERROR,
                        "id has surrounding whitespace, which firmware-rs cangen can't parse"
                    );
                }
            }
            Object ext = m.get("is_ext");
            if (ext != null && !(ext instanceof Boolean)) msgIssue(Severity.ERROR, "is_ext must be true or false");
            boolean isExt = Boolean.TRUE.equals(ext);
            curEncodable = m.containsKey("key");
            if (id != null) {
                if (id > 0x1FFFFFFFL) msgIssue(
                    Severity.ERROR,
                    "ID " + hex(id) + " exceeds the 29-bit extended range (max 0x1FFFFFFF)"
                );
                else if (id > 0x7FF && !isExt) msgIssue(
                    Severity.ERROR,
                    "ID " +
                        hex(id) +
                        " needs more than 11 bits but is_ext is not true." +
                        " firmware-rs cangen builds a standard ID from it and panics (StandardId::new fails above 0x7FF). Set \"is_ext\": true"
                );
            }

            Object descO = m.get("desc");
            if (!(descO instanceof String d) || d.isBlank()) msgIssue(
                Severity.ERROR,
                "Missing or empty \"desc\". firmware-rs cangen names the message's struct after it, so the build fails"
            );
            else {
                String sn = rustStructName(d);
                if (sn.isEmpty()) msgIssue(
                    Severity.ERROR,
                    "desc has no letters or digits, so firmware-rs can't name the struct"
                );
                else if (Character.isDigit(sn.charAt(0))) msgIssue(
                    Severity.ERROR,
                    "desc starts with a digit, so the Rust struct name " +
                        sn +
                        " isn't a valid identifier and cangen panics"
                );
                else if (sn.equals("Msg")) msgIssue(
                    Severity.ERROR,
                    "desc generates the struct Msg, which clashes with cangen's own Msg enum"
                );
                if (!DESC_OK.matcher(d).matches()) {
                    Set<Character> bad = new LinkedHashSet<>();
                    for (char c : d.toCharArray())
                        if (!(Character.isLetter(c) && c < 128) && c != '_' && !Character.isWhitespace(c)) bad.add(c);
                    StringBuilder b = new StringBuilder();
                    for (char c : bad) b.append(" '").append(c).append('\'');
                    msgIssue(Severity.WARN, "desc should contain only letters, whitespace and '_' (found" + b + ")");
                }
                if (lc(d).contains("overflow")) msgIssue(
                    Severity.INFO,
                    "desc contains \"overflow\", so this message is used as the node's overflow-detection broadcast"
                );
            }

            String bidir = null;
            if (m.containsKey("bidir_mode")) {
                bidir = str(m, "bidir_mode");
                if ("configuration".equals(bidir)) msgIssue(
                    Severity.ERROR,
                    "bidir_mode \"configuration\" parses, but code generation panics: it isn't available yet. Use \"broadcast\" or \"oneshot\""
                );
                else if (!"broadcast".equals(bidir) && !"oneshot".equals(bidir)) msgIssue(
                    Severity.ERROR,
                    "bidir_mode must be \"broadcast\" or \"oneshot\" (lowercase)"
                );
                if (!curEncodable) msgIssue(
                    Severity.WARN,
                    "bidir_mode only applies to encodable messages (messages with a \"key\")"
                );
            }
            String key = null;
            if (curEncodable) {
                key = str(m, "key");
                if (key == null || key.isBlank()) {
                    msgIssue(Severity.ERROR, "\"key\" must be a non-empty string");
                    key = null;
                } else if (key.contains("/") || key.contains(" ")) msgIssue(
                    Severity.WARN,
                    "key should not contain '/' or spaces (it becomes an MQTT topic level)"
                );
            }
            curBroadcast = curEncodable && (bidir == null || "broadcast".equals(bidir));

            if (m.containsKey("clients")) {
                List<Object> cl = asList(m.get("clients"));
                if (cl == null) msgIssue(Severity.ERROR, "clients must be a list of port numbers");
                else for (Object c : cl) {
                    Long port = longVal(c);
                    if (port == null || port < 0 || port > 65535) msgIssue(
                        Severity.ERROR,
                        "clients entry " + c + " is not a valid u16 port"
                    );
                    else if (!SUPPORTED_CLIENTS.contains((int) (long) port)) msgIssue(
                        Severity.WARN,
                        "clients port " + port + " is not a supported additional MQTT port (supported: 1882)"
                    );
                }
            }
            if (m.containsKey("sim_freq")) {
                BigDecimal sf = num(m, "sim_freq");
                if (sf == null) msgIssue(Severity.ERROR, "sim_freq must be a number");
                else if (sf.signum() <= 0) msgIssue(Severity.WARN, "sim_freq should be a positive number");
            }

            // ---- points
            List<Object> pts = asList(m.get("points"));
            if (pts == null) {
                msgIssue(
                    Severity.ERROR,
                    m.containsKey("points") ? "\"points\" must be a list" : "Missing \"points\" list"
                );
                pts = List.of();
            } else if (pts.isEmpty()) msgIssue(Severity.ERROR, "Message has no points");
            int n = pts.size();
            int[] off = offsets(pts);
            int total = off[n];
            boolean anyLittle = false;
            boolean[] parsed = new boolean[n];
            for (int i = 0; i < n; i++) {
                Map<String, Object> p = asMap(pts.get(i));
                if (p == null) {
                    add(Severity.ERROR, "Point " + (i + 1), i, -1, "Point is not an object");
                    continue;
                }
                parsed[i] = isParsed(p);
                if ("little".equals(p.get("endianness")) && parsed[i]) anyLittle = true;
                checkPoint(i, p, off[i]);
            }
            Map<String, List<Integer>> accessors = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> p = asMap(pts.get(i));
                String pn = str(p, "name");
                if (p == null || !parsed[i] || pn == null || pn.isEmpty()) continue;
                String sn = rustSnake(pn);
                if (!sn.isEmpty()) accessors.computeIfAbsent(sn, k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<Integer>> e : accessors.entrySet()) {
                if (e.getValue().size() < 2) continue;
                StringBuilder idx = new StringBuilder();
                for (int k : e.getValue()) {
                    if (!idx.isEmpty()) idx.append(", ");
                    idx.append('#').append(k + 1);
                }
                for (int k : e.getValue())
                    add(
                        Severity.ERROR,
                        pointLabel(asMap(pts.get(k)), k),
                        k,
                        -1,
                        "Points " +
                            idx +
                            " all become the Rust field `" +
                            e.getKey() +
                            "` (names are snake_cased), so the generated struct won't compile"
                    );
            }
            if (n > 0) {
                if (total % 8 != 0) {
                    int pad = 8 - (total % 8);
                    add(
                        Severity.ERROR,
                        "Layout",
                        -1,
                        -1,
                        "Total size is " +
                            total +
                            " bits, which is not byte aligned. Add a " +
                            pad +
                            "-bit padding point with \"parse\": false"
                    );
                }
                if (total > 64) add(
                    Severity.ERROR,
                    "Layout",
                    -1,
                    -1,
                    "Total size is " + total + " bits; a CAN frame holds at most 64 bits (8 bytes)"
                );
            }
            if (anyLittle) for (int i = 0; i < n; i++) {
                int sz = sizeOf(pts.get(i));
                if (off[i] % 8 != 0 || sz % 8 != 0) add(
                    Severity.ERROR,
                    pointLabel(asMap(pts.get(i)), i),
                    i,
                    -1,
                    "This message has little-endian points, so every point must be byte aligned for C gen (bits " +
                        off[i] +
                        "–" +
                        (off[i] + sz - 1) +
                        ")"
                );
            }

            // ---- fields
            List<Object> fds = asList(m.get("fields"));
            boolean[] referenced = new boolean[n];
            if (fds == null) {
                msgIssue(
                    Severity.ERROR,
                    m.containsKey("fields")
                        ? "\"fields\" must be a list"
                        : "Missing \"fields\" list; definition-rs requires it (use [] for none)"
                );
                fds = List.of();
            }
            for (int j = 0; j < fds.size(); j++) {
                Map<String, Object> f = asMap(fds.get(j));
                if (f == null) {
                    add(Severity.ERROR, "Field " + (j + 1), -1, j, "Field is not an object");
                    continue;
                }
                checkField(j, f, n, parsed, referenced, key);
            }
            int parsedCount = 0;
            for (boolean b : parsed) if (b) parsedCount++;
            if (fds.isEmpty() && parsedCount > 0) msgIssue(
                Severity.WARN,
                "No fields, so decoded points are never published to MQTT"
            );
            for (int i = 0; i < n; i++) {
                if (parsed[i] && !referenced[i] && asMap(pts.get(i)) != null) add(
                    Severity.WARN,
                    pointLabel(asMap(pts.get(i)), i),
                    i,
                    -1,
                    "Parsed point is not used by any field. Reference it in a field's values, or set \"parse\": false if it is padding"
                );
            }
        }

        void checkPoint(int i, Map<String, Object> p, int off) {
            String where = pointLabel(p, i);
            for (String k : p.keySet())
                if (!POINT_KEYS.contains(k)) add(Severity.ERROR, where, i, -1, unknownKey("point", k));
            Long sizeL = longVal(p.get("size"));
            if (sizeL == null || sizeL < 1) {
                add(Severity.ERROR, where, i, -1, "\"size\" must be a positive whole number of bits");
                return;
            }
            int size = (int) Math.min(sizeL, 4096);
            Object parseO = p.get("parse");
            if (parseO != null && !(parseO instanceof Boolean)) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "parse must be true or false"
            );
            if (!isParsed(p)) {
                List<String> extra = new ArrayList<>();
                for (String k : p.keySet()) if (!k.equals("size") && !k.equals("parse")) extra.add(k);
                if (!extra.isEmpty()) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "Padding points (\"parse\": false) should only contain \"size\". Remove: " +
                        String.join(", ", extra)
                );
                return;
            }
            if (size > 32) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "Parsed points can be at most 32 bits (this one is " + size + ")"
            );
            for (String b : new String[] { "signed", "ieee754_f32" })
                if (p.containsKey(b) && !(p.get(b) instanceof Boolean)) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    b + " must be true or false"
                );
            boolean signed = Boolean.TRUE.equals(p.get("signed"));
            boolean ieee = Boolean.TRUE.equals(p.get("ieee754_f32"));
            String end = null;
            if (p.containsKey("endianness")) {
                if (p.get("endianness") instanceof String e && (e.equals("big") || e.equals("little"))) end = e;
                else add(Severity.ERROR, where, i, -1, "endianness must be \"big\" or \"little\"");
                if (size <= 8) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Points of 8 bits or fewer can't have endianness (byte order means nothing inside one byte); remove it"
                );
            }
            boolean little = "little".equals(end);
            if (little) {
                if (off % 8 != 0 || size % 8 != 0) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Little-endian points must be byte aligned (starts at bit " + off + ", " + size + " bits)"
                );
                String fk = asMap(p.get("formatter")) == null ? "" : strOr(asMap(p.get("formatter")), "key", "");
                if (ieee) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "firmware-rs cangen panics on little-endian IEEE754 floats (not supported yet)"
                );
                else if (fk.equals("divide") || fk.equals("multiply")) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "firmware-rs cangen panics on little-endian points with a " + fk + " formatter (not supported yet)"
                );
                else if ("float".equals(p.get("c_type"))) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "firmware-rs cangen panics on little-endian points with c_type float (they're scaled, which isn't supported for little endian yet)"
                );
                add(
                    Severity.INFO,
                    where,
                    i,
                    -1,
                    "Prefer big endian where possible; the MC, charger box, etc. expect it"
                );
            }
            if (signed && !ieee) {
                if (size != 8 && size != 16 && size != 32) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Signed integer points must be 8, 16 or 32 bits (this one is " + size + ")"
                );
                if (off % 8 != 0) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Signed integer points must be byte aligned (this one starts at bit " + off + ")"
                );
            }
            if (ieee && size != 32) add(Severity.ERROR, where, i, -1, "IEEE754 float points must be 32 bits");
            if (size >= 8 && off % 8 != 0 && !(signed && !ieee)) add(
                Severity.WARN,
                where,
                i,
                -1,
                "Multi-bit value starts mid-byte (bit " +
                    off +
                    "). Keep bit-wise and byte-wise values in separate bytes by adding filler bits before it"
            );

            Object nO = p.get("name");
            if (nO != null && !(nO instanceof String)) add(Severity.ERROR, where, i, -1, "name must be a string");
            else if (!(nO instanceof String nm) || nm.isEmpty()) add(
                Severity.WARN,
                where,
                i,
                -1,
                "Point has no name. firmware-rs cangen treats unnamed points as reserved padding, so this value gets no accessor there"
            );
            else {
                String sn = rustSnake(nm);
                if (sn.isEmpty() || Character.isDigit(sn.charAt(0))) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Point name becomes \"" + sn + "\" in Rust, which isn't a valid identifier, so cangen panics"
                );
                else if (RUST_KEYWORDS.contains(sn)) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Point name becomes the Rust keyword `" + sn + "`, so the generated struct won't compile"
                );
                else if (BITFIELD_RESERVED.contains(sn)) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Point name becomes `" + sn + "`, which clashes with a method bitfield_struct generates"
                );
                else if (!C_IDENT.matcher(nm).matches()) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "Point name should be one word (a valid C identifier); Rust will call it `" + sn + "`"
                );
            }

            if (p.containsKey("c_type")) {
                String ct = str(p, "c_type");
                if (ct == null) add(Severity.ERROR, where, i, -1, "c_type must be a string");
                else if (!ct.isEmpty()) {
                    if (!CTYPE_BITS.containsKey(ct)) add(Severity.WARN, where, i, -1, "Unknown c_type \"" + ct + "\"");
                    else {
                        Integer w = CTYPE_BITS.get(ct);
                        if (w != null && w > 1 && size > w) add(
                            Severity.ERROR,
                            where,
                            i,
                            -1,
                            "c_type " + ct + " holds " + w + " bits but the point is " + size + " bits"
                        );
                        if (ct.startsWith("uint") && signed) add(
                            Severity.WARN,
                            where,
                            i,
                            -1,
                            "Point is signed but c_type " + ct + " is unsigned"
                        );
                        if (ct.startsWith("int") && !signed) add(
                            Severity.WARN,
                            where,
                            i,
                            -1,
                            "c_type " + ct + " is signed but \"signed\" is not true"
                        );
                        if (ct.equals("bool") && size > 1 && !p.containsKey("formatter")) add(
                            Severity.INFO,
                            where,
                            i,
                            -1,
                            "firmware-rs only generates bool for 1-bit points; this " + size + "-bit point becomes a u8"
                        );
                        BigDecimal div = divisor(p);
                        if (w != null && div != null && div.compareTo(BigDecimal.ONE) != 0) add(
                            Severity.WARN,
                            where,
                            i,
                            -1,
                            "A formatter scales this value, but c_type " +
                                ct +
                                " cannot hold fractions; consider float (firmware-rs exposes it as f32)"
                        );
                    }
                }
            }

            if (p.containsKey("formatter")) {
                Map<String, Object> fm = asMap(p.get("formatter"));
                if (fm == null) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "formatter must be an object like {\"key\": \"divide\", \"arg\": 100}"
                );
                else {
                    Object key = fm.get("key");
                    BigDecimal a = num(fm, "arg");
                    if (!(key instanceof String)) add(
                        Severity.ERROR,
                        where,
                        i,
                        -1,
                        "formatter.key must be a string, or cangen fails to read the file"
                    );
                    if (!fm.containsKey("arg")) add(
                        Severity.ERROR,
                        where,
                        i,
                        -1,
                        "formatter.arg is missing; cangen requires it for every formatter"
                    );
                    else if (a == null) add(Severity.ERROR, where, i, -1, "formatter.arg must be a number");
                    if (key instanceof String k) {
                        if (!k.equals("divide") && !k.equals("multiply")) add(
                            Severity.WARN,
                            where,
                            i,
                            -1,
                            "firmware-rs cangen only applies \"divide\" and \"multiply\". \"" +
                                k +
                                "\" is ignored, so the value is used unscaled"
                        );
                        else if (a != null) {
                            BigDecimal u = rustArg(a);
                            if (u.signum() == 0) add(
                                Severity.ERROR,
                                where,
                                i,
                                -1,
                                "The " + k + " arg " + plain(a) + " becomes 0 when cangen casts it to u32"
                            );
                            else if (u.compareTo(a) != 0) add(
                                Severity.WARN,
                                where,
                                i,
                                -1,
                                "cangen casts the " +
                                    k +
                                    " arg to an integer (arg as u32), so " +
                                    plain(a) +
                                    " becomes " +
                                    plain(u)
                            );
                        }
                    }
                }
            }

            double[] range = physRange(p);
            if (p.containsKey("default")) {
                BigDecimal dv = num(p, "default");
                if (dv == null) add(Severity.ERROR, where, i, -1, "default must be a number");
                else if (range != null && outOf(dv.doubleValue(), range)) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "default " +
                        plain(dv) +
                        " cannot be represented (range " +
                        fmtD(range[0]) +
                        " … " +
                        fmtD(range[1]) +
                        ")"
                );
                if (!curEncodable) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "default is only used by encodable messages (with a \"key\"), so it is ignored here"
                );
                else if (!curBroadcast) add(Severity.INFO, where, i, -1, "default is ignored for oneshot messages");
            } else if (curBroadcast) {
                add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "Broadcast encodable messages need a \"default\" (sent before any command arrives)"
                );
            }

            if (p.containsKey("sim")) checkSim(where, i, p.get("sim"), range);
        }

        static boolean outOf(double v, double[] r) {
            double eps = 1e-9 * Math.max(1, Math.abs(v));
            return v < r[0] - eps || v > r[1] + eps;
        }

        void checkSim(String where, int i, Object simO, double[] range) {
            Map<String, Object> s = asMap(simO);
            if (s == null) {
                add(Severity.ERROR, where, i, -1, "sim must be an object");
                return;
            }
            boolean hasEnum = s.containsKey("options");
            boolean hasSweep = SWEEP_KEYS.stream().anyMatch(s::containsKey);
            String rng = range == null ? "" : " (representable range " + fmtD(range[0]) + " … " + fmtD(range[1]) + ")";
            if (hasEnum && hasSweep) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "sim mixes sweep keys with enum options; use one or the other"
            );
            if (hasEnum) {
                for (String k : s.keySet())
                    if (!k.equals("options") && !SWEEP_KEYS.contains(k)) add(
                        Severity.ERROR,
                        where,
                        i,
                        -1,
                        unknownKey("sim", k)
                    );
                List<Object> opts = asList(s.get("options"));
                if (opts == null) {
                    add(Severity.ERROR, where, i, -1, "sim options must be a list of [value, frequency] pairs");
                    return;
                }
                if (opts.isEmpty()) {
                    add(Severity.ERROR, where, i, -1, "sim options list is empty");
                    return;
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (Object o : opts) {
                    List<Object> pr = asList(o);
                    if (
                        pr == null ||
                        pr.size() != 2 ||
                        !(pr.getFirst() instanceof BigDecimal v) ||
                        !(pr.getLast() instanceof BigDecimal fq)
                    ) {
                        add(
                            Severity.ERROR,
                            where,
                            i,
                            -1,
                            "Each sim option must be [value, frequency], found " + Json.writeCompact(o)
                        );
                        continue;
                    }
                    if (fq.signum() < 0) add(
                        Severity.ERROR,
                        where,
                        i,
                        -1,
                        "Sim option frequency " + plain(fq) + " is negative"
                    );
                    sum = sum.add(fq);
                    if (range != null && outOf(v.doubleValue(), range)) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "Sim option value " + plain(v) + " cannot be represented" + rng
                    );
                }
                if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) > 0) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Sim option frequencies add up to " + plain(sum) + "; they must add up to 1 or they won't be obeyed"
                );
            } else if (hasSweep) {
                for (String k : s.keySet())
                    if (!SWEEP_KEYS.contains(k)) add(Severity.ERROR, where, i, -1, unknownKey("sim", k));
                BigDecimal[] v = new BigDecimal[4];
                String[] names = { "min", "max", "inc_min", "inc_max" };
                for (int k = 0; k < 4; k++) {
                    v[k] = num(s, names[k]);
                    if (v[k] == null) add(
                        Severity.ERROR,
                        where,
                        i,
                        -1,
                        "Sweep sim is missing a numeric \"" + names[k] + "\""
                    );
                }
                if (s.containsKey("round") && !(s.get("round") instanceof Boolean)) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "sim round must be true or false"
                );
                if (v[0] != null && v[1] != null && v[0].compareTo(v[1]) > 0) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Sim min is greater than max"
                );
                if (v[2] != null && v[3] != null && v[2].compareTo(v[3]) > 0) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Sim inc_min is greater than inc_max"
                );
                if ((v[2] != null && v[2].signum() < 0) || (v[3] != null && v[3].signum() < 0)) add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "Sim increments must not be negative"
                );
                if (v[3] != null && v[3].signum() == 0) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "Sim inc_max is 0, so the value never changes"
                );
                if (range != null) {
                    if (v[0] != null && outOf(v[0].doubleValue(), range)) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "Sim min " + plain(v[0]) + " cannot be represented" + rng
                    );
                    if (v[1] != null && outOf(v[1].doubleValue(), range)) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "Sim max " + plain(v[1]) + " cannot be represented" + rng
                    );
                }
            } else {
                add(
                    Severity.ERROR,
                    where,
                    i,
                    -1,
                    "sim must be a sweep {min, max, inc_min, inc_max} or an enum {options}"
                );
            }
        }

        void checkField(int j, Map<String, Object> f, int n, boolean[] parsed, boolean[] ref, String key) {
            String where = "Field " + (j + 1);
            for (String k : f.keySet())
                if (!FIELD_KEYS.contains(k)) add(Severity.ERROR, where, -1, j, unknownKey("field", k));
            Object nO = f.get("name");
            boolean hasPlaceholder = false;
            if (!(nO instanceof String name) || name.isBlank()) add(
                Severity.ERROR,
                where,
                -1,
                j,
                "Missing topic \"name\""
            );
            else {
                if (name.endsWith("/")) add(Severity.WARN, where, -1, j, "Topic must not end with '/'");
                if (name.startsWith("/")) add(Severity.WARN, where, -1, j, "Topic should not start with '/'");
                if (name.contains("//")) add(Severity.WARN, where, -1, j, "Topic contains an empty level ('//')");
                if (name.matches(".*\\s.*")) add(Severity.WARN, where, -1, j, "Topic contains whitespace");
                if (name.contains("+") || name.contains("#")) add(
                    Severity.ERROR,
                    where,
                    -1,
                    j,
                    "Topic contains the MQTT wildcard characters '+' or '#'"
                );
                String trimmed = name.replaceAll("^/+|/+$", "");
                int levels = trimmed.isEmpty() ? 0 : trimmed.split("/").length;
                if (levels < 3) add(
                    Severity.INFO,
                    where,
                    -1,
                    j,
                    "Topic has " + levels + " level(s); 3 or more (Node/Group/Name) is preferred"
                );
                Matcher pm = PLACEHOLDER_ANY.matcher(name);
                while (pm.find()) {
                    hasPlaceholder = true;
                    Integer k = tryInt(pm.group(1));
                    if (k == null) add(
                        Severity.ERROR,
                        where,
                        -1,
                        j,
                        "Topic placeholder {" + pm.group(1) + "} must be a point index such as {1}"
                    );
                    else if (k < 1 || k > n) add(
                        Severity.ERROR,
                        where,
                        -1,
                        j,
                        "Topic placeholder {" +
                            k +
                            "} is out of bounds; points are indexed from 1 and this message has " +
                            n
                    );
                    else {
                        if (!parsed[k - 1]) add(
                            Severity.ERROR,
                            where,
                            -1,
                            j,
                            "Topic placeholder {" + k + "} refers to a padding (unparsed) point"
                        );
                        ref[k - 1] = true;
                    }
                }
                String stripped = PLACEHOLDER_ANY.matcher(name).replaceAll("");
                if (stripped.contains("{") || stripped.contains("}")) add(
                    Severity.ERROR,
                    where,
                    -1,
                    j,
                    "Topic has unbalanced braces"
                );
                if (key != null && !name.startsWith("Calypso/Bidir/State/" + key + "/")) add(
                    Severity.WARN,
                    where,
                    -1,
                    j,
                    "Encodable message topics should be \"Calypso/Bidir/State/" + key + "/<field_name>\""
                );
            }
            if (!f.containsKey("unit")) add(
                Severity.ERROR,
                where,
                -1,
                j,
                "Missing \"unit\"; firmware-rs cangen requires it and fails to read the file without it (use \"\" when unitless)"
            );
            else if (!(f.get("unit") instanceof String)) add(Severity.ERROR, where, -1, j, "unit must be a string");

            List<Object> vals = asList(f.get("values"));
            if (vals == null) add(Severity.ERROR, where, -1, j, "Missing \"values\" list (1-based point indices)");
            else {
                if (vals.isEmpty() && !hasPlaceholder) add(
                    Severity.WARN,
                    where,
                    -1,
                    j,
                    "values is empty, so this field publishes nothing"
                );
                Set<Long> seen = new HashSet<>();
                for (Object v : vals) {
                    Long k = longVal(v);
                    if (k == null) add(
                        Severity.ERROR,
                        where,
                        -1,
                        j,
                        "values entry " + Json.writeCompact(v) + " is not a whole number"
                    );
                    else if (k < 1 || k > n) add(
                        Severity.ERROR,
                        where,
                        -1,
                        j,
                        "values entry " +
                            k +
                            " is out of bounds; points are indexed from 1 and this message has " +
                            n +
                            " (build will fail)"
                    );
                    else {
                        if (!parsed[(int) (k - 1)]) add(
                            Severity.ERROR,
                            where,
                            -1,
                            j,
                            "values entry " + k + " refers to a padding (unparsed) point"
                        );
                        ref[(int) (k - 1)] = true;
                        if (!seen.add(k)) add(
                            Severity.WARN,
                            where,
                            -1,
                            j,
                            "values lists point " + k + " more than once"
                        );
                    }
                }
            }
            Object doc = f.get("doc");
            if (!f.containsKey("doc")) add(
                Severity.ERROR,
                where,
                -1,
                j,
                "Missing \"doc\"; firmware-rs cangen requires it and fails to read the file without it (use \"\" if there's nothing to say)"
            );
            else if (doc != null && !(doc instanceof String)) add(Severity.ERROR, where, -1, j, "doc must be a string");
            else if (!(doc instanceof String docText) || docText.isBlank()) add(
                Severity.INFO,
                where,
                -1,
                j,
                "No \"doc\" string describing the topic"
            );
            if (f.containsKey("desc") && !(f.get("desc") instanceof String)) add(
                Severity.ERROR,
                where,
                -1,
                j,
                "desc must be a string"
            );
        }
    }

    // ======================================================================================
    // Decoding (for the "Decode" tab)
    // ======================================================================================

    static long extractBits(byte[] data, int off, int size) {
        long v = 0;
        for (int b = 0; b < size; b++) {
            int bit = off + b;
            int byteIdx = bit / 8;
            int val = byteIdx < data.length ? (data[byteIdx] >> (7 - (bit % 8))) & 1 : 0;
            v = (v << 1) | val;
        }
        return v;
    }

    static byte[] parseHexBytes(String s) {
        String t = s.strip().replaceAll("0[xX]", " ").replaceAll("[,;:_\\-]", " ").strip();
        if (t.isEmpty()) return new byte[0];
        List<String> toks = new ArrayList<>();
        for (String tok : t.split("\\s+")) {
            if (tok.length() > 2) {
                // contiguous run like 01A2FF
                if (tok.length() % 2 != 0) return null;
                for (int k = 0; k < tok.length(); k += 2) toks.add(tok.substring(k, k + 2));
            } else toks.add(tok);
        }
        if (toks.size() > 64) return null;
        try {
            return HEX.parseHex(String.join("", toks));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ======================================================================================
    // Command-line validation
    // ======================================================================================

    static final class Cli {

        /** Decodes a candump log (or stdin with "-") against the spec: {@code --candump <log|-> [spec folders...]} */
        static int candump(List<String> args) {
            if (args.isEmpty()) {
                System.err.println("Usage: --candump <log-file|-> [spec folders or files...]");
                return 2;
            }
            String log = args.getFirst();
            List<Path> inputs = new ArrayList<>();
            for (String x : args.size() > 1 ? args.subList(1, args.size()) : List.of(".")) inputs.add(Paths.get(x));
            Loader.Loaded loaded = Loader.loadAll(inputs, List.of());
            if (loaded.error() != null) {
                System.err.println(loaded.error());
                return 2;
            }
            Map<String, SpecRef> idx = specIndex(loaded.all());
            int n = 0,
                unknown = 0,
                bad = 0,
                skipped = 0;
            boolean stdin = log.equals("-");
            try (
                java.io.BufferedReader in = stdin
                    ? new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))
                    : Files.newBufferedReader(Paths.get(log), StandardCharsets.UTF_8)
            ) {
                String line;
                while ((line = in.readLine()) != null) {
                    CanFrame fr = parseCandumpLine(line);
                    if (fr == null) {
                        if (!line.isBlank()) skipped++;
                        continue;
                    }
                    n++;
                    FrameCheck c = checkFrame(fr, idx);
                    StringBuilder b = new StringBuilder();
                    if (!Double.isNaN(fr.ts())) b.append(String.format(Locale.ROOT, "(%.6f) ", fr.ts()));
                    if (!fr.bus().isEmpty()) b.append(fr.bus()).append(' ');
                    b.append(fr.idText()).append("  ");
                    if (c.hit() != null && !fr.rtr()) {
                        Map<String, Object> m = c.hit().msg();
                        b.append(strOr(m, "desc", ""))
                            .append("  ")
                            .append(String.join("  ", decodeFrame(m, fr.data()).topics()));
                    } else b.append(fr.dataText());
                    if (c.level() > 0) b.append("  [").append(c.text()).append(']');
                    if (c.text().equals("Unknown ID")) unknown++;
                    else if (c.level() == 2) bad++;
                    System.out.println(b);
                    if (stdin) System.out.flush();
                }
            } catch (IOException e) {
                System.err.println(e.getMessage());
                return 2;
            }
            System.err.printf(
                "%n%d frames: %d with unknown IDs, %d with other problems, %d lines weren't CAN frames.%n",
                n,
                unknown,
                bad,
                skipped
            );
            return 0;
        }

        static int run(List<String> args, boolean strict, boolean info) {
            if (args.isEmpty()) args = List.of(".");
            List<Path> inputs = new ArrayList<>();
            for (String a : args) inputs.add(Paths.get(a));
            Loader.Loaded r = Loader.loadAll(inputs, List.of());
            if (r.error() != null) {
                System.err.println(r.error());
                return 2;
            }
            List<SpecFile> files = r.all();
            int skipped = r.skipped();
            Path base =
                inputs.size() == 1 && Files.isDirectory(inputs.getFirst())
                    ? inputs.getFirst().toAbsolutePath().normalize()
                    : Loader.commonParent(
                          files
                              .stream()
                              .map(f -> f.path)
                              .toList()
                      );
            List<Issue> issues = r.issues();
            int[] count = new int[3];
            for (Issue is : issues) {
                count[is.sev().ordinal()]++;
                if (is.sev() == Severity.INFO && !info) continue;
                String fn =
                    base != null && is.file().path.startsWith(base)
                        ? base.relativize(is.file().path).toString()
                        : is.file().path.toString();
                String id = is.msg() == null ? "-" : strOr(is.msg(), "id", "?");
                String text = is.text().replace("…", "...").replace("–", "-");
                System.out.printf(
                    "%-7s %s  %s  %s: %s%n",
                    is.sev().label.toUpperCase(Locale.ROOT),
                    fn,
                    id,
                    is.where(),
                    text
                );
            }
            int msgs = 0;
            for (SpecFile f : files) msgs += f.messages.size();
            System.out.printf(
                "%nChecked %d message(s) in %d file(s)%s: %d error(s), %d warning(s), %d info.%n",
                msgs,
                files.size(),
                skipped > 0 ? " (skipped " + skipped + " non-spec JSON file(s))" : "",
                count[0],
                count[1],
                count[2]
            );
            return count[0] > 0 || (strict && count[1] > 0) ? 1 : 0;
        }
    }

    // ======================================================================================
    // Theming: a flat look built on Metal, with switchable palettes (One Dark by default)
    // ======================================================================================

    static Theme T;

    static Color blend(Color a, Color b, double t) {
        return new Color(
            (int) Math.round(a.getRed() * t + b.getRed() * (1 - t)),
            (int) Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
            (int) Math.round(a.getBlue() * t + b.getBlue() * (1 - t))
        );
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    static String hx(Color c) {
        return "#%06X".formatted(c.getRGB() & 0xFFFFFF);
    }

    static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    static Color onColor(Color bg) {
        return luminance(bg) > 0.55 ? new Color(0x1B1D22) : Color.WHITE;
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
        else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static String ellipsize(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n) + "…";
            if (fm.stringWidth(t) <= w) return t;
        }
        return "";
    }

    /** A color palette. selection is derived from accent and panel by the factories. */
    record Theme(
        String name,
        boolean dark,
        boolean nativeLaf,
        Color window,
        Color panel,
        Color field,
        Color alt,
        Color border,
        Color hairline,
        Color fg,
        Color bright,
        Color muted,
        Color accent,
        Color selection,
        Color red,
        Color orange,
        Color yellow,
        Color green,
        Color cyan,
        Color blue,
        Color purple
    ) {
        static final String NATIVE = "Native (OS)";
        static Font UI, MONO;

        static Theme of(String name, boolean dark, int... c) {
            return of(name, dark, false, toColors(c));
        }

        static Theme of(String name, boolean dark, boolean nativeLaf, Color[] c) {
            return new Theme(
                name,
                dark,
                nativeLaf,
                c[0],
                c[1],
                c[2],
                c[3],
                c[4],
                c[5],
                c[6],
                c[7],
                c[8],
                c[9],
                blend(c[9], c[1], dark ? 0.30 : 0.20),
                c[10],
                c[11],
                c[12],
                c[13],
                c[14],
                c[15],
                c[16]
            );
        }

        static Color[] toColors(int[] c) {
            Color[] out = new Color[c.length];
            for (int i = 0; i < c.length; i++) out[i] = new Color(c[i]);
            return out;
        }

        //                                  window    panel     field     alt       border    hairline  fg        bright    muted     accent    red       orange    yellow    green     cyan      blue      purple
        static final List<Theme> ALL = List.of(
            Theme.of(
                "One Dark",
                true,
                0x21252B,
                0x282C34,
                0x1E2227,
                0x2C313A,
                0x3E4451,
                0x181A1F,
                0xABB2BF,
                0xE6E9EF,
                0x7F848E,
                0x61AFEF,
                0xE06C75,
                0xD19A66,
                0xE5C07B,
                0x98C379,
                0x56B6C2,
                0x61AFEF,
                0xC678DD
            ),
            Theme.of(
                "Dracula",
                true,
                0x21222C,
                0x282A36,
                0x1E1F29,
                0x2D2F3D,
                0x44475A,
                0x191A21,
                0xF8F8F2,
                0xFFFFFF,
                0x7E87AE,
                0xBD93F9,
                0xFF5555,
                0xFFB86C,
                0xF1FA8C,
                0x50FA7B,
                0x8BE9FD,
                0x82AAFF,
                0xFF79C6
            ),
            Theme.of(
                "Nord",
                true,
                0x272C36,
                0x2E3440,
                0x242933,
                0x333A47,
                0x434C5E,
                0x1F232B,
                0xD8DEE9,
                0xECEFF4,
                0x7B88A1,
                0x88C0D0,
                0xBF616A,
                0xD08770,
                0xEBCB8B,
                0xA3BE8C,
                0x8FBCBB,
                0x81A1C1,
                0xB48EAD
            ),
            Theme.of(
                "One Light",
                false,
                0xF0F0F1,
                0xFAFAFA,
                0xFFFFFF,
                0xF3F3F4,
                0xD4D4D8,
                0xE2E2E4,
                0x383A42,
                0x1F2024,
                0x868891,
                0x4078F2,
                0xE45649,
                0xC18401,
                0x986801,
                0x50A14F,
                0x0184BC,
                0x4078F2,
                0xA626A4
            )
        );

        static List<String> names() {
            List<String> n = new ArrayList<>();
            for (Theme t : ALL) n.add(t.name());
            n.add(NATIVE);
            return n;
        }

        Color button() {
            return blend(fg, panel, dark ? 0.08 : 0.05);
        }

        Color tint(Color c) {
            return blend(c, panel, dark ? 0.20 : 0.15);
        }

        Color chart(int i) {
            Color[] c = { blue, green, purple, orange, cyan, red, yellow };
            return c[Math.floorMod(i, c.length)];
        }

        static String pick(Set<String> fams, String... wanted) {
            for (String w : wanted) if (fams.contains(w)) return w;
            return null;
        }

        static synchronized void initFonts() {
            if (UI != null) return;
            Set<String> fams = new HashSet<>(
                Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
            );
            String ui = pick(
                fams,
                "Inter",
                "Segoe UI",
                "SF Pro Text",
                "Helvetica Neue",
                "Ubuntu",
                "Cantarell",
                "Noto Sans",
                "DejaVu Sans"
            );
            String mo = pick(
                fams,
                "JetBrains Mono",
                "Cascadia Mono",
                "Cascadia Code",
                "SF Mono",
                "Menlo",
                "Consolas",
                "Fira Code",
                "Ubuntu Mono",
                "DejaVu Sans Mono"
            );
            UI = new Font(ui == null ? Font.DIALOG : ui, Font.PLAIN, 13);
            MONO = new Font(mo == null ? Font.MONOSPACED : mo, Font.PLAIN, 13);
        }

        static Color ui(String key, Color def) {
            Color c = UIManager.getColor(key);
            return c == null ? def : c;
        }

        static Theme nativeTheme() {
            Color panel = ui("Panel.background", new Color(0xEEEEEE));
            Color field = ui("Table.background", Color.WHITE);
            Color fg = ui("Label.foreground", Color.BLACK);
            boolean dark = luminance(panel) < 0.5;
            Theme p = dark ? ALL.getFirst() : ALL.getLast();
            return Theme.of(NATIVE, dark, true, new Color[] {
                blend(fg, panel, 0.04),
                panel,
                field,
                blend(fg, field, 0.035),
                blend(fg, panel, 0.25),
                blend(fg, panel, 0.14),
                fg,
                fg,
                blend(fg, panel, 0.55),
                p.accent(),
                p.red(),
                p.orange(),
                p.yellow(),
                p.green(),
                p.cyan(),
                p.blue(),
                p.purple(),
            });
        }

        static void apply(String name) {
            initFonts();
            try {
                if (NATIVE.equals(name)) {
                    setNativeLookAndFeel();
                    T = nativeTheme();
                } else {
                    Theme t = ALL.getFirst();
                    for (Theme x : ALL) if (x.name().equals(name)) t = x;
                    T = t;
                    if (!applyFlatLaf(t)) {
                        UIManager.put("swing.boldMetal", Boolean.FALSE);
                        javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(new PaletteMetalTheme(t));
                        UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
                        install(UIManager.getLookAndFeelDefaults(), t);
                    }
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't apply theme " + name, e);
                if (T == null) T = ALL.getFirst();
            }
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
                w.repaint();
            }
        }

        static void reg(UIDefaults d, String id, Class<?> c) {
            d.put(id, c.getName());
            d.put(c.getName(), c);
        }

        /** Puts each role/value pair for every space-separated component prefix ("" for global keys). */
        static void put(UIDefaults d, String prefixes, Object... roleValues) {
            for (String prefix : prefixes.isEmpty() ? new String[] { "" } : prefixes.split(" "))
                for (int i = 0; i < roleValues.length; i += 2) d.put(
                    prefix.isEmpty() ? roleValues[i] : prefix + "." + roleValues[i],
                    roleValues[i + 1]
                );
        }

        /**
         * Uses FlatLaf when the Gradle build has bundled it, tinted with this palette. FlatLaf draws text fields, menus,
         * dialogs and file choosers; the animated buttons, sliding tab underline and hairline split panes stay ours.
         * Returns false (so the built-in look is used) when FlatLaf isn't on the classpath, e.g. java Tiresias.java.
         */
        static boolean applyFlatLaf(Theme t) {
            try {
                Class<?> flat = Class.forName("com.formdev.flatlaf.FlatLaf");
                Map<String, String> vars = Map.of(
                    "@background",
                    hx(t.panel()),
                    "@foreground",
                    hx(t.fg()),
                    "@accentColor",
                    hx(t.accent())
                );
                try {
                    flat.getMethod("setGlobalExtraDefaults", Map.class).invoke(null, vars);
                } catch (ReflectiveOperationException ignored) {}
                UIManager.setLookAndFeel(
                    t.dark() ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf"
                );
            } catch (ClassNotFoundException e) {
                return false;
            } catch (Exception e) {
                LOG.log(
                    System.Logger.Level.WARNING,
                    "FlatLaf is bundled but couldn't be applied; using the built-in look",
                    e
                );
                return false;
            }
            UIDefaults d = UIManager.getLookAndFeelDefaults();
            ColorUIResource window = new ColorUIResource(t.window()),
                panel = new ColorUIResource(t.panel()),
                field = new ColorUIResource(t.field()),
                sel = new ColorUIResource(t.selection());
            Object[] kv = {
                "defaultFont",
                new FontUIResource(UI),
                "Component.arc",
                8,
                "Button.arc",
                8,
                "TextComponent.arc",
                6,
                "ScrollBar.thumbArc",
                999,
                "ScrollBar.thumbInsets",
                new InsetsUIResource(2, 2, 2, 2),
                "ScrollBar.width",
                10,
                "ScrollBar.showButtons",
                false,
                "ScrollPane.smoothScrolling",
                true,
                "Component.focusWidth",
                1,
                "Panel.background",
                panel,
                "Viewport.background",
                panel,
                "ScrollPane.background",
                panel,
                "TabbedPane.background",
                panel,
                "Table.background",
                panel,
                "Table.selectionBackground",
                sel,
                "TableHeader.background",
                window,
                "Tree.background",
                window,
                "Tree.selectionBackground",
                sel,
                "SplitPane.background",
                window,
                "MenuBar.background",
                window,
                "TextField.background",
                field,
                "TextArea.background",
                field,
                "ComboBox.background",
                field,
                "List.background",
                field,
                "Table.cellNoFocusBorder",
                new BorderUIResource(new EmptyBorder(0, 8, 0, 8)),
                "Table.focusCellHighlightBorder",
                new BorderUIResource(new EmptyBorder(0, 8, 0, 8)),
                "SplitPane.dividerSize",
                5,
                "Tree.rowHeight",
                26,
                "Tree.rendererFillBackground",
                Boolean.FALSE,
                "Tree.expandedIcon",
                new ChevronIcon(true),
                "Tree.collapsedIcon",
                new ChevronIcon(false),
            };
            for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
            reg(d, "ButtonUI", FlatButtonUI.class);
            reg(d, "TabbedPaneUI", FlatTabbedPaneUI.class);
            reg(d, "SplitPaneUI", FlatSplitPaneUI.class);
            return true;
        }

        static void install(UIDefaults d, Theme t) {
            FontUIResource font = new FontUIResource(UI);
            for (Object k : new ArrayList<>(d.keySet()))
                if (k instanceof String key && key.endsWith(".font")) d.put(k, font);
            ColorUIResource window = new ColorUIResource(t.window()),
                panel = new ColorUIResource(t.panel()),
                field = new ColorUIResource(t.field()),
                fg = new ColorUIResource(t.fg()),
                bright = new ColorUIResource(t.bright()),
                muted = new ColorUIResource(t.muted()),
                sel = new ColorUIResource(t.selection()),
                border = new ColorUIResource(t.border()),
                accent = new ColorUIResource(t.accent()),
                button = new ColorUIResource(t.button()),
                tip = new ColorUIResource(blend(t.fg(), t.field(), t.dark() ? 0.10 : 0.04));
            Border fieldBorder = new FieldBorder();
            Border cell = new BorderUIResource(new EmptyBorder(0, 8, 0, 8));
            Border item = new BorderUIResource(new EmptyBorder(5, 8, 5, 12));
            Border popup = new BorderUIResource(
                new CompoundBorder(new LineBorder(t.border()), new EmptyBorder(4, 0, 4, 0))
            );
            Object none = new BorderUIResource(new EmptyBorder(0, 0, 0, 0)),
                hairline = new ColorUIResource(t.hairline());
            // Each call gives every listed component the same roles, e.g. all text components share caret/selection colors.
            put(
                d,
                "",
                "control",
                panel,
                "controlText",
                fg,
                "text",
                fg,
                "textText",
                fg,
                "window",
                field,
                "info",
                tip,
                "infoText",
                fg
            );
            put(
                d,
                "Panel Label OptionPane ToolBar CheckBox RadioButton MenuBar PopupMenu Tree TabbedPane",
                "foreground",
                fg
            );
            put(
                d,
                "Panel Viewport ScrollPane OptionPane ToolBar CheckBox RadioButton PopupMenu TabbedPane",
                "background",
                panel
            );
            put(
                d,
                "TextField FormattedTextField PasswordField TextArea EditorPane TextPane",
                "background",
                field,
                "foreground",
                fg,
                "caretForeground",
                bright,
                "selectionBackground",
                sel,
                "selectionForeground",
                bright,
                "inactiveForeground",
                muted
            );
            put(d, "TextField FormattedTextField PasswordField ComboBox", "border", fieldBorder);
            put(d, "ComboBox List Spinner FileChooser", "background", field, "foreground", fg);
            put(
                d,
                "ComboBox List Table Tree Menu MenuItem CheckBoxMenuItem RadioButtonMenuItem",
                "selectionBackground",
                sel,
                "selectionForeground",
                bright
            );
            put(d, "List Table", "focusCellHighlightBorder", cell, "cellNoFocusBorder", cell);
            put(
                d,
                "Menu MenuItem CheckBoxMenuItem RadioButtonMenuItem",
                "foreground",
                fg,
                "border",
                item,
                "acceleratorForeground",
                muted
            );
            put(d, "MenuItem CheckBoxMenuItem RadioButtonMenuItem", "background", panel);
            put(d, "CheckBox RadioButton", "focus", panel, "disabledText", muted);
            put(
                d,
                "TabbedPane",
                "selected",
                panel,
                "contentAreaColor",
                panel,
                "tabAreaBackground",
                panel,
                "focus",
                panel,
                "light",
                panel,
                "highlight",
                panel,
                "shadow",
                panel,
                "darkShadow",
                panel
            );
            put(d, "Tree MenuBar Menu SplitPane TableHeader", "background", window);
            put(d, "ScrollPane ToolBar SplitPane SplitPaneDivider", "border", none);
            Object[] kv = {
                "Label.disabledForeground",
                muted,
                "TextField.inactiveBackground",
                panel,
                "TextField.margin",
                new InsetsUIResource(0, 0, 0, 0),
                "TextArea.margin",
                new InsetsUIResource(6, 8, 6, 8),
                "ComboBox.disabledBackground",
                panel,
                "ComboBox.disabledForeground",
                muted,
                "ComboBox.buttonBackground",
                field,
                "FileChooser.listViewBackground",
                field,
                "Table.background",
                panel,
                "Table.foreground",
                fg,
                "Table.gridColor",
                hairline,
                "Table.scrollPaneBorder",
                none,
                "Table.dropLineColor",
                accent,
                "TableHeader.foreground",
                muted,
                "TableHeader.cellBorder",
                new BorderUIResource(new CompoundBorder(new Line(0, 0, 1, 0), new EmptyBorder(6, 8, 6, 8))),
                "Tree.textForeground",
                fg,
                "Tree.textBackground",
                window,
                "Tree.selectionBorderColor",
                sel,
                "Tree.hash",
                window,
                "Tree.line",
                window,
                "Tree.paintLines",
                Boolean.FALSE,
                "Tree.rendererFillBackground",
                Boolean.FALSE,
                "Tree.leftChildIndent",
                10,
                "Tree.rightChildIndent",
                12,
                "Tree.rowHeight",
                26,
                "Tree.drawsFocusBorderAroundIcon",
                Boolean.FALSE,
                "Tree.drawDashedFocusIndicator",
                Boolean.FALSE,
                "Tree.expandedIcon",
                new ChevronIcon(true),
                "Tree.collapsedIcon",
                new ChevronIcon(false),
                "SplitPane.dividerSize",
                5,
                "SplitPaneDivider.draggingColor",
                accent,
                "MenuBar.border",
                new BorderUIResource(new Line(0, 0, 1, 0)),
                "MenuItem.acceleratorSelectionForeground",
                bright,
                "MenuItem.disabledForeground",
                muted,
                "PopupMenu.border",
                popup,
                "Separator.foreground",
                border,
                "Separator.background",
                panel,
                "Separator.highlight",
                panel,
                "Separator.shadow",
                border,
                "PopupMenuSeparator.foreground",
                border,
                "PopupMenuSeparator.background",
                panel,
                "ToolTip.background",
                tip,
                "ToolTip.foreground",
                bright,
                "ToolTip.border",
                new BorderUIResource(new CompoundBorder(new LineBorder(t.border()), new EmptyBorder(5, 8, 5, 8))),
                "Button.background",
                button,
                "Button.foreground",
                fg,
                "Button.select",
                button,
                "Button.focus",
                accent,
                "Button.disabledText",
                muted,
                "Button.margin",
                new InsetsUIResource(4, 12, 4, 12),
                "CheckBox.icon",
                new CheckIcon(false),
                "RadioButton.icon",
                new CheckIcon(true),
                "OptionPane.messageForeground",
                fg,
                "TitledBorder.titleColor",
                fg,
            };
            for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
            for (String g : "Button CheckBox RadioButton ToggleButton ScrollBar MenuBar ToolBar Slider CheckBoxMenuItem RadioButtonMenuItem".split(
                " "
            ))
                d.remove(g + ".gradient");
            d.remove("Slider.focusGradient");
            d.remove("InternalFrame.activeTitleGradient");
            reg(d, "ScrollBarUI", FlatScrollBarUI.class);
            reg(d, "ButtonUI", FlatButtonUI.class);
            reg(d, "ComboBoxUI", FlatComboBoxUI.class);
            reg(d, "TabbedPaneUI", FlatTabbedPaneUI.class);
            reg(d, "SplitPaneUI", FlatSplitPaneUI.class);
        }
    }

    public static final class PaletteMetalTheme extends javax.swing.plaf.metal.DefaultMetalTheme {

        final Theme t;

        PaletteMetalTheme(Theme t) {
            this.t = t;
        }

        @Override
        public String getName() {
            return t.name();
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return new ColorUIResource(t.accent());
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return new ColorUIResource(t.accent());
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return new ColorUIResource(t.selection());
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return new ColorUIResource(t.border());
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return new ColorUIResource(t.border());
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return new ColorUIResource(t.panel());
        }

        @Override
        protected ColorUIResource getBlack() {
            return new ColorUIResource(t.fg());
        }

        @Override
        protected ColorUIResource getWhite() {
            return new ColorUIResource(t.field());
        }

        @Override
        public ColorUIResource getControlHighlight() {
            return new ColorUIResource(t.panel());
        }

        @Override
        public ColorUIResource getMenuBackground() {
            return new ColorUIResource(t.panel());
        }

        @Override
        public ColorUIResource getMenuSelectedBackground() {
            return new ColorUIResource(t.selection());
        }

        @Override
        public ColorUIResource getMenuSelectedForeground() {
            return new ColorUIResource(t.bright());
        }

        @Override
        public ColorUIResource getHighlightedTextColor() {
            return new ColorUIResource(t.bright());
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return new ColorUIResource(t.muted());
        }

        @Override
        public ColorUIResource getInactiveSystemTextColor() {
            return new ColorUIResource(t.muted());
        }

        FontUIResource f() {
            return new FontUIResource(Theme.UI);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return f();
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return f();
        }

        @Override
        public FontUIResource getUserTextFont() {
            return f();
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return f();
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return new FontUIResource(Theme.UI.deriveFont(Font.BOLD));
        }

        @Override
        public FontUIResource getSubTextFont() {
            return new FontUIResource(Theme.UI.deriveFont(Theme.UI.getSize2D() - 2));
        }
    }

    public static final class FlatScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatScrollBarUI();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            scrollbar.setOpaque(false);
        }

        @Override
        protected void configureScrollBarColors() {}

        static JButton none() {
            JButton b = new JButton();
            Dimension z = new Dimension(0, 0);
            b.setPreferredSize(z);
            b.setMinimumSize(z);
            b.setMaximumSize(z);
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return none();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return none();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            boolean hot = isThumbRollover() || isDragging;
            g2.setColor(alpha(T.fg(), hot ? 110 : 60));
            int pad = 3;
            int arc = Math.min(r.width, r.height) - 2 * pad;
            g2.fillRoundRect(r.x + pad, r.y + pad, r.width - 2 * pad, r.height - 2 * pad, arc, arc);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return scrollbar.getOrientation() == JScrollBar.VERTICAL ? new Dimension(12, 48) : new Dimension(48, 12);
        }
    }

    public static final class FlatButtonUI extends javax.swing.plaf.basic.BasicButtonUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatButtonUI();
        }

        @Override
        protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            LookAndFeel.installProperty(b, "opaque", Boolean.FALSE);
            b.setRolloverEnabled(true);
            if (b.getBorder() == null || b.getBorder() instanceof UIResource) b.setBorder(
                new BorderUIResource(new EmptyBorder(5, 12, 5, 12))
            );
        }

        /** Starts a hover fade whenever the button's rollover state changes. */
        static final ChangeListener HOVER = e -> {
            AbstractButton b = (AbstractButton) e.getSource();
            double target = b.getModel().isRollover() && b.isEnabled() ? 1 : 0;
            if (!Objects.equals(b.getClientProperty("hover#target"), target)) {
                b.putClientProperty("hover#target", target);
                fadeTo(b, "hover", target);
            }
        };

        @Override
        protected void installListeners(AbstractButton b) {
            super.installListeners(b);
            b.addChangeListener(HOVER);
        }

        @Override
        protected void uninstallListeners(AbstractButton b) {
            b.removeChangeListener(HOVER);
            super.uninstallListeners(b);
        }

        static boolean primary(AbstractButton b) {
            return Boolean.TRUE.equals(b.getClientProperty("primary"));
        }

        static boolean flat(AbstractButton b) {
            return Boolean.TRUE.equals(b.getClientProperty("flat")) || b.getParent() instanceof Bar;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            boolean primary = primary(b),
                flat = !primary && flat(b);
            Color base = primary ? T.accent() : T.button();
            Color bg = base;
            Color hovered = primary ? blend(Color.WHITE, base, 0.14) : blend(T.fg(), base, 0.09);
            double hover = fade(b, "hover");
            if (!b.isEnabled()) bg = primary ? blend(T.accent(), T.panel(), 0.4) : flat ? null : base;
            else if (m.isArmed() && m.isPressed()) bg = blend(T.fg(), base, primary ? 0.25 : 0.16);
            else if (flat) bg = hover > 0.01 ? alpha(hovered, (int) (255 * hover)) : null;
            else bg = blend(hovered, base, hover);
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int w = c.getWidth(),
                h = c.getHeight();
            if (bg != null) {
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w, h, 8, 8);
            }
            if (!flat && !primary) {
                g2.setColor(T.border());
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            }
            if (b.isFocusOwner()) {
                g2.setColor(T.accent());
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            }
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
            Graphics2D g2 = (Graphics2D) g;
            aa(g2);
            g2.setColor(!b.isEnabled() ? T.muted() : primary(b) ? onColor(T.accent()) : T.fg());
            g2.setFont(b.getFont());
            g2.drawString(text, r.x, r.y + g2.getFontMetrics().getAscent());
        }
    }

    static final class ArrowButton extends JButton {

        ArrowButton() {
            setFocusable(false);
            setBorder(new EmptyBorder(0, 4, 0, 8));
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        public void updateUI() {
            setUI(new javax.swing.plaf.basic.BasicButtonUI());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(22, 16);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(getModel().isRollover() ? T.fg() : T.muted());
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2 - 2,
                cy = getHeight() / 2;
            g2.drawPolyline(new int[] { cx - 4, cx, cx + 4 }, new int[] { cy - 2, cy + 2, cy - 2 }, 3);
            g2.dispose();
        }
    }

    public static final class FlatComboBoxUI extends javax.swing.plaf.basic.BasicComboBoxUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatComboBoxUI();
        }

        @Override
        protected JButton createArrowButton() {
            return new ArrowButton();
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            return new javax.swing.plaf.basic.BasicComboPopup(comboBox) {
                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setBorder(new LineBorder(T.border()));
                }
            };
        }

        @Override
        public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
            super.paintCurrentValue(g, bounds, false);
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            g.setColor(comboBox.isEnabled() ? comboBox.getBackground() : T.panel());
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    public static final class FlatTabbedPaneUI extends javax.swing.plaf.basic.BasicTabbedPaneUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatTabbedPaneUI();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(8, 14, 8, 14);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            tabAreaInsets = new Insets(0, 8, 0, 8);
            contentBorderInsets = new Insets(1, 0, 0, 0);
        }

        @Override
        protected void paintTabBackground(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {}

        @Override
        protected void paintTabBorder(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {}

        float underX = -1,
            underW;
        javax.swing.Timer slide;
        final ChangeListener slideToSelected = e -> {
            int i = tabPane.getSelectedIndex();
            if (i < 0 || underX < 0 || i >= tabPane.getTabCount()) return;
            Rectangle r = getTabBounds(tabPane, i);
            float fromX = underX,
                fromW = underW;
            if (slide != null) slide.stop();
            slide = animate(
                220,
                t -> {
                    underX = (float) (fromX + (r.x + 6 - fromX) * t);
                    underW = (float) (fromW + (r.width - 12 - fromW) * t);
                    tabPane.repaint();
                },
                null
            );
        };

        @Override
        protected void installListeners() {
            super.installListeners();
            tabPane.addChangeListener(slideToSelected);
        }

        @Override
        protected void uninstallListeners() {
            tabPane.removeChangeListener(slideToSelected);
            super.uninstallListeners();
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            int i = tabPane.getSelectedIndex();
            if (i < 0 || i >= tabPane.getTabCount()) return;
            Rectangle r = getTabBounds(tabPane, i);
            if (underX < 0 || slide == null || !slide.isRunning()) {
                underX = r.x + 6;
                underW = r.width - 12;
            }
            g.setColor(T.accent());
            g.fillRect(Math.round(underX), r.y + r.height - 2, Math.round(underW), 2);
        }

        @Override
        protected void paintContentBorder(Graphics g, int placement, int selected) {
            int y = calculateTabAreaHeight(placement, runCount, maxTabHeight);
            g.setColor(T.hairline());
            g.fillRect(0, y, tabPane.getWidth(), 1);
        }

        @Override
        protected void paintFocusIndicator(
            Graphics g,
            int p,
            Rectangle[] r,
            int i,
            Rectangle ir,
            Rectangle tr,
            boolean sel
        ) {}

        @Override
        protected void paintText(
            Graphics g,
            int placement,
            Font font,
            FontMetrics m,
            int i,
            String title,
            Rectangle tr,
            boolean sel
        ) {
            Graphics2D g2 = (Graphics2D) g;
            aa(g2);
            Color custom = tabPane.getForegroundAt(i);
            boolean isCustom = custom != null && !(custom instanceof UIResource);
            g2.setColor(isCustom ? custom : sel ? T.bright() : T.muted());
            g2.setFont(font);
            g2.drawString(title, tr.x, tr.y + m.getAscent());
        }

        @Override
        protected int getTabLabelShiftY(int p, int i, boolean sel) {
            return 0;
        }

        @Override
        protected int getTabLabelShiftX(int p, int i, boolean sel) {
            return 0;
        }
    }

    public static final class FlatSplitPaneUI extends javax.swing.plaf.basic.BasicSplitPaneUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatSplitPaneUI();
        }

        @Override
        public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
            return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                @Override
                public void setBorder(Border b) {}

                @Override
                public void paint(Graphics g) {
                    g.setColor(T.window());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(T.hairline());
                    if (splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) g.fillRect(
                        getWidth() / 2,
                        0,
                        1,
                        getHeight()
                    );
                    else g.fillRect(0, getHeight() / 2, getWidth(), 1);
                }
            };
        }
    }

    /** Rounded input border that lights up in the accent color while focused. */
    static final class FieldBorder extends AbstractBorder implements UIResource {

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean foc = fo != null && (fo == c || SwingUtilities.isDescendingFrom(fo, c));
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(foc ? T.accent() : T.border());
            g2.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(4, 7, 4, 7);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(4, 7, 4, 7);
            return i;
        }
    }

    /** Hairline separator drawn on the chosen sides in the current theme's line color. */
    static final class Line extends AbstractBorder implements UIResource {

        final int t, l, b, r;

        Line(int t, int l, int b, int r) {
            this.t = t;
            this.l = l;
            this.b = b;
            this.r = r;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(T.hairline());
            if (t > 0) g.fillRect(x, y, w, t);
            if (b > 0) g.fillRect(x, y + h - b, w, b);
            if (l > 0) g.fillRect(x, y, l, h);
            if (r > 0) g.fillRect(x + w - r, y, r, h);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(t, l, b, r);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(t, l, b, r);
            return i;
        }
    }

    record CheckIcon(boolean radio) implements Icon, UIResource {
        public int getIconWidth() {
            return 16;
        }

        public int getIconHeight() {
            return 16;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            boolean sel = b.getModel().isSelected(),
                en = b.isEnabled(),
                roll = b.getModel().isRollover();
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int s = 14,
                ox = x + 1,
                oy = y + 1;
            Color fill = sel ? (en ? T.accent() : blend(T.accent(), T.panel(), 0.4)) : T.field();
            Color stroke = sel ? fill : roll ? T.muted() : blend(T.fg(), T.field(), 0.32);
            Color mark = onColor(T.accent());
            if (radio) {
                g2.setColor(fill);
                g2.fillOval(ox, oy, s, s);
                g2.setColor(stroke);
                g2.drawOval(ox, oy, s - 1, s - 1);
                if (sel) {
                    g2.setColor(mark);
                    g2.fillOval(ox + 4, oy + 4, s - 8, s - 8);
                }
            } else {
                g2.setColor(fill);
                g2.fillRoundRect(ox, oy, s, s, 5, 5);
                g2.setColor(stroke);
                g2.drawRoundRect(ox, oy, s - 1, s - 1, 5, 5);
                if (sel) {
                    g2.setColor(mark);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[] { ox + 3, ox + 6, ox + 11 }, new int[] { oy + 7, oy + 10, oy + 4 }, 3);
                }
            }
            g2.dispose();
        }
    }

    record ChevronIcon(boolean down) implements Icon, UIResource {
        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(T.muted());
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (down) g2.drawPolyline(new int[] { x + 3, x + 6, x + 9 }, new int[] { y + 4, y + 7, y + 4 }, 3);
            else g2.drawPolyline(new int[] { x + 4, x + 7, x + 4 }, new int[] { y + 3, y + 6, y + 9 }, 3);
            g2.dispose();
        }
    }

    /** Panel whose background follows the theme: role 0 = window chrome, 1 = content panel. */
    static class Surface extends JPanel {

        final int role;

        Surface(LayoutManager lm, int role) {
            super(lm);
            this.role = role;
        }

        @Override
        public Color getBackground() {
            return T == null ? super.getBackground() : role == 0 ? T.window() : T.panel();
        }
    }

    /** A row of flat buttons (replaces JToolBar, whose Metal borders don't theme). */
    static final class Bar extends JPanel {

        Bar() {
            super(new FlowLayout(FlowLayout.LEFT, 2, 4));
            setOpaque(false);
            setBorder(new EmptyBorder(2, 6, 2, 6));
        }

        void sep() {
            JComponent s = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(T.border());
                    g.fillRect(getWidth() / 2, 2, 1, getHeight() - 4);
                }
            };
            s.setPreferredSize(new Dimension(11, 20));
            add(s);
        }
    }

    static class MutedLabel extends JLabel {

        MutedLabel(String s) {
            super(s);
        }

        @Override
        public Color getForeground() {
            return T == null ? super.getForeground() : T.muted();
        }
    }

    /** Label whose color is a semantic tone, so it re-colors when the theme changes. */
    static final class ToneLabel extends JLabel {

        static final int MUTED = 0,
            ERR = 1,
            WARN = 2,
            OK = 3;
        int tone;

        ToneLabel(String s) {
            super(s);
        }

        void set(String text, int tone) {
            setText(text);
            this.tone = tone;
            repaint();
        }

        @Override
        public Color getForeground() {
            if (T == null) return super.getForeground();
            return switch (tone) {
                case ERR -> T.red();
                case WARN -> T.yellow();
                case OK -> T.green();
                default -> T.muted();
            };
        }
    }

    /** Tree with full-width selection and hover rows. */
    static final class FullRowTree extends JTree {

        int hoverRow = -1,
            leavingRow = -1;
        double hoverT = 1;
        javax.swing.Timer hoverAnim;

        void setHover(int r) {
            if (r == hoverRow) return;
            leavingRow = hoverRow;
            hoverRow = r;
            if (hoverAnim != null) hoverAnim.stop();
            hoverAnim = animate(
                160,
                t -> {
                    hoverT = t;
                    repaint();
                },
                () -> leavingRow = -1
            );
        }

        void paintHover(Graphics g, int row, double amount) {
            Rectangle r = row < 0 || amount <= 0 ? null : getRowBounds(row);
            if (r == null) return;
            g.setColor(blend(T.fg(), T.window(), 0.06 * amount));
            g.fillRect(0, r.y, getWidth(), r.height);
        }

        FullRowTree(TreeModel m) {
            super(m);
            setOpaque(false);
            addMouseMotionListener(
                new MouseMotionAdapter() {
                    public void mouseMoved(MouseEvent e) {
                        int r = getClosestRowForLocation(e.getX(), e.getY());
                        Rectangle b = r < 0 ? null : getRowBounds(r);
                        if (b == null || e.getY() < b.y || e.getY() >= b.y + b.height) r = -1;
                        setHover(r);
                    }
                }
            );
            addMouseListener(
                new MouseAdapter() {
                    public void mouseExited(MouseEvent e) {
                        setHover(-1);
                    }
                }
            );
            addFocusListener(
                new FocusAdapter() {
                    public void focusGained(FocusEvent e) {
                        repaint();
                    }

                    public void focusLost(FocusEvent e) {
                        repaint();
                    }
                }
            );
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setRowHeight(26);
            putClientProperty("JTree.lineStyle", "None");
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(T.window());
            g.fillRect(0, 0, getWidth(), getHeight());
            paintHover(g, hoverRow, hoverT);
            paintHover(g, leavingRow, 1 - hoverT);
            int[] rows = getSelectionRows();
            if (rows != null) for (int row : rows) {
                Rectangle r = getRowBounds(row);
                if (r == null) continue;
                g.setColor(isFocusOwner() ? T.selection() : blend(T.selection(), T.window(), 0.6));
                g.fillRect(0, r.y, getWidth(), r.height);
                g.setColor(T.accent());
                g.fillRect(0, r.y, 3, r.height);
            }
            super.paintComponent(g);
        }
    }

    /** Accepts files dropped onto a window and hands them to open. */
    static TransferHandler fileDrop(Consumer<List<Path>> open) {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @SuppressWarnings("unchecked")
            @Override
            public boolean importData(TransferSupport s) {
                try {
                    open.accept(
                        ((List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor))
                            .stream()
                            .map(File::toPath)
                            .toList()
                    );
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
    }

    static void styleTable(JTable t) {
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setRowHeight(Math.max(26, t.getFontMetrics(t.getFont()).getHeight() + 10));
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
    }

    static boolean containsIdx(List<Object> l, int k) {
        for (Object o : l) {
            Long v = longVal(o);
            if (v != null && v == k) return true;
        }
        return false;
    }

    // ======================================================================================
    // Message families: same layout, topics that differ at one level (recognized, never stored)
    // ======================================================================================

    /** Messages whose topics differ only at level `seg`; members is mutable and compared by identity. */
    record Family(SpecFile file, int seg, List<Map<String, Object>> members) {
        Family(SpecFile file, int seg) {
            this(file, seg, new ArrayList<>());
        }

        String template() {
            if (members.isEmpty()) return "";
            String n = str(asMap(listOrEmpty(members.getFirst().get("fields")).getFirst()), "name");
            String[] s = n.split("/", -1);
            s[seg] = "*";
            return String.join("/", Arrays.copyOf(s, seg + 1));
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    static String familyShape(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Object o : listOrEmpty(m.get("points")))
            b.append(sizeOf(o))
                .append(isParsed(asMap(o)) ? "" : "p")
                .append(',');
        return b
            .append('|')
            .append(listOrEmpty(m.get("fields")).size())
            .toString();
    }

    static String memberToken(Map<String, Object> m, int seg) {
        for (Object o : listOrEmpty(m.get("fields"))) {
            String n = str(asMap(o), "name");
            if (n == null) return null;
            String[] s = n.split("/", -1);
            return seg < s.length ? s[seg] : null;
        }
        return null;
    }

    static void findFamilies(List<SpecFile> files, SpecFile only, Map<Map<String, Object>, Family> families) {
        families.values().removeIf(fam -> only == null || fam.file() == only);
        for (SpecFile f : files) {
            if (only != null && f != only) continue;
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            Map<String, Integer> segOf = new HashMap<>();
            for (Object o : f.messages) {
                Map<String, Object> m = asMap(o);
                if (m == null) continue;
                List<Object> fds = listOrEmpty(m.get("fields"));
                if (fds.isEmpty()) continue;
                List<String[]> names = new ArrayList<>();
                int minLen = Integer.MAX_VALUE;
                boolean ok = true;
                for (Object fo : fds) {
                    String n = str(asMap(fo), "name");
                    if (n == null || n.isEmpty()) {
                        ok = false;
                        break;
                    }
                    String[] sp = n.split("/", -1);
                    names.add(sp);
                    minLen = Math.min(minLen, sp.length);
                }
                if (!ok || minLen < 2) continue;
                String shape = familyShape(m);
                for (int k = 0; k < minLen - 1; k++) {
                    String tok = names.getFirst()[k];
                    if (tok.isEmpty()) continue;
                    boolean same = true;
                    for (String[] sp : names)
                        if (!sp[k].equals(tok)) {
                            same = false;
                            break;
                        }
                    if (!same) continue;
                    StringBuilder key = new StringBuilder(shape).append('|').append(k);
                    for (String[] sp : names) {
                        String[] c = sp.clone();
                        c[k] = "*";
                        key.append('|').append(String.join("/", c));
                    }
                    groups.computeIfAbsent(key.toString(), x -> new ArrayList<>()).add(m);
                    segOf.put(key.toString(), k);
                }
            }
            List<Map.Entry<String, List<Map<String, Object>>>> sorted = new ArrayList<>(groups.entrySet());
            sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
            for (Map.Entry<String, List<Map<String, Object>>> e : sorted) {
                List<Map<String, Object>> free = new ArrayList<>();
                for (Map<String, Object> m : e.getValue()) if (!families.containsKey(m)) free.add(m);
                if (free.size() < 2) continue;
                int seg = segOf.get(e.getKey());
                Set<String> toks = new HashSet<>();
                boolean distinct = true;
                for (Map<String, Object> m : free) if (!toks.add(String.valueOf(memberToken(m, seg)))) distinct = false;
                if (!distinct) continue;
                Family fam = new Family(f, seg);
                fam.members().addAll(free);
                for (Map<String, Object> m : free) families.put(m, fam);
            }
        }
    }

    // ======================================================================================
    // CAN traffic: candump parsing and decoding against the spec
    // ======================================================================================

    static final HexFormat HEX = HexFormat.of().withUpperCase();
    static final HexFormat HEX_SPACED = HexFormat.ofDelimiter(" ").withUpperCase();

    /** One CAN frame read from candump output. */
    record CanFrame(double ts, String bus, long id, boolean ext, boolean rtr, boolean fd, byte[] data, String line) {
        String key() {
            return (ext ? "e" : "s") + id;
        }

        String idText() {
            return ext ? "%08X".formatted(id) : "%03X".formatted(id);
        }

        String dataText() {
            return rtr ? "remote request" : HEX_SPACED.formatHex(data);
        }
    }

    /** candump -L log lines, cansend-style lines, and (ts) bus ID#DATA, including CAN FD ## and remote R. */
    static final Pattern CANDUMP_LOG = Pattern.compile(
        "^\\s*(?:\\(([^)]*)\\)\\s+)?(?:(\\S+)\\s+)?([0-9A-Fa-f]{1,8})(##?)(R\\d*|[0-9A-Fa-f.]*)(?:\\s+[RT])?\\s*$"
    );
    /** Default candump output: [ (ts) ] bus [RX - -] ID [len] bytes..., with optional ASCII after. */
    static final Pattern CANDUMP_STD = Pattern.compile(
        "^\\s*(?:\\(([^)]*)\\)\\s+)?(\\S+)\\s+(?:(?:RX|TX)\\s+\\S+\\s+\\S+\\s+)?([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+\\[(\\d{1,2})]\\s*(.*)$"
    );

    static double parseTs(String s) {
        if (s == null) return Double.NaN;
        s = s.strip();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {}
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(s.replace(' ', 'T'));
            return t.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() + t.getNano() / 1e9;
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    static CanFrame parseCandumpLine(String line) {
        if (line == null || line.isBlank()) return null;
        line = line.strip().replaceFirst("^cansend\\s+", "");
        Matcher m = CANDUMP_LOG.matcher(line);
        if (m.matches()) {
            String id = m.group(3),
                body = m.group(5);
            boolean fd = m.group(4).equals("##");
            if (fd && !body.isEmpty()) body = body.substring(1);
            boolean rtr = body.startsWith("R");
            body = body.replace(".", "");
            if (!rtr && body.length() % 2 != 0) return null;
            return new CanFrame(
                parseTs(m.group(1)),
                Objects.requireNonNullElse(m.group(2), ""),
                Long.parseLong(id, 16),
                id.length() > 3,
                rtr,
                fd,
                rtr ? new byte[0] : HEX.parseHex(body),
                line
            );
        }
        m = CANDUMP_STD.matcher(line);
        if (m.matches()) {
            String id = m.group(3),
                rest = m.group(5).strip();
            int len = Integer.parseInt(m.group(4));
            boolean rtr = rest.toLowerCase(Locale.ROOT).contains("remote");
            StringBuilder hex = new StringBuilder();
            if (!rtr) for (String tok : rest.split("\\s+")) {
                if (hex.length() / 2 >= len || !tok.matches("[0-9A-Fa-f]{2}")) break;
                hex.append(tok);
            }
            return new CanFrame(
                parseTs(m.group(1)),
                m.group(2),
                Long.parseLong(id, 16),
                id.length() == 8,
                rtr,
                len > 8,
                HEX.parseHex(hex),
                line
            );
        }
        return null;
    }

    /** Spec messages keyed by frame type and ID: "s" + id for standard, "e" + id for extended. */
    static Map<String, SpecRef> specIndex(List<SpecFile> files) {
        Map<String, SpecRef> idx = new HashMap<>();
        for (SpecFile f : files)
            for (Object o : f.messages) {
                if (
                    !(asMap(o) instanceof Map<String, Object> m) || !(parseId(str(m, "id")) instanceof Long id)
                ) continue;
                idx.putIfAbsent((Boolean.TRUE.equals(m.get("is_ext")) ? "e" : "s") + id, new SpecRef(f, m));
            }
        return idx;
    }

    /** Result of checking a frame against the spec. level: 0 fine, 1 warning, 2 problem. hit is null for unknown IDs. */
    record FrameCheck(SpecRef hit, String text, int level) {}

    static FrameCheck checkFrame(CanFrame fr, Map<String, SpecRef> idx) {
        SpecRef hit = idx.get(fr.key());
        if (hit == null) {
            SpecRef other = idx.get((fr.ext() ? "s" : "e") + fr.id());
            if (other == null) return new FrameCheck(null, "Unknown ID", 2);
            return new FrameCheck(
                other,
                "Frame is %s but the spec's %s is %s (check is_ext). Decoded anyway".formatted(
                    fr.ext() ? "extended" : "standard",
                    strOr(other.msg(), "id", "?"),
                    fr.ext() ? "standard" : "extended"
                ),
                2
            );
        }
        if (fr.rtr()) return new FrameCheck(hit, "Remote request", 1);
        List<Object> pts = listOrEmpty(hit.msg().get("points"));
        int need = (offsets(pts)[pts.size()] + 7) / 8;
        int have = fr.data().length;
        if (have < need) return new FrameCheck(
            hit,
            "Too short: %d of %d bytes (firmware-rs drops it)".formatted(have, need),
            2
        );
        if (have > need) return new FrameCheck(
            hit,
            "Longer than the spec: %d bytes, spec uses %d".formatted(have, need),
            1
        );
        return new FrameCheck(hit, "OK", 0);
    }

    /** A decoded frame: per-point rows (#, name, raw, value), MQTT lines, and a one-line summary. */
    record Decoded(List<String[]> points, List<String> topics, StringBuilder summary) {
        Decoded() {
            this(new ArrayList<>(), new ArrayList<>(), new StringBuilder());
        }
    }

    /** Decodes a frame's bytes with a message's points and fields, the same way the Decode test tab does. */
    static Decoded decodeFrame(Map<String, Object> msg, byte[] data) {
        Decoded d = new Decoded();
        List<Object> pts = listOrEmpty(msg.get("points"));
        int[] off = offsets(pts);
        double[] values = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            int size = sizeOf(p);
            String n = String.valueOf(i + 1),
                bits = size <= 1 ? String.valueOf(off[i]) : off[i] + "–" + (off[i] + size - 1);
            if (p == null || size < 1 || size > 64) {
                d.points().add(new String[] { n, "(invalid)", bits, "", "" });
                continue;
            }
            if (!isParsed(p)) {
                d.points().add(new String[] { n, "(padding)", bits, "", "" });
                continue;
            }
            long raw = extractBits(data, off[i], size);
            if ("little".equals(p.get("endianness")) && size % 8 == 0) {
                long r = 0;
                for (int k = 0; k < size / 8; k++) r = (r << 8) | ((raw >>> (8 * k)) & 0xFF);
                raw = r;
            }
            String val;
            if (Boolean.TRUE.equals(p.get("ieee754_f32")) && size == 32) {
                values[i] = Float.intBitsToFloat((int) raw);
                val = fmtD(values[i]);
            } else {
                long v = raw;
                if (Boolean.TRUE.equals(p.get("signed")) && size < 64 && ((raw >>> (size - 1)) & 1) == 1) v =
                    raw - (1L << size);
                BigDecimal div = divisor(p);
                values[i] = div == null ? v : v / div.doubleValue();
                val =
                    fmtD(values[i]) +
                    (div == null ? "  (formatter \"" + formatterKey(p) + "\" has no usable arg)" : "");
            }
            d.points().add(new String[] {
                n,
                strOr(p, "name", ""),
                bits,
                "0x" + Long.toHexString(raw).toUpperCase(Locale.ROOT) + "  (" + raw + ")",
                val,
            });
        }
        for (Object o : listOrEmpty(msg.get("fields"))) {
            Map<String, Object> f = asMap(o);
            if (f == null) continue;
            String topic = strOr(f, "name", "?");
            Matcher mm = PLACEHOLDER_NUM.matcher(topic);
            StringBuilder tb = new StringBuilder();
            while (mm.find()) {
                int k = Integer.parseInt(mm.group(1));
                mm.appendReplacement(
                    tb,
                    Matcher.quoteReplacement(k >= 1 && k <= pts.size() ? fmtD(values[k - 1]) : "?")
                );
            }
            mm.appendTail(tb);
            StringBuilder vs = new StringBuilder();
            for (Object v : listOrEmpty(f.get("values"))) {
                Long k = longVal(v);
                if (!vs.isEmpty()) vs.append(", ");
                vs.append(k != null && k >= 1 && k <= pts.size() ? fmtD(values[(int) (k - 1)]) : "?");
            }
            String unit = strOr(f, "unit", "");
            d.topics().add(tb + " = " + vs + (unit.isEmpty() ? "" : " " + unit));
            String t = tb.toString();
            String last = t.substring(t.lastIndexOf('/') + 1);
            if (!d.summary().isEmpty()) d.summary().append("   ");
            d.summary()
                .append(last)
                .append('=')
                .append(vs)
                .append(unit.isEmpty() ? "" : " " + unit);
        }
        return d;
    }

    static final class TrafficWindow extends JFrame {

        final App app;
        final List<CanFrame> frames = new ArrayList<>();
        final List<Integer> view = new ArrayList<>();
        final Map<String, Agg> aggs = new LinkedHashMap<>();
        final List<Agg> aggView = new ArrayList<>();
        final Map<String, String> searchText = new HashMap<>();
        Map<String, SpecRef> index;
        double t0 = Double.NaN;
        int skippedLines;

        final HintField filter = new HintField("Filter by ID, message, topic or bus…", 24);
        final JCheckBox perId = new JCheckBox("One row per ID", true);
        final JCheckBox problemsOnly = new JCheckBox("Problems only");
        final JCheckBox follow = new JCheckBox("Follow new frames", true);
        final JButton liveBtn;
        final JTextArea detail = new JTextArea();
        final ToneLabel stat = new ToneLabel(" ");
        final AbstractTableModel model;
        final JTable table;
        final java.util.concurrent.ConcurrentLinkedQueue<CanFrame> pending =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        final javax.swing.Timer pump, specTimer;
        volatile Process proc;

        static final class Agg {

            CanFrame last;
            int count, level;
            double first = Double.NaN,
                lastTs = Double.NaN;
            String text = "OK";
        }

        static final String[] FRAME_COLS = {
            "#",
            "Time (s)",
            "Bus",
            "ID",
            "Message",
            "Len",
            "Data",
            "Decoded",
            "Status",
        };
        static final String[] AGG_COLS = {
            "ID",
            "Message",
            "Frames",
            "Rate (Hz)",
            "Len",
            "Last data",
            "Last decoded",
            "Status",
        };

        TrafficWindow(App app) {
            super("CAN traffic");
            this.app = app;
            Icons.install(this);
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            index = specIndex(app.files);

            model = new AbstractTableModel() {
                public int getRowCount() {
                    return perId.isSelected() ? aggView.size() : view.size();
                }

                public int getColumnCount() {
                    return perId.isSelected() ? AGG_COLS.length : FRAME_COLS.length;
                }

                public String getColumnName(int c) {
                    return perId.isSelected() ? AGG_COLS[c] : FRAME_COLS[c];
                }

                public Object getValueAt(int r, int c) {
                    return perId.isSelected() ? aggValue(aggView.get(r), c) : frameValue(view.get(r), c);
                }
            };
            table = new JTable(model);
            styleTable(table);
            table.setDefaultRenderer(
                Object.class,
                new StyledRenderer((t, row, col, sel, comp) -> {
                    int level = rowLevel(row);
                    boolean statusCol = col == model.getColumnCount() - 1;
                    if (statusCol && !sel) comp.setForeground(
                        level == 2 ? T.red() : level == 1 ? T.yellow() : T.muted()
                    );
                    if (level == 2 && !sel && !statusCol) comp.setBackground(T.tint(T.red()));
                    String name = model.getColumnName(col);
                    if (name.equals("ID") || name.contains("data") || name.equals("Data")) comp.setFont(
                        mono(Font.PLAIN, t.getFont().getSize())
                    );
                    if (statusCol || name.contains("ecoded")) comp.setToolTipText(
                        String.valueOf(model.getValueAt(row, col))
                    );
                })
            );
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) showDetail();
            });
            table.addMouseListener(
                new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) openInEditor();
                    }
                }
            );
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

            detail.setEditable(false);
            detail.setFont(mono(Font.PLAIN, detail.getFont().getSize()));

            Bar top = new Bar();
            top.add(button("Open log…", "Open candump output or a candump -L log", this::openLog));
            top.add(button("Paste…", "Paste candump or cansend lines", this::paste));
            top.sep();
            liveBtn = button(
                "Listen live…",
                "Run candump on the car's computer over SSH (or on this computer) and decode as frames arrive",
                this::toggleLive
            );
            top.add(liveBtn);
            top.sep();
            top.add(button("Clear", null, this::clear));
            for (JCheckBox b : new JCheckBox[] { perId, problemsOnly, follow }) {
                b.setOpaque(false);
                top.add(b);
            }
            perId.addActionListener(e -> {
                model.fireTableStructureChanged();
                rebuildView();
            });
            problemsOnly.addActionListener(e -> rebuildView());
            javax.swing.Timer filterTimer = new javax.swing.Timer(200, e -> rebuildView());
            filterTimer.setRepeats(false);
            filter.getDocument().addDocumentListener(MessageEditor.docListener(filterTimer::restart));

            JPanel north = new JPanel(new BorderLayout());
            north.add(top, BorderLayout.NORTH);
            JPanel fr = new JPanel(new BorderLayout());
            fr.setOpaque(false);
            fr.setBorder(new EmptyBorder(0, 10, 6, 10));
            fr.add(filter, BorderLayout.CENTER);
            north.add(fr, BorderLayout.SOUTH);

            JPanel detailPane = new JPanel(new BorderLayout());
            Bar db = new Bar();
            db.add(button("Open message in editor", "Double-clicking a row does the same", this::openInEditor));
            detailPane.add(db, BorderLayout.NORTH);
            detailPane.add(new JScrollPane(detail), BorderLayout.CENTER);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), detailPane);
            split.setResizeWeight(0.65);
            split.setBorder(null);

            JPanel statusBar = new Surface(new BorderLayout(), 0);
            statusBar.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
            statusBar.add(stat, BorderLayout.CENTER);

            getContentPane().add(north, BorderLayout.NORTH);
            getContentPane().add(split, BorderLayout.CENTER);
            getContentPane().add(statusBar, BorderLayout.SOUTH);

            pump = new javax.swing.Timer(150, e -> drain());
            pump.start();
            specTimer = new javax.swing.Timer(400, e -> applySpecChange());
            specTimer.setRepeats(false);
            addWindowListener(
                new WindowAdapter() {
                    public void windowClosed(WindowEvent e) {
                        stopLive();
                        pump.stop();
                        app.traffic = null;
                    }
                }
            );
            setTransferHandler(fileDrop(ps -> ps.forEach(this::readFile)));
            stat.set(
                "Open a candump log, paste lines, or listen live over SSH. You can also drop log files here.",
                ToneLabel.MUTED
            );
            setSize(1200, 760);
            setLocationRelativeTo(app);
        }

        // ---- data in

        void openLog() {
            JFileChooser fc = new JFileChooser(app.prefs.get("lastLogDir", System.getProperty("user.home")));
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle("Open candump logs");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            app.prefs.put("lastLogDir", fc.getCurrentDirectory().getAbsolutePath());
            for (File f : fc.getSelectedFiles()) readFile(f.toPath());
        }

        void readFile(Path p) {
            stat.set("Reading " + p.getFileName() + "…", ToneLabel.MUTED);
            Thread t = new Thread(() -> {
                int n = 0,
                    skipped = 0;
                try (java.io.BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        CanFrame f = parseCandumpLine(line);
                        if (f == null) {
                            if (!line.isBlank()) skipped++;
                            continue;
                        }
                        pending.add(f);
                        n++;
                    }
                    final int fn = n,
                        fs = skipped;
                    SwingUtilities.invokeLater(() -> {
                        skippedLines += fs;
                        stat.set(
                            "Read " +
                                fn +
                                " frames from " +
                                p.getFileName() +
                                (fs > 0 ? " (" + fs + " lines weren't CAN frames)" : ""),
                            ToneLabel.MUTED
                        );
                    });
                } catch (IOException | RuntimeException ex) {
                    SwingUtilities.invokeLater(() ->
                        stat.set("Couldn't read " + p.getFileName() + ": " + ex.getMessage(), ToneLabel.ERR)
                    );
                }
            }, "candump-reader");
            t.setDaemon(true);
            t.start();
        }

        void paste() {
            JTextArea ta = new JTextArea(18, 80);
            ta.setFont(mono(Font.PLAIN, ta.getFont().getSize()));
            String clip = "";
            try {
                clip = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            } catch (Exception ignored) {}
            if (clip != null && (clip.contains("#") || clip.contains("["))) ta.setText(clip);
            int r = JOptionPane.showConfirmDialog(
                this,
                new JScrollPane(ta),
                "Paste candump or cansend lines",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );
            if (r != JOptionPane.OK_OPTION) return;
            int n = 0,
                skipped = 0;
            for (String line : ta.getText().split("\\r?\\n")) {
                String l = line.strip();
                if (l.startsWith("cansend ")) l = l.replaceFirst("^cansend\\s+", "");
                CanFrame f = parseCandumpLine(l);
                if (f == null) {
                    if (!l.isEmpty()) skipped++;
                    continue;
                }
                pending.add(f);
                n++;
            }
            stat.set(
                "Added " + n + " frames" + (skipped > 0 ? " (" + skipped + " lines weren't CAN frames)" : ""),
                skipped > 0 ? ToneLabel.WARN : ToneLabel.MUTED
            );
        }

        // ---- live capture: candump on the car's computer over SSH (or on this computer)

        static String sessionPassword; // kept in memory only, never saved

        // The car's network and computer. Change these if the car's setup changes.
        static final String CAR_WIFI = "Hermesbay";
        static final String CAR_HOST = "192.168.100.12";
        static final String CAR_USER = "root";
        static final List<String> WRONG_WIFI = List.of("NuWave", "eduroam");

        /** Name of the Wi-Fi network this computer is on, or null if it can't be told (wired, no Wi-Fi, or hidden by the OS). */
        static String currentWifi() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String[]> cmds = new ArrayList<>();
            if (os.contains("mac")) {
                cmds.add(new String[] { "networksetup", "-getairportnetwork", "en0" });
                cmds.add(new String[] { "networksetup", "-getairportnetwork", "en1" });
                cmds.add(new String[] { "ipconfig", "getsummary", "en0" });
            } else if (os.contains("windows")) {
                cmds.add(new String[] { "netsh", "wlan", "show", "interfaces" });
            } else {
                cmds.add(new String[] { "nmcli", "-t", "-f", "active,ssid", "dev", "wifi" });
                cmds.add(new String[] { "iwgetid", "-r" });
            }
            for (String[] cmd : cmds) {
                String out = runQuick(cmd);
                if (out == null) continue;
                String ssid = parseSsid(cmd[0], out);
                if (ssid != null && !ssid.isEmpty() && !ssid.contains("<redacted>")) return ssid;
            }
            return null;
        }

        static final Pattern SSID_LINE = Pattern.compile("^SSID\\s*:\\s*(.+)$");

        static String parseSsid(String tool, String out) {
            for (String line : out.split("\r?\n")) {
                String l = line.strip();
                String found = switch (tool) {
                    case "networksetup" -> l.startsWith("Current Wi-Fi Network:")
                        ? l.substring(l.indexOf(':') + 1).strip()
                        : null;
                    case "ipconfig", "netsh" -> {
                        Matcher m = SSID_LINE.matcher(l);
                        yield m.matches() ? m.group(1).strip() : null;
                    }
                    case "nmcli" -> l.startsWith("yes:") ? l.substring(4).replace("\\:", ":") : null;
                    default -> l.isEmpty() ? null : l;
                };
                if (found != null) return found;
            }
            return null;
        }

        static String runQuick(String[] cmd) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getOutputStream().close();
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        static boolean wrongWifi(String ssid) {
            if (ssid == null) return false;
            for (String w : WRONG_WIFI) if (w.equalsIgnoreCase(ssid.strip())) return true;
            return false;
        }

        void toggleLive() {
            if (proc != null) {
                stopLive();
                return;
            }
            Preferences pr = app.prefs;
            JRadioButton viaSsh = new JRadioButton(
                "On the car's computer, over SSH",
                !pr.getBoolean("liveLocal", false)
            );
            JRadioButton local = new JRadioButton(
                "On this computer (Linux with a CAN adapter)",
                pr.getBoolean("liveLocal", false)
            );
            ButtonGroup g = new ButtonGroup();
            g.add(viaSsh);
            g.add(local);
            JTextField host = new JTextField(pr.get("sshHost", CAR_HOST), 16);
            JTextField port = new JTextField(pr.get("sshPort", "22"), 4);
            JTextField user = new JTextField(pr.get("sshUser", CAR_USER), 12);
            JPasswordField pass = new JPasswordField(sessionPassword == null ? "" : sessionPassword, 16);
            JCheckBox remember = new JCheckBox("Remember the password until I close the app", sessionPassword != null);
            JComboBox<String> ifc = editableCombo(List.of("can0", "can1", "vcan0"));
            ifc.setSelectedItem(pr.get("liveIface", "can0"));
            JTextField command = new JTextField(pr.get("liveCommand", "candump -L {iface}"), 24);
            for (JComponent c : new JComponent[] { viaSsh, local, remember }) c.setOpaque(false);

            JPanel f = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            int y = 0;
            ToneLabel wifi = new ToneLabel("Checking which Wi-Fi network you're on…");
            final String[] ssid = new String[1];
            final boolean[] checked = new boolean[1];
            c.gridx = 0;
            c.gridy = y++;
            c.gridwidth = 4;
            f.add(wifi, c);
            Thread wt = new Thread(() -> {
                String found = currentWifi();
                SwingUtilities.invokeLater(() -> {
                    ssid[0] = found;
                    checked[0] = true;
                    if (wrongWifi(found)) wifi.set(
                        "<html><b>You're on " +
                            escHtml(found) +
                            ".</b> The car's computer is only reachable on " +
                            CAR_WIFI +
                            ". Switch Wi-Fi to " +
                            CAR_WIFI +
                            " first.</html>",
                        ToneLabel.ERR
                    );
                    else if (found != null && found.equalsIgnoreCase(CAR_WIFI)) wifi.set(
                        "Connected to " + CAR_WIFI + ".",
                        ToneLabel.OK
                    );
                    else if (found != null) wifi.set(
                        "You're on \"" + found + "\". The car's computer is on " + CAR_WIFI + ".",
                        ToneLabel.WARN
                    );
                    else wifi.set("Make sure you're connected to " + CAR_WIFI + ".", ToneLabel.MUTED);
                    Window w = SwingUtilities.getWindowAncestor(wifi);
                    if (w != null) w.pack();
                });
            }, "wifi-check");
            wt.setDaemon(true);
            wt.start();
            c.gridy = y++;
            f.add(viaSsh, c);
            c.gridy = y++;
            f.add(local, c);
            c.gridwidth = 1;
            c.gridy = y;
            c.gridx = 0;
            f.add(new MutedLabel("Host"), c);
            c.gridx = 1;
            f.add(host, c);
            c.gridx = 2;
            f.add(new MutedLabel("Port"), c);
            c.gridx = 3;
            f.add(port, c);
            y++;
            c.gridy = y;
            c.gridx = 0;
            f.add(new MutedLabel("User"), c);
            c.gridx = 1;
            f.add(user, c);
            y++;
            c.gridy = y;
            c.gridx = 0;
            f.add(new MutedLabel("Password"), c);
            c.gridx = 1;
            f.add(pass, c);
            c.gridx = 2;
            c.gridwidth = 2;
            f.add(new MutedLabel("empty = use your SSH key"), c);
            c.gridwidth = 1;
            y++;
            c.gridy = y++;
            c.gridx = 1;
            c.gridwidth = 3;
            f.add(remember, c);
            c.gridwidth = 1;
            c.gridy = y;
            c.gridx = 0;
            f.add(new MutedLabel("Interface"), c);
            c.gridx = 1;
            f.add(ifc, c);
            y++;
            c.gridy = y;
            c.gridx = 0;
            f.add(new MutedLabel("Command"), c);
            c.gridx = 1;
            c.gridwidth = 3;
            f.add(command, c);
            y++;
            c.gridy = y;
            c.gridx = 1;
            f.add(
                new MutedLabel(
                    "{iface} is replaced by the interface. The output must be candump -L or default candump lines."
                ),
                c
            );
            Runnable sync = () -> {
                boolean s = viaSsh.isSelected();
                for (JComponent x : new JComponent[] { host, port, user, pass, remember }) x.setEnabled(s);
            };
            viaSsh.addActionListener(e -> sync.run());
            local.addActionListener(e -> sync.run());
            sync.run();

            while (true) {
                int r = JOptionPane.showConfirmDialog(
                    this,
                    f,
                    "Listen to a CAN bus",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
                );
                if (r != JOptionPane.OK_OPTION) return;
                if (viaSsh.isSelected() && host.getText().isBlank()) {
                    JOptionPane.showMessageDialog(this, "Enter the car computer's host name or IP address.");
                    continue;
                }
                if (!port.getText().strip().matches("\\d{1,5}")) {
                    JOptionPane.showMessageDialog(this, "Port must be a number (usually 22).");
                    continue;
                }
                String net = checked[0] ? ssid[0] : currentWifi();
                if (viaSsh.isSelected() && wrongWifi(net)) {
                    Object[] opts = { "I've switched, connect", "Connect anyway", "Cancel" };
                    int w = JOptionPane.showOptionDialog(
                        this,
                        "You're on " +
                            net +
                            ", but the car's computer is only reachable on " +
                            CAR_WIFI +
                            ".\nSwitch your Wi-Fi to " +
                            CAR_WIFI +
                            ", then connect.",
                        "Wrong Wi-Fi network",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        opts,
                        opts[0]
                    );
                    if (w == 2 || w == JOptionPane.CLOSED_OPTION) return;
                    if (w == 0 && wrongWifi(currentWifi())) {
                        JOptionPane.showMessageDialog(
                            this,
                            "Still on " + currentWifi() + ". Switch to " + CAR_WIFI + " and try again.",
                            "Wrong Wi-Fi network",
                            JOptionPane.WARNING_MESSAGE
                        );
                        continue;
                    }
                }
                break;
            }
            String iface = String.valueOf(ifc.getEditor().getItem()).strip();
            if (iface.isEmpty()) iface = "can0";
            String remote = command.getText().isBlank()
                ? "candump -L " + iface
                : command.getText().strip().replace("{iface}", iface);
            String password = new String(pass.getPassword());
            pr.putBoolean("liveLocal", local.isSelected());
            pr.put("sshHost", host.getText().strip());
            pr.put("sshPort", port.getText().strip());
            pr.put("sshUser", user.getText().strip());
            pr.put("liveIface", iface);
            pr.put("liveCommand", command.getText().strip());
            sessionPassword = remember.isSelected() && !password.isEmpty() ? password : null;

            List<String> cmd = new ArrayList<>();
            ProcessBuilder pb = new ProcessBuilder(cmd);
            String where;
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
            if (viaSsh.isSelected()) {
                String target = (user.getText().isBlank() ? "" : user.getText().strip() + "@") + host.getText().strip();
                where = iface + " on " + target;
                // -tt gives the remote command a terminal: candump then prints each frame immediately,
                // and it's stopped when the connection closes.
                cmd.addAll(
                    Arrays.asList(
                        "ssh",
                        "-tt",
                        "-p",
                        port.getText().strip(),
                        "-o",
                        "ConnectTimeout=8",
                        "-o",
                        "ServerAliveInterval=5",
                        "-o",
                        "ServerAliveCountMax=3",
                        "-o",
                        "StrictHostKeyChecking=accept-new"
                    )
                );
                if (password.isEmpty()) cmd.addAll(Arrays.asList("-o", "BatchMode=yes"));
                else {
                    cmd.addAll(Arrays.asList("-o", "NumberOfPasswordPrompts=1"));
                    try {
                        Path askpass = askpassScript(windows);
                        pb.environment().put("SSH_ASKPASS", askpass.toString());
                        pb.environment().put("SSH_ASKPASS_REQUIRE", "force");
                        pb.environment().putIfAbsent("DISPLAY", ":0");
                        pb.environment().put("TIRESIAS_SSH_PW", password);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(
                            this,
                            "Couldn't prepare password login: " + ex.getMessage() + "\nUse an SSH key instead."
                        );
                        return;
                    }
                }
                cmd.add(target);
                cmd.add(remote);
            } else {
                where = iface + " on this computer";
                if (windows) cmd.addAll(Arrays.asList("cmd", "/c", remote));
                else cmd.addAll(Arrays.asList("sh", "-c", remote));
            }
            pb.redirectErrorStream(true);
            try {
                proc = pb.start();
            } catch (IOException ex) {
                proc = null;
                JOptionPane.showMessageDialog(
                    this,
                    viaSsh.isSelected()
                        ? "Couldn't run ssh: " +
                              ex.getMessage() +
                              "\n\nmacOS and Linux include ssh. On Windows, turn on\nSettings > System > Optional features > OpenSSH Client."
                        : "Couldn't run \"" +
                              remote +
                              "\": " +
                              ex.getMessage() +
                              "\n\ncandump is part of can-utils on Linux (sudo apt install can-utils).",
                    "Can't start",
                    JOptionPane.WARNING_MESSAGE
                );
                return;
            }
            startReader(proc, where, iface);
        }

        /** A tiny SSH_ASKPASS helper that hands ssh the password from the environment (never written to disk). */
        static Path askpassScript(boolean windows) throws IOException {
            Path p;
            if (windows) {
                p = Files.createTempFile("tiresias-askpass", ".cmd");
                String jar = ownJar();
                String java = new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath();
                String body =
                    jar != null
                        ? "@\"" + java + "\" -cp \"" + jar + "\" Tiresias --askpass\r\n"
                        : "@echo %TIRESIAS_SSH_PW%\r\n";
                Files.writeString(p, body);
            } else {
                p = Files.createTempFile("tiresias-askpass", ".sh");
                Files.writeString(
                    p,
                    """
                    #!/bin/sh
                    printf '%s\\n' "$TIRESIAS_SSH_PW"
                    """
                );
                if (!p.toFile().setExecutable(true, true)) throw new IOException("Couldn't make " + p + " executable");
            }
            p.toFile().deleteOnExit();
            return p;
        }

        static String ownJar() {
            try {
                Path p = Paths.get(Tiresias.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                return Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? p.toString()
                    : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** Turns ssh/candump error output into a sentence about what to fix. */
        static String explain(String noise, String iface) {
            if (noise == null) return null;
            String n = noise.toLowerCase(Locale.ROOT);
            if (
                n.contains("permission denied")
            ) return "SSH login failed. Check the user and password, or set up a key once with: ssh-copy-id user@host";
            if (
                n.contains("could not resolve")
            ) return "Can't find that host name. Check the address, or use the car computer's IP.";
            if (n.contains("timed out") || n.contains("no route") || n.contains("unreachable")) {
                String net = currentWifi();
                return (
                    "Can't reach the car's computer. " +
                    (wrongWifi(net)
                        ? "You're on " + net + "; connect to " + CAR_WIFI + " instead."
                        : "Make sure you're on " + CAR_WIFI + " and the address is right.")
                );
            }
            if (
                n.contains("connection refused")
            ) return "The car's computer refused the connection. Is SSH running there, and is the port right?";
            if (
                n.contains("host key verification failed") || n.contains("remote host identification has changed")
            ) return "The car's SSH host key changed. If that's expected (reinstalled OS), remove the old key with: ssh-keygen -R host";
            if (
                n.contains("command not found") || n.contains("not found")
            ) return "candump isn't installed on the car's computer. Install can-utils there (sudo apt install can-utils).";
            if (n.contains("no such device") || n.contains("siocgifindex")) return (
                "There's no interface named " + iface + " there. Check with: ip link"
            );
            if (n.contains("network is down")) return (
                iface + " is down. Bring it up first, e.g. sudo ip link set " + iface + " up type can bitrate 500000"
            );
            return noise;
        }

        void startReader(Process p, String where, String iface) {
            liveBtn.setText("Stop listening");
            stat.set("Connecting to " + where + "…", ToneLabel.MUTED);
            Thread t = new Thread(() -> {
                String lastNoise = null;
                int got = 0;
                try (
                    java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
                    )
                ) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        CanFrame f = parseCandumpLine(line);
                        if (f != null) {
                            pending.add(f);
                            if (got++ == 0) SwingUtilities.invokeLater(() ->
                                stat.set("Listening to " + where, ToneLabel.OK)
                            );
                            continue;
                        }
                        String l = line.strip();
                        if (
                            l.isEmpty() ||
                            l.startsWith("Warning: Permanently added") ||
                            l.matches("(?i)connection to .* closed\\.?")
                        ) continue;
                        lastNoise = l;
                    }
                } catch (IOException ignored) {}
                final String why = explain(lastNoise, iface);
                final int frames = got;
                final boolean userStopped = proc != p;
                SwingUtilities.invokeLater(() -> {
                    if (proc == p) proc = null;
                    liveBtn.setText("Listen live…");
                    if (userStopped) stat.set("Stopped listening to " + where, ToneLabel.MUTED);
                    else if (frames == 0 && why != null) {
                        stat.set("Couldn't listen to " + where + ": " + why, ToneLabel.ERR);
                        JOptionPane.showMessageDialog(
                            this,
                            why,
                            "Couldn't listen to " + where,
                            JOptionPane.WARNING_MESSAGE
                        );
                    } else stat.set(
                        "Connection to " + where + " ended" + (why != null ? ": " + why : ""),
                        why != null ? ToneLabel.WARN : ToneLabel.MUTED
                    );
                });
            }, "candump-live");
            t.setDaemon(true);
            t.start();
        }

        void stopLive() {
            Process p = proc;
            proc = null;
            if (p != null) p.destroy();
            if (liveBtn != null) liveBtn.setText("Listen live…");
        }

        void clear() {
            pending.clear();
            frames.clear();
            aggs.clear();
            t0 = Double.NaN;
            skippedLines = 0;
            rebuildView();
            detail.setText("");
        }

        void drain() {
            if (pending.isEmpty()) return;
            int start = view.size();
            int added = 0;
            CanFrame f;
            while (added < 20000 && (f = pending.poll()) != null) {
                if (Double.isNaN(t0) && !Double.isNaN(f.ts())) t0 = f.ts();
                frames.add(f);
                FrameCheck c = checkFrame(f, index);
                Agg a = aggs.computeIfAbsent(f.key(), k -> new Agg());
                a.count++;
                a.last = f;
                if (Double.isNaN(a.first)) a.first = f.ts();
                a.lastTs = f.ts();
                if (c.level() >= a.level) {
                    a.level = c.level();
                    a.text = c.text();
                }
                if (!perId.isSelected() && passes(f, c)) view.add(frames.size() - 1);
                added++;
            }
            if (frames.size() > 300000) {
                frames.subList(0, 100000).clear();
                rebuildView();
                return;
            }
            if (perId.isSelected()) rebuildAggView();
            else if (view.size() > start) {
                model.fireTableRowsInserted(start, view.size() - 1);
                if (follow.isSelected()) table.scrollRectToVisible(table.getCellRect(view.size() - 1, 0, true));
            }
            updateStat();
        }

        // ---- filtering and views

        String search(String key, SpecRef hit, CanFrame f) {
            return searchText.computeIfAbsent(key, k -> {
                StringBuilder b = new StringBuilder(f.idText())
                    .append(' ')
                    .append("0x")
                    .append(Long.toHexString(f.id()))
                    .append(' ');
                if (hit != null) {
                    Map<String, Object> m = hit.msg();
                    b.append(strOr(m, "desc", "")).append(' ');
                    for (Object o : listOrEmpty(m.get("fields"))) b.append(strOr(asMap(o), "name", "")).append(' ');
                }
                return b.toString().toLowerCase(Locale.ROOT);
            });
        }

        boolean passes(CanFrame f, FrameCheck c) {
            if (problemsOnly.isSelected() && c.level() == 0) return false;
            String q = filter.getText().strip().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) return true;
            return search(f.key(), c.hit(), f).contains(q) || f.bus().toLowerCase(Locale.ROOT).contains(q);
        }

        void rebuildView() {
            view.clear();
            if (!perId.isSelected()) for (int i = 0; i < frames.size(); i++) {
                CanFrame f = frames.get(i);
                if (passes(f, checkFrame(f, index))) view.add(i);
            }
            rebuildAggView();
            updateStat();
        }

        void rebuildAggView() {
            Object selKey = null;
            int sr = table.getSelectedRow();
            if (perId.isSelected() && sr >= 0 && sr < aggView.size()) selKey = aggView.get(sr).last.key();
            aggView.clear();
            if (perId.isSelected()) {
                for (Agg a : aggs.values()) if (passes(a.last, checkFrame(a.last, index))) aggView.add(a);
                aggView.sort(Comparator.comparing((Agg a) -> a.last.ext()).thenComparingLong(a -> a.last.id()));
            }
            model.fireTableDataChanged();
            if (selKey != null) for (int i = 0; i < aggView.size(); i++) if (aggView.get(i).last.key().equals(selKey)) {
                table.setRowSelectionInterval(i, i);
                break;
            }
        }

        void updateStat() {
            int unknown = 0,
                problems = 0;
            for (Agg a : aggs.values()) {
                if (a.text.equals("Unknown ID")) unknown++;
                else if (a.level == 2) problems++;
            }
            stat.set(
                frames.size() +
                    " frames, " +
                    aggs.size() +
                    " IDs" +
                    (unknown > 0 ? ", " + unknown + " unknown IDs" : "") +
                    (problems > 0 ? ", " + problems + " IDs with problems" : "") +
                    (proc != null ? "   (listening)" : ""),
                unknown + problems > 0 ? ToneLabel.WARN : ToneLabel.MUTED
            );
        }

        int rowLevel(int row) {
            if (perId.isSelected()) return row < aggView.size() ? aggView.get(row).level : 0;
            return row < view.size() ? checkFrame(frames.get(view.get(row)), index).level : 0;
        }

        Object frameValue(int i, int c) {
            CanFrame f = frames.get(i);
            FrameCheck ck = checkFrame(f, index);
            return switch (c) {
                case 0 -> i + 1;
                case 1 -> Double.isNaN(f.ts())
                    ? ""
                    : String.format(Locale.ROOT, "%.6f", Double.isNaN(t0) ? f.ts() : f.ts() - t0);
                case 2 -> f.bus();
                case 3 -> f.idText() + (f.fd() ? " FD" : "");
                case 4 -> ck.hit() == null ? "" : strOr(ck.hit().msg(), "desc", "");
                case 5 -> f.data().length;
                case 6 -> f.dataText();
                case 7 -> ck.hit() == null || f.rtr() ? "" : decodeFrame(ck.hit().msg(), f.data()).summary().toString();
                default -> ck.text();
            };
        }

        Object aggValue(Agg a, int c) {
            CanFrame f = a.last;
            FrameCheck ck = checkFrame(f, index);
            return switch (c) {
                case 0 -> f.idText() + (f.fd() ? " FD" : "");
                case 1 -> ck.hit() == null ? "" : strOr(ck.hit().msg(), "desc", "");
                case 2 -> a.count;
                case 3 -> {
                    double span = a.lastTs - a.first;
                    yield a.count < 2 || Double.isNaN(span) || span <= 0
                        ? ""
                        : fmtD(Math.round(((a.count - 1) / span) * 10) / 10.0);
                }
                case 4 -> f.data().length;
                case 5 -> f.dataText();
                case 6 -> ck.hit() == null || f.rtr() ? "" : decodeFrame(ck.hit().msg(), f.data()).summary().toString();
                default -> a.text;
            };
        }

        CanFrame selectedFrame() {
            int r = table.getSelectedRow();
            if (r < 0) return null;
            if (perId.isSelected()) return r < aggView.size() ? aggView.get(r).last : null;
            return r < view.size() ? frames.get(view.get(r)) : null;
        }

        void showDetail() {
            CanFrame f = selectedFrame();
            if (f == null) {
                detail.setText("");
                return;
            }
            FrameCheck c = checkFrame(f, index);
            StringBuilder b = new StringBuilder();
            b.append("ID ")
                .append(f.idText())
                .append(f.ext() ? " (extended)" : " (standard)")
                .append(f.fd() ? ", CAN FD" : "")
                .append(f.bus().isEmpty() ? "" : ", " + f.bus())
                .append(", ")
                .append(f.data().length)
                .append(" bytes\n");
            b.append("Status: ").append(c.text()).append('\n');
            b.append("Line:   ").append(f.line()).append('\n');
            if (c.hit() != null && !f.rtr()) {
                Map<String, Object> m = c.hit().msg();
                b.append("Spec:   ")
                    .append(strOr(m, "id", "?"))
                    .append("  ")
                    .append(strOr(m, "desc", ""))
                    .append("  (")
                    .append(app.relName(c.hit().file()))
                    .append(")\n\n");
                Decoded d = decodeFrame(m, f.data());
                b.append("Points\n");
                for (String[] p : d.points()) b.append("  #%-4s %-28s %-8s %-22s %s%n".formatted((Object[]) p));
                b.append("\nMQTT\n");
                for (String t : d.topics()) b.append("  ").append(t).append('\n');
            }
            detail.setText(b.toString());
            detail.setCaretPosition(0);
        }

        void openInEditor() {
            CanFrame f = selectedFrame();
            if (f == null) return;
            FrameCheck c = checkFrame(f, index);
            if (c.hit() == null) {
                stat.set("No spec message has ID " + f.idText(), ToneLabel.WARN);
                return;
            }
            app.selectMessage(c.hit().file(), c.hit().msg());
            app.toFront();
        }

        // ---- spec changes: re-check every frame against the edited spec

        void specChanged() {
            specTimer.restart();
        }

        void applySpecChange() {
            index = specIndex(app.files);
            searchText.clear();
            for (Agg a : aggs.values()) {
                a.level = 0;
                a.text = "OK";
            }
            for (CanFrame f : frames) {
                FrameCheck c = checkFrame(f, index);
                Agg a = aggs.get(f.key());
                if (a != null && c.level() >= a.level) {
                    a.level = c.level();
                    a.text = c.text();
                }
            }
            rebuildView();
            showDetail();
        }
    }

    // ======================================================================================
    // Animation: small timer-driven tweens (View > Animations turns them off)
    // ======================================================================================

    /** User settings, in a holder class so the command-line tools never touch preferences. */
    static final class Settings {

        static final Preferences NODE = migrate(Preferences.userRoot().node("tiresias"));

        /** Carries settings over from before the app was renamed (it was "CAN Spec Browser"), once. */
        static Preferences migrate(Preferences node) {
            try {
                Preferences root = Preferences.userRoot();
                if (node.keys().length == 0 && root.nodeExists("ner-cangen-spec-browser")) {
                    Preferences old = root.node("ner-cangen-spec-browser");
                    for (String k : old.keys()) node.put(k, old.get(k, ""));
                    node.flush();
                }
            } catch (java.util.prefs.BackingStoreException ignored) {} // nothing to carry over
            return node;
        }

        static boolean animations = NODE.getBoolean("animations", true);
    }

    /** Calls frame(t) as t eases from 0 to 1 over ms milliseconds (ease-out cubic), then done. Runs on the Swing thread. */
    static javax.swing.Timer animate(int ms, DoubleConsumer frame, Runnable done) {
        javax.swing.Timer timer = new javax.swing.Timer(15, null);
        if (!Settings.animations) {
            frame.accept(1);
            if (done != null) done.run();
            return timer;
        }
        long start = System.nanoTime();
        timer.addActionListener(e -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1e6 / ms);
            frame.accept(1 - Math.pow(1 - t, 3));
            if (t >= 1) {
                timer.stop();
                if (done != null) done.run();
            }
        });
        timer.setInitialDelay(0);
        timer.start();
        return timer;
    }

    /** Animates a 0..1 client property toward target and repaints: used for hover fades. */
    static void fadeTo(JComponent c, String key, double target) {
        double from = fade(c, key);
        if (from == target) return;
        if (c.getClientProperty(key + "#timer") instanceof javax.swing.Timer t) t.stop();
        c.putClientProperty(
            key + "#timer",
            animate(
                150,
                p -> {
                    c.putClientProperty(key, from + (target - from) * p);
                    c.repaint();
                },
                null
            )
        );
    }

    static double fade(JComponent c, String key) {
        return c.getClientProperty(key) instanceof Double d ? d : 0;
    }

    /** Wraps content so it can fade in, e.g. when the editor switches to another message. */
    static final class FadeUI extends javax.swing.plaf.LayerUI<JComponent> {

        float alpha = 1f;
        javax.swing.Timer timer;

        void fadeIn(JLayer<?> layer) {
            if (timer != null) timer.stop();
            timer = animate(
                200,
                t -> {
                    alpha = (float) (0.2 + 0.8 * t);
                    layer.repaint();
                },
                () -> alpha = 1f
            );
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            if (alpha >= 1f) {
                super.paint(g, c);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
            super.paint(g2, c);
            g2.dispose();
        }
    }

    /** Runs change, fading a snapshot of the window's old look out over the new one. */
    static void crossfade(JRootPane root, int ms, Runnable change) {
        JLayeredPane lp = root.getLayeredPane();
        if (!Settings.animations || !root.isShowing() || lp.getWidth() <= 0) {
            change.run();
            return;
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            lp.getWidth(),
            lp.getHeight(),
            java.awt.image.BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = img.createGraphics();
        lp.paint(g);
        g.dispose();
        float[] alpha = { 1f };
        JComponent ghost = new JComponent() {
            @Override
            protected void paintComponent(Graphics gg) {
                Graphics2D g2 = (Graphics2D) gg.create();
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha[0]));
                g2.drawImage(img, 0, 0, null);
                g2.dispose();
            }
        };
        ghost.setBounds(0, 0, lp.getWidth(), lp.getHeight());
        lp.add(ghost, JLayeredPane.DRAG_LAYER);
        change.run();
        animate(
            ms,
            t -> {
                alpha[0] = (float) (1 - t);
                ghost.repaint();
            },
            () -> {
                lp.remove(ghost);
                lp.repaint();
            }
        );
    }

    /** Glass pane shown while files load: blocks input and, if loading takes a moment, fades in a spinner. */
    static final class LoadingOverlay extends JComponent {

        String text = "";
        double angle, alpha;
        final javax.swing.Timer spin = new javax.swing.Timer(16, e -> {
            angle += 0.11;
            repaint();
        });
        final javax.swing.Timer reveal = new javax.swing.Timer(150, e ->
            animate(
                220,
                t -> {
                    alpha = t;
                    repaint();
                },
                null
            )
        );

        LoadingOverlay() {
            setOpaque(false);
            setVisible(false); // a visible glass pane would swallow every click; it's only shown while loading
            addMouseListener(new MouseAdapter() {});
            addMouseMotionListener(new MouseMotionAdapter() {});
            addKeyListener(new KeyAdapter() {});
            setFocusTraversalKeysEnabled(false);
            reveal.setRepeats(false);
        }

        void start(String message) {
            text = message;
            alpha = 0;
            setVisible(true);
            requestFocusInWindow();
            spin.start();
            reveal.restart();
        }

        void stop() {
            reveal.stop();
            double from = alpha;
            animate(
                180,
                t -> {
                    alpha = from * (1 - t);
                    repaint();
                },
                () -> {
                    spin.stop();
                    setVisible(false);
                }
            );
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (alpha <= 0 || T == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
            g.setColor(alpha(T.panel(), 190));
            g.fillRect(0, 0, getWidth(), getHeight());
            int w = 240,
                h = 132,
                x = (getWidth() - w) / 2,
                y = (getHeight() - h) / 2;
            g.setColor(T.alt());
            g.fillRoundRect(x, y, w, h, 16, 16);
            g.setColor(T.border());
            g.drawRoundRect(x, y, w - 1, h - 1, 16, 16);
            int d = 36,
                cx = x + (w - d) / 2,
                cy = y + 26;
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(T.border());
            g.drawOval(cx, cy, d, d);
            g.setColor(T.accent());
            int startDeg = (int) Math.toDegrees(-angle) % 360;
            g.drawArc(cx, cy, d, d, startDeg, 100 + (int) (60 * Math.sin(angle * 0.7)));
            g.setFont(getFont() == null ? Theme.UI : getFont());
            g.setColor(T.fg());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + h - 30);
            g.dispose();
        }
    }

    // ======================================================================================
    // App icon: drawn in code so it works everywhere (even run from source). A custom icon.png bundled into the
    // jar by the Gradle build replaces it, and --export-icons writes the .png/.icns/.ico files the packages need.
    // ======================================================================================

    static final class Icons {

        private static java.awt.image.BufferedImage custom;
        private static boolean customChecked;

        /** The team's own icon, if the build bundled an icon.png into the jar. */
        static synchronized java.awt.image.BufferedImage custom() {
            if (!customChecked) {
                customChecked = true;
                try (var in = Tiresias.class.getResourceAsStream("/icon.png")) {
                    if (in != null) custom = javax.imageio.ImageIO.read(in);
                } catch (IOException ignored) {}
            }
            return custom;
        }

        /**
         * Apple's continuous-corner rounded square (the macOS "squircle"): unlike a plain rounded rectangle, the curve
         * starts about 1.53 radii along each edge and eases in, which is what makes macOS icons look softer.
         */
        static java.awt.geom.Path2D squircle(double x, double y, double size, double r) {
            r = Math.min(r, size / (2 * 1.52866483));
            // one corner's control points, as distances (along, across) from the corner, in units of r
            double[] a = {
                1.52866483,
                1.08849323,
                0.86840689,
                0.66993427,
                0.37282392,
                0.16905899,
                0.06549600,
                0,
                0,
                0,
            };
            double[] b = {
                0,
                0,
                0,
                0.06549600,
                0.16905899,
                0.37282392,
                0.66993427,
                0.86840689,
                1.08849323,
                1.52866483,
            };
            var p = new java.awt.geom.Path2D.Double();
            double[][] corners = {
                { x + size, y, -1, 1, 0 },
                { x + size, y + size, -1, -1, 1 },
                { x, y + size, 1, -1, 0 },
                { x, y, 1, 1, 1 },
            };
            for (double[] c : corners) {
                double[] px = new double[10],
                    py = new double[10];
                for (int k = 0; k < 10; k++) {
                    double u = (c[4] == 0 ? a[k] : b[k]) * r,
                        v = (c[4] == 0 ? b[k] : a[k]) * r;
                    px[k] = c[0] + c[2] * u;
                    py[k] = c[1] + c[3] * v;
                }
                if (p.getCurrentPoint() == null) p.moveTo(px[0], py[0]);
                else p.lineTo(px[0], py[0]);
                for (int k = 1; k < 10; k += 3) p.curveTo(px[k], py[k], px[k + 1], py[k + 1], px[k + 2], py[k + 2]);
            }
            p.closePath();
            return p;
        }

        /** The built-in icon, on Apple's macOS template: an 824/1024 continuous-corner tile with a soft drop shadow. */
        static java.awt.image.BufferedImage draw(int size) {
            var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double s = size / 1024.0;
            double inset = size <= 32 ? 0 : 100 * s; // the template's margin, dropped at tiny sizes for legibility
            double tile = size - 2 * inset;
            var shape = squircle(inset, inset, tile, (tile * 185.4) / 824);
            if (size >= 64) {
                // the template's drop shadow: 28px blur, 12px down, at 1024
                var shadow = new java.awt.image.BufferedImage(
                    size,
                    size,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE
                );
                Graphics2D sg = shadow.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                sg.setColor(new Color(0, 0, 0, 110));
                sg.translate(0, 12 * s);
                sg.fill(shape);
                sg.dispose();
                int k = Math.max(3, (int) Math.round(28 * s) | 1);
                float[] w = new float[k];
                Arrays.fill(w, 1f / k);
                for (var kernel : List.of(new java.awt.image.Kernel(k, 1, w), new java.awt.image.Kernel(1, k, w)))
                    shadow = new java.awt.image.ConvolveOp(kernel, java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(
                        shadow,
                        null
                    );
                g.drawImage(shadow, 0, 0, null);
            }
            g.setPaint(
                new GradientPaint(0, (float) inset, new Color(0x2C313A), 0, (float) (inset + tile), new Color(0x1E2227))
            );
            g.fill(shape);
            g.setColor(new Color(255, 255, 255, 28));
            g.setStroke(new BasicStroke((float) Math.max(1, 6 * s)));
            g.draw(shape);
            // three rows of blocks; widths are in eighths of a row, like bits in a byte
            int[][] rows = { { 5, 3 }, { 2, 6 }, { 3, 2, 3 } };
            Color[][] colors = {
                { new Color(0x61AFEF), new Color(0x98C379) },
                { new Color(0xC678DD), new Color(0xD19A66) },
                { new Color(0x56B6C2), new Color(0xE5C07B), new Color(0xE06C75) },
            };
            double pad = tile * 0.17,
                gap = tile * 0.045,
                rowH = (tile - 2 * pad - 2 * gap) / 3,
                unit = (tile - 2 * pad) / 8;
            for (int row = 0; row < rows.length; row++) {
                double x = inset + pad,
                    y = inset + pad + row * (rowH + gap);
                for (int k = 0; k < rows[row].length; k++) {
                    double w = rows[row][k] * unit - (k < rows[row].length - 1 ? gap : 0);
                    g.setColor(colors[row][k]);
                    g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, w, rowH, rowH * 0.45, rowH * 0.45));
                    x += rows[row][k] * unit;
                }
            }
            g.dispose();
            return img;
        }

        static java.awt.image.BufferedImage image(int size) {
            var c = custom();
            if (c == null) return draw(size);
            var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(c, 0, 0, size, size, null);
            g.dispose();
            return img;
        }

        /** Window/taskbar icons at the sizes each OS asks for, plus the macOS Dock icon. */
        static void install(Window w) {
            w.setIconImages(
                List.of(16, 20, 24, 32, 40, 48, 64, 128, 256)
                    .stream()
                    .map(n -> (Image) image(n))
                    .toList()
            );
            setDockIcon();
            // On macOS, Java resets the Dock to its default icon once its own startup finishes, which can come after
            // the call above; setting it again when the window opens makes it stick.
            w.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowOpened(WindowEvent e) {
                        setDockIcon();
                    }
                }
            );
        }

        static void setDockIcon() {
            try {
                if (
                    Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)
                ) Taskbar.getTaskbar().setIconImage(image(512));
            } catch (RuntimeException ignored) {} // some desktops refuse; the window icon still applies
        }

        static byte[] png(int size) throws IOException {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image(size), "png", out);
            return out.toByteArray();
        }

        /** {@code --export-icons <dir> [custom.png]}: writes icon.png (1024), icon-256.png, icon.icns (macOS) and icon.ico (Windows). */
        static int export(List<String> args) {
            if (args.isEmpty()) {
                System.err.println("Usage: --export-icons <output-dir> [source.png]");
                return 2;
            }
            try {
                Path dir = Files.createDirectories(Paths.get(args.getFirst()));
                if (args.size() > 1) {
                    custom = javax.imageio.ImageIO.read(Paths.get(args.get(1)).toFile());
                    customChecked = true;
                    if (custom == null) {
                        System.err.println("Not a readable image: " + args.get(1));
                        return 2;
                    }
                }
                Files.write(dir.resolve("icon.png"), png(1024));
                Files.write(dir.resolve("icon-256.png"), png(256));
                // ICNS: big-endian "icns" + total length, then (type, length, PNG data) entries
                String[][] icns = {
                    { "icp4", "16" },
                    { "icp5", "32" },
                    { "icp6", "64" },
                    { "ic07", "128" },
                    { "ic08", "256" },
                    { "ic09", "512" },
                    { "ic10", "1024" },
                    { "ic11", "32" },
                    { "ic12", "64" },
                    { "ic13", "256" },
                    { "ic14", "512" },
                };
                var body = new java.io.ByteArrayOutputStream();
                for (String[] e : icns) {
                    byte[] data = png(Integer.parseInt(e[1]));
                    body.write(e[0].getBytes(StandardCharsets.US_ASCII));
                    body.write(
                        java.nio.ByteBuffer.allocate(4)
                            .putInt(8 + data.length)
                            .array()
                    );
                    body.write(data);
                }
                var icnsFile = java.nio.ByteBuffer.allocate(8 + body.size())
                    .put("icns".getBytes(StandardCharsets.US_ASCII))
                    .putInt(8 + body.size())
                    .put(body.toByteArray());
                Files.write(dir.resolve("icon.icns"), icnsFile.array());
                // ICO: little-endian header and directory, PNG-compressed images (Windows Vista and later)
                int[] sizes = { 16, 24, 32, 48, 64, 128, 256 };
                List<byte[]> images = new ArrayList<>();
                for (int n : sizes) images.add(png(n));
                int offset = 6 + 16 * sizes.length,
                    total =
                        offset +
                        images
                            .stream()
                            .mapToInt(b -> b.length)
                            .sum();
                var ico = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                ico.putShort((short) 0)
                    .putShort((short) 1)
                    .putShort((short) sizes.length);
                for (int i = 0; i < sizes.length; i++) {
                    int n = sizes[i] >= 256 ? 0 : sizes[i];
                    ico.put((byte) n)
                        .put((byte) n)
                        .put((byte) 0)
                        .put((byte) 0)
                        .putShort((short) 1)
                        .putShort((short) 32)
                        .putInt(images.get(i).length)
                        .putInt(offset);
                    offset += images.get(i).length;
                }
                for (byte[] b : images) ico.put(b);
                Files.write(dir.resolve("icon.ico"), ico.array());
                System.out.println("Wrote icon.png, icon-256.png, icon.icns and icon.ico to " + dir.toAbsolutePath());
                return 0;
            } catch (IOException e) {
                System.err.println("Couldn't export icons: " + e.getMessage());
                return 2;
            }
        }
    }

    // ======================================================================================
    // Small UI helpers
    // ======================================================================================

    record SevIcon(Severity s) implements Icon {
        static final SevIcon ERR = new SevIcon(Severity.ERROR),
            WRN = new SevIcon(Severity.WARN),
            INF = new SevIcon(Severity.INFO),
            OK = new SevIcon(null);

        static Icon of(Severity s) {
            if (s == null) return OK;
            return switch (s) {
                case ERROR -> ERR;
                case WARN -> WRN;
                case INFO -> INF;
            };
        }

        public void paintIcon(Component comp, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            if (s == null) {
                g2.setColor(alpha(T.green(), 150));
                g2.fillOval(x + 3, y + 3, 6, 6);
            } else {
                g2.setColor(s.color());
                g2.fillOval(x + 1, y + 1, 10, 10);
            }
            g2.dispose();
        }

        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }
    }

    static final class HintField extends JTextField {

        final String hint;

        HintField(String hint, int cols) {
            super(cols);
            this.hint = hint;
            addFocusListener(
                new FocusAdapter() {
                    public void focusGained(FocusEvent e) {
                        repaint();
                    }

                    public void focusLost(FocusEvent e) {
                        repaint();
                    }
                }
            );
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(T.muted());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint, in.left + 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        }
    }

    @SuppressWarnings("MagicConstant") // style is always Font.PLAIN or Font.BOLD at call sites
    static Font mono(int style, float size) {
        Theme.initFonts();
        return Theme.MONO.deriveFont(style, size);
    }

    static JButton button(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        if (tip != null) b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    static JComboBox<String> editableCombo(Collection<String> items) {
        JComboBox<String> c = new JComboBox<>(items.toArray(new String[0]));
        c.setEditable(true);
        return c;
    }

    interface RowStyle {
        void style(JTable t, int row, int col, boolean sel, JComponent c);
    }

    static final class StyledRenderer extends DefaultTableCellRenderer {

        final RowStyle rs;

        StyledRenderer(RowStyle rs) {
            this.rs = rs;
        }

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            setToolTipText(null);
            setFont(t.getFont());
            setForeground(sel ? T.bright() : T.fg());
            setBackground(sel ? T.selection() : row % 2 == 1 ? T.alt() : T.panel());
            if (rs != null) rs.style(t, row, col, sel, this);
            return this;
        }
    }

    static final class StyledBoolRenderer extends JCheckBox implements TableCellRenderer {

        final RowStyle rs;

        StyledBoolRenderer(RowStyle rs) {
            this.rs = rs;
            setHorizontalAlignment(CENTER);
            setBorderPainted(false);
            setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            setSelected(Boolean.TRUE.equals(v));
            setToolTipText(null);
            setForeground(sel ? T.bright() : T.fg());
            setBackground(sel ? T.selection() : row % 2 == 1 ? T.alt() : T.panel());
            rs.style(t, row, col, sel, this);
            return this;
        }
    }

    static void refreshTable(JTable t, AbstractTableModel m) {
        int[] sel = t.getSelectedRows();
        m.fireTableDataChanged();
        for (int r : sel) if (r < t.getRowCount()) t.addRowSelectionInterval(r, r);
    }

    static void selectRow(JTable t, int r) {
        if (r < 0 || r >= t.getRowCount()) return;
        t.setRowSelectionInterval(r, r);
        t.scrollRectToVisible(t.getCellRect(r, 0, true));
    }

    static void stopEditing(JTable t) {
        if (
            t.isEditing() && t.getCellEditor() != null && !t.getCellEditor().stopCellEditing()
        ) t.getCellEditor().cancelCellEditing();
    }

    static final String[] SEARCH_MODES = { "All", "ID", "Description", "Topic", "Point name", "Unit", "Doc / notes" };
    static final List<String> C_TYPES = List.of(
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
    static final List<String> COMMON_UNITS = List.of(
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

    // ======================================================================================
    // Main window
    // ======================================================================================

    record FileNode(App app, SpecFile f) {
        @Override
        public String toString() {
            return app.relName(f) + (f.dirty ? " *" : "") + "   (" + f.messages.size() + ")";
        }
    }

    record MsgNode(SpecFile f, Map<String, Object> m) {
        @Override
        public String toString() {
            return strOr(m, "id", "(no id)") + "   " + strOr(m, "desc", "");
        }
    }

    static final class App extends JFrame {

        final List<SpecFile> files = new ArrayList<>();
        List<Path> lastInputs = new ArrayList<>();
        Path baseDir;
        List<Issue> issues = new ArrayList<>();
        final Map<Map<String, Object>, Severity> msgSev = new IdentityHashMap<>();
        final Map<SpecFile, Severity> fileSev = new IdentityHashMap<>();
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("spec");
        final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        final JTree tree = new FullRowTree(treeModel);
        final HintField search = new HintField("Search messages…  (Ctrl+F)", 18);
        final JComboBox<String> searchMode = new JComboBox<>(SEARCH_MODES);
        final JCheckBox onlyIssues = new JCheckBox("Only with problems");
        final JCheckBox sortById = new JCheckBox("Sort by ID");
        final MessageEditor editor;
        final IssuesPanel issuesPanel;
        final JLabel status = new JLabel(" ");
        final JLabel statusRight = new JLabel(" ");
        final Preferences prefs = Settings.NODE;
        final LoadingOverlay overlay = new LoadingOverlay();
        final javax.swing.Timer searchTimer;
        boolean rebuilding,
            firstBuild = true,
            rebuildScheduled;
        SpecFile selectedFileOnly;

        App() {
            super("Tiresias");
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            editor = new MessageEditor(this);
            issuesPanel = new IssuesPanel(this);

            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(
                new DefaultTreeCellRenderer() {
                    public Component getTreeCellRendererComponent(
                        JTree t,
                        Object v,
                        boolean sel,
                        boolean exp,
                        boolean leaf,
                        int row,
                        boolean foc
                    ) {
                        super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, false);
                        setBorderSelectionColor(null);
                        setBackgroundSelectionColor(null);
                        setBackgroundNonSelectionColor(null);
                        setBackground(T.window());
                        setOpaque(false);
                        setIconTextGap(7);
                        Object u = ((DefaultMutableTreeNode) v).getUserObject();
                        Font base = t.getFont();
                        setForeground(sel ? T.bright() : T.fg());
                        if (u instanceof FileNode fn) {
                            SpecFile f = fn.f();
                            setIcon(SevIcon.of(fileSev.get(f)));
                            setFont(base.deriveFont(Font.BOLD));
                            setText(
                                "<html>" +
                                    escHtml(relName(f)) +
                                    "&nbsp;&nbsp;<font color='" +
                                    hx(T.muted()) +
                                    "'>" +
                                    f.messages.size() +
                                    "</font>" +
                                    (f.dirty
                                        ? "&nbsp;&nbsp;<font color='" + hx(T.orange()) + "'>● unsaved</font>"
                                        : "") +
                                    "</html>"
                            );
                            setToolTipText(f.path.toString() + (f.loadError != null ? " — " + f.loadError : ""));
                        } else if (u instanceof MsgNode mn) {
                            setIcon(SevIcon.of(msgSev.get(mn.m())));
                            setFont(base);
                            setText(
                                "<html><font face='" +
                                    Theme.MONO.getFamily() +
                                    "' color='" +
                                    hx(sel ? T.bright() : T.accent()) +
                                    "'>" +
                                    escHtml(strOr(mn.m(), "id", "(no id)")) +
                                    "</font>&nbsp;&nbsp;" +
                                    escHtml(strOr(mn.m(), "desc", "")) +
                                    "</html>"
                            );
                            setToolTipText(null);
                        }
                        return this;
                    }
                }
            );
            ToolTipManager.sharedInstance().registerComponent(tree);
            tree.addTreeSelectionListener(e -> {
                if (rebuilding) return;
                Object u = selectedUserObject();
                if (u instanceof MsgNode(SpecFile f, Map<String, Object> m)) {
                    selectedFileOnly = null;
                    editor.load(f, m);
                } else if (u instanceof FileNode fn) {
                    selectedFileOnly = fn.f();
                    editor.showFile(selectedFileOnly);
                }
            });
            tree.addMouseListener(
                new MouseAdapter() {
                    public void mousePressed(MouseEvent e) {
                        popup(e);
                    }

                    public void mouseReleased(MouseEvent e) {
                        popup(e);
                    }

                    void popup(MouseEvent e) {
                        if (!e.isPopupTrigger()) return;
                        TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
                        if (tp != null) tree.setSelectionPath(tp);
                        JPopupMenu pm = new JPopupMenu();
                        pm.add(item("New message", null, App.this::newMessage));
                        pm.add(item("Duplicate message", null, App.this::duplicateMessage));
                        pm.add(item("Delete message", null, App.this::deleteMessage));
                        pm.addSeparator();
                        pm.add(item("Save this file", null, App.this::saveCurrent));
                        pm.show(tree, e.getX(), e.getY());
                    }
                }
            );
            tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteMsg");
            tree.getActionMap().put(
                "deleteMsg",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        deleteMessage();
                    }
                }
            );

            searchTimer = new javax.swing.Timer(180, e -> {
                editor.setHighlight(search.getText().strip(), searchMode.getSelectedIndex());
                rebuildTree();
            });
            searchTimer.setRepeats(false);
            search.getDocument().addDocumentListener(
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        searchTimer.restart();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        searchTimer.restart();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        searchTimer.restart();
                    }
                }
            );
            search.addKeyListener(
                new KeyAdapter() {
                    public void keyPressed(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) search.setText("");
                        if (e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_ENTER) {
                            tree.requestFocusInWindow();
                            if (tree.getSelectionCount() == 0) selectFirstMessage();
                        }
                    }
                }
            );
            searchMode.addActionListener(e -> searchTimer.restart());
            onlyIssues.addActionListener(e -> rebuildTree());
            sortById.addActionListener(e -> rebuildTree());

            JPanel searchRow = new JPanel(new BorderLayout(4, 0));
            searchRow.setOpaque(false);
            onlyIssues.setOpaque(false);
            sortById.setOpaque(false);
            searchRow.add(searchMode, BorderLayout.WEST);
            searchRow.add(search, BorderLayout.CENTER);
            searchRow.add(button("×", "Clear search", () -> search.setText("")), BorderLayout.EAST);
            JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            opts.setOpaque(false);
            opts.add(onlyIssues);
            opts.add(sortById);
            JPanel leftTop = new JPanel(new BorderLayout(0, 6));
            leftTop.setOpaque(false);
            leftTop.add(searchRow, BorderLayout.NORTH);
            leftTop.add(opts, BorderLayout.SOUTH);
            JPanel left = new Surface(new BorderLayout(0, 6), 0);
            left.setBorder(new EmptyBorder(10, 10, 6, 4));
            left.add(leftTop, BorderLayout.NORTH);
            JScrollPane treeScroll = new JScrollPane(tree) {
                @Override
                public Color getBackground() {
                    return T == null ? super.getBackground() : T.window();
                }
            };
            left.add(treeScroll, BorderLayout.CENTER);
            left.setMinimumSize(new Dimension(220, 100));

            JSplitPane h = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, editor);
            h.setDividerLocation(prefs.getInt("hdiv", 340));
            h.setContinuousLayout(true);
            JSplitPane v = new JSplitPane(JSplitPane.VERTICAL_SPLIT, h, issuesPanel);
            v.setResizeWeight(0.75);
            v.setDividerLocation(prefs.getInt("vdiv", 560));
            v.setContinuousLayout(true);

            JPanel statusBar = new Surface(new BorderLayout(), 0);
            statusBar.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
            status.setForeground(null);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(
                "permanentFocusOwner",
                e -> {
                    for (Object o : new Object[] { e.getOldValue(), e.getNewValue() }) {
                        if (!(o instanceof Component c)) continue;
                        c.repaint();
                        Container par = c.getParent();
                        if (par instanceof JComboBox) par.repaint();
                    }
                }
            );
            statusBar.add(status, BorderLayout.CENTER);
            statusBar.add(statusRight, BorderLayout.EAST);

            getContentPane().add(v, BorderLayout.CENTER);
            getContentPane().add(statusBar, BorderLayout.SOUTH);
            setJMenuBar(buildMenu());
            setGlassPane(overlay);
            Icons.install(this);

            setTransferHandler(fileDrop(ps -> openPaths(ps, files.isEmpty())));

            addWindowListener(
                new WindowAdapter() {
                    public void windowClosing(WindowEvent e) {
                        editor.commitAll();
                        if (discardCancelled()) return;
                        Rectangle r = getBounds();
                        prefs.putInt("x", r.x);
                        prefs.putInt("y", r.y);
                        prefs.putInt("w", r.width);
                        prefs.putInt("h", r.height);
                        prefs.putInt("hdiv", h.getDividerLocation());
                        prefs.putInt("vdiv", v.getDividerLocation());
                        dispose();
                        System.exit(0);
                    }
                }
            );

            int w = prefs.getInt("w", 1280),
                hh = prefs.getInt("h", 820);
            setSize(w, hh);
            int x = prefs.getInt("x", -1),
                y = prefs.getInt("y", -1);
            if (x >= 0 && y >= 0 && isOnScreen(new Rectangle(x, y, w, hh))) setLocation(x, y);
            else setLocationRelativeTo(null);
            updateTitle();
            updateUndoItems();
        }

        static boolean isOnScreen(Rectangle r) {
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (gd.getDefaultConfiguration().getBounds().intersects(r)) return true;
            return false;
        }

        JMenuItem item(String text, KeyStroke ks, Runnable r) {
            JMenuItem i = new JMenuItem(text);
            if (ks != null) i.setAccelerator(ks);
            i.addActionListener(e -> r.run());
            return i;
        }

        JMenuBar buildMenu() {
            int M = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            JMenuBar mb = new JMenuBar();
            JMenu file = new JMenu("File");
            file.add(item("Open folder…", KeyStroke.getKeyStroke(KeyEvent.VK_O, M), this::chooseFolder));
            file.add(
                item(
                    "Add files…",
                    KeyStroke.getKeyStroke(KeyEvent.VK_O, M | InputEvent.SHIFT_DOWN_MASK),
                    this::chooseFiles
                )
            );
            file.add(item("New spec file…", null, this::newSpecFile));
            file.add(item("Reload from disk", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), this::reload));
            file.addSeparator();
            file.add(item("Save file", KeyStroke.getKeyStroke(KeyEvent.VK_S, M), this::saveCurrent));
            file.add(
                item("Save all", KeyStroke.getKeyStroke(KeyEvent.VK_S, M | InputEvent.SHIFT_DOWN_MASK), this::saveAll)
            );
            file.addSeparator();
            file.add(item("Exit", null, () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
            JMenu edit = new JMenu("Edit");
            undoItem = item("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, M), this::undo);
            redoItem = item("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, M | InputEvent.SHIFT_DOWN_MASK), this::redo);
            edit.add(undoItem);
            edit.add(redoItem);
            getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, M), "redoY");
            getRootPane().getActionMap().put(
                "redoY",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        redo();
                    }
                }
            );
            edit.addSeparator();
            edit.add(item("New message", KeyStroke.getKeyStroke(KeyEvent.VK_N, M), this::newMessage));
            edit.add(item("Duplicate message", KeyStroke.getKeyStroke(KeyEvent.VK_D, M), this::duplicateMessage));
            edit.add(item("Delete message", null, this::deleteMessage));
            edit.addSeparator();
            edit.add(
                item("Find", KeyStroke.getKeyStroke(KeyEvent.VK_F, M), () -> {
                    search.requestFocusInWindow();
                    search.selectAll();
                })
            );
            JMenu view = new JMenu("View");
            JMenu themes = new JMenu("Theme");
            ButtonGroup tg = new ButtonGroup();
            String cur = prefs.get("theme", "One Dark");
            for (String nm : Theme.names()) {
                JRadioButtonMenuItem mi = new JRadioButtonMenuItem(nm, nm.equals(cur));
                mi.addActionListener(e -> setTheme(nm));
                tg.add(mi);
                themes.add(mi);
            }
            view.add(themes);
            JCheckBoxMenuItem anim = new JCheckBoxMenuItem("Animations", Settings.animations);
            anim.addActionListener(e -> {
                Settings.animations = anim.isSelected();
                prefs.putBoolean("animations", Settings.animations);
            });
            view.add(anim);
            JMenu tools = new JMenu("Tools");
            tools.add(
                item("Validate now", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), () -> {
                    editor.commitAll();
                    revalidateAll();
                    rebuildTree();
                })
            );
            tools.add(item("Find free CAN IDs…", null, this::findFreeIds));
            tools.add(item("Fix outdated keys in all files", null, this::fixOutdatedKeys));
            tools.add(item("ID map (all messages)…", KeyStroke.getKeyStroke(KeyEvent.VK_I, M), this::showIdMap));
            tools.add(
                item(
                    "Decode CAN traffic (candump)…",
                    KeyStroke.getKeyStroke(KeyEvent.VK_T, M | InputEvent.SHIFT_DOWN_MASK),
                    this::openTraffic
                )
            );
            JMenu help = new JMenu("Help");
            help.add(item("Spec rules checked", null, this::showRules));
            help.add(
                item("About", null, () ->
                    JOptionPane.showMessageDialog(
                        this,
                        "Tiresias\nBrowse, search, edit and validate Calypso CAN spec JSON,\nand see what will break the firmware build before it does.\n\nNamed for the prophet who warns Odysseus of the dangers ahead.\n\nJava " +
                            System.getProperty("java.version"),
                        "About",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                )
            );
            mb.add(file);
            mb.add(edit);
            mb.add(view);
            mb.add(tools);
            mb.add(help);
            return mb;
        }

        void setTheme(String name) {
            editor.commitAll();
            crossfade(getRootPane(), 320, () -> {
                Theme.apply(name);
                prefs.put("theme", name);
                revalidateAll();
                editor.themeChanged();
                rebuildTree();
            });
            status.setText("Theme: " + name);
        }

        // ---------- opening / saving

        static List<Path> initialPaths(List<String> args) {
            List<Path> ps = new ArrayList<>();
            if (!args.isEmpty()) for (String a : args) ps.add(Paths.get(a));
            else for (String s : Settings.NODE.get("lastPaths", "").split(Pattern.quote(File.pathSeparator))) {
                if (!s.isEmpty() && Files.exists(Paths.get(s))) ps.add(Paths.get(s));
            }
            return ps;
        }

        void openInitial(List<Path> ps, CompletableFuture<Loader.Loaded> preload) {
            if (preload == null) {
                editor.showWelcome();
                return;
            }
            overlay.start("Loading spec files…");
            preload.whenComplete((r, ex) ->
                SwingUtilities.invokeLater(() -> {
                    overlay.stop();
                    applyLoaded(r != null ? r : Loader.Loaded.failed(String.valueOf(ex)), ps, true);
                })
            );
        }

        void chooseFolder() {
            JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Open a folder of CAN spec JSON files");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openPaths(
                Collections.singletonList(fc.getSelectedFile().toPath()),
                true
            );
        }

        void chooseFiles() {
            JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON spec files", "json"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                List<Path> ps = new ArrayList<>();
                for (File f : fc.getSelectedFiles()) ps.add(f.toPath());
                openPaths(ps, files.isEmpty());
            }
        }

        void openPaths(List<Path> paths, boolean replace) {
            openPaths(paths, replace, null);
        }

        /** Loads files on a background thread behind the loading overlay, then runs after (if any) on the Swing thread. */
        void openPaths(List<Path> paths, boolean replace, Runnable after) {
            editor.commitAll();
            if (replace && discardCancelled()) return;
            List<SpecFile> keep = replace ? List.of() : List.copyOf(files);
            overlay.start(replace ? "Loading spec files…" : "Adding files…");
            Thread.ofVirtual()
                .name("spec-loader")
                .start(() -> {
                    Loader.Loaded r = Loader.loadAll(paths, keep);
                    SwingUtilities.invokeLater(() -> {
                        overlay.stop();
                        if (applyLoaded(r, paths, replace) && after != null) after.run();
                    });
                });
        }

        boolean applyLoaded(Loader.Loaded r, List<Path> paths, boolean replace) {
            if (r.error() != null) {
                JOptionPane.showMessageDialog(this, r.error(), "Cannot open", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            if (replace) {
                files.clear();
                lastInputs = new ArrayList<>();
                editor.clear();
                undoStack.clear();
                redoStack.clear();
                snap.clear();
                savedText.clear();
            }
            for (Path p : paths) {
                Path n = p.toAbsolutePath().normalize();
                if (!lastInputs.contains(n)) lastInputs.add(n);
            }
            files.addAll(r.added());
            snap.putAll(r.snapshots());
            savedText.putAll(r.snapshots());
            baseDir =
                lastInputs.size() == 1 && Files.isDirectory(lastInputs.getFirst())
                    ? lastInputs.getFirst()
                    : Loader.commonParent(
                          files
                              .stream()
                              .map(f -> f.path)
                              .toList()
                      );
            files.sort(Comparator.comparing(f -> relName(f).toLowerCase(Locale.ROOT)));
            prefs.put("lastPaths", String.join(File.pathSeparator, lastInputs.stream().map(Path::toString).toList()));
            Path first = lastInputs.isEmpty() ? null : lastInputs.getFirst();
            if (first != null) prefs.put("lastDir", (Files.isDirectory(first) ? first : first.getParent()).toString());
            families.clear();
            families.putAll(r.families());
            if (traffic != null) traffic.specChanged();
            applyIssues(r.issues());
            updateUndoItems();
            firstBuild = true;
            rebuildTree();
            if (editor.msg == null) editor.showOverview();
            int msgs = files
                .stream()
                .mapToInt(f -> f.messages.size())
                .sum();
            status.setText(
                "Loaded " +
                    files.size() +
                    " file(s), " +
                    msgs +
                    " messages" +
                    (r.added().isEmpty() && !replace ? " (nothing new)" : "") +
                    (r.skipped() > 0 ? " · skipped " + r.skipped() + " JSON file(s) that aren't message lists" : "")
            );
            updateTitle();
            return true;
        }

        void reload() {
            if (lastInputs.isEmpty()) return;
            editor.commitAll();
            if (discardCancelled()) return;
            for (SpecFile f : files) f.dirty = false;
            Map<String, Object> sel = editor.msg;
            String selId = sel == null ? null : str(sel, "id");
            Path selPath = editor.file == null ? null : editor.file.path;
            openPaths(new ArrayList<>(lastInputs), true, () -> {
                if (selId != null) for (SpecFile f : files)
                    if (f.path.equals(selPath)) for (Object o : f.messages)
                        if (selId.equals(str(asMap(o), "id"))) {
                            selectMessage(f, asMap(o));
                            return;
                        }
            });
        }

        /** Asks what to do with unsaved changes; true if the user canceled (or saving failed), so the caller should stop. */
        boolean discardCancelled() {
            List<SpecFile> dirty = new ArrayList<>();
            for (SpecFile f : files) if (f.dirty) dirty.add(f);
            if (dirty.isEmpty()) return false;
            StringBuilder sb = new StringBuilder("Unsaved changes in:\n");
            for (SpecFile f : dirty) sb.append("   ").append(relName(f)).append('\n');
            Object[] options = { "Save all", "Discard changes", "Cancel" };
            int r = JOptionPane.showOptionDialog(
                this,
                sb.toString(),
                "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
            );
            if (r == 0) return !saveAll();
            return r != 1;
        }

        boolean save(SpecFile f) {
            editor.commitAll();
            if (f.loadError != null) {
                JOptionPane.showMessageDialog(
                    this,
                    relName(f) + " could not be loaded, so it can't be saved:\n" + f.loadError,
                    "Cannot save",
                    JOptionPane.ERROR_MESSAGE
                );
                return false;
            }
            long errs = issues
                .stream()
                .filter(i -> i.file() == f && i.sev() == Severity.ERROR)
                .count();
            if (errs > 0) {
                int r = JOptionPane.showConfirmDialog(
                    this,
                    relName(f) + " has " + errs + " error(s) that will fail the spec build.\nSave anyway?",
                    "File has errors",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                if (r != JOptionPane.YES_OPTION) return false;
            }
            try {
                Loader.save(f);
                savedText.put(f, Json.write(f.messages));
                snap.put(f, savedText.get(f));
                status.setText("Saved " + relName(f));
                tree.repaint();
                updateTitle();
                return true;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                    this,
                    "Could not save " + f.path + ":\n" + ex.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE
                );
                return false;
            }
        }

        void saveCurrent() {
            SpecFile f = currentFile();
            if (f == null && files.size() == 1) f = files.getFirst();
            if (f == null) {
                status.setText("Select a file or message to save");
                return;
            }
            save(f);
        }

        boolean saveAll() {
            editor.commitAll();
            boolean ok = true;
            for (SpecFile f : files) if (f.dirty) ok &= save(f);
            return ok;
        }

        void newSpecFile() {
            JFileChooser fc = new JFileChooser(
                baseDir != null ? baseDir.toFile() : new File(prefs.get("lastDir", System.getProperty("user.home")))
            );
            fc.setDialogTitle("Create a new spec file");
            fc.setSelectedFile(new File("new_node.json"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path p = fc.getSelectedFile().toPath();
            if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) p = p.resolveSibling(
                p.getFileName() + ".json"
            );
            if (Files.exists(p)) {
                JOptionPane.showMessageDialog(this, p + " already exists.", "File exists", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                Files.writeString(p, "[]\n");
                Path created = p.toAbsolutePath().normalize();
                openPaths(List.of(p), false, () ->
                    files
                        .stream()
                        .filter(f -> f.path.equals(created))
                        .findFirst()
                        .ifPresent(this::selectFile)
                );
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                    this,
                    ex.getMessage(),
                    "Could not create file",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }

        // ---------- message operations

        SpecFile currentFile() {
            if (editor.file != null) return editor.file;
            return selectedFileOnly;
        }

        Set<Long> usedIds() {
            Set<Long> s = new HashSet<>();
            for (SpecFile f : files)
                for (Object o : f.messages) {
                    Long v = parseId(str(asMap(o), "id"));
                    if (v != null) s.add(v);
                }
            return s;
        }

        String nextFreeId(long after, boolean ext) {
            Set<Long> used = usedIds();
            long max = ext ? 0x1FFFFFFFL : 0x7FFL;
            for (long v = after + 1; v <= max; v++) if (!used.contains(v)) return hex(v);
            for (long v = 0; v <= Math.min(after, max); v++) if (!used.contains(v)) return hex(v);
            return null;
        }

        String predominantNode(SpecFile f) {
            Map<String, Integer> c = new HashMap<>();
            for (Object o : f.messages)
                for (Object fo : listOrEmpty(asMap(o) == null ? null : asMap(o).get("fields"))) {
                    String nm = str(asMap(fo), "name");
                    if (nm == null || nm.isEmpty()) continue;
                    String node = nm.split("/")[0];
                    if (!node.equals("Calypso") && !node.isEmpty()) c.merge(node, 1, Integer::sum);
                }
            return c.isEmpty() ? "Node" : Collections.max(c.entrySet(), Map.Entry.comparingByValue()).getKey();
        }

        void newMessage() {
            editor.commitAll();
            SpecFile f = currentFile();
            if (f == null) {
                if (files.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Open a folder of spec files first (File > Open folder…).");
                    return;
                }
                f = pickFile();
                if (f == null) return;
            }
            if (f.loadError != null) {
                JOptionPane.showMessageDialog(this, "That file failed to load and can't be edited.");
                return;
            }
            long maxStd = -1;
            for (Object o : f.messages) {
                Long v = parseId(str(asMap(o), "id"));
                if (v != null && v <= 0x7FF) maxStd = Math.max(maxStd, v);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            String id = nextFreeId(maxStd, false);
            m.put("id", id == null ? "0x0" : id);
            m.put("desc", "New Message");
            List<Object> pts = new ArrayList<>();
            pts.add(newPoint("value"));
            m.put("points", pts);
            List<Object> fds = new ArrayList<>();
            fds.add(newField(predominantNode(f) + "/NewMessage/value", 1));
            m.put("fields", fds);
            int idx =
                editor.msg != null && editor.file == f
                    ? indexOfIdentity(f.messages, editor.msg) + 1
                    : f.messages.size();
            f.messages.add(idx, m);
            nextUndoLabel = "new message";
            changed(f, false);
            rebuildTree();
            selectMessage(f, m);
            editor.focusId();
        }

        void duplicateMessage() {
            editor.commitAll();
            if (editor.msg == null) {
                status.setText("Select a message to duplicate");
                return;
            }
            SpecFile f = editor.file;
            Map<String, Object> copy = Json.deepCopy(editor.msg);
            Long v = parseId(str(copy, "id"));
            boolean ext = Boolean.TRUE.equals(copy.get("is_ext"));
            if (v != null) {
                String nid = nextFreeId(v, ext || v > 0x7FF);
                if (nid != null) copy.put("id", nid);
            }
            copy.put("desc", strOr(copy, "desc", "") + " Copy");
            f.messages.add(indexOfIdentity(f.messages, editor.msg) + 1, copy);
            nextUndoLabel = "duplicate " + strOr(editor.msg, "id", "message");
            changed(f, false);
            rebuildTree();
            selectMessage(f, copy);
            status.setText("Duplicated as " + str(copy, "id") + ". Rename its topics: duplicate topics are flagged.");
        }

        void deleteMessage() {
            editor.commitAll();
            if (editor.msg == null) return;
            SpecFile f = editor.file;
            Map<String, Object> m = editor.msg;
            int r = JOptionPane.showConfirmDialog(
                this,
                "Delete message " + strOr(m, "id", "?") + " \"" + strOr(m, "desc", "") + "\"?",
                "Delete message",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (r != JOptionPane.OK_OPTION) return;
            int idx = indexOfIdentity(f.messages, m);
            if (idx >= 0) f.messages.remove(idx);
            forgetFamilyMember(m);
            editor.detach();
            nextUndoLabel = "delete " + strOr(m, "id", "message");
            changed(f, false);
            rebuildTree();
            selectFile(f);
        }

        SpecFile pickFile() {
            String[] names = new String[files.size()];
            for (int i = 0; i < files.size(); i++) names[i] = relName(files.get(i));
            Object r = JOptionPane.showInputDialog(
                this,
                "Add the new message to which file?",
                "Choose file",
                JOptionPane.QUESTION_MESSAGE,
                null,
                names,
                names[0]
            );
            if (r == null) return null;
            for (SpecFile f : files) if (relName(f).equals(r)) return f;
            return null;
        }

        // ---------- undo / redo
        // Each change stores the file's JSON before and after, so every kind of edit (tables, drag,
        // JSON tab, bulk fixes, family copies) can be undone without per-operation code.

        /** One undoable change: the file's JSON before and after, and where the user was. */
        record UndoEntry(SpecFile f, String before, String after, String label, int msgIndex, int tab) {}

        final Deque<UndoEntry> undoStack = new ArrayDeque<>(),
            redoStack = new ArrayDeque<>();
        final Map<SpecFile, String> snap = new IdentityHashMap<>(),
            savedText = new IdentityHashMap<>();
        String nextUndoLabel;
        boolean restoring;
        JMenuItem undoItem, redoItem;
        TrafficWindow traffic;

        void recordUndo(SpecFile f) {
            String label = nextUndoLabel;
            nextUndoLabel = null;
            if (f == null || f.loadError != null || restoring) return;
            String now = Json.write(f.messages);
            String before = snap.get(f);
            f.dirty = !now.equals(savedText.get(f));
            if (before == null || now.equals(before)) {
                snap.put(f, now);
                return;
            }
            if (label == null) label =
                editor.msg != null && editor.file == f
                    ? "edit to " + strOr(editor.msg, "id", "message")
                    : "edit in " + relName(f);
            int mi = editor.file == f && editor.msg != null ? indexOfIdentity(f.messages, editor.msg) : -1;
            undoStack.push(new UndoEntry(f, before, now, label, mi, editor.tabs.getSelectedIndex()));
            while (undoStack.size() > 200) undoStack.removeLast();
            redoStack.clear();
            snap.put(f, now);
            updateUndoItems();
        }

        void undo() {
            editor.commitAll();
            UndoEntry e = undoStack.poll();
            if (e == null) {
                status.setText("Nothing to undo");
                return;
            }
            restore(e.f(), e.before(), e.msgIndex(), e.tab());
            redoStack.push(e);
            status.setText("Undid " + e.label());
            updateUndoItems();
        }

        void redo() {
            editor.commitAll();
            UndoEntry e = redoStack.poll();
            if (e == null) {
                status.setText("Nothing to redo");
                return;
            }
            restore(e.f(), e.after(), e.msgIndex(), e.tab());
            undoStack.push(e);
            status.setText("Redid " + e.label());
            updateUndoItems();
        }

        void restore(SpecFile f, String text, int msgIndex, int tab) {
            if (!files.contains(f)) {
                status.setText("That file is no longer open");
                return;
            }
            restoring = true;
            try {
                // remember family membership by position, since restoring creates new objects
                Map<Family, int[]> famIdx = new IdentityHashMap<>();
                for (Family fam : new HashSet<>(families.values())) {
                    if (fam.file() != f) continue;
                    int[] ix = new int[fam.members().size()];
                    for (int i = 0; i < ix.length; i++) ix[i] = indexOfIdentity(f.messages, fam.members().get(i));
                    famIdx.put(fam, ix);
                }
                int oldSize = f.messages.size();
                editor.detach();
                List<Object> l = asList(Json.parse(text));
                f.messages.clear();
                if (l != null) f.messages.addAll(l);
                snap.put(f, text);
                f.dirty = !text.equals(savedText.get(f));
                if (f.messages.size() == oldSize) {
                    families.values().removeIf(fam -> fam.file() == f);
                    for (Map.Entry<Family, int[]> e : famIdx.entrySet()) {
                        Family fam = e.getKey();
                        fam.members().clear();
                        for (int i : e.getValue())
                            if (i >= 0 && i < f.messages.size() && asMap(f.messages.get(i)) != null) fam.members().add(
                                asMap(f.messages.get(i))
                            );
                        if (fam.members().size() >= 2) for (Map<String, Object> m : fam.members()) families.put(m, fam);
                    }
                } else computeFamilies(f);
                revalidateAll();
                rebuildTree();
                Map<String, Object> m =
                    msgIndex >= 0 && msgIndex < f.messages.size() ? asMap(f.messages.get(msgIndex)) : null;
                if (m != null) {
                    selectMessage(f, m);
                    if (tab >= 0 && tab < editor.tabs.getTabCount()) editor.tabs.setSelectedIndex(tab);
                } else selectFile(f);
                if (traffic != null) traffic.specChanged();
                updateTitle();
            } finally {
                restoring = false;
            }
        }

        void updateUndoItems() {
            if (undoItem == null) return;
            UndoEntry u = undoStack.peek(),
                r = redoStack.peek();
            undoItem.setEnabled(u != null);
            undoItem.setText(u == null ? "Undo" : "Undo " + u.label());
            redoItem.setEnabled(r != null);
            redoItem.setText(r == null ? "Redo" : "Redo " + r.label());
        }

        // ---------- message families
        // A family is a set of messages in one file with the same point sizes and field count, whose topics
        // are identical except for one level (e.g. VCU/eFuses/<Dashboard|Brake|...>/ADC). Nothing is
        // stored in the JSON: families are recognized when files are opened.

        final Map<Map<String, Object>, Family> families = new IdentityHashMap<>();

        void computeFamilies(SpecFile only) {
            findFamilies(files, only, families);
        }

        void forgetFamilyMember(Map<String, Object> m) {
            Family fam = families.remove(m);
            if (fam == null) return;
            fam.members().removeIf(x -> x == m);
            if (fam.members().size() < 2) {
                for (Map<String, Object> x : fam.members()) families.remove(x);
                fam.members().clear();
            }
        }

        void openTraffic() {
            editor.commitAll();
            if (traffic == null) traffic = new TrafficWindow(this);
            traffic.setVisible(true);
            traffic.toFront();
        }

        // ---------- change propagation

        void changed(SpecFile f, boolean structural) {
            if (f != null) {
                f.dirty = true;
                recordUndo(f);
                DefaultMutableTreeNode fn = findFileNode(f);
                if (fn != null) treeModel.nodeChanged(fn);
            }
            revalidateAll();
            if (structural) scheduleRebuild();
            else tree.repaint();
            if (traffic != null) traffic.specChanged();
            updateTitle();
        }

        void scheduleRebuild() {
            if (rebuildScheduled) return;
            rebuildScheduled = true;
            SwingUtilities.invokeLater(() -> {
                rebuildScheduled = false;
                rebuildTree();
            });
        }

        void revalidateAll() {
            applyIssues(Validator.validate(files));
        }

        void applyIssues(List<Issue> found) {
            issues = found;
            msgSev.clear();
            fileSev.clear();
            int[] c = new int[3];
            for (Issue is : issues) {
                c[is.sev().ordinal()]++;
                if (is.sev() == Severity.INFO) continue;
                if (is.msg() != null) msgSev.merge(is.msg(), is.sev(), Severity::worse);
                fileSev.merge(is.file(), is.sev(), Severity::worse);
            }
            issuesPanel.setIssues(issues);
            editor.issuesChanged();
            statusRight.setText(
                "<html><font color='" +
                    hx(T.red()) +
                    "'>● " +
                    c[0] +
                    " errors</font>&nbsp;&nbsp;&nbsp;<font color='" +
                    hx(T.yellow()) +
                    "'>● " +
                    c[1] +
                    " warnings</font>&nbsp;&nbsp;&nbsp;<font color='" +
                    hx(T.blue()) +
                    "'>● " +
                    c[2] +
                    " info</font></html>"
            );
        }

        void updateTitle() {
            boolean dirty = false;
            for (SpecFile f : files) dirty |= f.dirty;
            setTitle("Tiresias" + (baseDir != null ? " — " + baseDir : "") + (dirty ? "  (unsaved changes)" : ""));
        }

        String relName(SpecFile f) {
            if (baseDir != null && f.path.startsWith(baseDir) && !f.path.equals(baseDir)) return baseDir
                .relativize(f.path)
                .toString();
            return f.path.getFileName().toString();
        }

        // ---------- tree

        Object selectedUserObject() {
            TreePath tp = tree.getSelectionPath();
            if (tp == null) return null;
            return ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        }

        boolean isProblem(Map<String, Object> m) {
            return msgSev.get(m) != null;
        }

        void rebuildTree() {
            rebuilding = true;
            try {
                Set<SpecFile> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
                for (int i = 0; i < root.getChildCount(); i++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
                    if (tree.isExpanded(new TreePath(c.getPath()))) expanded.add(((FileNode) c.getUserObject()).f());
                }
                root.removeAllChildren();
                String q = search.getText().strip().toLowerCase(Locale.ROOT);
                int mode = searchMode.getSelectedIndex();
                boolean filtering = !q.isEmpty() || onlyIssues.isSelected();
                int shown = 0,
                    total = 0;
                for (SpecFile f : files) {
                    DefaultMutableTreeNode fn = new DefaultMutableTreeNode(new FileNode(this, f));
                    List<Map<String, Object>> ms = new ArrayList<>();
                    for (Object o : f.messages) {
                        Map<String, Object> m = asMap(o);
                        if (m != null) ms.add(m);
                    }
                    total += ms.size();
                    if (sortById.isSelected()) ms.sort(
                        Comparator.comparingLong(m -> {
                            Long v = parseId(str(m, "id"));
                            return v == null ? Long.MAX_VALUE : v;
                        })
                    );
                    for (Map<String, Object> m : ms) {
                        if (!matches(m, q, mode)) continue;
                        if (onlyIssues.isSelected() && !isProblem(m)) continue;
                        fn.add(new DefaultMutableTreeNode(new MsgNode(f, m), false));
                        shown++;
                    }
                    boolean fileProblem = f.loadError != null || !f.parseWarnings.isEmpty();
                    if (
                        !filtering || fn.getChildCount() > 0 || (onlyIssues.isSelected() && q.isEmpty() && fileProblem)
                    ) root.add(fn);
                }
                treeModel.reload();
                for (int i = 0; i < root.getChildCount(); i++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
                    if (
                        filtering || firstBuild || expanded.contains(((FileNode) c.getUserObject()).f())
                    ) tree.expandPath(new TreePath(c.getPath()));
                }
                firstBuild = false;
                DefaultMutableTreeNode sel = null;
                if (editor.msg != null) sel = findNode(editor.msg);
                else if (selectedFileOnly != null) sel = findFileNode(selectedFileOnly);
                if (sel != null) {
                    TreePath tp = new TreePath(sel.getPath());
                    tree.setSelectionPath(tp);
                    tree.scrollPathToVisible(tp);
                }
                if (filtering) status.setText("Showing " + shown + " of " + total + " messages");
                else if (!files.isEmpty()) status.setText(files.size() + " file(s), " + total + " messages");
            } finally {
                rebuilding = false;
            }
        }

        static boolean matches(Map<String, Object> m, String q, int mode) {
            if (q.isEmpty()) return true;
            boolean all = mode == 0;
            if (all || mode == 1) {
                if (lc(str(m, "id")).contains(q)) return true;
                Long v = parseId(str(m, "id")),
                    qv = parseLooseNumber(q);
                if (v != null && v.equals(qv)) return true;
            }
            if ((all || mode == 2) && lc(str(m, "desc")).contains(q)) return true;
            if (all && lc(str(m, "key")).contains(q)) return true;
            for (Object o : listOrEmpty(m.get("fields"))) {
                Map<String, Object> f = asMap(o);
                if (f == null) continue;
                if ((all || mode == 3) && lc(str(f, "name")).contains(q)) return true;
                if ((all || mode == 5) && lc(str(f, "unit")).equals(q)) return true;
                if (
                    (all || mode == 6) && (lc(str(f, "doc")).contains(q) || lc(str(f, "desc")).contains(q))
                ) return true;
            }
            if (all || mode == 4) for (Object o : listOrEmpty(m.get("points")))
                if (lc(str(asMap(o), "name")).contains(q)) return true;
            return false;
        }

        DefaultMutableTreeNode findNode(Map<String, Object> m) {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
                for (int j = 0; j < fn.getChildCount(); j++) {
                    DefaultMutableTreeNode mn = (DefaultMutableTreeNode) fn.getChildAt(j);
                    if (((MsgNode) mn.getUserObject()).m() == m) return mn;
                }
            }
            return null;
        }

        DefaultMutableTreeNode findFileNode(SpecFile f) {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
                if (((FileNode) fn.getUserObject()).f() == f) return fn;
            }
            return null;
        }

        void selectMessage(SpecFile f, Map<String, Object> m) {
            DefaultMutableTreeNode n = findNode(m);
            if (n == null) {
                search.setText("");
                searchTimer.stop();
                onlyIssues.setSelected(false);
                editor.setHighlight("", 0);
                rebuildTree();
                n = findNode(m);
            }
            if (n != null) {
                TreePath tp = new TreePath(n.getPath());
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
            }
            if (editor.msg != m) editor.load(f, m);
        }

        void selectFile(SpecFile f) {
            DefaultMutableTreeNode n = findFileNode(f);
            if (n != null) tree.setSelectionPath(new TreePath(n.getPath()));
            else {
                selectedFileOnly = f;
                editor.showFile(f);
            }
        }

        void selectFirstMessage() {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
                if (fn.getChildCount() > 0) {
                    tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode) fn.getChildAt(0)).getPath()));
                    return;
                }
            }
        }

        void navigate(Issue is) {
            if (is.msg() == null) {
                selectFile(is.file());
                return;
            }
            selectMessage(is.file(), is.msg());
            editor.focusIssue(is);
        }

        // ---------- vocab for dropdowns

        Set<String> units() {
            Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            s.addAll(COMMON_UNITS);
            for (SpecFile f : files)
                for (Object o : f.messages)
                    for (Object fo : listOrEmpty(asMap(o) == null ? null : asMap(o).get("fields"))) {
                        String u = str(asMap(fo), "unit");
                        if (u != null) s.add(u);
                    }
            return s;
        }

        Set<String> formatterKeys() {
            Set<String> s = new TreeSet<>();
            s.add("");
            s.add("divide");
            for (SpecFile f : files)
                for (Object o : f.messages)
                    for (Object po : listOrEmpty(asMap(o) == null ? null : asMap(o).get("points"))) {
                        Map<String, Object> p = asMap(po);
                        if (p == null) continue;
                        String k = formatterKey(p);
                        if (!k.isEmpty()) s.add(k);
                    }
            return s;
        }

        // ---------- tools

        /** Renames default_value to default and converts "format": "divideN" to a formatter, keeping key order. */
        void fixOutdatedKeys() {
            editor.commitAll();
            int defaults = 0,
                formats = 0,
                skipped = 0;
            for (SpecFile f : files) {
                if (f.loadError != null) continue;
                boolean changedFile = false;
                for (Object o : f.messages)
                    for (Object po : listOrEmpty(asMap(o) == null ? null : asMap(o).get("points"))) {
                        Map<String, Object> p = asMap(po);
                        if (p == null) continue;
                        boolean hasDv = p.containsKey("default_value") && !p.containsKey("default");
                        String fmt = str(p, "format");
                        Matcher mm = fmt == null ? null : LEGACY_DIVIDE.matcher(fmt);
                        boolean hasFmt = mm != null && mm.matches() && !p.containsKey("formatter");
                        if (p.containsKey("format") && !hasFmt) skipped++;
                        if (!hasDv && !hasFmt) continue;
                        Map<String, Object> rebuilt = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : p.entrySet()) {
                            if (hasDv && e.getKey().equals("default_value")) {
                                rebuilt.put("default", e.getValue());
                                defaults++;
                            } else if (hasFmt && e.getKey().equals("format")) {
                                Map<String, Object> fm = new LinkedHashMap<>();
                                fm.put("key", "divide");
                                fm.put("arg", new BigDecimal(mm.group(1)));
                                rebuilt.put("formatter", fm);
                                formats++;
                            } else rebuilt.put(e.getKey(), e.getValue());
                        }
                        p.clear();
                        p.putAll(rebuilt);
                        changedFile = true;
                    }
                if (changedFile) {
                    nextUndoLabel = "fix outdated keys";
                    recordUndo(f);
                }
            }
            revalidateAll();
            rebuildTree();
            editor.refresh();
            updateTitle();
            String msg =
                defaults +
                " default_value key(s) renamed to default, " +
                formats +
                " format string(s) converted to formatter." +
                (skipped > 0 ? " " + skipped + " format value(s) weren't divideN and need fixing by hand." : "") +
                (defaults + formats > 0 ? " Review, then save." : "");
            JOptionPane.showMessageDialog(this, msg, "Fix outdated keys", JOptionPane.INFORMATION_MESSAGE);
        }

        void findFreeIds() {
            String start = editor.msg != null ? strOr(editor.msg, "id", "0x0") : "0x0";
            String in = JOptionPane.showInputDialog(this, "List free CAN IDs starting after (hex):", start);
            if (in == null) return;
            Long v = parseLooseNumber(in.strip());
            if (v == null) {
                JOptionPane.showMessageDialog(this, "Enter a hex ID such as 0x500");
                return;
            }
            Set<Long> used = usedIds();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (long k = v + 1; k <= 0x1FFFFFFFL && n < 16; k++) if (!used.contains(k)) {
                sb.append(hex(k))
                    .append(k > 0x7FF ? "  (extended)" : "")
                    .append('\n');
                n++;
            }
            JTextArea ta = new JTextArea(sb.toString(), 16, 24);
            ta.setFont(mono(Font.PLAIN, 13));
            ta.setEditable(false);
            JOptionPane.showMessageDialog(
                this,
                new JScrollPane(ta),
                "Free IDs after " + hex(v),
                JOptionPane.INFORMATION_MESSAGE
            );
        }

        void showIdMap() {
            editor.commitAll();
            record IdRow(
                long sortKey,
                String id,
                String frame,
                String desc,
                int bits,
                int fields,
                String fileName,
                SpecRef ref
            ) {
                Object column(int c) {
                    return switch (c) {
                        case 0 -> id;
                        case 1 -> frame;
                        case 2 -> desc;
                        case 3 -> bits;
                        case 4 -> fields;
                        default -> fileName;
                    };
                }
            }
            List<IdRow> rows = new ArrayList<>();
            for (SpecFile f : files)
                for (Object o : f.messages) {
                    if (!(asMap(o) instanceof Map<String, Object> m)) continue;
                    Long v = parseId(str(m, "id"));
                    List<Object> pts = listOrEmpty(m.get("points"));
                    rows.add(
                        new IdRow(
                            v == null ? Long.MAX_VALUE : v,
                            strOr(m, "id", "?"),
                            Boolean.TRUE.equals(m.get("is_ext")) ? "ext" : "std",
                            strOr(m, "desc", ""),
                            offsets(pts)[pts.size()],
                            listOrEmpty(m.get("fields")).size(),
                            relName(f),
                            new SpecRef(f, m)
                        )
                    );
                }
            rows.sort(Comparator.comparingLong(IdRow::sortKey));
            String[] cols = { "ID", "Frame", "Description", "Bits", "Fields", "File" };
            AbstractTableModel tm = new AbstractTableModel() {
                public int getRowCount() {
                    return rows.size();
                }

                public int getColumnCount() {
                    return cols.length;
                }

                public String getColumnName(int c) {
                    return cols[c];
                }

                public Class<?> getColumnClass(int c) {
                    return c == 3 || c == 4 ? Integer.class : String.class;
                }

                public Object getValueAt(int r, int c) {
                    return rows.get(r).column(c);
                }
            };
            JTable t = new JTable(tm);
            styleTable(t);
            t.setDefaultRenderer(Object.class, new StyledRenderer(null));
            t.setDefaultRenderer(Integer.class, new StyledRenderer(null));
            t.setAutoCreateRowSorter(true);
            t.getColumnModel()
                .getColumn(0)
                .setCellRenderer(
                    new StyledRenderer((tb, row, col, sel, c) -> c.setFont(mono(Font.PLAIN, tb.getFont().getSize())))
                );
            t.getColumnModel().getColumn(2).setPreferredWidth(320);
            JDialog d = new JDialog(this, "ID map: " + rows.size() + " messages (double-click to open)", false);
            t.addMouseListener(
                new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() != 2 || t.getSelectedRow() < 0) return;
                        SpecRef ref = rows.get(t.convertRowIndexToModel(t.getSelectedRow())).ref();
                        selectMessage(ref.file(), ref.msg());
                    }
                }
            );
            d.add(new JScrollPane(t));
            d.setSize(760, 520);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        }

        void showRules() {
            String html = """
            <html><body style='width:520px;font-family:sans-serif'>
            <p><b>Errors</b> break the spec build or code generation. <b>Warnings</b> break a "should" rule. \
            <b>Info</b> notes style and documentation gaps.</p>
            <p><b>Keys</b>: definition-rs rejects any key it doesn't know, in messages, points, fields and sims. \
            The point default is "default" (not default_value), and scaling uses "formatter" (not format).</p>
            <p><b>Messages</b>: id is a unique lowercase-0x hex string; IDs above 0x7FF need is_ext; \
            desc uses only letters, whitespace and _; total bits are a multiple of 8 and at most 64; \
            bidir_mode is broadcast or oneshot; clients are supported ports (1882).</p>
            <p><b>Points</b>: parsed points are 1–32 bits; padding points (parse:false) only contain size; \
            signed integers are 8/16/32 bits and byte aligned; IEEE754 floats are 32 bits; \
            points of 8 bits or fewer have no endianness; little-endian points are byte aligned and make the \
            whole message byte aligned; c_type is wide enough; byte-sized values don't start mid-byte; \
            sim is a sweep or an enum whose frequencies add up to 1; sim and default values fit after the formatter.</p>
            <p><b>Fields</b>: values and {n} topic placeholders reference existing parsed points (indexed from 1); \
            every field has unit and doc; topics have no trailing slash, whitespace or wildcards, and 3+ levels \
            are preferred; encodable topics live under Calypso/Bidir/State/{key}/; topics are unique across all files.</p>
            <p><b>firmware-rs cangen</b> also needs unique struct names (from desc) and point accessor names \
            (snake_case of name), no Rust keywords, divide/multiply formatters with a positive whole-number arg, \
            and no formatter, float or IEEE754 on little-endian points. Unnamed points become padding there.</p>
            <p>From the command line: <tt>java -jar Tiresias.jar --validate &lt;folder&gt; [--strict] [--info]</tt></p>
            </body></html>
            """;
            JOptionPane.showMessageDialog(
                this,
                new JLabel(html),
                "Spec rules checked",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    // ======================================================================================
    // Issues panel
    // ======================================================================================

    static final class IssuesPanel extends JPanel {

        final App app;
        List<Issue> all = new ArrayList<>(),
            shown = new ArrayList<>();
        final JComboBox<String> filter = new JComboBox<>(new String[] {
            "Errors and warnings",
            "Errors only",
            "Everything (with info)",
        });
        final JCheckBox currentOnly = new JCheckBox("Selected message only");
        final JLabel title = new JLabel("Problems");
        final AbstractTableModel model;
        final JTable table;

        IssuesPanel(App app) {
            super(new BorderLayout(0, 2));
            this.app = app;
            setBorder(new EmptyBorder(4, 8, 4, 8));
            String[] cols = { "Severity", "File", "ID", "Location", "Problem" };
            model = new AbstractTableModel() {
                public int getRowCount() {
                    return shown.size();
                }

                public int getColumnCount() {
                    return cols.length;
                }

                public String getColumnName(int c) {
                    return cols[c];
                }

                public Object getValueAt(int r, int c) {
                    Issue is = shown.get(r);
                    return switch (c) {
                        case 0 -> is.sev().label;
                        case 1 -> app.relName(is.file());
                        case 2 -> is.msg() == null ? "" : strOr(is.msg(), "id", "?");
                        case 3 -> is.where();
                        default -> is.text();
                    };
                }
            };
            table = new JTable(model);
            styleTable(table);
            table.setDefaultRenderer(Object.class, new StyledRenderer(null));
            table.setAutoCreateRowSorter(true);
            table
                .getColumnModel()
                .getColumn(0)
                .setCellRenderer(
                    new StyledRenderer((t, row, col, sel, c) -> {
                        Issue is = shown.get(t.convertRowIndexToModel(row));
                        ((JLabel) c).setIcon(SevIcon.of(is.sev()));
                        if (!sel) c.setForeground(is.sev().color());
                    })
                );
            table
                .getColumnModel()
                .getColumn(4)
                .setCellRenderer(
                    new StyledRenderer((t, row, col, sel, c) ->
                        c.setToolTipText(
                            "<html><body style='width:420px'>" +
                                escHtml(shown.get(t.convertRowIndexToModel(row)).text()) +
                                "</body></html>"
                        )
                    )
                );
            table.getColumnModel().getColumn(0).setPreferredWidth(80);
            table.getColumnModel().getColumn(0).setMaxWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(110);
            table.getColumnModel().getColumn(2).setPreferredWidth(70);
            table.getColumnModel().getColumn(2).setMaxWidth(120);
            table.getColumnModel().getColumn(3).setPreferredWidth(150);
            table.getColumnModel().getColumn(4).setPreferredWidth(620);
            table.addMouseListener(
                new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) go();
                    }
                }
            );
            table
                .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "go");
            table.getActionMap().put(
                "go",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        go();
                    }
                }
            );

            filter.addActionListener(e -> refilter());
            currentOnly.addActionListener(e -> refilter());
            JPanel top = new JPanel(new BorderLayout());
            top.setBorder(new EmptyBorder(4, 4, 4, 0));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            top.add(title, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            currentOnly.setOpaque(false);
            right.add(new MutedLabel("Double-click a row to jump to it"));
            right.add(currentOnly);
            right.add(filter);
            top.add(right, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
        }

        void go() {
            int r = table.getSelectedRow();
            if (r < 0) return;
            app.navigate(shown.get(table.convertRowIndexToModel(r)));
        }

        void setIssues(List<Issue> l) {
            all = l;
            refilter();
        }

        void refilter() {
            int f = filter.getSelectedIndex();
            Map<String, Object> cur = app.editor == null ? null : app.editor.msg;
            shown = new ArrayList<>();
            int shownErr = 0;
            for (Issue is : all) {
                if (f == 0 && is.sev() == Severity.INFO) continue;
                if (f == 1 && is.sev() != Severity.ERROR) continue;
                if (currentOnly.isSelected() && is.msg() != cur) continue;
                shown.add(is);
                if (is.sev() == Severity.ERROR) shownErr++;
            }
            model.fireTableDataChanged();
            title.setText(
                shown.isEmpty()
                    ? "No problems found"
                    : "Problems (" + shown.size() + (shownErr > 0 ? ", " + shownErr + " errors" : "") + ")"
            );
        }
    }

    // ======================================================================================
    // Message editor
    // ======================================================================================

    static final class MessageEditor extends JPanel {

        final App app;
        SpecFile file;
        Map<String, Object> msg;
        boolean loading;
        final CardLayout cards = new CardLayout();
        final JPanel cardPanel = new JPanel(cards);
        final FadeUI fadeUI = new FadeUI();
        final JLayer<JComponent> fadeLayer = new JLayer<>(cardPanel, fadeUI);
        final JLabel placeholder = new JLabel("", SwingConstants.CENTER);
        Runnable placeholderRefresh;
        final JButton placeholderNew;

        final JTextField idField = new JTextField(9),
            descField = new JTextField(22),
            simFreqField = new JTextField(5),
            keyField = new JTextField(10),
            clientsField = new JTextField(7);
        final JCheckBox extBox = new JCheckBox("Extended ID");
        final JComboBox<String> bidirBox = new JComboBox<>(new String[] { "", "broadcast", "oneshot" });
        final JLabel summary = new JLabel(" ");
        final JLabel msgProblems = new JLabel();
        final JTabbedPane tabs = new JTabbedPane();

        final PointsModel pointsModel = new PointsModel();
        final JTable pointsTable = new JTable(pointsModel);
        final FieldsModel fieldsModel = new FieldsModel();
        final JTable fieldsTable = new JTable(fieldsModel);
        final JLabel fieldMapping = new JLabel(" ");
        final JTextArea fieldDesc = new JTextArea(3, 40);
        Map<String, Object> detailField;

        final BitLayoutPanel layoutPanel = new BitLayoutPanel();
        final JTextField decodeInput = new JTextField();
        final DefaultTableModel decodeModel = new DefaultTableModel(
            new Object[] { "#", "Name", "Bits", "Raw", "Value" },
            0
        ) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        final JTextArea decodeOut = new JTextArea(6, 40);
        final ToneLabel decodeStatus = new ToneLabel(" ");
        final JTextArea jsonArea = new JTextArea();
        final ToneLabel jsonStatus = new ToneLabel(" ");

        final Map<Integer, Severity> pointSev = new HashMap<>(),
            fieldSev = new HashMap<>();
        final Map<Integer, List<Issue>> pointIssues = new HashMap<>(),
            fieldIssues = new HashMap<>();
        String highlight = "";
        int highlightMode = 0;
        final JComboBox<String> unitCombo = editableCombo(COMMON_UNITS);
        final JComboBox<String> formatterCombo = editableCombo(List.of("", "divide"));

        MessageEditor(App app) {
            super(new BorderLayout());
            this.app = app;

            JPanel ph = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = 0;
            ph.add(placeholder, g);
            placeholderNew = button("New message in this file", null, app::newMessage);
            g.gridy = 1;
            g.insets = new Insets(12, 0, 0, 0);
            ph.add(placeholderNew, g);
            cardPanel.add(ph, "empty");
            cardPanel.add(buildEditor(), "editor");
            add(fadeLayer, BorderLayout.CENTER);
            cards.show(cardPanel, "empty");
            if (isShowing()) fadeUI.fadeIn(fadeLayer);
        }

        JComponent buildEditor() {
            idField.setFont(mono(Font.BOLD, idField.getFont().getSize() + 3));
            descField.setFont(descField.getFont().deriveFont(descField.getFont().getSize2D() + 2));
            idField.setToolTipText("Hex CAN ID, e.g. 0x1A0");
            descField.setToolTipText(
                "Letters, whitespace and _ only. Containing \"overflow\" makes it the node's overflow broadcast."
            );
            simFreqField.setToolTipText("Simulation period/frequency (sim_freq). Leave empty to omit.");
            keyField.setToolTipText(
                "Makes this an encodable message: Calypso sends it when it receives Calypso/Bidir/Command/{key}. Leave empty for decode-only."
            );
            clientsField.setToolTipText("Extra MQTT client ports, comma separated (supported: 1882)");
            extBox.setToolTipText("is_ext — required for IDs above 0x7FF and for all encodable messages to be correct");
            bidirBox.setToolTipText("broadcast: sent ~1 s with latest/default value. oneshot: sent once per command.");

            bind(idField, t -> {
                if (putStr(msg, "id", t.strip(), false)) changed(true);
            });
            bind(descField, t -> {
                if (putStr(msg, "desc", t.strip(), false)) changed(true);
            });
            bind(simFreqField, t -> {
                BigDecimal b = null;
                if (!t.isBlank()) {
                    b = parseNum(t);
                    if (b == null) {
                        bad(simFreqField, "sim_freq must be a number");
                        return;
                    }
                }
                if (putNum(msg, "sim_freq", b)) changed(false);
            });
            bind(keyField, t -> {
                if (putStr(msg, "key", t.strip(), true)) changed(true);
            });
            bind(clientsField, t -> {
                List<Object> l = new ArrayList<>();
                for (String s : t.split("[,\\s]+")) {
                    if (s.isEmpty()) continue;
                    Integer v = tryInt(s);
                    if (v == null) {
                        bad(clientsField, "clients must be port numbers separated by commas");
                        return;
                    }
                    l.add(BigDecimal.valueOf(v));
                }
                Object cur = msg.get("clients");
                if (l.isEmpty()) {
                    if (cur != null) {
                        msg.remove("clients");
                        changed(false);
                    }
                    return;
                }
                if (cur == null || !Json.write(cur).equals(Json.write(l))) {
                    msg.put("clients", l);
                    changed(false);
                }
            });
            extBox.addActionListener(e -> {
                if (!loading && msg != null && putBool(msg, "is_ext", extBox.isSelected(), false)) changed(false);
            });
            bidirBox.addActionListener(e -> {
                if (loading || msg == null) return;
                if (putStr(msg, "bidir_mode", (String) bidirBox.getSelectedItem(), true)) changed(false);
            });

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(new EmptyBorder(12, 14, 8, 14));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 3, 2, 3);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            int x = 0;
            c.gridy = 0;
            c.gridx = x++;
            form.add(new MutedLabel("ID"), c);
            c.gridx = x++;
            form.add(idField, c);
            c.gridx = x++;
            form.add(button("Next free", "Set the ID to the next unused ID after this one", this::nextFreeId), c);
            c.gridx = x++;
            form.add(new MutedLabel("  Description"), c);
            c.gridx = x++;
            c.weightx = 1;
            c.gridwidth = 3;
            form.add(descField, c);
            c.weightx = 0;
            c.gridwidth = 1;
            x += 2;
            c.gridx = x;
            form.add(extBox, c);
            x = 0;
            c.gridy = 1;
            c.gridx = x++;
            form.add(new MutedLabel("Key"), c);
            c.gridx = x++;
            form.add(keyField, c);
            c.gridx = x++;
            form.add(bidirBox, c);
            c.gridx = x++;
            form.add(new MutedLabel("  Sim freq"), c);
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row2.setOpaque(false);
            extBox.setOpaque(false);
            row2.add(simFreqField);
            row2.add(new MutedLabel("   Clients"));
            row2.add(clientsField);
            c.gridx = x;
            c.gridwidth = 4;
            form.add(row2, c);
            c.gridwidth = 1;
            c.gridy = 2;
            c.gridx = 0;
            c.gridwidth = 8;
            form.add(buildFamilyRow(), c);
            c.gridy = 3;
            form.add(summary, c);
            c.gridy = 4;
            form.add(msgProblems, c);

            tabs.addTab("Points", buildPointsTab());
            tabs.addTab("Fields (MQTT topics)", buildFieldsTab());
            tabs.addTab("Bit layout", new JScrollPane(layoutPanel));
            tabs.setBorder(new EmptyBorder(0, 6, 0, 6));
            tabs.addTab("Decode test", buildDecodeTab());
            tabs.addTab("cansend", buildSendTab());
            tabs.addTab("JSON", buildJsonTab());
            tabs.addChangeListener(e -> {
                Component sel = tabs.getSelectedComponent();
                if (tabs.getSelectedIndex() == TAB_JSON) loadJson();
                if (tabs.getSelectedIndex() == TAB_DECODE && sel != null) decode();
                if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
            });

            JPanel p = new JPanel(new BorderLayout());
            p.add(form, BorderLayout.NORTH);
            p.add(tabs, BorderLayout.CENTER);
            return p;
        }

        void bind(JTextField tf, Consumer<String> commit) {
            Runnable r = () -> {
                if (loading || msg == null) return;
                String t = tf.getText();
                if (t.equals(tf.getClientProperty("orig"))) return; // untouched: never write defaults into the file
                tf.putClientProperty("orig", t);
                commit.accept(t);
            };
            tf.addActionListener(e -> r.run());
            tf.addFocusListener(
                new FocusAdapter() {
                    public void focusLost(FocusEvent e) {
                        r.run();
                    }
                }
            );
        }

        void bad(JComponent c, String text) {
            Toolkit.getDefaultToolkit().beep();
            app.status.setText(text);
            if (c != null) c.requestFocusInWindow();
        }

        // ---------- points tab

        static final int P_NUM = 0,
            P_BITS = 1,
            P_SIZE = 2,
            P_NAME = 3,
            P_TOPIC = 4,
            P_CTYPE = 5,
            P_SIGNED = 6,
            P_ENDIAN = 7,
            P_PARSE = 8,
            P_FMT = 9,
            P_ARG = 10,
            P_IEEE = 11,
            P_DEFAULT = 12,
            P_SIM = 13;

        JComponent buildPointsTab() {
            pointsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            pointsTable.setSurrendersFocusOnKeystroke(true);
            styleTable(pointsTable);
            RowStyle rs = (t, row, col, sel, comp) -> {
                Map<String, Object> p = pointsModel.pt(row);
                boolean pad = p != null && !isParsed(p);
                if (pad && !sel) comp.setForeground(T.muted());
                Severity s = pointSev.get(row);
                if (s != null && !sel) comp.setBackground(s.bg());
                else if (!sel && rowMatches(p, true)) comp.setBackground(T.tint(T.accent()));
                if (col == P_TOPIC && p != null && !pad) {
                    boolean none = fieldsReferencing(msg, row + 1).isEmpty();
                    if (none) comp.setForeground(T.yellow());
                    else if (!sel) comp.setForeground(T.fg());
                    comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
                }
                if (col == P_NAME && !pad) comp.setFont(t.getFont().deriveFont(Font.BOLD));
                List<Issue> l = pointIssues.get(row);
                if (l != null) comp.setToolTipText(issueTip(l));
                else if (col == P_SIM) comp.setToolTipText("Double-click to edit the simulation");
                else if (col == P_TOPIC && p != null && !pad) comp.setToolTipText(
                    fieldsReferencing(msg, row + 1).isEmpty()
                        ? "Double-click to publish this point as a new MQTT field"
                        : "Double-click to open the field"
                );
            };
            pointsTable.setDefaultRenderer(Object.class, new StyledRenderer(rs));
            pointsTable.setDefaultRenderer(String.class, new StyledRenderer(rs));
            pointsTable.setDefaultRenderer(Integer.class, new StyledRenderer(rs));
            pointsTable.setDefaultRenderer(Boolean.class, new StyledBoolRenderer(rs));
            TableColumnModel cm = pointsTable.getColumnModel();
            cm.getColumn(P_CTYPE).setCellEditor(new DefaultCellEditor(editableCombo(C_TYPES)));
            cm.getColumn(P_ENDIAN).setCellEditor(
                new DefaultCellEditor(new JComboBox<>(new String[] { "", "big", "little" }))
            );
            cm.getColumn(P_FMT).setCellEditor(new DefaultCellEditor(formatterCombo));
            int[] widths = { 34, 64, 44, 150, 230, 80, 52, 62, 48, 72, 52, 60, 58, 200 };
            for (int i = 0; i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);
            cm.getColumn(P_NUM).setMaxWidth(44);
            pointsTable.addMouseListener(
                new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        int r = pointsTable.rowAtPoint(e.getPoint()),
                            col = pointsTable.columnAtPoint(e.getPoint());
                        if (e.getClickCount() != 2 || r < 0) return;
                        if (col == P_SIM) editSim(r);
                        if (col == P_TOPIC) openOrCreateField(r);
                    }
                }
            );

            Bar tb = new Bar();
            tb.add(button("Add point", "Append a new 8-bit point", this::addPoint));
            tb.add(
                button(
                    "Insert above",
                    "Insert a point above the selected one (field references are renumbered)",
                    this::insertPoint
                )
            );
            tb.add(button("Duplicate", null, this::duplicatePoint));
            tb.add(button("Remove", "Remove selected points (field references are renumbered)", this::removePoints));
            tb.add(button("▲", "Move up (field references follow)", () -> movePoint(-1)));
            tb.add(button("▼", "Move down (field references follow)", () -> movePoint(1)));
            tb.sep();
            tb.add(
                button("Publish to MQTT", "Open the field that publishes the selected point, or create one", () -> {
                    int r = pointsTable.getSelectedRow();
                    if (r >= 0) openOrCreateField(r);
                })
            );
            tb.add(
                button(
                    "Pad to byte",
                    "Append a parse:false padding point so the message is byte aligned",
                    this::padToByte
                )
            );
            tb.add(
                button("Edit sim…", "Edit the selected point's simulation", () -> {
                    int r = pointsTable.getSelectedRow();
                    if (r >= 0) editSim(r);
                })
            );
            JPanel p = new JPanel(new BorderLayout());
            p.add(tb, BorderLayout.NORTH);
            p.add(new JScrollPane(pointsTable), BorderLayout.CENTER);
            JLabel hint = new MutedLabel(
                "Points are indexed from 1. \"Published to\" shows the MQTT field each point feeds; double-click it to jump there or create one."
            );
            hint.setBorder(new EmptyBorder(6, 10, 6, 10));
            p.add(hint, BorderLayout.SOUTH);
            return p;
        }

        /** Jump to the field that publishes point r, or create one for it. */
        void openOrCreateField(int r) {
            if (msg == null) return;
            stopEditing(pointsTable);
            Map<String, Object> p = pointsModel.pt(r);
            if (!isParsed(p)) {
                app.status.setText("Padding points aren't published");
                return;
            }
            List<Object> fds = fields();
            for (int j = 0; j < fds.size(); j++) {
                Map<String, Object> f = asMap(fds.get(j));
                if (f == null) continue;
                if (
                    containsIdx(listOrEmpty(f.get("values")), r + 1) ||
                    strOr(f, "name", "").contains("{" + (r + 1) + "}")
                ) {
                    tabs.setSelectedIndex(1);
                    selectRow(fieldsTable, j);
                    return;
                }
            }
            String nm = strOr(p, "name", "");
            String topic = topicPrefix() + "/" + (nm.isEmpty() ? "point" + (r + 1) : nm);
            List<Object> l = ensureList(msg, "fields");
            l.add(newField(topic, r + 1));
            changed(false);
            tabs.setSelectedIndex(1);
            selectRow(fieldsTable, l.size() - 1);
            app.status.setText("Point " + (r + 1) + " is now published as " + topic + ". Check the unit and doc.");
        }

        /** Move point `from` to insertion slot `slot` (0..n), renumbering every field reference. */
        void movePointTo(int from, int slot) {
            layoutPanel.animateChange(() -> doMovePointTo(from, slot));
        }

        void doMovePointTo(int from, int slot) {
            if (msg == null) return;
            List<Object> pts = ensureList(msg, "points");
            int n = pts.size();
            if (from < 0 || from >= n || slot < 0 || slot > n) return;
            int to = slot > from ? slot - 1 : slot;
            if (to == from) return;
            stopEditing(pointsTable);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) order.add(i);
            order.remove(Integer.valueOf(from));
            order.add(to, from);
            List<Object> reordered = new ArrayList<>();
            for (int i : order) reordered.add(pts.get(i));
            pts.clear();
            pts.addAll(reordered);
            int[] newPos = new int[n + 1];
            for (int k = 0; k < n; k++) newPos[order.get(k) + 1] = k + 1;
            remapPointRefs(msg, k -> k >= 1 && k <= n ? newPos[k] : k);
            changed(false);
            selectRow(pointsTable, to);
            app.status.setText(
                "Moved " +
                    pointLabel(asMap(pts.get(to)), to).replaceFirst("Point \\d+", "point") +
                    " to position " +
                    (to + 1) +
                    "; field references were renumbered"
            );
        }

        void selectPoint(int i, boolean open) {
            selectRow(pointsTable, i);
            if (open) tabs.setSelectedIndex(0);
        }

        /** Cell editor for a field's points: a checklist popup of the message's points. */
        final class PointPicker extends AbstractCellEditor implements TableCellEditor {

            List<Object> result = new ArrayList<>();
            final JLabel label = new JLabel();
            int row = -1;

            public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int r, int c) {
                row = r;
                Map<String, Object> f = fieldsModel.fd(r);
                result = new ArrayList<>(listOrEmpty(f == null ? null : f.get("values")));
                label.setText(String.valueOf(v));
                label.setOpaque(true);
                label.setBackground(T.selection());
                label.setForeground(T.bright());
                label.setBorder(new EmptyBorder(0, 8, 0, 8));
                SwingUtilities.invokeLater(() -> show(t, r, c));
                return label;
            }

            public Object getCellEditorValue() {
                return new ArrayList<>(result);
            }

            void show(JTable t, int r, int c) {
                if (msg == null) return;
                JPopupMenu pm = new JPopupMenu();
                pm.setFocusable(false);
                JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
                list.setOpaque(false);
                list.setBorder(new EmptyBorder(4, 10, 4, 10));
                List<Object> pts = points();
                String self = strOr(fieldsModel.fd(r), "name", "");
                for (int i = 0; i < pts.size(); i++) {
                    Map<String, Object> p = asMap(pts.get(i));
                    if (!isParsed(p)) continue;
                    final int k = i + 1;
                    List<String> other = fieldsReferencing(msg, k);
                    other.remove(self);
                    String nm = strOr(p, "name", "");
                    JCheckBox cb = new JCheckBox(
                        "<html><b>#" +
                            k +
                            "</b>&nbsp; " +
                            escHtml(nm.isEmpty() ? "unnamed" : nm) +
                            "&nbsp;&nbsp;<font color='" +
                            hx(T.muted()) +
                            "'>" +
                            sizeOf(p) +
                            " bits" +
                            (other.isEmpty()
                                ? ""
                                : ", also in " +
                                  escHtml(other.getFirst()) +
                                  (other.size() > 1 ? " +" + (other.size() - 1) : "")) +
                            "</font></html>"
                    );
                    cb.setFocusable(false);
                    cb.setOpaque(false);
                    cb.setSelected(containsIdx(result, k));
                    cb.addActionListener(e -> {
                        if (cb.isSelected()) {
                            if (!containsIdx(result, k)) result.add(BigDecimal.valueOf(k));
                        } else result.removeIf(o -> {
                            Long l = longVal(o);
                            return l != null && l == k;
                        });
                    });
                    list.add(cb);
                }
                if (list.getComponentCount() == 0) list.add(new MutedLabel("This message has no parsed points yet"));
                JPanel wrap = new JPanel(new BorderLayout(0, 4));
                wrap.setOpaque(false);
                JLabel head = new MutedLabel("Points this topic publishes, in the order you tick them");
                head.setBorder(new EmptyBorder(6, 12, 2, 12));
                wrap.add(head, BorderLayout.NORTH);
                JScrollPane sp = new JScrollPane(list);
                sp.setBorder(null);
                sp.setOpaque(false);
                sp.getViewport().setOpaque(false);
                Dimension pref = list.getPreferredSize();
                sp.setPreferredSize(new Dimension(Math.max(300, pref.width + 24), Math.min(pref.height + 6, 340)));
                wrap.add(sp, BorderLayout.CENTER);
                JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
                south.setOpaque(false);
                JButton done = button("Done", null, () -> pm.setVisible(false));
                done.putClientProperty("primary", Boolean.TRUE);
                south.add(done);
                wrap.add(south, BorderLayout.SOUTH);
                pm.add(wrap);
                pm.addPopupMenuListener(
                    new PopupMenuListener() {
                        public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

                        public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                            SwingUtilities.invokeLater(PointPicker.this::finish);
                        }

                        public void popupMenuCanceled(PopupMenuEvent e) {}
                    }
                );
                Rectangle rc = t.getCellRect(r, c, true);
                pm.show(t, rc.x, rc.y + rc.height);
            }

            void finish() {
                if (fieldsTable.isEditing() && fieldsTable.getCellEditor() == this) stopCellEditing();
                else if (row >= 0) fieldsModel.setValueAt(new ArrayList<>(result), row, 3);
            }
        }

        void themeChanged() {
            if (msg != null) {
                refreshSummary();
                issuesChanged();
                loadFieldDetail();
                layoutPanel.repaint();
            } else if (placeholderRefresh != null) placeholderRefresh.run();
        }

        boolean rowMatches(Map<String, Object> m, boolean point) {
            if (highlight.isEmpty() || m == null) return false;
            String q = highlight.toLowerCase(Locale.ROOT);
            if (point) return (highlightMode == 0 || highlightMode == 4) && lc(str(m, "name")).contains(q);
            if ((highlightMode == 0 || highlightMode == 3) && lc(str(m, "name")).contains(q)) return true;
            if ((highlightMode == 0 || highlightMode == 5) && lc(str(m, "unit")).equals(q)) return true;
            return (
                (highlightMode == 0 || highlightMode == 6) &&
                (lc(str(m, "doc")).contains(q) || lc(str(m, "desc")).contains(q))
            );
        }

        static String issueTip(List<Issue> l) {
            StringBuilder sb = new StringBuilder("<html><body style='width:380px'>");
            for (Issue is : l)
                sb.append("<font color='")
                    .append(hx(is.sev().color()))
                    .append("'>")
                    .append(is.sev().label)
                    .append("</font>: ")
                    .append(escHtml(is.text()))
                    .append("<br>");
            return sb.append("</body></html>").toString();
        }

        List<Object> points() {
            return msg == null ? List.of() : listOrEmpty(msg.get("points"));
        }

        List<Object> fields() {
            return msg == null ? List.of() : listOrEmpty(msg.get("fields"));
        }

        final class PointsModel extends AbstractTableModel {

            final String[] cols = {
                "#",
                "Bits",
                "Size",
                "Name",
                "Published to (MQTT)",
                "C type",
                "Signed",
                "Endian",
                "Parse",
                "Formatter",
                "Arg",
                "IEEE754",
                "Default",
                "Simulation",
            };

            Map<String, Object> pt(int r) {
                List<Object> l = points();
                return r >= 0 && r < l.size() ? asMap(l.get(r)) : null;
            }

            public int getRowCount() {
                return points().size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            public String getColumnName(int c) {
                return cols[c];
            }

            public Class<?> getColumnClass(int c) {
                return switch (c) {
                    case P_NUM, P_SIZE -> Integer.class;
                    case P_SIGNED, P_PARSE, P_IEEE -> Boolean.class;
                    default -> String.class;
                };
            }

            public boolean isCellEditable(int r, int c) {
                return pt(r) != null && c >= P_SIZE && c <= P_DEFAULT && c != P_TOPIC;
            }

            public Object getValueAt(int r, int c) {
                Map<String, Object> p = pt(r);
                if (c == P_NUM) return r + 1;
                if (p == null) return c == P_NAME ? "(not an object)" : null;
                return switch (c) {
                    case P_BITS -> {
                        int[] off = offsets(points());
                        int sz = sizeOf(p);
                        yield sz <= 1 ? String.valueOf(off[r]) : off[r] + "–" + (off[r] + sz - 1);
                    }
                    case P_SIZE -> longVal(p.get("size")) instanceof Long l ? (Object) (int) (long) l : null;
                    case P_NAME -> strOr(p, "name", "");
                    case P_TOPIC -> {
                        if (!isParsed(p)) yield "padding";
                        List<String> t = fieldsReferencing(msg, r + 1);
                        yield t.isEmpty() ? "not published" : String.join(", ", t);
                    }
                    case P_CTYPE -> strOr(p, "c_type", "");
                    case P_SIGNED -> Boolean.TRUE.equals(p.get("signed"));
                    case P_ENDIAN -> strOr(p, "endianness", "");
                    case P_PARSE -> isParsed(p);
                    case P_FMT -> formatterKey(p);
                    case P_ARG -> formatterArg(p);
                    case P_IEEE -> Boolean.TRUE.equals(p.get("ieee754_f32"));
                    case P_DEFAULT -> plain(num(p, "default"));
                    case P_SIM -> simSummary(p.get("sim"));
                    default -> null;
                };
            }

            public void setValueAt(Object v, int r, int c) {
                Map<String, Object> p = pt(r);
                if (p == null) return;
                if (Objects.equals(v instanceof String str ? str.strip() : v, getValueAt(r, c))) return;
                String s = v == null ? "" : String.valueOf(v).strip();
                BigDecimal num = s.isEmpty() ? null : parseNum(s);
                if ((c == P_ARG || c == P_DEFAULT) && !s.isEmpty() && num == null) {
                    bad(null, (c == P_ARG ? "Formatter arg" : "default") + " must be a number");
                    return;
                }
                Integer newSize = v instanceof Integer n && n >= 1 ? n : null;
                if (c == P_SIZE && newSize == null) {
                    bad(null, "Size must be a whole number of bits, 1 or more");
                    return;
                }
                boolean ch = switch (c) {
                    case P_SIZE -> putNum(p, "size", BigDecimal.valueOf(newSize));
                    case P_NAME -> putStr(p, "name", s, true);
                    case P_CTYPE -> putStr(p, "c_type", s, true);
                    case P_SIGNED -> putBool(p, "signed", Boolean.TRUE.equals(v), false);
                    case P_ENDIAN -> putStr(p, "endianness", s, true);
                    case P_PARSE -> putBool(p, "parse", Boolean.TRUE.equals(v), true);
                    case P_FMT -> setFormatterKey(p, s);
                    case P_ARG -> setFormatterArg(p, num);
                    case P_IEEE -> putBool(p, "ieee754_f32", Boolean.TRUE.equals(v), false);
                    case P_DEFAULT -> putNum(p, "default", num);
                    default -> false;
                };
                if (ch) changed(false);
            }
        }

        boolean setFormatterKey(Map<String, Object> p, String k) {
            if (p.containsKey("format") && !p.containsKey("formatter")) return putStr(p, "format", k, true);
            if (k.isEmpty()) {
                if (p.containsKey("formatter")) {
                    p.remove("formatter");
                    return true;
                }
                return false;
            }
            Map<String, Object> f = asMap(p.get("formatter"));
            if (f == null) {
                f = new LinkedHashMap<>();
                p.put("formatter", f);
            }
            if (k.equals(f.get("key"))) return false;
            f.put("key", k);
            return true;
        }

        boolean setFormatterArg(Map<String, Object> p, BigDecimal b) {
            Map<String, Object> f = asMap(p.get("formatter"));
            if (f == null) {
                if (b == null) return false;
                if (p.containsKey("format")) {
                    bad(null, "This point uses the legacy \"format\" string; clear it before adding a formatter arg");
                    return false;
                }
                f = new LinkedHashMap<>();
                f.put("key", "divide");
                p.put("formatter", f);
            }
            return putNum(f, "arg", b);
        }

        void addPoint() {
            if (msg == null) return;
            stopEditing(pointsTable);
            List<Object> pts = ensureList(msg, "points");
            pts.add(newPoint("point" + (pts.size() + 1)));
            changed(false);
            selectRow(pointsTable, pts.size() - 1);
        }

        void insertPoint() {
            if (msg == null) return;
            stopEditing(pointsTable);
            int r = pointsTable.getSelectedRow();
            if (r < 0) {
                addPoint();
                return;
            }
            List<Object> pts = ensureList(msg, "points");
            pts.add(r, newPoint("point" + (pts.size() + 1)));
            final int at = r + 1;
            remapPointRefs(msg, k -> k >= at ? k + 1 : k);
            changed(false);
            selectRow(pointsTable, r);
        }

        void duplicatePoint() {
            int r = pointsTable.getSelectedRow();
            if (msg == null || r < 0) return;
            stopEditing(pointsTable);
            List<Object> pts = ensureList(msg, "points");
            pts.add(r + 1, Json.deepCopy(pts.get(r)));
            final int at = r + 2;
            remapPointRefs(msg, k -> k >= at ? k + 1 : k);
            changed(false);
            selectRow(pointsTable, r + 1);
        }

        void removePoints() {
            int[] rows = pointsTable.getSelectedRows();
            if (msg == null || rows.length == 0) return;
            stopEditing(pointsTable);
            Set<String> refs = new LinkedHashSet<>();
            for (int r : rows) refs.addAll(fieldsReferencing(msg, r + 1));
            String q = "Remove " + rows.length + " point(s)?";
            if (!refs.isEmpty()) q +=
                "\nThese fields reference them and will lose those references:\n   " + String.join("\n   ", refs);
            if (
                JOptionPane.showConfirmDialog(app, q, "Remove points", JOptionPane.OK_CANCEL_OPTION) !=
                JOptionPane.OK_OPTION
            ) return;
            List<Object> pts = ensureList(msg, "points");
            Arrays.sort(rows);
            for (int i = rows.length - 1; i >= 0; i--) {
                final int k1 = rows[i] + 1;
                pts.remove(rows[i]);
                remapPointRefs(msg, k -> k == k1 ? -1 : k > k1 ? k - 1 : k);
            }
            changed(false);
            selectRow(pointsTable, Math.min(rows[0], pts.size() - 1));
        }

        void movePoint(int dir) {
            int r = pointsTable.getSelectedRow();
            if (msg == null || r < 0) return;
            int t = r + dir;
            if (t < 0 || t >= points().size()) return;
            movePointTo(r, dir > 0 ? t + 1 : t);
        }

        void padToByte() {
            if (msg == null) return;
            stopEditing(pointsTable);
            List<Object> pts = ensureList(msg, "points");
            int total = offsets(pts)[pts.size()];
            int pad = (8 - (total % 8)) % 8;
            if (pad == 0) {
                app.status.setText("Already byte aligned (" + total + " bits)");
                return;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("size", BigDecimal.valueOf(pad));
            p.put("parse", Boolean.FALSE);
            pts.add(p);
            changed(false);
            selectRow(pointsTable, pts.size() - 1);
            app.status.setText("Added " + pad + " padding bit(s); the message is now " + (total + pad) + " bits");
        }

        void editSim(int row) {
            Map<String, Object> p = pointsModel.pt(row);
            if (p == null) return;
            stopEditing(pointsTable);
            Map<String, Object> cur = asMap(p.get("sim"));
            JDialog d = new JDialog(app, "Simulation for " + pointLabel(p, row), true);
            JRadioButton rNone = new JRadioButton("None"),
                rSweep = new JRadioButton("Sweep (range)"),
                rEnum = new JRadioButton("Enum (choices)");

            ButtonGroup bg = new ButtonGroup();
            bg.add(rNone);
            bg.add(rSweep);
            bg.add(rEnum);
            JTextField fMin = new JTextField(8),
                fMax = new JTextField(8),
                fIncMin = new JTextField(8),
                fIncMax = new JTextField(8);
            JCheckBox fRound = new JCheckBox("Round to whole numbers");
            DefaultTableModel em = new DefaultTableModel(new Object[] { "Value", "Probability" }, 0);
            JTable et = new JTable(em);
            styleTable(et);
            et.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            ToneLabel sumLabel = new ToneLabel(" ");
            Runnable updSum = () -> {
                BigDecimal s = BigDecimal.ZERO;
                boolean ok = true;
                for (int i = 0; i < em.getRowCount(); i++) {
                    BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                    if (b == null) ok = false;
                    else s = s.add(b);
                }
                boolean one = ok && s.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) <= 0;
                sumLabel.set(
                    "Probabilities add up to " + (ok ? plain(s) : "?") + (one ? "" : ". They must add up to 1"),
                    one ? ToneLabel.OK : ToneLabel.ERR
                );
            };
            em.addTableModelListener(e -> updSum.run());

            if (cur != null && cur.containsKey("options")) {
                rEnum.setSelected(true);
                for (Object o : listOrEmpty(cur.get("options"))) {
                    List<Object> pr = asList(o);
                    if (pr != null && pr.size() == 2) em.addRow(new Object[] {
                        Json.writeCompact(pr.getFirst()),
                        Json.writeCompact(pr.getLast()),
                    });
                }
            } else if (cur != null) {
                rSweep.setSelected(true);
                fMin.setText(plain(num(cur, "min")));
                fMax.setText(plain(num(cur, "max")));
                fIncMin.setText(plain(num(cur, "inc_min")));
                fIncMax.setText(plain(num(cur, "inc_max")));
                fRound.setSelected(Boolean.TRUE.equals(cur.get("round")));
            } else rNone.setSelected(true);
            if (em.getRowCount() == 0) {
                em.addRow(new Object[] { "0", "0.5" });
                em.addRow(new Object[] { "1", "0.5" });
            }
            updSum.run();

            JPanel sweepP = new JPanel(new GridLayout(0, 2, 6, 4));
            sweepP.add(new JLabel("Min value"));
            sweepP.add(fMin);
            sweepP.add(new JLabel("Max value"));
            sweepP.add(fMax);
            sweepP.add(new JLabel("Min increment"));
            sweepP.add(fIncMin);
            sweepP.add(new JLabel("Max increment"));
            sweepP.add(fIncMax);
            sweepP.add(fRound);
            JPanel enumP = new JPanel(new BorderLayout(0, 4));
            JScrollPane esp = new JScrollPane(et);
            esp.setPreferredSize(new Dimension(320, 150));
            enumP.add(esp, BorderLayout.CENTER);
            JPanel eb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            eb.add(button("Add", null, () -> em.addRow(new Object[] { String.valueOf(em.getRowCount()), "0" })));
            eb.add(
                button("Remove", null, () -> {
                    stopEditing(et);
                    int r = et.getSelectedRow();
                    if (r >= 0) em.removeRow(r);
                })
            );
            eb.add(
                button("Equal split", "Give every choice the same probability", () -> {
                    stopEditing(et);
                    int n = em.getRowCount();
                    if (n == 0) return;
                    BigDecimal each = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.DOWN);
                    BigDecimal rest = BigDecimal.ONE.subtract(each.multiply(BigDecimal.valueOf(n - 1)));
                    for (int i = 0; i < n; i++) em.setValueAt(plain(i == n - 1 ? rest : each), i, 1);
                })
            );
            eb.add(
                button("Normalize", "Scale probabilities so they add up to 1", () -> {
                    stopEditing(et);
                    BigDecimal s = BigDecimal.ZERO;
                    for (int i = 0; i < em.getRowCount(); i++) {
                        BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                        if (b != null) s = s.add(b);
                    }
                    if (s.signum() == 0) return;
                    BigDecimal acc = BigDecimal.ZERO;
                    for (int i = 0; i < em.getRowCount(); i++) {
                        BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                        if (b == null) b = BigDecimal.ZERO;
                        BigDecimal nb =
                            i == em.getRowCount() - 1
                                ? BigDecimal.ONE.subtract(acc)
                                : b.divide(s, 6, java.math.RoundingMode.HALF_UP);
                        acc = acc.add(nb);
                        em.setValueAt(plain(nb), i, 1);
                    }
                })
            );
            JPanel south = new JPanel(new BorderLayout());
            south.add(eb, BorderLayout.NORTH);
            south.add(sumLabel, BorderLayout.SOUTH);
            enumP.add(south, BorderLayout.SOUTH);

            CardLayout cl = new CardLayout();
            JPanel opts = new JPanel(cl);
            opts.add(new JLabel("This point is not simulated."), "none");
            opts.add(sweepP, "sweep");
            opts.add(enumP, "enum");
            ActionListener sw = e ->
                cl.show(opts, rSweep.isSelected() ? "sweep" : rEnum.isSelected() ? "enum" : "none");
            rNone.addActionListener(sw);
            rSweep.addActionListener(sw);
            rEnum.addActionListener(sw);
            sw.actionPerformed(null);

            double[] range = physRange(p);
            JLabel rangeL = new MutedLabel(
                range == null
                    ? "Values are in physical units (after the formatter)."
                    : "Representable range after the formatter: " + fmtD(range[0]) + " … " + fmtD(range[1])
            );

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.add(rNone);
            top.add(rSweep);
            top.add(rEnum);
            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.setBorder(new EmptyBorder(10, 12, 10, 12));
            content.add(top, BorderLayout.NORTH);
            content.add(opts, BorderLayout.CENTER);
            content.add(rangeL, BorderLayout.SOUTH);

            JButton ok = new JButton("Apply");
            ok.putClientProperty("primary", Boolean.TRUE);
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> d.dispose());
            ok.addActionListener(e -> {
                stopEditing(et);
                Object newSim;
                if (rNone.isSelected()) newSim = null;
                else if (rSweep.isSelected()) {
                    BigDecimal a = parseNum(fMin.getText()),
                        b = parseNum(fMax.getText()),
                        c1 = parseNum(fIncMin.getText()),
                        c2 = parseNum(fIncMax.getText());
                    if (a == null || b == null || c1 == null || c2 == null) {
                        JOptionPane.showMessageDialog(d, "Enter numbers for min, max and both increments.");
                        return;
                    }
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("min", a);
                    s.put("max", b);
                    s.put("inc_min", c1);
                    s.put("inc_max", c2);
                    s.put("round", fRound.isSelected());
                    newSim = s;
                } else {
                    List<Object> optsL = new ArrayList<>();
                    for (int i = 0; i < em.getRowCount(); i++) {
                        BigDecimal v = parseNum(String.valueOf(em.getValueAt(i, 0))),
                            f = parseNum(String.valueOf(em.getValueAt(i, 1)));
                        if (v == null || f == null) {
                            JOptionPane.showMessageDialog(
                                d,
                                "Row " + (i + 1) + " needs a numeric value and probability."
                            );
                            return;
                        }
                        optsL.add(new ArrayList<Object>(Arrays.asList(v, f)));
                    }
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("options", optsL);
                    newSim = s;
                }
                boolean ch;
                if (newSim == null) {
                    ch = p.containsKey("sim");
                    p.remove("sim");
                } else {
                    ch = !p.containsKey("sim") || !Json.write(p.get("sim")).equals(Json.write(newSim));
                    p.put("sim", newSim);
                }
                d.dispose();
                if (ch) changed(false);
            });
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btns.add(cancel);
            btns.add(ok);
            d.getRootPane().setDefaultButton(ok);
            d.add(content, BorderLayout.CENTER);
            d.add(btns, BorderLayout.SOUTH);
            d.pack();
            d.setLocationRelativeTo(app);
            d.setVisible(true);
        }

        // ---------- fields tab

        JComponent buildFieldsTab() {
            fieldsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            fieldsTable.setSurrendersFocusOnKeystroke(true);
            styleTable(fieldsTable);
            RowStyle rs = (t, row, col, sel, comp) -> {
                Map<String, Object> f = fieldsModel.fd(row);
                Severity s = fieldSev.get(row);
                if (s != null && !sel) comp.setBackground(s.bg());
                else if (!sel && rowMatches(f, false)) comp.setBackground(T.tint(T.accent()));
                List<Issue> l = fieldIssues.get(row);
                if (l != null) comp.setToolTipText(issueTip(l));
                else if (col == 3 && f != null) comp.setToolTipText(valuesTip(f));
                if (col == 1) comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
                if (col == 3 && f != null && listOrEmpty(f.get("values")).isEmpty() && !sel) comp.setForeground(
                    T.yellow()
                );
            };
            fieldsTable.setDefaultRenderer(Object.class, new StyledRenderer(rs));
            fieldsTable.setDefaultRenderer(String.class, new StyledRenderer(rs));
            fieldsTable.setDefaultRenderer(Integer.class, new StyledRenderer(rs));
            TableColumnModel cm = fieldsTable.getColumnModel();
            cm.getColumn(2).setCellEditor(new DefaultCellEditor(unitCombo));
            cm.getColumn(3).setCellEditor(new PointPicker());
            int[] widths = { 34, 320, 60, 190, 240, 200 };
            for (int i = 0; i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);
            cm.getColumn(0).setMaxWidth(40);
            fieldsTable.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) loadFieldDetail();
            });

            fieldDesc.setLineWrap(true);
            fieldDesc.setWrapStyleWord(true);
            fieldDesc.addFocusListener(
                new FocusAdapter() {
                    public void focusLost(FocusEvent e) {
                        commitFieldDetail();
                    }
                }
            );

            Bar tb = new Bar();
            tb.add(button("Add field", null, this::addField));
            tb.add(button("Duplicate", null, this::duplicateField));
            tb.add(button("Remove", null, this::removeFields));
            tb.add(button("▲", "Move up", () -> moveField(-1)));
            tb.add(button("▼", "Move down", () -> moveField(1)));
            tb.sep();
            tb.add(
                button(
                    "Add fields for unused points",
                    "Create one field per parsed point that no field publishes yet",
                    this::generateFields
                )
            );

            JPanel detail = new JPanel(new BorderLayout(0, 6));
            detail.setBorder(new EmptyBorder(8, 10, 8, 10));
            detail.add(fieldMapping, BorderLayout.NORTH);
            JPanel dRow = new JPanel(new BorderLayout(8, 0));
            dRow.add(new MutedLabel("Notes (desc)"), BorderLayout.WEST);
            dRow.add(new JScrollPane(fieldDesc), BorderLayout.CENTER);
            detail.add(dRow, BorderLayout.CENTER);

            JPanel p = new JPanel(new BorderLayout());
            p.add(tb, BorderLayout.NORTH);
            JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(fieldsTable), detail);
            sp.setResizeWeight(0.8);
            sp.setBorder(null);
            p.add(sp, BorderLayout.CENTER);
            return p;
        }

        String valuesTip(Map<String, Object> f) {
            StringBuilder sb = new StringBuilder("<html>");
            List<Object> pts = points();
            for (Object v : listOrEmpty(f.get("values"))) {
                Long k = longVal(v);
                if (k == null || k < 1 || k > pts.size()) {
                    sb.append(escHtml(String.valueOf(v))).append(": (invalid)<br>");
                    continue;
                }
                Map<String, Object> p = asMap(pts.get((int) (k - 1)));
                sb.append(k)
                    .append(": ")
                    .append(escHtml(strOr(p, "name", "?")))
                    .append(" (")
                    .append(sizeOf(p))
                    .append(" bits)<br>");
            }
            return sb.append("</html>").toString();
        }

        final class FieldsModel extends AbstractTableModel {

            final String[] cols = { "#", "Topic", "Unit", "Maps to points", "Doc", "Notes (desc)" };

            Map<String, Object> fd(int r) {
                List<Object> l = fields();
                return r >= 0 && r < l.size() ? asMap(l.get(r)) : null;
            }

            public int getRowCount() {
                return fields().size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            public String getColumnName(int c) {
                return cols[c];
            }

            public Class<?> getColumnClass(int c) {
                return c == 0 ? Integer.class : String.class;
            }

            public boolean isCellEditable(int r, int c) {
                return c > 0 && fd(r) != null;
            }

            public Object getValueAt(int r, int c) {
                if (c == 0) return r + 1;
                Map<String, Object> f = fd(r);
                if (f == null) return c == 1 ? "(not an object)" : "";
                return switch (c) {
                    case 1 -> strOr(f, "name", "");
                    case 2 -> strOr(f, "unit", "");
                    case 3 -> {
                        StringBuilder sb = new StringBuilder();
                        List<Object> pts = points();
                        for (Object v : listOrEmpty(f.get("values"))) {
                            if (!sb.isEmpty()) sb.append(", ");
                            Long k = longVal(v);
                            if (k == null || k < 1 || k > pts.size()) {
                                sb.append(Json.writeCompact(v)).append(" (missing)");
                                continue;
                            }
                            String nm = strOr(asMap(pts.get((int) (k - 1))), "name", "");
                            sb.append('#')
                                .append(k)
                                .append(nm.isEmpty() ? "" : " " + nm);
                        }
                        yield sb.isEmpty() ? "choose points…" : sb.toString();
                    }
                    case 4 -> strOr(f, "doc", "");
                    default -> strOr(f, "desc", "");
                };
            }

            public void setValueAt(Object v, int r, int c) {
                Map<String, Object> f = fd(r);
                if (f == null) return;
                String s = v == null ? "" : String.valueOf(v);
                if (s.strip().equals(getValueAt(r, c))) return;
                List<Object> newValues = new ArrayList<>();
                if (c == 3) {
                    if (v instanceof List<?> list) newValues.addAll(list);
                    else for (String tok : s.split("[,\\s]+")) {
                        if (tok.isEmpty()) continue;
                        Integer k = tryInt(tok);
                        if (k == null) {
                            bad(null, "Points must be 1-based point numbers separated by commas, e.g. 1, 2, 3");
                            return;
                        }
                        newValues.add(BigDecimal.valueOf(k));
                    }
                }
                boolean ch = switch (c) {
                    case 1 -> putStr(f, "name", s.strip(), false);
                    case 2 -> putStr(f, "unit", s.strip(), false);
                    case 3 -> {
                        Object cur = f.get("values");
                        if (cur != null && Json.write(cur).equals(Json.write(newValues))) yield false;
                        f.put("values", newValues);
                        yield true;
                    }
                    case 4 -> putStr(f, "doc", s.strip(), false);
                    case 5 -> putStr(f, "desc", s.strip(), false);
                    default -> false;
                };
                if (ch) changed(false);
            }
        }

        void loadFieldDetail() {
            commitFieldDetail();
            int r = fieldsTable.getSelectedRow();
            detailField = fieldsModel.fd(r);
            if (detailField == null) {
                fieldMapping.setText(" ");
                fieldDesc.setText("");
                fieldDesc.setEnabled(false);
                return;
            }
            fieldDesc.setEnabled(true);
            fieldDesc.setText(strOr(detailField, "desc", ""));
            fieldDesc.setCaretPosition(0);
            StringBuilder sb = new StringBuilder("<html><b>Publishes:</b> ");
            List<Object> pts = points();
            int n = 0;
            for (Object v : listOrEmpty(detailField.get("values"))) {
                Long k = longVal(v);
                if (n++ > 0) sb.append(", &nbsp;");
                if (k == null || k < 1 || k > pts.size()) {
                    sb.append("<font color='")
                        .append(hx(T.red()))
                        .append("'>")
                        .append(escHtml(Json.writeCompact(v)))
                        .append(" (no such point)</font>");
                    continue;
                }
                Map<String, Object> p = asMap(pts.get((int) (k - 1)));
                sb.append('#')
                    .append(k)
                    .append(' ')
                    .append(escHtml(strOr(p, "name", "?")))
                    .append(" <font color='")
                    .append(hx(T.muted()))
                    .append("'>(")
                    .append(sizeOf(p))
                    .append(" bits");
                if (p != null && !formatterKey(p).isEmpty()) sb.append(", ")
                    .append(escHtml(formatterKey(p)))
                    .append(' ')
                    .append(formatterArg(p));
                sb.append(")</font>");
            }
            if (n == 0) sb.append("<font color='")
                .append(hx(T.yellow()))
                .append("'>nothing yet. Click the Maps to points cell to choose.</font>");
            fieldMapping.setText(sb.append("</html>").toString());
        }

        void commitFieldDetail() {
            if (detailField == null || loading) return;
            String t = fieldDesc.getText().strip();
            if (t.equals(strOr(detailField, "desc", ""))) return;
            if (putStr(detailField, "desc", t, false)) {
                changed(false);
            }
        }

        String topicPrefix() {
            String key = str(msg, "key");
            if (key != null && !key.isEmpty()) return "Calypso/Bidir/State/" + key;
            List<String[]> segs = new ArrayList<>();
            for (Object o : fields()) {
                String nm = str(asMap(o), "name");
                if (nm != null && nm.contains("/")) segs.add(nm.split("/"));
            }
            if (segs.isEmpty()) return (
                app.predominantNode(file) + "/" + strOr(msg, "desc", "Message").replaceAll("[^A-Za-z0-9_]", "")
            );
            int len = segs.getFirst().length - 1;
            for (String[] s : segs) {
                len = Math.min(len, s.length - 1);
                for (int i = 0; i < len; i++) if (!s[i].equals(segs.getFirst()[i])) {
                    len = i;
                    break;
                }
            }
            if (len <= 0) return app.predominantNode(file);
            return String.join("/", Arrays.copyOf(segs.getFirst(), len));
        }

        void addField() {
            if (msg == null) return;
            stopEditing(fieldsTable);
            List<Object> fds = ensureList(msg, "fields");
            fds.add(newField(topicPrefix() + "/new_field", 0));
            changed(false);
            selectRow(fieldsTable, fds.size() - 1);
            fieldsTable.editCellAt(fds.size() - 1, 1);
            Component ed = fieldsTable.getEditorComponent();
            if (ed != null) ed.requestFocusInWindow();
        }

        void duplicateField() {
            int r = fieldsTable.getSelectedRow();
            if (msg == null || r < 0) return;
            stopEditing(fieldsTable);
            List<Object> fds = ensureList(msg, "fields");
            fds.add(r + 1, Json.deepCopy(fds.get(r)));
            changed(false);
            selectRow(fieldsTable, r + 1);
        }

        void removeFields() {
            int[] rows = fieldsTable.getSelectedRows();
            if (msg == null || rows.length == 0) return;
            stopEditing(fieldsTable);
            commitFieldDetail();
            detailField = null;
            List<Object> fds = ensureList(msg, "fields");
            Arrays.sort(rows);
            for (int i = rows.length - 1; i >= 0; i--) fds.remove(rows[i]);
            changed(false);
            selectRow(fieldsTable, Math.min(rows[0], fds.size() - 1));
        }

        void moveField(int dir) {
            int r = fieldsTable.getSelectedRow();
            if (msg == null || r < 0) return;
            List<Object> fds = ensureList(msg, "fields");
            int t = r + dir;
            if (t < 0 || t >= fds.size()) return;
            stopEditing(fieldsTable);
            commitFieldDetail();
            Collections.swap(fds, r, t);
            changed(false);
            selectRow(fieldsTable, t);
        }

        void generateFields() {
            if (msg == null) return;
            stopEditing(fieldsTable);
            List<Object> pts = points();
            boolean[] ref = new boolean[pts.size()];
            for (Object o : fields()) {
                Map<String, Object> f = asMap(o);
                if (f == null) continue;
                for (Object v : listOrEmpty(f.get("values"))) {
                    Long k = longVal(v);
                    if (k != null && k >= 1 && k <= pts.size()) ref[(int) (k - 1)] = true;
                }
                Matcher mm = PLACEHOLDER_NUM.matcher(strOr(f, "name", ""));
                while (mm.find()) {
                    int k = Integer.parseInt(mm.group(1));
                    if (k >= 1 && k <= pts.size()) ref[k - 1] = true;
                }
            }
            String prefix = topicPrefix();
            List<Object> fds = ensureList(msg, "fields");
            int added = 0;
            for (int i = 0; i < pts.size(); i++) {
                Map<String, Object> p = asMap(pts.get(i));
                if (!isParsed(p) || ref[i]) continue;
                String nm = strOr(p, "name", "");
                fds.add(newField(prefix + "/" + (nm.isEmpty() ? "point" + (i + 1) : nm), i + 1));
                added++;
            }
            if (added == 0) {
                app.status.setText("Every parsed point is already published by a field");
                return;
            }
            changed(false);
            app.status.setText("Added " + added + " field(s) under " + prefix + "/ — review the names, units and docs");
        }

        // ---------- message families

        final JPanel familyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        final JComboBox<String> familyCombo = new JComboBox<>();
        final ToneLabel familyInfo = new ToneLabel(" ");
        List<Map<String, Object>> familyMembers = new ArrayList<>();

        JComponent buildFamilyRow() {
            familyRow.setOpaque(false);
            familyRow.add(new MutedLabel("Family"));
            familyRow.add(familyCombo);
            familyRow.add(
                button(
                    "Add member…",
                    "Copy this message as a new member with its own name and the next free ID",
                    this::addFamilyMember
                )
            );
            familyRow.add(
                button(
                    "Copy layout to family…",
                    "Give other members this message's points and fields, keeping their own names",
                    this::copyLayoutToFamily
                )
            );
            familyRow.add(familyInfo);
            familyCombo.setToolTipText("Messages whose topics differ only in one level. Pick one to open it.");
            familyCombo.addActionListener(e -> {
                if (loading || msg == null) return;
                int i = familyCombo.getSelectedIndex();
                if (i >= 0 && i < familyMembers.size() && familyMembers.get(i) != msg) app.selectMessage(
                    file,
                    familyMembers.get(i)
                );
            });
            familyRow.setVisible(false);
            return familyRow;
        }

        static String replaceWord(String s, String from, String to) {
            if (s == null || from == null || from.isEmpty()) return s;
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(from) + "(?![A-Za-z0-9_])")
                .matcher(s)
                .replaceAll(Matcher.quoteReplacement(to));
        }

        static void renameInField(Map<String, Object> fd, int seg, String from, String to) {
            if (fd == null) return;
            String n = str(fd, "name");
            if (n != null) {
                String[] parts = n.split("/", -1);
                if (seg < parts.length && parts[seg].equals(from)) {
                    parts[seg] = to;
                    fd.put("name", String.join("/", parts));
                }
            }
            for (String k : new String[] { "doc", "desc" })
                if (fd.get(k) instanceof String text) fd.put(k, replaceWord(text, from, to));
        }

        /** src's points and fields, renamed for target: what "copy layout" would give target. */
        static Map<String, Object> layoutFor(Map<String, Object> src, Map<String, Object> target, int seg) {
            String from = memberToken(src, seg),
                to = memberToken(target, seg);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("points", Json.deepCopy(src.containsKey("points") ? src.get("points") : new ArrayList<>()));
            List<Object> fds = Json.deepCopy(src.containsKey("fields") ? asList(src.get("fields")) : new ArrayList<>());
            if (from != null && to != null) for (Object o : fds) renameInField(asMap(o), seg, from, to);
            out.put("fields", fds);
            return out;
        }

        /** Short description of what copying `lay` onto `s` would change, or null if nothing. */
        static String describeDiff(Map<String, Object> s, Map<String, Object> lay) {
            List<String> parts = new ArrayList<>();
            for (String what : new String[] { "points", "fields" }) {
                List<Object> a = listOrEmpty(s.get(what)),
                    b = listOrEmpty(lay.get(what));
                String one = what.equals("points") ? "point" : "field";
                for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                    if (i >= a.size()) {
                        parts.add("adds " + one + " #" + (i + 1));
                        continue;
                    }
                    if (i >= b.size()) {
                        parts.add("removes " + one + " #" + (i + 1));
                        continue;
                    }
                    Map<String, Object> x = asMap(a.get(i)),
                        y = asMap(b.get(i));
                    if (Json.write(a.get(i)).equals(Json.write(b.get(i)))) continue;
                    if (x == null || y == null) {
                        parts.add(one + " #" + (i + 1));
                        continue;
                    }
                    Set<String> keys = new LinkedHashSet<>(x.keySet());
                    keys.addAll(y.keySet());
                    List<String> changedKeys = new ArrayList<>();
                    for (String k : keys) if (!Json.write(x.get(k)).equals(Json.write(y.get(k)))) changedKeys.add(k);
                    parts.add(one + " #" + (i + 1) + " " + String.join(", ", changedKeys));
                }
            }
            if (parts.isEmpty()) return null;
            String t = String.join("; ", parts);
            return t.length() > 160 ? t.substring(0, 157) + "…" : t;
        }

        void refreshFamily() {
            Family fam = msg == null ? null : app.families.get(msg);
            familyRow.setVisible(fam != null);
            if (fam == null) return;
            loading = true;
            try {
                familyMembers = new ArrayList<>(fam.members());
                DefaultComboBoxModel<String> mdl = new DefaultComboBoxModel<>();
                for (Map<String, Object> m : familyMembers)
                    mdl.addElement(strOr(m, "id", "?") + "  " + memberToken(m, fam.seg()));
                familyCombo.setModel(mdl);
                for (int i = 0; i < familyMembers.size(); i++) if (
                    familyMembers.get(i) == msg
                ) familyCombo.setSelectedIndex(i);
            } finally {
                loading = false;
            }
            int differ = 0;
            for (Map<String, Object> s : fam.members())
                if (s != msg && describeDiff(s, layoutFor(msg, s, fam.seg())) != null) differ++;
            familyInfo.set(
                fam.members().size() +
                    " messages under " +
                    fam.template() +
                    (differ == 0
                        ? ", all with the same layout"
                        : ", " + differ + " laid out differently from this one"),
                differ == 0 ? ToneLabel.MUTED : ToneLabel.WARN
            );
        }

        void addFamilyMember() {
            Family fam = msg == null ? null : app.families.get(msg);
            if (fam == null) return;
            commitAll();
            String from = memberToken(msg, fam.seg());
            if (from == null) {
                bad(null, "This message's topics no longer match its family, so it can't be copied as a member");
                return;
            }
            Object in = JOptionPane.showInputDialog(
                app,
                "Name for the new member. It replaces \"" + from + "\" in topics, docs and the description.",
                "Add family member",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                from + "2"
            );
            if (in == null) return;
            String name = in.toString().strip();
            if (name.isEmpty() || name.contains("/") || name.contains(" ")) {
                bad(null, "Use one word without / or spaces");
                return;
            }
            for (Map<String, Object> m : fam.members())
                if (name.equals(memberToken(m, fam.seg()))) {
                    bad(null, "There's already a member named " + name);
                    return;
                }
            Map<String, Object> copy = Json.deepCopy(msg);
            for (Object o : listOrEmpty(copy.get("fields"))) renameInField(asMap(o), fam.seg(), from, name);
            String d = strOr(copy, "desc", "");
            String nd = replaceWord(d, from, name);
            copy.put("desc", nd.equals(d) ? (d + " " + name).strip() : nd);
            long maxId = -1;
            boolean ext = false;
            for (Map<String, Object> m : fam.members()) {
                Long v = parseId(str(m, "id"));
                if (v != null && v > maxId) {
                    maxId = v;
                    ext = Boolean.TRUE.equals(m.get("is_ext"));
                }
            }
            String nid = app.nextFreeId(maxId, ext || maxId > 0x7FF);
            if (nid != null) copy.put("id", nid);
            SpecFile f = file;
            int at = 0;
            for (Map<String, Object> m : fam.members()) at = Math.max(at, indexOfIdentity(f.messages, m) + 1);
            f.messages.add(at, copy);
            fam.members().add(copy);
            app.families.put(copy, fam);
            app.nextUndoLabel = "add family member " + name;
            app.changed(f, false);
            app.rebuildTree();
            app.selectMessage(f, copy);
            app.status.setText("Added " + name + " as " + str(copy, "id") + ". Check its description and ID.");
        }

        void copyLayoutToFamily() {
            Family fam = msg == null ? null : app.families.get(msg);
            if (fam == null) return;
            commitAll();
            List<Map<String, Object>> sibs = new ArrayList<>(),
                lays = new ArrayList<>();
            List<JCheckBox> boxes = new ArrayList<>();
            JPanel list = new JPanel(new GridLayout(0, 1, 0, 4));
            list.setOpaque(false);
            for (Map<String, Object> s : fam.members()) {
                if (s == msg) continue;
                Map<String, Object> lay = layoutFor(msg, s, fam.seg());
                String diff = describeDiff(s, lay);
                if (diff == null) continue;
                JCheckBox cb = new JCheckBox(
                    "<html><b>" +
                        escHtml(strOr(s, "id", "?") + "  " + memberToken(s, fam.seg())) +
                        "</b>&nbsp;&nbsp;<font color='" +
                        hx(T.muted()) +
                        "'>" +
                        escHtml(diff) +
                        "</font></html>",
                    true
                );
                cb.setOpaque(false);
                list.add(cb);
                sibs.add(s);
                lays.add(lay);
                boxes.add(cb);
            }
            if (sibs.isEmpty()) {
                app.status.setText("Every family member already has this layout");
                return;
            }
            String from = Objects.requireNonNullElse(memberToken(msg, fam.seg()), "?");
            JPanel content = new JPanel(new BorderLayout(0, 10));
            content.add(
                new JLabel(
                    "<html><body style='width:520px'>Replace the points and fields of the ticked messages with those of <b>" +
                        escHtml(strOr(msg, "id", "") + " " + from) +
                        "</b>. \"" +
                        escHtml(from) +
                        "\" becomes each member's own name in topics and docs. " +
                        "IDs, descriptions and other settings stay as they are. Untick members whose differences are intentional. You can undo this.</body></html>"
                ),
                BorderLayout.NORTH
            );
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(620, Math.min(360, list.getPreferredSize().height + 10)));
            content.add(sp, BorderLayout.CENTER);
            int r = JOptionPane.showConfirmDialog(
                app,
                content,
                "Copy layout to family",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );
            if (r != JOptionPane.OK_OPTION) return;
            int n = 0;
            for (int i = 0; i < sibs.size(); i++) {
                if (!boxes.get(i).isSelected()) continue;
                sibs.get(i).put("points", lays.get(i).get("points"));
                sibs.get(i).put("fields", lays.get(i).get("fields"));
                n++;
            }
            if (n == 0) return;
            app.nextUndoLabel = "copy layout to " + n + " family member" + (n == 1 ? "" : "s");
            changed(false);
            refreshFamily();
            app.status.setText(
                "Copied this layout to " + n + " member" + (n == 1 ? "" : "s") + ". Undo (Ctrl+Z) reverts it."
            );
        }

        /** Drop the current message without committing pending edits (used when undo replaces the data). */
        void detach() {
            if (pointsTable.isEditing()) pointsTable.getCellEditor().cancelCellEditing();
            if (fieldsTable.isEditing()) fieldsTable.getCellEditor().cancelCellEditing();
            msg = null;
            file = null;
            detailField = null;
        }

        // ---------- cansend tab

        static final int TAB_DECODE = 3,
            TAB_SEND = 4,
            TAB_JSON = 5;
        final JComboBox<String> sendIface = editableCombo(List.of("can0", "can1", "vcan0"));
        final JPanel sendRows = new JPanel(new GridBagLayout());
        final JTextField sendCmd = new JTextField();
        final JLabel sendBytes = new JLabel(" ");
        final ToneLabel sendStatus = new ToneLabel(" ");
        final Map<Map<String, Object>, Map<Integer, String>> sendValues = new IdentityHashMap<>();
        final Map<Integer, JTextField> sendInputs = new HashMap<>();
        final Map<Integer, ToneLabel> sendRaw = new HashMap<>();
        boolean sendBuilding;

        JComponent buildSendTab() {
            sendCmd.setEditable(false);
            sendCmd.setFont(mono(Font.BOLD, sendCmd.getFont().getSize() + 2));
            sendBytes.setFont(mono(Font.PLAIN, sendBytes.getFont().getSize()));
            sendRows.setOpaque(false);
            sendRows.setBorder(new EmptyBorder(6, 14, 6, 14));
            JTextField ifaceEditor = (JTextField) sendIface.getEditor().getEditorComponent();
            ifaceEditor.getDocument().addDocumentListener(docListener(this::updateSend));
            sendIface.addActionListener(e -> updateSend());
            sendIface.setSelectedItem(app.prefs.get("canIface", "can0"));

            Bar top = new Bar();
            top.add(new MutedLabel("  Interface "));
            sendIface.setPreferredSize(new Dimension(110, sendIface.getPreferredSize().height));
            top.add(sendIface);
            top.sep();
            top.add(new MutedLabel("Fill with "));
            top.add(button("Defaults", "Each point's default where set, otherwise 0", () -> fillSend("default")));
            top.add(button("Zeros", null, () -> fillSend("zero")));
            top.add(button("Maximums", "Largest value each point can hold", () -> fillSend("max")));
            top.add(button("Random sim values", "Pick values the way the simulator would", () -> fillSend("sim")));

            JScrollPane sp = new JScrollPane(sendRows);
            sp.getVerticalScrollBar().setUnitIncrement(16);

            JPanel out = new JPanel(new BorderLayout(0, 6));
            out.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(10, 14, 10, 14)));
            JPanel cmdRow = new JPanel(new BorderLayout(8, 0));
            cmdRow.setOpaque(false);
            cmdRow.add(sendCmd, BorderLayout.CENTER);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            btns.setOpaque(false);
            JButton copy = button("Copy command", null, () -> {
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(sendCmd.getText()), null);
                app.prefs.put("canIface", ifaceName());
                app.status.setText("Copied: " + sendCmd.getText());
            });
            copy.putClientProperty("primary", Boolean.TRUE);
            btns.add(
                button("Check in Decode test", "Decode this payload to confirm it reads back as entered", () -> {
                    String cmd = sendCmd.getText();
                    int h = cmd.indexOf('#');
                    if (h < 0) return;
                    decodeInput.setText(
                        cmd
                            .substring(h + 1)
                            .replaceAll("(..)", "$1 ")
                            .strip()
                    );
                    tabs.setSelectedIndex(TAB_DECODE);
                })
            );
            btns.add(copy);
            cmdRow.add(btns, BorderLayout.EAST);
            out.add(cmdRow, BorderLayout.NORTH);
            out.add(sendBytes, BorderLayout.CENTER);
            out.add(sendStatus, BorderLayout.SOUTH);

            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(sp, BorderLayout.CENTER);
            p.add(out, BorderLayout.SOUTH);
            return p;
        }

        static DocumentListener docListener(Runnable r) {
            return new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    r.run();
                }

                public void removeUpdate(DocumentEvent e) {
                    r.run();
                }

                public void changedUpdate(DocumentEvent e) {
                    r.run();
                }
            };
        }

        String ifaceName() {
            Object o = sendIface.getEditor().getItem();
            String s = o == null ? "" : o.toString().strip();
            return s.isEmpty() ? "can0" : s;
        }

        Map<Integer, String> sendMap() {
            return sendValues.computeIfAbsent(msg, k -> new HashMap<>());
        }

        String unitFor(int pointIndex1) {
            for (Object o : fields()) {
                Map<String, Object> f = asMap(o);
                if (f != null && containsIdx(listOrEmpty(f.get("values")), pointIndex1)) {
                    String u = strOr(f, "unit", "");
                    if (!u.isEmpty()) return u;
                }
            }
            return "";
        }

        void buildSend() {
            if (msg == null) return;
            sendBuilding = true;
            try {
                sendRows.removeAll();
                sendInputs.clear();
                sendRaw.clear();
                Map<Integer, String> vals = sendMap();
                List<Object> pts = points();
                int[] off = offsets(pts);
                GridBagConstraints c = new GridBagConstraints();
                c.insets = new Insets(4, 4, 4, 10);
                c.anchor = GridBagConstraints.WEST;
                c.gridy = 0;
                String[] heads = { "Point", "Value", "", "Range", "Raw bits" };
                for (int i = 0; i < heads.length; i++) {
                    c.gridx = i;
                    sendRows.add(new MutedLabel(heads[i]), c);
                }
                for (int i = 0; i < pts.size(); i++) {
                    c.gridy = i + 1;
                    Map<String, Object> p = asMap(pts.get(i));
                    int size = sizeOf(pts.get(i));
                    String nm = p == null ? "(invalid)" : strOr(p, "name", "");
                    JLabel name = new JLabel("#" + (i + 1) + "  " + (nm.isEmpty() ? "unnamed" : nm));
                    c.gridx = 0;
                    c.fill = GridBagConstraints.NONE;
                    c.weightx = 0;
                    if (!isParsed(p)) {
                        name = new MutedLabel("#" + (i + 1) + "  padding");
                        sendRows.add(name, c);
                        c.gridx = 1;
                        sendRows.add(new MutedLabel("always 0"), c);
                        c.gridx = 3;
                        sendRows.add(new MutedLabel(size + " bit" + (size == 1 ? "" : "s") + " at " + off[i]), c);
                        continue;
                    }
                    name.setFont(name.getFont().deriveFont(Font.BOLD));
                    sendRows.add(name, c);

                    final int idx = i;
                    String init = vals.containsKey(i)
                        ? vals.get(i)
                        : plain(num(p, "default")).isEmpty()
                          ? "0"
                          : plain(num(p, "default"));
                    vals.put(i, init);
                    JTextField input;
                    JComponent editorComp;
                    List<String> choices = sendChoices(p);
                    if (!choices.isEmpty()) {
                        JComboBox<String> cb = editableCombo(choices);
                        cb.setSelectedItem(init);
                        input = (JTextField) cb.getEditor().getEditorComponent();
                        cb.addActionListener(e -> {
                            if (!sendBuilding) {
                                vals.put(idx, String.valueOf(cb.getEditor().getItem()));
                                updateSend();
                            }
                        });
                        editorComp = cb;
                    } else {
                        input = new JTextField(init, 12);
                        editorComp = input;
                    }
                    input.setFont(mono(Font.PLAIN, input.getFont().getSize()));
                    input.getDocument().addDocumentListener(
                        docListener(() -> {
                            if (!sendBuilding) {
                                vals.put(idx, input.getText());
                                updateSend();
                            }
                        })
                    );
                    sendInputs.put(i, input);
                    editorComp.setPreferredSize(new Dimension(150, editorComp.getPreferredSize().height));
                    c.gridx = 1;
                    sendRows.add(editorComp, c);

                    c.gridx = 2;
                    sendRows.add(new MutedLabel(unitFor(i + 1)), c);

                    double[] range = physRange(p);
                    StringBuilder info = new StringBuilder();
                    if (Boolean.TRUE.equals(p.get("ieee754_f32"))) info.append("32-bit float");
                    else if (range != null) info.append(fmtD(range[0])).append(" … ").append(fmtD(range[1]));
                    else info.append("raw value (formatter \"").append(formatterKey(p)).append("\" not applied)");
                    info.append("   ")
                        .append(size)
                        .append(size == 1 ? " bit" : " bits");
                    BigDecimal div = divisor(p);
                    if (div != null && div.compareTo(BigDecimal.ONE) != 0) info.append(", sent ×").append(plain(div));
                    c.gridx = 3;
                    sendRows.add(new MutedLabel(info.toString()), c);

                    ToneLabel raw = new ToneLabel(" ");
                    raw.setFont(mono(Font.PLAIN, raw.getFont().getSize()));
                    sendRaw.put(i, raw);
                    c.gridx = 4;
                    c.weightx = 1;
                    sendRows.add(raw, c);
                    c.weightx = 0;
                }
                c.gridy = pts.size() + 1;
                c.gridx = 0;
                c.weighty = 1;
                c.gridwidth = 5;
                sendRows.add(Box.createVerticalGlue(), c);
            } finally {
                sendBuilding = false;
            }
            sendRows.revalidate();
            sendRows.repaint();
            updateSend();
        }

        /** Preset values for the dropdown: enum sim options, or 0/1 for single-bit and bool points. */
        static List<String> sendChoices(Map<String, Object> p) {
            List<String> out = new ArrayList<>();
            Map<String, Object> sim = asMap(p.get("sim"));
            if (sim != null) for (Object o : listOrEmpty(sim.get("options"))) {
                List<Object> pr = asList(o);
                if (pr != null && pr.size() == 2 && pr.getFirst() instanceof BigDecimal v) out.add(plain(v));
            }
            if (out.isEmpty() && (sizeOf(p) == 1 || "bool".equals(str(p, "c_type")))) {
                out.add("0");
                out.add("1");
            }
            return out;
        }

        static Double parseSendValue(String s) {
            s = s == null ? "" : s.strip();
            if (s.isEmpty()) return 0.0;
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on")) return 1.0;
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off")) return 0.0;
            try {
                if (s.matches("-?0[xX][0-9a-fA-F]+")) {
                    boolean neg = s.startsWith("-");
                    long v = Long.parseLong(s.substring(neg ? 3 : 2), 16);
                    return (double) (neg ? -v : v);
                }
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Raw (pre-shift) bits for a physical value, or null with a reason in err[0]. */
        static Long encodePoint(Map<String, Object> p, double v, String[] err) {
            int size = sizeOf(p);
            if (Boolean.TRUE.equals(p.get("ieee754_f32"))) return Float.floatToRawIntBits((float) v) & 0xFFFFFFFFL;
            BigDecimal div = divisor(p);
            double scaled = div == null ? v : v * div.doubleValue();
            long r = Math.round(scaled);
            boolean signed = Boolean.TRUE.equals(p.get("signed"));
            long lo = signed ? -(1L << (size - 1)) : 0,
                hi = signed ? (1L << (size - 1)) - 1 : (1L << size) - 1;
            if (r < lo || r > hi) {
                err[0] =
                    "doesn't fit in " +
                    size +
                    (signed ? " signed" : "") +
                    " bits (raw " +
                    r +
                    ", allowed " +
                    lo +
                    "…" +
                    hi +
                    "); clamped";
                r = Math.clamp(r, lo, hi);
            } else if (div != null && Math.abs(scaled - r) > 1e-6) {
                err[0] = "rounded to " + fmtD(r / div.doubleValue());
            }
            long mask = size >= 64 ? -1L : (1L << size) - 1;
            r &= mask;
            if ("little".equals(p.get("endianness")) && size % 8 == 0) {
                long s2 = 0;
                for (int k = 0; k < size / 8; k++) s2 = (s2 << 8) | ((r >>> (8 * k)) & 0xFF);
                r = s2;
            }
            return r;
        }

        static void writeBits(byte[] data, int off, int size, long raw) {
            for (int b = 0; b < size; b++) {
                if (((raw >>> (size - 1 - b)) & 1) == 0) continue;
                int pos = off + b;
                if (pos / 8 < data.length) data[pos / 8] |= (byte) (1 << (7 - (pos % 8)));
            }
        }

        static String cansendId(Map<String, Object> msg) {
            Long id = parseId(str(msg, "id"));
            if (id == null) return null;
            boolean ext = Boolean.TRUE.equals(msg.get("is_ext")) || id > 0x7FF;
            return ext ? "%08X".formatted(id) : "%03X".formatted(id);
        }

        void updateSend() {
            if (msg == null || sendBuilding) return;
            List<Object> pts = points();
            int[] off = offsets(pts);
            int total = off[pts.size()];
            byte[] data = new byte[(total + 7) / 8];
            Map<Integer, String> vals = sendMap();
            List<String> problems = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                Map<String, Object> p = asMap(pts.get(i));
                if (!isParsed(p)) continue;
                int size = sizeOf(p);
                ToneLabel raw = sendRaw.get(i);
                if (size < 1 || size > 64) {
                    problems.add("#" + (i + 1) + " has an invalid size");
                    continue;
                }
                Double v = parseSendValue(vals.get(i));
                if (v == null) {
                    problems.add("#" + (i + 1) + " \"" + vals.get(i) + "\" isn't a number");
                    if (raw != null) raw.set("not a number", ToneLabel.ERR);
                    continue;
                }
                String[] err = new String[1];
                long r = encodePoint(p, v, err);
                writeBits(data, off[i], size, r);
                String bits = Long.toBinaryString(r);
                while (bits.length() < size) bits = "0" + bits;
                String text = (size <= 16 ? bits + "   " : "") + "0x" + Long.toHexString(r).toUpperCase(Locale.ROOT);
                if (err[0] != null) {
                    boolean clamped = err[0].contains("clamped");
                    (clamped ? problems : notes).add("#" + (i + 1) + " " + err[0]);
                    if (raw != null) raw.set(text + "   " + err[0], clamped ? ToneLabel.ERR : ToneLabel.WARN);
                } else if (raw != null) raw.set(text, ToneLabel.MUTED);
            }
            StringBuilder hexP = new StringBuilder(),
                spaced = new StringBuilder();
            for (byte b : data) {
                hexP.append("%02X".formatted(b & 0xFF));
                spaced.append("%02X ".formatted(b & 0xFF));
            }
            String id = cansendId(msg);
            if (id == null) {
                sendCmd.setText("");
                sendStatus.set("This message's id isn't a valid hex ID, so no command can be built.", ToneLabel.ERR);
                return;
            }
            sendCmd.setText("cansend " + ifaceName() + " " + id + "#" + hexP);
            boolean ext = id.length() == 8;
            sendBytes.setText(
                "<html><font color='" +
                    hx(T.muted()) +
                    "'>" +
                    data.length +
                    " byte" +
                    (data.length == 1 ? "" : "s") +
                    ":</font>&nbsp; " +
                    spaced.toString().strip() +
                    "&nbsp;&nbsp;<font color='" +
                    hx(T.muted()) +
                    "'>" +
                    (ext ? "extended (29-bit) ID" : "standard (11-bit) ID") +
                    "</font></html>"
            );
            if (total > 64) problems.add(0, "The frame is " + total + " bits; classic CAN carries at most 8 bytes");
            else if (total % 8 != 0) notes.add(
                0,
                "Frame isn't byte aligned; the last " + (8 - (total % 8)) + " bits are sent as 0"
            );
            if (!problems.isEmpty()) sendStatus.set(String.join("   ·   ", problems), ToneLabel.ERR);
            else if (!notes.isEmpty()) sendStatus.set(String.join("   ·   ", notes), ToneLabel.WARN);
            else sendStatus.set(
                "Values are in physical units; the formatter is reversed before sending.",
                ToneLabel.MUTED
            );
        }

        void fillSend(String mode) {
            if (msg == null) return;
            List<Object> pts = points();
            Map<Integer, String> vals = sendMap();
            Random rnd = new Random();
            for (int i = 0; i < pts.size(); i++) {
                Map<String, Object> p = asMap(pts.get(i));
                if (!isParsed(p)) continue;
                double[] range = physRange(p);
                String v = switch (mode) {
                    case "default" -> plain(num(p, "default")).isEmpty() ? "0" : plain(num(p, "default"));
                    case "max" -> range == null ? "0" : fmtD(range[1]);
                    case "sim" -> {
                        String pickV = "0";
                        Map<String, Object> sim = asMap(p.get("sim"));
                        if (sim != null && sim.containsKey("options")) {
                            double pick = rnd.nextDouble(),
                                acc = 0;
                            for (Object o : listOrEmpty(sim.get("options"))) {
                                List<Object> pr = asList(o);
                                if (
                                    pr == null ||
                                    pr.size() != 2 ||
                                    !(pr.getFirst() instanceof BigDecimal value) ||
                                    !(pr.getLast() instanceof BigDecimal freq)
                                ) continue;
                                pickV = plain(value);
                                acc += freq.doubleValue();
                                if (pick < acc) break;
                            }
                        } else if (sim != null && num(sim, "min") != null && num(sim, "max") != null) {
                            double lo = num(sim, "min").doubleValue(),
                                hi = num(sim, "max").doubleValue();
                            double x = lo + rnd.nextDouble() * (hi - lo);
                            pickV = Boolean.TRUE.equals(sim.get("round"))
                                ? String.valueOf(Math.round(x))
                                : fmtD(Math.round(x * 1000) / 1000.0);
                        } else if (range != null) {
                            pickV = fmtD(range[0] + Math.floor(rnd.nextDouble() * (range[1] - range[0] + 1e-9)));
                        }
                        yield pickV;
                    }
                    default -> "0";
                };
                vals.put(i, v);
            }
            buildSend();
        }

        // ---------- decode tab

        JComponent buildDecodeTab() {
            decodeInput.setFont(mono(Font.PLAIN, decodeInput.getFont().getSize() + 1));
            decodeInput.setToolTipText("Hex bytes, e.g. 01 A2 FF 00 or 01A2FF00");
            decodeInput.addActionListener(e -> decode());
            decodeInput.getDocument().addDocumentListener(
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        decode();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        decode();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        decode();
                    }
                }
            );
            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setBorder(new EmptyBorder(8, 8, 4, 8));
            top.add(new JLabel("Frame data (hex)"), BorderLayout.WEST);
            top.add(decodeInput, BorderLayout.CENTER);
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            b.add(
                button("Random", "Fill with random bytes of the right length", () -> {
                    int bytes = (offsets(points())[points().size()] + 7) / 8;
                    Random rnd = new Random();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < bytes; i++) sb.append("%02X ".formatted(rnd.nextInt(256)));
                    decodeInput.setText(sb.toString().strip());
                })
            );
            b.add(
                button("Zeros", null, () -> {
                    int bytes = (offsets(points())[points().size()] + 7) / 8;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < bytes; i++) sb.append("00 ");
                    decodeInput.setText(sb.toString().strip());
                })
            );
            top.add(b, BorderLayout.EAST);
            JTable t = new JTable(decodeModel);
            styleTable(t);
            t.setDefaultRenderer(Object.class, new StyledRenderer(null));
            t.getColumnModel().getColumn(0).setMaxWidth(44);
            t.getColumnModel()
                .getColumn(3)
                .setCellRenderer(
                    new StyledRenderer((tb, row, col, sel, c) -> c.setFont(mono(Font.PLAIN, tb.getFont().getSize())))
                );
            decodeOut.setEditable(false);
            decodeOut.setFont(mono(Font.PLAIN, decodeOut.getFont().getSize()));
            JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(t), new JScrollPane(decodeOut));
            sp.setResizeWeight(0.55);
            sp.setBorder(null);
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(sp, BorderLayout.CENTER);
            decodeStatus.setBorder(new EmptyBorder(2, 8, 4, 8));
            p.add(decodeStatus, BorderLayout.SOUTH);
            return p;
        }

        void decode() {
            decodeModel.setRowCount(0);
            decodeOut.setText("");
            if (msg == null) return;
            byte[] data = parseHexBytes(decodeInput.getText());
            if (data == null) {
                decodeStatus.set("Couldn't read those bytes. Use hex pairs such as 01 A2 FF.", ToneLabel.ERR);
                return;
            }
            List<Object> pts = points();
            int need = (offsets(pts)[pts.size()] + 7) / 8;
            if (data.length < need) {
                decodeStatus.set(
                    "Frame has " +
                        data.length +
                        " byte(s); this message needs " +
                        need +
                        ". firmware-rs drops short frames; missing bytes are shown as 0 here.",
                    ToneLabel.WARN
                );
            } else if (data.length > need) {
                decodeStatus.set(
                    "Frame has " + data.length + " bytes; this message only uses " + need + ".",
                    ToneLabel.WARN
                );
            } else {
                decodeStatus.set("Decoded with big-endian bit order (MSB first), matching Calypso.", ToneLabel.MUTED);
            }
            Decoded d = decodeFrame(msg, data);
            for (String[] row : d.points()) decodeModel.addRow(row);
            decodeOut.setText(String.join("\n", d.topics()));
            decodeOut.setCaretPosition(0);
        }

        // ---------- JSON tab

        final javax.swing.undo.UndoManager jsonUndo = new javax.swing.undo.UndoManager();

        JComponent buildJsonTab() {
            jsonArea.setFont(mono(Font.PLAIN, jsonArea.getFont().getSize()));
            jsonArea.setTabSize(2);
            // Ctrl+Z inside the JSON editor undoes typing there, not the last change to the spec
            jsonArea.getDocument().addUndoableEditListener(jsonUndo);
            int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            jsonArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey), "jsonUndo");
            jsonArea
                .getInputMap()
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey | InputEvent.SHIFT_DOWN_MASK), "jsonRedo");
            jsonArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuKey), "jsonRedo");
            jsonArea.getActionMap().put(
                "jsonUndo",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        if (jsonUndo.canUndo()) jsonUndo.undo();
                    }
                }
            );
            jsonArea.getActionMap().put(
                "jsonRedo",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        if (jsonUndo.canRedo()) jsonUndo.redo();
                    }
                }
            );
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton apply = button("Apply JSON", "Replace this message with the edited JSON", this::applyJson);
            apply.putClientProperty("primary", Boolean.TRUE);
            b.add(apply);
            b.add(button("Revert", null, this::loadJson));
            b.add(
                button("Copy", "Copy this message's JSON to the clipboard", () ->
                    Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new StringSelection(jsonArea.getText()), null)
                )
            );
            b.add(jsonStatus);
            JPanel p = new JPanel(new BorderLayout());
            p.add(b, BorderLayout.NORTH);
            p.add(new JScrollPane(jsonArea), BorderLayout.CENTER);
            return p;
        }

        void loadJson() {
            if (msg == null) return;
            jsonArea.setText(Json.write(msg));
            jsonArea.setCaretPosition(0);
            jsonUndo.discardAllEdits();
            jsonStatus.set("Edit anything here, then Apply. Keys the forms don't show are kept.", ToneLabel.MUTED);
        }

        void applyJson() {
            if (msg == null) return;
            Object v;
            try {
                v = Json.parse(jsonArea.getText());
            } catch (JsonException ex) {
                jsonStatus.set(ex.getMessage(), ToneLabel.ERR);
                return;
            }
            Map<String, Object> nm = asMap(v);
            if (nm == null) {
                jsonStatus.set("The JSON must be a single message object { … }", ToneLabel.ERR);
                return;
            }
            msg.clear();
            msg.putAll(nm);
            changed(true);
            refreshHeader();
            loadJson();
            jsonStatus.set("Applied", ToneLabel.OK);
        }

        // ---------- load / refresh

        void showWelcome() {
            placeholderRefresh = this::showWelcome;
            commitAll();
            msg = null;
            file = null;
            placeholder.setText(
                "<html><div style='text-align:center'><b>Open a folder of CAN spec JSON files</b><br><br>" +
                    "File &gt; Open folder… (Ctrl+O), or drop files or a folder onto this window.</div></html>"
            );
            placeholderNew.setVisible(false);
            cards.show(cardPanel, "empty");
            if (isShowing()) fadeUI.fadeIn(fadeLayer);
        }

        void showOverview() {
            placeholderRefresh = this::showOverview;
            commitAll();
            msg = null;
            file = null;
            int msgs = 0;
            for (SpecFile f : app.files) msgs += f.messages.size();
            placeholder.setText(
                "<html><div style='text-align:center'><b>" +
                    app.files.size() +
                    " files, " +
                    msgs +
                    " messages</b><br><br>" +
                    "Pick a message on the left, or search by ID, description, topic, point name or unit.</div></html>"
            );
            placeholderNew.setVisible(false);
            cards.show(cardPanel, "empty");
            if (isShowing()) fadeUI.fadeIn(fadeLayer);
            app.issuesPanel.refilter();
        }

        void showFile(SpecFile f) {
            placeholderRefresh = () -> showFile(f);
            commitAll();
            msg = null;
            file = f;
            int e = 0,
                w = 0;
            for (Issue is : app.issues)
                if (is.file() == f) {
                    if (is.sev() == Severity.ERROR) e++;
                    else if (is.sev() == Severity.WARN) w++;
                }
            String err =
                f.loadError == null
                    ? ""
                    : "<br><br><font color='" + hx(T.red()) + "'>" + escHtml(f.loadError) + "</font>";
            placeholder.setText(
                "<html><div style='text-align:center'><b>" +
                    escHtml(app.relName(f)) +
                    "</b><br>" +
                    escHtml(f.path.toString()) +
                    "<br><br>" +
                    f.messages.size() +
                    " messages, " +
                    e +
                    " errors, " +
                    w +
                    " warnings" +
                    (f.dirty ? ", unsaved changes" : "") +
                    err +
                    "</div></html>"
            );
            placeholderNew.setVisible(f.loadError == null);
            cards.show(cardPanel, "empty");
            if (isShowing()) fadeUI.fadeIn(fadeLayer);
            app.issuesPanel.refilter();
        }

        void clear() {
            commitAll();
            msg = null;
            file = null;
            detailField = null;
            showOverview();
        }

        void load(SpecFile f, Map<String, Object> m) {
            if (m == msg) {
                refresh();
                return;
            }
            commitAll();
            file = f;
            msg = m;
            detailField = null;
            unitCombo.setModel(new DefaultComboBoxModel<>(app.units().toArray(new String[0])));
            formatterCombo.setModel(new DefaultComboBoxModel<>(app.formatterKeys().toArray(new String[0])));
            refreshHeader();
            issuesChanged();
            pointsModel.fireTableDataChanged();
            fieldsModel.fireTableDataChanged();
            loadFieldDetail();
            layoutPanel.setMessage(this);
            decodeInput.setText("");
            if (tabs.getSelectedIndex() == TAB_JSON) loadJson();
            if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
            refreshSummary();
            refreshFamily();
            cards.show(cardPanel, "editor");
            if (isShowing()) fadeUI.fadeIn(fadeLayer);
            app.issuesPanel.refilter();
        }

        void commitAll() {
            if (msg == null) return;
            stopEditing(pointsTable);
            stopEditing(fieldsTable);
            if (loading) return;
            // flush header fields (their focus-lost may arrive after selection changes)
            for (JTextField tf : new JTextField[] { idField, descField, simFreqField, keyField, clientsField })
                for (ActionListener al : tf.getActionListeners())
                    al.actionPerformed(new ActionEvent(tf, ActionEvent.ACTION_PERFORMED, ""));
            commitFieldDetail();
        }

        void refreshHeader() {
            loading = true;
            try {
                idField.setText(strOr(msg, "id", ""));
                descField.setText(strOr(msg, "desc", ""));
                extBox.setSelected(Boolean.TRUE.equals(msg.get("is_ext")));
                keyField.setText(strOr(msg, "key", ""));
                bidirBox.setSelectedItem(strOr(msg, "bidir_mode", ""));
                simFreqField.setText(plain(num(msg, "sim_freq")));
                StringBuilder cl = new StringBuilder();
                for (Object o : listOrEmpty(msg.get("clients"))) {
                    if (!cl.isEmpty()) cl.append(", ");
                    cl.append(Json.writeCompact(o));
                }
                clientsField.setText(cl.toString());
                for (JTextField tf : new JTextField[] { idField, descField, simFreqField, keyField, clientsField })
                    tf.putClientProperty("orig", tf.getText());
            } finally {
                loading = false;
            }
        }

        void refreshSummary() {
            if (msg == null) return;
            List<Object> pts = points();
            int total = offsets(pts)[pts.size()];
            boolean aligned = total % 8 == 0 && total <= 64 && total > 0;
            int parsed = 0;
            for (Object o : pts) if (isParsed(asMap(o))) parsed++;
            String kind = msg.containsKey("key")
                ? "encodable (" + strOr(msg, "bidir_mode", "broadcast") + ")"
                : "decode only";
            Long id = parseId(str(msg, "id"));
            String dec = id == null ? "" : " = " + id + " decimal";
            summary.setText(
                "<html>" +
                    "<font color='" +
                    hx(aligned ? T.green() : T.red()) +
                    "'>" +
                    total +
                    " bits (" +
                    (total / 8.0 == total / 8 ? String.valueOf(total / 8) : fmtD(total / 8.0)) +
                    " bytes)</font>&nbsp;&nbsp; " +
                    pts.size() +
                    " points (" +
                    parsed +
                    " parsed), " +
                    fields().size() +
                    " fields, " +
                    kind +
                    "&nbsp;&nbsp;<font color='" +
                    hx(T.muted()) +
                    "'>" +
                    escHtml(app.relName(file)) +
                    dec +
                    "</font></html>"
            );
        }

        void refresh() {
            if (msg == null) return;
            refreshTable(pointsTable, pointsModel);
            refreshTable(fieldsTable, fieldsModel);
            layoutPanel.setMessage(this);
            refreshSummary();
            refreshFamily();
            if (tabs.getSelectedIndex() == TAB_DECODE) decode();
            if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
        }

        void changed(boolean structural) {
            app.changed(file, structural);
            refresh();
        }

        void issuesChanged() {
            pointSev.clear();
            fieldSev.clear();
            pointIssues.clear();
            fieldIssues.clear();
            if (msg == null) {
                msgProblems.setText("");
                return;
            }
            List<Issue> header = new ArrayList<>();
            for (Issue is : app.issues) {
                if (is.msg() != msg) continue;
                if (is.point() >= 0) {
                    pointIssues.computeIfAbsent(is.point(), k -> new ArrayList<>()).add(is);
                    if (is.sev() != Severity.INFO) pointSev.merge(is.point(), is.sev(), Severity::worse);
                } else if (is.field() >= 0) {
                    fieldIssues.computeIfAbsent(is.field(), k -> new ArrayList<>()).add(is);
                    if (is.sev() != Severity.INFO) fieldSev.merge(is.field(), is.sev(), Severity::worse);
                } else if (is.sev() != Severity.INFO) header.add(is);
            }
            if (header.isEmpty()) msgProblems.setText("");
            else {
                StringBuilder sb = new StringBuilder("<html>");
                int n = 0;
                for (Issue is : header) {
                    if (n++ == 4) {
                        sb.append("<font color='")
                            .append(hx(T.muted()))
                            .append("'>…and ")
                            .append(header.size() - 4)
                            .append(" more in Problems below</font>");
                        break;
                    }
                    sb.append("<font color='")
                        .append(hx(is.sev().color()))
                        .append("'>● </font>")
                        .append(escHtml(is.text()))
                        .append("<br>");
                }
                msgProblems.setText(sb.append("</html>").toString());
            }
            int pe = 0,
                fe = 0;
            for (Severity s : pointSev.values()) if (s == Severity.ERROR) pe++;
            for (Severity s : fieldSev.values()) if (s == Severity.ERROR) fe++;
            tabs.setTitleAt(
                0,
                "Points" + (pointSev.isEmpty() ? "" : " (" + pointSev.size() + (pe > 0 ? "!" : "") + ")")
            );
            tabs.setTitleAt(
                1,
                "Fields (MQTT topics)" + (fieldSev.isEmpty() ? "" : " (" + fieldSev.size() + (fe > 0 ? "!" : "") + ")")
            );
            tabs.setForegroundAt(0, pe > 0 ? new Color(T.red().getRGB()) : null);
            tabs.setForegroundAt(1, fe > 0 ? new Color(T.red().getRGB()) : null);
            pointsTable.repaint();
            fieldsTable.repaint();
            layoutPanel.repaint();
        }

        void setHighlight(String q, int mode) {
            highlight = q;
            highlightMode = mode;
            pointsTable.repaint();
            fieldsTable.repaint();
        }

        void focusIssue(Issue is) {
            if (is.point() >= 0) {
                tabs.setSelectedIndex(0);
                selectRow(pointsTable, is.point());
            } else if (is.field() >= 0) {
                tabs.setSelectedIndex(1);
                selectRow(fieldsTable, is.field());
            }
        }

        void focusId() {
            idField.requestFocusInWindow();
            idField.selectAll();
        }

        void nextFreeId() {
            if (msg == null) return;
            Long v = parseId(str(msg, "id"));
            boolean ext = Boolean.TRUE.equals(msg.get("is_ext"));
            String nid = app.nextFreeId(v == null ? -1 : v, ext);
            if (nid == null) {
                app.status.setText("No free ID found");
                return;
            }
            idField.setText(nid);
            idField.putClientProperty("orig", nid);
            if (putStr(msg, "id", nid, false)) changed(true);
            app.status.setText("ID set to " + nid);
        }
    }

    // ======================================================================================
    // Bit layout visualisation (drag blocks or legend rows to reorder points)
    // ======================================================================================

    static final class BitLayoutPanel extends JPanel implements Scrollable {

        static final int LEFT = 82,
            TOP = 70,
            CH = 48,
            LH = 30,
            GAP = 34;
        MessageEditor ed;
        int[] owner = new int[0];
        int total, rows;
        int hover = -1,
            dragFrom = -1,
            dropSlot = -1;
        Point pressAt, dragAt;
        boolean dragging;
        java.awt.image.BufferedImage ghost;
        float ghostAlpha;
        double slideT = 1;
        final Map<Object, Integer> oldOrder = new IdentityHashMap<>();

        /** Runs a reorder, crossfading the grid and sliding legend rows from their old positions. */
        void animateChange(Runnable change) {
            if (!Settings.animations || !isShowing() || getWidth() <= 0 || ed == null || ed.msg == null) {
                change.run();
                return;
            }
            ghost = new java.awt.image.BufferedImage(
                getWidth(),
                Math.max(1, legendTop() - 30),
                java.awt.image.BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D g = ghost.createGraphics();
            paint(g);
            g.dispose();
            oldOrder.clear();
            List<Object> pts = ed.points();
            for (int i = 0; i < pts.size(); i++) oldOrder.put(pts.get(i), i);
            change.run();
            animate(
                280,
                t -> {
                    slideT = t;
                    ghostAlpha = (float) (1 - t);
                    repaint();
                },
                () -> {
                    ghost = null;
                    oldOrder.clear();
                    slideT = 1;
                    repaint();
                }
            );
        }

        BitLayoutPanel() {
            setToolTipText("");
            MouseAdapter ma = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    dragFrom = hit(e.getPoint());
                    pressAt = e.getPoint();
                    dragging = false;
                }

                public void mouseDragged(MouseEvent e) {
                    if (dragFrom < 0 || pressAt == null) return;
                    if (!dragging && pressAt.distance(e.getPoint()) < 5) return;
                    dragging = true;
                    dragAt = e.getPoint();
                    dropSlot = slotAt(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    scrollRectToVisible(new Rectangle(e.getX() - 20, e.getY() - 20, 40, 40));
                    repaint();
                }

                public void mouseReleased(MouseEvent e) {
                    int from = dragFrom,
                        slot = dropSlot;
                    boolean wasDrag = dragging;
                    dragFrom = -1;
                    dropSlot = -1;
                    dragging = false;
                    dragAt = null;
                    pressAt = null;
                    setCursor(hover >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    repaint();
                    if (ed == null || from < 0) return;
                    if (wasDrag) {
                        if (slot >= 0) ed.movePointTo(from, slot);
                    } else ed.selectPoint(from, e.getClickCount() >= 2);
                }

                public void mouseMoved(MouseEvent e) {
                    int h = hit(e.getPoint());
                    if (h != hover) {
                        hover = h;
                        setCursor(h >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                        repaint();
                    }
                }

                public void mouseExited(MouseEvent e) {
                    if (hover != -1 && !dragging) {
                        hover = -1;
                        repaint();
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override
        public Color getBackground() {
            return T == null ? super.getBackground() : T.panel();
        }

        int cw() {
            return Math.clamp((getWidth() - LEFT - 40) / 8, 48, 104);
        }

        void setMessage(MessageEditor ed) {
            this.ed = ed;
            List<Object> pts = ed.points();
            int[] off = offsets(pts);
            total = off[pts.size()];
            rows = Math.clamp((total + 7) / 8, 1, 64);
            owner = new int[rows * 8];
            Arrays.fill(owner, -1);
            for (int i = 0; i < pts.size(); i++) {
                int sz = sizeOf(pts.get(i));
                for (int b = off[i]; b < off[i] + sz && b < owner.length; b++) owner[b] = i;
            }
            if (hover >= pts.size()) hover = -1;
            revalidate();
            repaint();
        }

        int legendTop() {
            return TOP + rows * CH + GAP + 22;
        }

        @Override
        public Dimension getPreferredSize() {
            int n = ed == null ? 0 : ed.points().size();
            return new Dimension(LEFT + 8 * 64 + 40, legendTop() + n * LH + 30);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() != null && getParent().getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }

        /** Point index under the mouse, in the grid or the legend; -1 for none. */
        int hit(Point p) {
            if (ed == null || ed.msg == null) return -1;
            int cw = cw();
            if (p.x >= LEFT && p.x < LEFT + 8 * cw && p.y >= TOP && p.y < TOP + rows * CH) {
                int pos = ((p.y - TOP) / CH) * 8 + (p.x - LEFT) / cw;
                return pos < owner.length ? owner[pos] : -1;
            }
            int n = ed.points().size(),
                lt = legendTop();
            if (p.y >= lt && p.y < lt + n * LH && p.x >= 10 && p.x < getWidth() - 10) return (p.y - lt) / LH;
            return -1;
        }

        /** Insertion slot (0..n) for a drop at p. */
        int slotAt(Point p) {
            List<Object> pts = ed.points();
            int n = pts.size();
            int[] off = offsets(pts);
            int cw = cw();
            if (p.y < legendTop() - GAP / 2) {
                int row = Math.clamp((p.y - TOP) / CH, 0, rows - 1);
                double col = Math.clamp((p.x - LEFT) / (double) cw, 0.0, 7.999);
                double posF = row * 8 + col;
                int pos = Math.min((int) posF, owner.length - 1);
                int o = owner[pos];
                if (o < 0) return n;
                double mid = off[o] + sizeOf(pts.get(o)) / 2.0;
                return posF < mid ? o : o + 1;
            }
            double r = (p.y - legendTop()) / (double) LH;
            return Math.clamp(Math.round(r), 0, n);
        }

        static int significance(Map<String, Object> p, int off, int size, int pos) {
            int rel = pos - off;
            if (p != null && "little".equals(p.get("endianness")) && size % 8 == 0 && off % 8 == 0) return (
                (rel / 8) * 8 + (7 - (rel % 8))
            );
            return size - 1 - rel;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (ed == null || ed.msg == null || T == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            List<Object> pts = ed.points();
            int n = pts.size();
            int[] off = offsets(pts);
            int cw = cw();
            Font f = getFont();
            Font small = f.deriveFont(f.getSize2D() - 1.5f);
            Font bold = f.deriveFont(Font.BOLD);

            g.setFont(f);
            g.setColor(T.muted());
            g.drawString(
                "Drag a block, or a row in the list below, to reorder points. Field references are renumbered for you.",
                18,
                26
            );

            g.setFont(small);
            FontMetrics sfm = g.getFontMetrics();
            g.drawString("bit", 18, TOP - 12);
            for (int b = 0; b < 8; b++) {
                String s = String.valueOf(7 - b);
                g.drawString(s, LEFT + b * cw + (cw - sfm.stringWidth(s)) / 2, TOP - 12);
            }
            for (int r = 0; r < rows; r++) {
                g.setColor(r >= 8 ? T.red() : T.muted());
                g.drawString("Byte " + r, 18, TOP + r * CH + CH / 2 + 4);
            }

            // unused bits inside the last byte
            g.setStroke(
                new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] { 3f, 3f }, 0f)
            );
            g.setColor(T.border());
            for (int pos = 0; pos < owner.length; pos++) {
                if (owner[pos] >= 0) continue;
                int x = LEFT + (pos % 8) * cw + 2,
                    y = TOP + (pos / 8) * CH + 3;
                g.drawRoundRect(x, y, cw - 5, CH - 7, 10, 10);
            }
            g.setStroke(new BasicStroke(1f));

            for (int r = 0; r < rows; r++) {
                int b = 0;
                while (b < 8) {
                    int o = owner[r * 8 + b];
                    int e = b;
                    while (e + 1 < 8 && owner[r * 8 + e + 1] == o) e++;
                    if (o >= 0) drawRun(g, pts, off, r, b, e, o, cw, bold, small);
                    b = e + 1;
                }
            }

            boolean showDrop = dragging && dropSlot >= 0 && dropSlot != dragFrom && dropSlot != dragFrom + 1;
            if (showDrop) {
                int bit = dropSlot < n ? off[dropSlot] : total;
                int row = bit / 8,
                    col = bit % 8;
                if (col == 0 && bit > 0 && (dropSlot >= n || row >= rows)) {
                    row = bit / 8 - 1;
                    col = 8;
                }
                row = Math.min(row, rows - 1);
                int x = LEFT + col * cw;
                g.setColor(T.accent());
                g.fillRoundRect(x - 2, TOP + row * CH, 4, CH, 4, 4);
            }

            // legend
            int lt = legendTop();
            g.setFont(bold);
            g.setColor(T.fg());
            g.drawString("Points in frame order", 18, lt - 12);
            FontMetrics bfm = g.getFontMetrics(bold);
            int w = getWidth();
            int nameX = 58,
                metaX = Math.max(290, nameX + 230),
                topicX = Math.max(metaX + 230, w / 2);
            for (int i = 0; i < n; i++) {
                Integer was = oldOrder.get(pts.get(i));
                int y = lt + i * LH + (was == null ? 0 : (int) Math.round((was - i) * LH * (1 - slideT)));
                Map<String, Object> p = asMap(pts.get(i));
                boolean pad = !isParsed(p);
                Composite oldC = g.getComposite();
                if (dragging && i == dragFrom) g.setComposite(AlphaComposite.SrcOver.derive(0.4f));
                if (i == hover || (dragging && i == dragFrom)) {
                    g.setColor(blend(T.fg(), T.panel(), 0.06));
                    g.fillRoundRect(10, y + 1, w - 20, LH - 2, 8, 8);
                }
                g.setColor(T.muted());
                for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 2; dx++) g.fillOval(
                    18 + dx * 4,
                    y + 9 + dy * 4,
                    2,
                    2
                );
                Color pc = pad ? T.muted() : T.chart(i);
                g.setColor(blend(pc, T.panel(), 0.35));
                g.fillRoundRect(32, y + 8, 14, 14, 4, 4);
                g.setColor(pc);
                g.drawRoundRect(32, y + 8, 13, 13, 4, 4);
                Severity s = ed.pointSev.get(i);
                int base = y + LH / 2 + 5;
                String nm = p == null ? "(invalid)" : strOr(p, "name", "");
                g.setFont(bold);
                g.setColor(s == Severity.ERROR ? T.red() : pad ? T.muted() : T.bright());
                g.drawString(
                    ellipsize(
                        "#" + (i + 1) + "  " + (nm.isEmpty() ? (pad ? "padding" : "unnamed") : nm),
                        bfm,
                        metaX - nameX - 12
                    ),
                    nameX,
                    base
                );
                int sz = sizeOf(pts.get(i));
                StringBuilder meta = new StringBuilder();
                meta.append(sz)
                    .append(sz == 1 ? " bit" : " bits")
                    .append(" at ")
                    .append(off[i]);
                if (p != null && !pad) {
                    if (Boolean.TRUE.equals(p.get("signed"))) meta.append(", signed");
                    if ("little".equals(p.get("endianness"))) meta.append(", little endian");
                    if (Boolean.TRUE.equals(p.get("ieee754_f32"))) meta.append(", float");
                    String fk = formatterKey(p);
                    if (!fk.isEmpty()) meta.append(", ").append(fk).append(' ').append(formatterArg(p));
                }
                g.setFont(f);
                g.setColor(T.muted());
                FontMetrics fm = g.getFontMetrics();
                g.drawString(ellipsize(meta.toString(), fm, topicX - metaX - 12), metaX, base);
                if (!pad) {
                    List<String> topics = fieldsReferencing(ed.msg, i + 1);
                    if (topics.isEmpty()) {
                        g.setColor(T.yellow());
                        g.drawString("not published to MQTT", topicX, base);
                    } else {
                        g.setColor(T.fg());
                        g.drawString(ellipsize("→ " + String.join(",  ", topics), fm, w - topicX - 20), topicX, base);
                    }
                }
                if (s != null) {
                    g.setColor(s.color());
                    g.fillOval(w - 30, y + LH / 2 - 4, 8, 8);
                }
                g.setComposite(oldC);
            }
            if (showDrop) {
                g.setColor(T.accent());
                g.fillRoundRect(10, lt + dropSlot * LH - 2, w - 20, 3, 3, 3);
            }

            if (total > 64) {
                g.setColor(T.red());
                g.setFont(bold);
                g.drawString("Frame is " + (total - 64) + " bits over the 8-byte limit", LEFT, TOP + rows * CH + 22);
            } else if (total % 8 != 0) {
                g.setColor(T.red());
                g.setFont(bold);
                g.drawString(
                    "Not byte aligned: " + (8 - (total % 8)) + " bits short of a full byte",
                    LEFT,
                    TOP + rows * CH + 22
                );
            } else {
                g.setColor(T.muted());
                g.setFont(small);
                g.drawString(total + " bits, " + total / 8 + " bytes", LEFT, TOP + rows * CH + 22);
            }

            if (ghost != null) {
                Composite oc = g.getComposite();
                g.setComposite(AlphaComposite.SrcOver.derive(ghostAlpha));
                g.drawImage(ghost, 0, 0, null);
                g.setComposite(oc);
            }
            if (dragging && dragAt != null && dragFrom >= 0 && dragFrom < n) {
                String label = "#" + (dragFrom + 1) + "  " + strOr(asMap(pts.get(dragFrom)), "name", "");
                g.setFont(bold);
                FontMetrics fm = g.getFontMetrics();
                int cwid = fm.stringWidth(label) + 20;
                g.setColor(T.accent());
                g.fillRoundRect(dragAt.x + 14, dragAt.y + 10, cwid, 24, 12, 12);
                g.setColor(onColor(T.accent()));
                g.drawString(label, dragAt.x + 24, dragAt.y + 27);
            }
            g.dispose();
        }

        void drawRun(
            Graphics2D g,
            List<Object> pts,
            int[] off,
            int r,
            int b0,
            int b1,
            int o,
            int cw,
            Font bold,
            Font small
        ) {
            Map<String, Object> p = asMap(pts.get(o));
            boolean pad = !isParsed(p);
            int x = LEFT + b0 * cw + 2,
                y = TOP + r * CH + 3,
                w = (b1 - b0 + 1) * cw - 5,
                h = CH - 7;
            Color pc = pad ? T.muted() : T.chart(o);
            Composite oldC = g.getComposite();
            if (dragging && o == dragFrom) g.setComposite(AlphaComposite.SrcOver.derive(0.35f));
            java.awt.geom.RoundRectangle2D.Float rr = new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, 10, 10);
            if (pad) {
                g.setColor(blend(T.muted(), T.panel(), 0.10));
                g.fill(rr);
                Shape clip = g.getClip();
                g.clip(rr);
                g.setColor(blend(T.muted(), T.panel(), 0.28));
                for (int k = -h; k < w; k += 7) g.drawLine(x + k, y + h, x + k + h, y);
                g.setClip(clip);
            } else {
                g.setColor(blend(pc, T.panel(), T.dark() ? 0.26 : 0.20));
                g.fill(rr);
                g.setColor(alpha(pc, 70));
                for (int k = b0 + 1; k <= b1; k++) {
                    int lx = LEFT + k * cw;
                    g.drawLine(lx, y + 8, lx, y + h - 8);
                }
            }
            Severity s = ed.pointSev.get(o);
            boolean hot = o == hover || (dragging && o == dragFrom);
            g.setStroke(new BasicStroke(hot ? 2f : 1.2f));
            g.setColor(s == Severity.ERROR ? T.red() : hot ? pc : blend(pc, T.panel(), 0.65));
            g.draw(rr);
            g.setStroke(new BasicStroke(1f));
            if (s != null && w >= 30) {
                g.setColor(s.color());
                g.fillOval(x + w - 12, y + 6, 7, 7);
            }

            Shape clip = g.getClip();
            g.clip(rr);
            String nm = p == null ? "?" : strOr(p, "name", "");
            int size = sizeOf(pts.get(o));
            String line1, line2;
            if (pad) {
                line1 = "pad";
                line2 = size + " bit" + (size == 1 ? "" : "s");
            } else {
                line1 = "#" + (o + 1) + (nm.isEmpty() ? "" : " " + nm);
                int hi = significance(p, off[o], size, r * 8 + b0),
                    lo = significance(p, off[o], size, r * 8 + b1);
                line2 = hi == lo ? "b" + hi : "b" + hi + "–" + lo;
            }
            g.setFont(bold);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(pad ? T.muted() : T.bright());
            if (w < 44) {
                String t = pad ? "" : "#" + (o + 1);
                g.drawString(t, x + (w - fm.stringWidth(t)) / 2, y + h / 2 + 4);
            } else {
                g.drawString(ellipsize(line1, fm, w - 12), x + 7, y + 18);
                g.setFont(small);
                g.setColor(T.muted());
                g.drawString(ellipsize(line2, g.getFontMetrics(), w - 12), x + 7, y + h - 7);
            }
            g.setClip(clip);
            g.setComposite(oldC);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            if (ed == null || ed.msg == null || dragging) return null;
            int cw = cw();
            int o = hit(e.getPoint());
            if (o < 0) {
                if (e.getX() >= LEFT && e.getX() < LEFT + 8 * cw && e.getY() >= TOP && e.getY() < TOP + rows * CH) {
                    int pos = ((e.getY() - TOP) / CH) * 8 + (e.getX() - LEFT) / cw;
                    return "Bit " + pos + " is unused";
                }
                return null;
            }
            List<Object> pts = ed.points();
            Map<String, Object> p = asMap(pts.get(o));
            int[] off = offsets(pts);
            int sz = sizeOf(pts.get(o));
            StringBuilder s = new StringBuilder(
                "<html><b>" +
                    escHtml(pointLabel(p, o)) +
                    "</b><br>Frame bits " +
                    off[o] +
                    "–" +
                    (off[o] + sz - 1) +
                    ", " +
                    sz +
                    " bits"
            );
            if (isParsed(p)) {
                List<String> topics = fieldsReferencing(ed.msg, o + 1);
                s.append("<br>").append(
                    topics.isEmpty()
                        ? "<font color='" + hx(T.yellow()) + "'>Not published to any MQTT topic</font>"
                        : "Published to " + escHtml(String.join(", ", topics))
                );
            } else s.append("<br>Padding (not parsed)");
            List<Issue> l = ed.pointIssues.get(o);
            if (l != null) for (Issue is : l)
                s.append("<br><font color='")
                    .append(hx(is.sev().color()))
                    .append("'>")
                    .append(escHtml(is.text()))
                    .append("</font>");
            s.append("<br><font color='")
                .append(hx(T.muted()))
                .append("'>Click to select, double-click to edit, drag to move</font>");
            return s.append("</html>").toString();
        }
    }
}
