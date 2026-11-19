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

// ======================================================================================
// Bit layout visualisation (drag blocks or legend rows to reorder points)
// ======================================================================================

public final class BitLayoutPanel extends JPanel implements Scrollable {

    public static final int LEFT = 82,
        TOP = 70,
        CH = 48,
        LH = 30,
        GAP = 34;
    public MessageEditor ed;
    public int[] owner = new int[0];
    public int total, rows;
    public int hover = -1,
        dragFrom = -1,
        dropSlot = -1;
    public Point pressAt, dragAt;
    public boolean dragging;
    public java.awt.image.BufferedImage ghost;
    public float ghostAlpha;
    public double slideT = 1;
    public final Map<Object, Integer> oldOrder = new IdentityHashMap<>();

    /** Runs a reorder, crossfading the grid and sliding legend rows from their old positions. */
    public void animateChange(Runnable change) {
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

    public BitLayoutPanel() {
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

    public int cw() {
        return Math.clamp((getWidth() - LEFT - 40) / 8, 48, 104);
    }

    public void setMessage(MessageEditor ed) {
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

    public int legendTop() {
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
    public int hit(Point p) {
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
    public int slotAt(Point p) {
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

    public static int significance(Map<String, Object> p, int off, int size, int pos) {
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
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] { 3f, 3f }, 0f));
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
            for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 2; dx++) g.fillOval(18 + dx * 4, y + 9 + dy * 4, 2, 2);
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
            g.drawString(total + " bits, " + (total / 8) + " bytes", LEFT, TOP + rows * CH + 22);
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

    public void drawRun(
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
