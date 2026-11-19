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

public final class Json {

    public static Object parse(String s) {
        return new Parser(s).parseAll();
    }

    public static final class Parser {

        public final String s;
        public int i;
        public final List<String> warnings = new ArrayList<>();

        public Parser(String s) {
            this.s = s;
        }

        public Object parseAll() {
            ws();
            Object v = value();
            ws();
            if (i < s.length()) throw err("Unexpected trailing content");
            return v;
        }

        public JsonException err(String m) {
            return new JsonException(m + " (" + pos(i) + ")");
        }

        public String pos(int at) {
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

        public char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        public void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        public Object value() {
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

        public void lit(String w) {
            if (!s.startsWith(w, i)) throw err("Expected '" + w + "'");
            i += w.length();
        }

        public Map<String, Object> obj() {
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

        public List<Object> arr() {
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

        public String str() {
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

        public BigDecimal num() {
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

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        sb.append('\n');
        return sb.toString();
    }

    public static String writeCompact(Object v) {
        String s = write(v);
        return s.replaceAll("\\s*\\n\\s*", " ").strip();
    }

    public static void indent(StringBuilder sb, int n) {
        sb.repeat("  ", n);
    }

    public static void write(StringBuilder sb, Object v, int ind) {
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

    public static void quote(StringBuilder sb, String s) {
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
    public static <T> T deepCopy(T v) {
        return (T) parse(write(v));
    }
}
