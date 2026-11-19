package tiresias.spec;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
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

public final class Validator {

    public static final Set<String> MSG_KEYS = set(
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
    public static final Set<String> FIELD_KEYS = set("name", "unit", "doc", "desc", "values");
    public static final Set<String> POINT_KEYS = set(
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
    public static final Set<String> META_KEYS = set("desc", "fields");
    public static final Set<String> META_FIELD_KEYS = set("name", "unit", "doc", "desc");
    /** Keys definition-rs reads as Option, so a null value is the same as leaving the key out. */
    public static final Set<String> MSG_OPTIONAL = set("key", "is_ext", "sim_freq", "clients");
    public static final Set<String> POINT_OPTIONAL = set(
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
    public static final Set<String> FIELD_OPTIONAL = set("desc");
    /** Names the generated code uses unqualified, so a message struct with one of them won't compile. */
    public static final Set<String> GENERATED_NAMES = set(
        "Id",
        "StandardId",
        "ExtendedId",
        "CanMessage",
        "ToCanFrame",
        "OutOfRange",
        "Frame",
        "Self"
    );

    /** The object without its null-valued optional keys (definition-rs reads null as "not set"). */
    public static Map<String, Object> withoutNulls(Map<String, Object> m, Set<String> optional) {
        boolean any = false;
        for (String k : optional) if (m.containsKey(k) && m.get(k) == null) any = true;
        if (!any) return m;
        Map<String, Object> c = new LinkedHashMap<>(m);
        for (String k : optional) if (c.containsKey(k) && c.get(k) == null) c.remove(k);
        return c;
    }

    /**
     * True for a number written as a whole number (8, not 8.0 or 8e0). definition-rs reads size, values and clients
     * as usize/u16, and serde_json only reads numbers written that way into them.
     */
    public static boolean wholeLiteral(Object o) {
        return o instanceof BigDecimal b && b.scale() == 0 && !b.toString().contains("E");
    }

    public static String wholeHint(Object o, String what) {
        return (
            what +
            " " +
            o +
            " must be written as a whole number (" +
            ((BigDecimal) o).stripTrailingZeros().toPlainString() +
            "); definition-rs reads it as an integer and fails on a decimal point or exponent, so the build fails"
        );
    }

    /** The methods bitfield_struct and cangen generate for a point's accessor name. */
    public static List<String> generatedMethods(String sn, Map<String, Object> p) {
        List<String> l = new ArrayList<>(
            List.of(sn, "with_" + sn, "set_" + sn, "with_" + sn + "_checked", "set_" + sn + "_checked")
        );
        boolean ieee = Boolean.TRUE.equals(p.get("ieee754_f32"));
        if (!ieee && (rustScaled(p) || "float".equals(p.get("c_type")))) {
            l.add("try_with_" + sn);
            l.add("try_set_" + sn);
        }
        return l;
    }

    public static String unknownKey(String kind, String k) {
        String hint = k.equals("default_value")
            ? " The key is \"default\" (the README's default_value is out of date)."
            : k.equals("format")
                ? " Use \"formatter\": {\"key\": \"divide\", \"arg\": N} instead."
                : " Typo?";
        return (
            "Unknown " + kind + " key \"" + k + "\". definition-rs rejects unknown keys, so the build fails." + hint
        );
    }

    public static final Set<String> SWEEP_KEYS = set("min", "max", "inc_min", "inc_max", "round");
    public static final Set<Integer> SUPPORTED_CLIENTS = Set.of(1882);
    public static final Map<String, Integer> CTYPE_BITS = new LinkedHashMap<>();

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

    public static Set<String> set(String... s) {
        return Set.of(s);
    }

    public final List<Issue> out = new ArrayList<>();
    public SpecFile cf;
    public Map<String, Object> cm;
    public boolean curEncodable, curBroadcast;

    public void add(Severity s, String where, int pt, int fd, String text) {
        addFix(s, where, pt, fd, text, null);
    }

    /** Adds an issue the editor can fix by itself (fix may be null). */
    public void addFix(Severity s, String where, int pt, int fd, String text, Fix fix) {
        out.add(new Issue(s, cf, cm, where, pt, fd, text, fix));
    }

    public void msgIssue(Severity s, String text) {
        add(s, "Message", -1, -1, text);
    }

    public void msgFix(Severity s, String text, Fix fix) {
        addFix(s, "Message", -1, -1, text, fix);
    }

    public static Fix fix(FixKind kind, String label) {
        return new Fix(kind, null, label);
    }

    /** The fix for an unknown point key, when it's an outdated spelling with a known replacement. */
    public static Fix outdatedKeyFix(Map<String, Object> p, String k) {
        if (k.equals("default_value") && !p.containsKey("default")) return fix(
            FixKind.RENAME_DEFAULT_VALUE,
            "Rename default_value to default"
        );
        Matcher mm = k.equals("format") ? LEGACY_DIVIDE.matcher(strOr(p, "format", "")) : null;
        if (mm != null && mm.matches() && !p.containsKey("formatter")) return fix(
            FixKind.LEGACY_FORMAT,
            "Replace format with \"formatter\": {\"key\": \"divide\", \"arg\": " + mm.group(1) + "}"
        );
        return null;
    }

    public static Fix topicFix(String name) {
        String c = Fixes.cleanTopic(name);
        return c.isEmpty() ? null : fix(FixKind.TOPIC_SLASHES, "Change the topic to \"" + c + "\"");
    }

    public static List<Issue> validate(List<SpecFile> files) {
        Validator v = new Validator();
        v.run(files);
        return v.out;
    }

    public void run(List<SpecFile> files) {
        Map<String, List<SpecRef>> ids = new LinkedHashMap<>();
        Map<String, List<SpecRef>> structs = new LinkedHashMap<>();
        Map<String, List<FieldRef>> topics = new LinkedHashMap<>();
        for (SpecFile f : files) {
            cf = f;
            cm = null;
            String fname = f.path.getFileName().toString();
            if (!fname.endsWith(".json")) add(
                Severity.WARN,
                "File",
                -1,
                -1,
                "firmware-rs cangen only reads files ending in a lowercase \".json\", so it skips this file"
            );
            Path dir = f.path.getParent();
            for (Path a = dir == null ? null : dir.getParent(); a != null; a = a.getParent()) if (
                a.getFileName() != null && a.getFileName().toString().equals("can-messages")
            ) {
                add(
                    Severity.WARN,
                    "File",
                    -1,
                    -1,
                    "firmware-rs cangen only reads the files directly in can-messages/, so it skips this file in " +
                        dir.getFileName() +
                        "/"
                );
                break;
            }
            if (f.loadError != null) {
                add(Severity.ERROR, "File", -1, -1, f.loadError);
                continue;
            }
            for (String w : f.parseWarnings)
                add(
                    w.startsWith("Duplicate key") ? Severity.ERROR : Severity.WARN,
                    "File",
                    -1,
                    -1,
                    w.replace("; the last value wins", "; definition-rs rejects a key given twice, so the build fails")
                );
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
                if (id != null) ids.computeIfAbsent((Boolean.TRUE.equals(m.get("is_ext")) ? "ext:" : "std:") + id, k ->
                    new ArrayList<>()
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
    public void checkMeta(Map<String, Object> m) {
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
            f = withoutNulls(f, FIELD_OPTIONAL);
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

    public static String others(List<SpecRef> refs, SpecRef self, Function<SpecRef, String> label) {
        return String.join(
            "; ",
            refs
                .stream()
                .filter(b -> b != self)
                .map(label)
                .toList()
        );
    }

    public void checkMessage(Map<String, Object> m) {
        for (String k : m.keySet()) if (!MSG_KEYS.contains(k)) msgIssue(Severity.ERROR, unknownKey("message", k));
        m = withoutNulls(m, MSG_OPTIONAL);

        Long id = null;
        Object idO = m.get("id");
        if (!(idO instanceof String rawId)) msgIssue(Severity.ERROR, "Missing \"id\" (a hex string such as \"0x1A0\")");
        else {
            String s = rawId.strip();
            if (!HEX_ID.matcher(s).matches()) {
                if (s.matches("[0-9a-fA-F]{1,16}")) {
                    id = Long.parseUnsignedLong(s, 16);
                    msgFix(
                        Severity.WARN,
                        "id \"" +
                            s +
                            "\" has no \"0x\" prefix. firmware-rs cangen still reads it as hex, but Tiresias needs the prefix, so it can't decode this message",
                        fix(FixKind.ID_FORMAT, "Write the ID as \"" + Fixes.normalId(s) + "\"")
                    );
                } else msgIssue(Severity.ERROR, "id \"" + s + "\" must be a hexadecimal string such as \"0x1A0\"");
                if (!rawId.equals(s)) msgFix(
                    Severity.ERROR,
                    "id has surrounding whitespace, which firmware-rs cangen can't parse",
                    fix(FixKind.ID_FORMAT, "Write the ID as \"" + Fixes.normalId(s) + "\"")
                );
            } else {
                id = parseId(s);
                Fix idFix = fix(FixKind.ID_FORMAT, "Write the ID as \"" + Fixes.normalId(s) + "\"");
                if (s.startsWith("0X")) msgFix(
                    Severity.ERROR,
                    "id uses an uppercase \"0X\" prefix. firmware-rs cangen only strips a lowercase \"0x\", so parsing fails and the build panics",
                    idFix
                );
                if (!rawId.equals(s)) msgFix(
                    Severity.ERROR,
                    "id has surrounding whitespace, which firmware-rs cangen can't parse",
                    idFix
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
            else if (id > 0x7FF && !isExt) msgFix(
                Severity.ERROR,
                "ID " +
                    hex(id) +
                    " needs more than 11 bits but is_ext is not true." +
                    ((id & 0xFFFF) <= 0x7FF
                        ? " firmware-rs cangen cuts it to 16 bits (id as u16) and builds standard ID " +
                              hex(id & 0xFFFF) +
                              ", which never matches this message's frames, so it's never decoded. Set \"is_ext\": true"
                        : " firmware-rs cangen builds a standard ID from it and panics (StandardId::new fails above 0x7FF). Set \"is_ext\": true"),
                fix(FixKind.SET_EXT, "Set \"is_ext\": true")
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
            else if (GENERATED_NAMES.contains(sn)) msgIssue(
                Severity.ERROR,
                "desc generates the struct " + sn + ", which clashes with the " + sn + " the generated code uses"
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
            else if (!"broadcast".equals(bidir) && !"oneshot".equals(bidir)) {
                String low = bidir == null ? "" : bidir.strip().toLowerCase(Locale.ROOT);
                boolean fixable = low.equals("broadcast") || low.equals("oneshot");
                msgFix(
                    Severity.ERROR,
                    "bidir_mode must be \"broadcast\" or \"oneshot\" (lowercase)",
                    fixable ? new Fix(FixKind.BIDIR_CASE, low, "Change bidir_mode to \"" + low + "\"") : null
                );
            }
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
                if (port != null && !wholeLiteral(c)) msgIssue(Severity.ERROR, wholeHint(c, "clients entry"));
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
            msgIssue(Severity.ERROR, m.containsKey("points") ? "\"points\" must be a list" : "Missing \"points\" list");
            pts = List.of();
        } else if (pts.isEmpty()) msgIssue(
            Severity.WARN,
            "Message has no points; firmware-rs builds it as an empty message with no data bytes"
        );
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
        // two different names whose generated methods collide (foo makes with_foo, and so does a point named with_foo)
        Map<String, Map<Integer, String>> methods = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : accessors.entrySet())
            for (int k : e.getValue())
                for (String mth : generatedMethods(e.getKey(), asMap(pts.get(k))))
                    methods.computeIfAbsent(mth, x -> new LinkedHashMap<>()).put(k, e.getKey());
        for (Map.Entry<String, Map<Integer, String>> e : methods.entrySet()) {
            if (new HashSet<>(e.getValue().values()).size() < 2) continue;
            StringBuilder who = new StringBuilder();
            for (Map.Entry<Integer, String> w : e.getValue().entrySet()) {
                if (!who.isEmpty()) who.append(", ");
                who.append('#')
                    .append(w.getKey() + 1)
                    .append(" `")
                    .append(w.getValue())
                    .append('`');
            }
            for (int k : e.getValue().keySet())
                add(
                    Severity.ERROR,
                    pointLabel(asMap(pts.get(k)), k),
                    k,
                    -1,
                    "Points " +
                        who +
                        " all generate a method named `" +
                        e.getKey() +
                        "` (bitfield_struct makes x, with_x, set_x and their _checked versions), so the generated struct won't compile. Rename one of them"
                );
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
                addFix(
                    Severity.WARN,
                    "Layout",
                    -1,
                    -1,
                    "Total size is " +
                        total +
                        " bits, which is not byte aligned. firmware-rs pads it to " +
                        (total + pad) / 8 +
                        ((total + pad) / 8 == 1 ? " byte" : " bytes") +
                        " itself; add a " +
                        pad +
                        "-bit padding point with \"parse\": false to make that explicit",
                    fix(
                        FixKind.BYTE_ALIGN,
                        Fixes.trailingPadding(pts) != null
                            ? "Grow the last padding point by " + pad + " bits"
                            : "Add a " + pad + "-bit padding point"
                    )
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
                Severity.WARN,
                pointLabel(asMap(pts.get(i)), i),
                i,
                -1,
                "This message has little-endian points, so every point should be byte aligned for C gen; firmware-rs doesn't need it (bits " +
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
            msgFix(
                Severity.ERROR,
                m.containsKey("fields")
                    ? "\"fields\" must be a list"
                    : "Missing \"fields\" list; definition-rs requires it (use [] for none)",
                m.containsKey("fields") ? null : fix(FixKind.ADD_FIELDS, "Add an empty \"fields\" list")
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

    public void checkPoint(int i, Map<String, Object> p, int off) {
        String where = pointLabel(p, i);
        for (String k : p.keySet())
            if (!POINT_KEYS.contains(k)) addFix(
                Severity.ERROR,
                where,
                i,
                -1,
                unknownKey("point", k),
                outdatedKeyFix(p, k)
            );
        p = withoutNulls(p, POINT_OPTIONAL);
        Long sizeL = longVal(p.get("size"));
        if (sizeL == null || sizeL < 1) {
            add(Severity.ERROR, where, i, -1, "\"size\" must be a positive whole number of bits");
            return;
        }
        if (!wholeLiteral(p.get("size"))) add(Severity.ERROR, where, i, -1, wholeHint(p.get("size"), "size"));
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
            if (!extra.isEmpty()) addFix(
                Severity.WARN,
                where,
                i,
                -1,
                "Padding points (\"parse\": false) should only contain \"size\". Remove: " + String.join(", ", extra),
                fix(FixKind.PADDING_KEYS, "Remove " + String.join(", ", extra) + " from this padding point")
            );
            return;
        }
        if (size > 64) add(
            Severity.ERROR,
            where,
            i,
            -1,
            "Parsed points can be at most 64 bits (this one is " + size + ")"
        );
        else if (size > 32) add(
            Severity.WARN,
            where,
            i,
            -1,
            "Parsed point is " +
                size +
                " bits; firmware-rs stores it as a 64-bit integer, which is unusual for CAN data"
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
            Object eo = p.get("endianness");
            if (!(eo instanceof String e)) add(Severity.ERROR, where, i, -1, "endianness must be a string");
            else if (e.equals("big") || e.equals("little")) end = e;
            else add(
                Severity.WARN,
                where,
                i,
                -1,
                "endianness \"" +
                    e +
                    "\" isn't \"big\" or \"little\"; firmware-rs treats anything but \"little\" as big endian"
            );
            if (size <= 8 && !("little".equals(end) && size % 8 != 0)) addFix(
                Severity.WARN,
                where,
                i,
                -1,
                "endianness has no effect on points of 8 bits or fewer (byte order means nothing inside one byte); remove it",
                new Fix(FixKind.REMOVE_POINT_KEY, "endianness", "Remove \"endianness\"")
            );
        }
        boolean little = "little".equals(end);
        if (little) {
            if (size % 8 != 0) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "firmware-rs cangen panics on little-endian points that aren't whole bytes (this one is " +
                    size +
                    " bits)"
            );
            else if (size != 8 && size != 16 && size != 32 && size != 64) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "firmware-rs cangen byte-swaps little-endian points as a " +
                    (size < 32 ? "u32 (4 bytes)" : "u64 (8 bytes)") +
                    ", so this " +
                    size +
                    "-bit value decodes wrong. Use 16 or 32 bits, or big endian"
            );
            if (off % 8 != 0) add(
                Severity.WARN,
                where,
                i,
                -1,
                "Little-endian point starts mid-byte (bit " + off + "); C gen expects them on byte boundaries"
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
            add(Severity.INFO, where, i, -1, "Prefer big endian where possible; the MC, charger box, etc. expect it");
        }
        // cangen builds signed integers only when the point isn't scaled to an f32 (and isn't a 1-bit bool)
        String ctype = str(p, "c_type");
        boolean signedInt =
            signed && !ieee && !rustScaled(p) && !"float".equals(ctype) && !("bool".equals(ctype) && size == 1);
        if (signedInt) {
            if (size != 8 && size != 16 && size != 32 && size != 64) add(
                Severity.ERROR,
                where,
                i,
                -1,
                "firmware-rs cangen only builds signed integers of 8, 16, 32 or 64 bits and panics on this " +
                    size +
                    "-bit one. Change the size, or scale it with a formatter"
            );
            if (off % 8 != 0) add(
                Severity.WARN,
                where,
                i,
                -1,
                "Signed integer point starts mid-byte (bit " +
                    off +
                    "); firmware-rs builds it, but keep signed values on byte boundaries"
            );
        }
        if (ieee && size != 32) add(Severity.ERROR, where, i, -1, "IEEE754 float points must be 32 bits");
        if (size >= 8 && off % 8 != 0 && !signedInt) add(
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
                    String gen = rustGenType(p);
                    if (w != null && w > 1 && size > w) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "c_type " +
                            ct +
                            " holds " +
                            w +
                            " bits but the point is " +
                            size +
                            " bits" +
                            (gen.isEmpty() ? "" : "; firmware-rs ignores c_type here and makes it a " + gen)
                    );
                    if (ct.equals("double") && !gen.isEmpty() && !gen.equals("f32")) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "firmware-rs cangen has no double: without a formatter this point becomes a " +
                            gen +
                            ", not a float. Use c_type \"float\""
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
                    if (ct.equals("bool") && size > 1 && !gen.isEmpty() && !gen.equals("f32")) add(
                        Severity.INFO,
                        where,
                        i,
                        -1,
                        "firmware-rs only generates bool for 1-bit points; this " + size + "-bit point becomes a " + gen
                    );
                    BigDecimal div = divisor(p);
                    if (w != null && div != null && div.compareTo(BigDecimal.ONE) != 0) add(
                        Severity.WARN,
                        where,
                        i,
                        -1,
                        "A formatter scales this value, so firmware-rs exposes it as f32, but c_type " +
                            ct +
                            " can't hold fractions; c_type \"float\" describes it better"
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
                            "definition-rs reads the " +
                                k +
                                " arg as an f32 and cangen casts it to an integer (arg as u32), so " +
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

    public static boolean outOf(double v, double[] r) {
        double eps = 1e-9 * Math.max(1, Math.abs(v));
        return v < r[0] - eps || v > r[1] + eps;
    }

    public void checkSim(String where, int i, Object simO, double[] range) {
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
            boolean pairsOk = true;
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
                    pairsOk = false;
                    continue;
                }
                if (fq.signum() < 0) {
                    add(Severity.ERROR, where, i, -1, "Sim option frequency " + plain(fq) + " is negative");
                    pairsOk = false;
                }
                sum = sum.add(fq);
                if (range != null && outOf(v.doubleValue(), range)) add(
                    Severity.WARN,
                    where,
                    i,
                    -1,
                    "Sim option value " + plain(v) + " cannot be represented" + rng
                );
            }
            if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) > 0) addFix(
                Severity.ERROR,
                where,
                i,
                -1,
                "Sim option frequencies add up to " + plain(sum) + "; they must add up to 1 or they won't be obeyed",
                pairsOk && sum.signum() > 0 ? fix(FixKind.SIM_SCALE, "Scale the frequencies so they add up to 1") : null
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
            if (s.get("round") != null && !(s.get("round") instanceof Boolean)) add(
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
            add(Severity.ERROR, where, i, -1, "sim must be a sweep {min, max, inc_min, inc_max} or an enum {options}");
        }
    }

    public void checkField(int j, Map<String, Object> f, int n, boolean[] parsed, boolean[] ref, String key) {
        String where = "Field " + (j + 1);
        f = withoutNulls(f, FIELD_OPTIONAL);
        for (String k : f.keySet())
            if (!FIELD_KEYS.contains(k)) add(Severity.ERROR, where, -1, j, unknownKey("field", k));
        Object nO = f.get("name");
        boolean hasPlaceholder = false;
        if (!(nO instanceof String name) || name.isBlank()) add(Severity.ERROR, where, -1, j, "Missing topic \"name\"");
        else {
            if (name.endsWith("/")) addFix(Severity.WARN, where, -1, j, "Topic must not end with '/'", topicFix(name));
            if (name.startsWith("/")) addFix(
                Severity.WARN,
                where,
                -1,
                j,
                "Topic should not start with '/'",
                topicFix(name)
            );
            if (name.contains("//")) addFix(
                Severity.WARN,
                where,
                -1,
                j,
                "Topic contains an empty level ('//')",
                topicFix(name)
            );
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
        if (!f.containsKey("unit")) addFix(
            Severity.ERROR,
            where,
            -1,
            j,
            "Missing \"unit\"; firmware-rs cangen requires it and fails to read the file without it (use \"\" when unitless)",
            new Fix(FixKind.ADD_FIELD_KEY, "unit", "Add \"unit\": \"\"")
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
                else if (!wholeLiteral(v)) add(Severity.ERROR, where, -1, j, wholeHint(v, "values entry"));
                else if (k < 0) add(
                    Severity.ERROR,
                    where,
                    -1,
                    j,
                    "values entry " + k + " is negative; definition-rs reads point numbers as usize, so the build fails"
                );
                else if (k < 1 || k > n) add(
                    Severity.WARN,
                    where,
                    -1,
                    j,
                    "values entry " +
                        k +
                        " is out of bounds; points are indexed from 1 and this message has " +
                        n +
                        ", so this field can't publish it"
                );
                else {
                    if (!parsed[(int) (k - 1)]) add(
                        Severity.WARN,
                        where,
                        -1,
                        j,
                        "values entry " + k + " refers to a padding (unparsed) point, which is never decoded"
                    );
                    ref[(int) (k - 1)] = true;
                    if (!seen.add(k)) addFix(
                        Severity.WARN,
                        where,
                        -1,
                        j,
                        "values lists point " + k + " more than once",
                        fix(FixKind.DEDUPE_VALUES, "Remove the repeated entries from values")
                    );
                }
            }
        }
        Object doc = f.get("doc");
        if (!f.containsKey("doc")) addFix(
            Severity.ERROR,
            where,
            -1,
            j,
            "Missing \"doc\"; firmware-rs cangen requires it and fails to read the file without it (use \"\" if there's nothing to say)",
            new Fix(FixKind.ADD_FIELD_KEY, "doc", "Add \"doc\": \"\"")
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
