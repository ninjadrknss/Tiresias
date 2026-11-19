package tiresias.ui;

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
import tiresias.spec.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;

// ======================================================================================
// File overview: a file's messages as a grid of tiles
// ======================================================================================

/** A tree row painted as icon + up to three text runs, each with its own font and color. */
public abstract class TreeRow extends JComponent implements javax.swing.tree.TreeCellRenderer {

    public static final int ICON_GAP = 7,
        RUN_GAP = 8,
        VPAD = 2;
    public Icon icon;
    public String a, b, c;
    public Font fa, fb;
    public Color ca, cb, cc;

    public void set(Icon icon, String a, Font fa, Color ca, String b, Font fb, Color cb, String c, Color cc) {
        this.icon = icon;
        this.a = a;
        this.fa = fa;
        this.ca = ca;
        this.b = b == null || b.isEmpty() ? null : b;
        this.fb = fb;
        this.cb = cb;
        this.c = c;
        this.cc = cc;
    }

    @Override
    public Dimension getPreferredSize() {
        int w = 0,
            h = 0;
        if (icon != null) {
            w += icon.getIconWidth() + ICON_GAP;
            h = icon.getIconHeight();
        }
        FontMetrics ma = getFontMetrics(fa),
            mb = getFontMetrics(fb);
        w += ma.stringWidth(a);
        if (b != null) w += RUN_GAP + mb.stringWidth(b);
        if (c != null) w += RUN_GAP + mb.stringWidth(c);
        h = Math.max(h, Math.max(ma.getHeight(), mb.getHeight()));
        return new Dimension(w + 2, h + 2 * VPAD);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        int x = 0,
            h = getHeight();
        if (icon != null) {
            icon.paintIcon(this, g, x, (h - icon.getIconHeight()) / 2);
            x += icon.getIconWidth() + ICON_GAP;
        }
        FontMetrics ma = getFontMetrics(fa),
            mb = getFontMetrics(fb);
        int base = (h - Math.max(ma.getHeight(), mb.getHeight())) / 2 + Math.max(ma.getAscent(), mb.getAscent());
        g.setFont(fa);
        g.setColor(ca);
        g.drawString(a, x, base);
        x += ma.stringWidth(a);
        g.setFont(fb);
        if (b != null) {
            x += RUN_GAP;
            g.setColor(cb);
            g.drawString(b, x, base);
            x += mb.stringWidth(b);
        }
        if (c != null) {
            x += RUN_GAP;
            g.setColor(cc);
            g.drawString(c, x, base);
        }
        g.dispose();
    }
}
