package tiresias.traffic.dashboards;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.traffic.TrafficWindow.*;
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
import tiresias.ui.*;

/** The eFuse page: one card per eFuse, laid out like the VCU board, with current, voltage, state and a trace. */
public final class EfuseBoard extends JComponent implements Scrollable {

    /** One eFuse on the VCU board: the label printed there, and its spec message ID. */
    public record EfuseSlot(String label, long id) {}

    /** The VCU's eFuses as they sit on the board, column by column, top to bottom. Edit here if the board changes. */
    public static final EfuseSlot[][] EFUSE_LAYOUT = {
        {
            new EfuseSlot("PUMP1", 0xEF6),
            new EfuseSlot("PUMP2", 0xEF7),
            new EfuseSlot("BATTBOXFANS", 0xEF5),
            new EfuseSlot("SPARE", 0xEFA),
        },
        {
            new EfuseSlot("MC", 0xEF9),
            new EfuseSlot("RADFANS", 0xEF4),
            new EfuseSlot("BATTBOX", 0xEF8),
            new EfuseSlot("DASHBOARD", 0xEF0),
        },
        {
            new EfuseSlot("BRAKELIGHT", 0xEF1),
            new EfuseSlot("SIDE BRBS", 0xEF2), // the spec's "Shutdown eFuse"
            new EfuseSlot("LVBOARDS", 0xEF3),
        },
    };

    public static int pointIndex(Agg a, String name) {
        for (int i = 0; i < a.meta.size(); i++) if (a.meta.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** An eFuse's control_state, a car command from Calypso that the VCU tracks: 0 on, 1 auto, 2 off. */
    public static final double CONTROL_AUTO = 1;

    public static String controlText(double v) {
        return v == 0 ? "on" : v == 1 ? "auto" : v == 2 ? "off" : "";
    }

    public static double val(Agg a, int i) {
        return a == null || a.cur == null || i < 0 || i >= a.cur.length ? Double.NaN : a.cur[i];
    }

    public final TrafficWindow w;

    public static final int PAD = 16,
        GAP = 12,
        MIN_W = 230,
        MIN_H = 118,
        FOOT_H = 26,
        TRACE_S = 30;
    public EfuseSlot selected;
    public EfuseSlot hover;

    public EfuseBoard(TrafficWindow w) {
        this.w = w;
        setToolTipText("");
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                EfuseSlot s = hit(e.getPoint());
                if (s != hover) {
                    hover = s;
                    setCursor(s != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                EfuseSlot s = hit(e.getPoint());
                if (s == null) return;
                selected = s;
                repaint();
                if (e.getClickCount() == 2) w.openInEditor();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public Rectangle card(int col, int row) {
        int w = (getWidth() - 2 * PAD - 2 * GAP) / 3,
            h = Math.max(MIN_H, (getHeight() - 2 * PAD - 3 * GAP - FOOT_H) / 4);
        return new Rectangle(PAD + col * (w + GAP), PAD + row * (h + GAP), w, h);
    }

    public EfuseSlot hit(Point p) {
        for (int c = 0; c < EFUSE_LAYOUT.length; c++) for (int r = 0; r < EFUSE_LAYOUT[c].length; r++) if (
            card(c, r).contains(p)
        ) return EFUSE_LAYOUT[c][r];
        return null;
    }

    public Agg selectedAgg() {
        return selected == null ? null : efuseAgg(selected);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(3 * MIN_W + 2 * GAP + 2 * PAD, 4 * MIN_H + 3 * GAP + 2 * PAD + FOOT_H);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        EfuseSlot s = hit(e.getPoint());
        if (s == null) return null;
        Agg a = efuseAgg(s);
        StringBuilder b = new StringBuilder("<html><b>")
            .append(escHtml(s.label()))
            .append("</b>  ")
            .append(hex(s.id()));
        if (a == null || a.cur == null) return b.append("<br>No frames yet.</html>").toString();
        b.append("  ")
            .append(escHtml(a.msg == null ? "" : strOr(a.msg, "desc", "")))
            .append("<table cellspacing=0 cellpadding=0>");
        for (int i = 0; i < a.meta.size(); i++) {
            PointMeta pm = a.meta.get(i);
            if (!pm.parsed()) continue;
            String mode = pm.name().equalsIgnoreCase("control_state") ? controlText(a.cur[i]) : "";
            b.append("<tr><td><tt>")
                .append(escHtml(pm.name()))
                .append("</tt>&nbsp;&nbsp;</td><td align=right><b>")
                .append(escHtml(fmtD(a.cur[i])))
                .append("</b></td><td>&nbsp;")
                .append(escHtml(mode.isEmpty() ? pm.unit() : "(" + mode + ")"))
                .append("</td></tr>");
        }
        if (val(a, pointIndex(a, "control_state")) == CONTROL_AUTO) b.append(
            "</table>Auto: the VCU switches this eFuse on and off itself,<br>depending on conditions in its code.<table>"
        );
        return b
            .append("</table>Last seen ")
            .append(escHtml(w.lastSeenText(a)))
            .append("</html>")
            .toString();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setColor(T.panel());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font f = Theme.UI,
            bold = f.deriveFont(Font.BOLD, f.getSize2D() + 1),
            small = f.deriveFont(f.getSize2D() - 1.5f),
            big = f.deriveFont(Font.BOLD, f.getSize2D() + 9);
        for (int c = 0; c < EFUSE_LAYOUT.length; c++) for (int r = 0; r < EFUSE_LAYOUT[c].length; r++) {
            EfuseSlot s = EFUSE_LAYOUT[c][r];
            Rectangle k = card(c, r);
            Agg a = efuseAgg(s);
            boolean has = a != null && a.cur != null,
                stale = has && w.stale(a),
                sel = s == selected,
                hot = s == hover;
            double cur = has ? val(a, pointIndex(a, "current")) : Double.NaN,
                volt = has ? val(a, pointIndex(a, "voltage")) : Double.NaN,
                faulted = has ? val(a, pointIndex(a, "is_faulted")) : Double.NaN,
                enabled = has ? val(a, pointIndex(a, "is_enabled")) : Double.NaN,
                control = has ? val(a, pointIndex(a, "control_state")) : Double.NaN;
            boolean fault = faulted > 0,
                on = enabled > 0,
                auto = control == CONTROL_AUTO;
            String state = !has ? "NO DATA" : fault ? "FAULT" : on ? "ON" : "OFF";
            Color sc = !has ? T.muted() : fault ? T.red() : on ? T.green() : T.muted();
            g.setColor(fault ? T.tint(T.red()) : hot ? blend(T.fg(), T.alt(), 0.05) : T.alt());
            g.fillRoundRect(k.x, k.y, k.width, k.height, 14, 14);
            g.setStroke(new BasicStroke(sel ? 2f : 1f));
            g.setColor(sel ? T.accent() : fault ? T.red() : hot ? T.border() : T.hairline());
            g.drawRoundRect(k.x, k.y, k.width - 1, k.height - 1, 14, 14);
            int x = k.x + 14;
            g.setFont(bold);
            g.setColor(stale ? T.muted() : T.bright());
            g.drawString(s.label(), x, k.y + 24);
            // state chip
            g.setFont(small.deriveFont(Font.BOLD));
            FontMetrics sm = g.getFontMetrics();
            String chip = stale ? "STALE" : state;
            Color cc = stale ? T.yellow() : sc;
            int cw = sm.stringWidth(chip) + 14,
                cx = k.x + k.width - cw - 12;
            g.setColor(blend(cc, T.alt(), 0.22));
            g.fillRoundRect(cx, k.y + 10, cw, sm.getHeight() + 4, 10, 10);
            g.setColor(cc);
            g.drawString(chip, cx + 7, k.y + 12 + sm.getAscent());
            if (auto) {
                // the VCU decides when it's on, so OFF here can be normal
                int aw = sm.stringWidth("AUTO") + 14,
                    ax = cx - aw - 6;
                g.setColor(blend(T.accent(), T.alt(), 0.22));
                g.fillRoundRect(ax, k.y + 10, aw, sm.getHeight() + 4, 10, 10);
                g.setColor(T.accent());
                g.drawString("AUTO", ax + 7, k.y + 12 + sm.getAscent());
            }
            g.setFont(small);
            g.setColor(T.muted());
            g.drawString(
                hex(s.id()) + (a != null && a.msg != null ? "  " + strOr(a.msg, "desc", "") : ""),
                x,
                k.y + 42
            );
            if (!has) {
                g.drawString("No frames yet", x, k.y + 72);
                continue;
            }
            g.setFont(big);
            g.setColor(stale ? T.muted() : fault ? T.red() : T.bright());
            String cs = Double.isNaN(cur) ? "–" : fmtD(Math.round(cur * 100) / 100.0) + " A";
            g.drawString(cs, x, k.y + 78);
            int after = x + g.getFontMetrics().stringWidth(cs) + 12;
            g.setFont(f);
            g.setColor(T.muted());
            if (!Double.isNaN(volt)) g.drawString(fmtD(Math.round(volt * 100) / 100.0) + " V", after, k.y + 78);
            // current over the last TRACE_S seconds
            int ci = pointIndex(a, "current");
            if (ci >= 0 && k.height > 100) drawTrace(
                g,
                a,
                ci + 1,
                new Rectangle(x, k.y + 88, k.width - 28, k.height - 98),
                fault ? T.red() : T.accent()
            );
        }
        // the legend for the AUTO chip
        Rectangle last = card(0, 3);
        g.setFont(small);
        FontMetrics lm = g.getFontMetrics();
        int ly = last.y + last.height + GAP + lm.getAscent();
        g.setFont(small.deriveFont(Font.BOLD));
        g.setColor(T.accent());
        g.drawString("AUTO", PAD, ly);
        int lx = PAD + g.getFontMetrics().stringWidth("AUTO") + 8;
        g.setFont(small);
        g.setColor(T.muted());
        g.drawString(
            "Control state 1: the VCU switches the eFuse on and off itself, depending on conditions in its code, so OFF can be normal.",
            lx,
            ly
        );
        g.dispose();
    }

    public void drawTrace(Graphics2D g, Agg a, int col, Rectangle r, Color c) {
        double end = w.now();
        if (Double.isNaN(end) || r.height < 8) return;
        double start = end - TRACE_S,
            lo = Double.POSITIVE_INFINITY,
            hi = Double.NEGATIVE_INFINITY;
        List<double[]> h = a.history;
        int from = h.size() - 1;
        while (from > 0 && h.get(from)[0] > start) from--;
        for (int i = from; i < h.size(); i++) {
            double v = col < h.get(i).length ? h.get(i)[col] : Double.NaN;
            if (!Double.isFinite(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (lo > hi) return;
        if (hi - lo < 0.1) {
            hi += 0.05;
            lo -= 0.05;
        }
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        boolean first = true;
        double py = 0;
        for (int i = from; i < h.size(); i++) {
            double[] s = h.get(i);
            double v = col < s.length ? s[col] : Double.NaN;
            if (!Double.isFinite(v) || Double.isNaN(s[0])) continue;
            double x = r.x + Math.max(0, (s[0] - start) / TRACE_S) * r.width,
                y = r.y + r.height - ((v - lo) / (hi - lo)) * r.height;
            if (first) p.moveTo(x, y);
            else {
                p.lineTo(x, py);
                p.lineTo(x, y);
            }
            first = false;
            py = y;
        }
        if (!first) p.lineTo(r.x + r.width, py);
        g.setColor(T.hairline());
        g.drawLine(r.x, r.y + r.height, r.x + r.width, r.y + r.height);
        g.setColor(c);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return r.height - 40;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport v && v.getWidth() >= getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport v && v.getHeight() >= getPreferredSize().height;
    }

    public Agg efuseAgg(EfuseSlot s) {
        Agg a = w.aggs.get("e" + s.id());
        return a != null ? a : w.aggs.get("s" + s.id());
    }
}
