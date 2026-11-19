package tiresias.editor;

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
import tiresias.spec.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/** A file's messages as tiles. Click one, or use the arrow keys and Enter, to open it. */
public final class MessageGrid extends TileGrid<Map<String, Object>> {

    public MessageGrid(Function<Map<String, Object>, Severity> severity, Consumer<Map<String, Object>> open) {
        super(severity, open);
    }

    public static boolean meta(Map<String, Object> m) {
        return !m.containsKey("id") && !m.containsKey("points");
    }

    public static String bitsText(int bits) {
        return bits % 8 == 0 ? bits / 8 + (bits == 8 ? " byte" : " bytes") : bits + (bits == 1 ? " bit" : " bits");
    }

    @Override
    public String key(Map<String, Object> m) {
        return meta(m) ? "meta" : strOr(m, "id", "no id");
    }

    @Override
    public boolean keyMuted(Map<String, Object> m) {
        return meta(m);
    }

    @Override
    public String badge(Map<String, Object> m) {
        return Boolean.TRUE.equals(m.get("is_ext")) ? "extended" : null;
    }

    @Override
    public String title(Map<String, Object> m) {
        return strOr(m, "desc", "(no description)");
    }

    @Override
    public String detail(Map<String, Object> m) {
        int fields = listOrEmpty(m.get("fields")).size();
        String f = fields + (fields == 1 ? " field" : " fields");
        if (meta(m)) return "meta message · " + f;
        List<Object> pts = listOrEmpty(m.get("points"));
        int bits = offsets(pts)[pts.size()];
        return bitsText(bits) + " · " + pts.size() + (pts.size() == 1 ? " point" : " points") + " · " + f;
    }

    @Override
    public String tooltip(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("<html><b>")
            .append(escHtml(strOr(m, "id", "") + "  " + strOr(m, "desc", "")))
            .append("</b>");
        List<Object> pts = listOrEmpty(m.get("points"));
        if (pts.isEmpty()) return b.append("</html>").toString();
        int total = offsets(pts)[pts.size()],
            padding = 0;
        List<Map<String, Object>> named = new ArrayList<>();
        for (Object o : pts) {
            Map<String, Object> p = asMap(o);
            if (Boolean.FALSE.equals(p.get("parse"))) padding += p.get("size") instanceof Number n ? n.intValue() : 0;
            else named.add(p);
        }
        b.append("<br>")
            .append(total)
            .append(total == 1 ? " bit" : " bits");
        if (total % 8 == 0) b.append(" (").append(bitsText(total)).append(")");
        if (padding > 0) b.append(", including ")
            .append(padding)
            .append(padding == 1 ? " bit" : " bits")
            .append(" of padding");
        b.append("<table cellspacing=0 cellpadding=0>");
        int k = 0;
        for (Map<String, Object> p : named) {
            if (k++ == 16) {
                b.append("<tr><td>… ")
                    .append(named.size() - 16)
                    .append(" more</td></tr>");
                break;
            }
            int bits = p.get("size") instanceof Number n ? n.intValue() : 0;
            b.append("<tr><td><tt>")
                .append(escHtml(strOr(p, "name", "(unnamed)")))
                .append("</tt></td><td align=right>&nbsp;&nbsp;")
                .append(bits)
                .append(bits == 1 ? " bit" : " bits")
                .append("</td></tr>");
        }
        return b.append("</table></html>").toString();
    }
}
