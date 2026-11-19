package tiresias.traffic;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
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
import tiresias.spec.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/**
 * CAN traffic: candump parsing, matching frames to the spec, and decoding their points.
 */
public final class Can {

    private Can() {}

    // ======================================================================================
    // Decoding (for the "Decode" tab)
    // ======================================================================================

    public static long extractBits(byte[] data, int off, int size) {
        long v = 0;
        for (int b = 0; b < size; b++) {
            int bit = off + b;
            int byteIdx = bit / 8;
            int val = byteIdx < data.length ? (data[byteIdx] >> (7 - (bit % 8))) & 1 : 0;
            v = (v << 1) | val;
        }
        return v;
    }

    public static byte[] parseHexBytes(String s) {
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
    // CAN traffic: candump parsing and decoding against the spec
    // ======================================================================================

    public static final HexFormat HEX = HexFormat.of().withUpperCase();
    public static final HexFormat HEX_SPACED = HexFormat.ofDelimiter(" ").withUpperCase();

    /** One CAN frame read from candump output. */
    public record CanFrame(
        double ts,
        String bus,
        long id,
        boolean ext,
        boolean rtr,
        boolean fd,
        byte[] data,
        String line
    ) {
        public String key() {
            return (ext ? "e" : "s") + id;
        }

        public String idText() {
            return ext ? "%08X".formatted(id) : "%03X".formatted(id);
        }

        public String dataText() {
            return rtr ? "remote request" : HEX_SPACED.formatHex(data);
        }
    }

    /** candump -L log lines, cansend-style lines, and (ts) bus ID#DATA, including CAN FD ## and remote R. */
    public static final Pattern CANDUMP_LOG = Pattern.compile(
        "^\\s*(?:\\(([^)]*)\\)\\s+)?(?:(\\S+)\\s+)?([0-9A-Fa-f]{1,8})(##?)(R\\d*|[0-9A-Fa-f.]*)(?:\\s+[RT])?\\s*$"
    );
    /** Default candump output: [ (ts) ] bus [RX - -] ID [len] bytes..., with optional ASCII after. */
    public static final Pattern CANDUMP_STD = Pattern.compile(
        "^\\s*(?:\\(([^)]*)\\)\\s+)?(\\S+)\\s+(?:(?:RX|TX)\\s+\\S+\\s+\\S+\\s+)?([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+\\[(\\d{1,2})]\\s*(.*)$"
    );

    public static double parseTs(String s) {
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

    public static CanFrame parseCandumpLine(String line) {
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
    public static Map<String, SpecRef> specIndex(List<SpecFile> files) {
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
    public record FrameCheck(SpecRef hit, String text, int level) {}

    public static FrameCheck checkFrame(CanFrame fr, Map<String, SpecRef> idx) {
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
    public record Decoded(List<String[]> points, List<String> topics, StringBuilder summary) {
        public Decoded() {
            this(new ArrayList<>(), new ArrayList<>(), new StringBuilder());
        }
    }

    /** Decodes a frame's bytes with a message's points and fields, the same way the Decode test tab does. */
    /** A point's raw bits from a frame, byte-swapped for little-endian points. */
    public static long pointRaw(Map<String, Object> p, byte[] data, int off, int size) {
        long raw = extractBits(data, off, size);
        if ("little".equals(p.get("endianness")) && size % 8 == 0) {
            long r = 0;
            for (int k = 0; k < size / 8; k++) r = (r << 8) | ((raw >>> (8 * k)) & 0xFF);
            raw = r;
        }
        return raw;
    }

    /** A point's value from its raw bits: IEEE 754 float, sign extension, then the formatter's divisor. */
    public static double pointValue(Map<String, Object> p, long raw, int size) {
        if (Boolean.TRUE.equals(p.get("ieee754_f32")) && size == 32) return Float.intBitsToFloat((int) raw);
        long v = raw;
        if (Boolean.TRUE.equals(p.get("signed")) && size < 64 && ((raw >>> (size - 1)) & 1) == 1) v =
            raw - (1L << size);
        BigDecimal div = divisor(p);
        return div == null ? v : v / div.doubleValue();
    }

    /** One point of a message as the decoded traffic view shows it: its name, size, and the unit of its field. */
    public record PointMeta(String name, int size, String unit, boolean parsed) {}

    public static List<PointMeta> pointMeta(Map<String, Object> msg) {
        List<Object> pts = listOrEmpty(msg.get("points"));
        String[] units = new String[pts.size()];
        for (Object o : listOrEmpty(msg.get("fields"))) {
            Map<String, Object> f = asMap(o);
            if (f == null) continue;
            String unit = strOr(f, "unit", "");
            for (Object v : listOrEmpty(f.get("values"))) {
                Long k = longVal(v);
                if (k != null && k >= 1 && k <= pts.size() && units[(int) (k - 1)] == null) units[(int) (k - 1)] = unit;
            }
        }
        List<PointMeta> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            int size = sizeOf(p);
            boolean ok = p != null && size >= 1 && size <= 64 && isParsed(p);
            out.add(
                new PointMeta(
                    p == null ? "#" + (i + 1) : strOr(p, "name", "#" + (i + 1)),
                    size,
                    units[i] == null ? "" : units[i],
                    ok
                )
            );
        }
        return out;
    }

    /** Every point's value in a frame (NaN for padding and invalid points); raw bits go into rawOut if given. */
    public static double[] pointValues(Map<String, Object> msg, byte[] data, long[] rawOut) {
        List<Object> pts = listOrEmpty(msg.get("points"));
        int[] off = offsets(pts);
        double[] v = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            int size = sizeOf(p);
            if (p == null || size < 1 || size > 64 || !isParsed(p)) {
                v[i] = Double.NaN;
                continue;
            }
            long raw = pointRaw(p, data, off[i], size);
            if (rawOut != null) rawOut[i] = raw;
            v[i] = pointValue(p, raw, size);
        }
        return v;
    }

    public static Decoded decodeFrame(Map<String, Object> msg, byte[] data) {
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
            long raw = pointRaw(p, data, off[i], size);
            values[i] = pointValue(p, raw, size);
            String val = fmtD(values[i]);
            if (!(Boolean.TRUE.equals(p.get("ieee754_f32")) && size == 32) && divisor(p) == null) val +=
                "  (formatter \"" + formatterKey(p) + "\" has no usable arg)";
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
}
