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

/** The loaded files as tiles, shown when no file or message is selected. Click one to open it. */
public final class FileGrid extends TileGrid<SpecFile> {

    public final App app;

    public FileGrid(App app, Consumer<SpecFile> open) {
        super(f -> app.fileSev.get(f), open);
        this.app = app;
    }

    @Override
    public String key(SpecFile f) {
        return app.relName(f);
    }

    @Override
    public String badge(SpecFile f) {
        return f.dirty ? "unsaved" : null;
    }

    @Override
    public String title(SpecFile f) {
        if (f.loadError != null) return "Couldn't be loaded";
        int n = f.messages.size();
        long lo = Long.MAX_VALUE,
            hi = Long.MIN_VALUE;
        for (Object o : f.messages) {
            Long v = asMap(o) == null ? null : parseId(str(asMap(o), "id"));
            if (v == null) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        String range = lo > hi ? "" : lo == hi ? " · " + hexId(lo) : " · " + hexId(lo) + "–" + hexId(hi);
        return n + (n == 1 ? " message" : " messages") + range;
    }

    public static String hexId(long v) {
        return String.format("0x%03X", v);
    }

    @Override
    public String detail(SpecFile f) {
        if (f.loadError != null) return f.loadError;
        int e = 0,
            w = 0;
        for (Issue is : app.issues)
            if (is.file() == f) {
                if (is.sev() == Severity.ERROR) e++;
                else if (is.sev() == Severity.WARN) w++;
            }
        if (e == 0 && w == 0) return "No errors or warnings";
        return e + (e == 1 ? " error" : " errors") + " · " + w + (w == 1 ? " warning" : " warnings");
    }

    @Override
    public String tooltip(SpecFile f) {
        StringBuilder b = new StringBuilder("<html><b>").append(escHtml(app.relName(f))).append("</b>");
        if (f.loadError != null) return b.append("<br>").append(escHtml(f.loadError)).append("</html>").toString();
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Object o : f.messages) if (asMap(o) instanceof Map<String, Object> m) ms.add(m);
        if (app.sortById.isSelected()) sortMessagesById(ms);
        b.append("<table cellspacing=0 cellpadding=0>");
        int k = 0;
        for (Map<String, Object> m : ms) {
            if (k++ == 40) {
                b.append("<tr><td></td><td>… ")
                    .append(ms.size() - 40)
                    .append(" more</td></tr>");
                break;
            }
            b.append("<tr><td><tt>")
                .append(escHtml(MessageGrid.meta(m) ? "meta" : strOr(m, "id", "no id")))
                .append("</tt>&nbsp;&nbsp;</td><td>")
                .append(escHtml(strOr(m, "desc", "")))
                .append("</td></tr>");
        }
        return b.append("</table></html>").toString();
    }
}
