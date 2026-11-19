package tiresias.traffic;

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
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/**
 * One point of one CAN ID over time, drawn as a step line (CAN values hold until the next frame). It shows the last
 * {@code window} seconds up to {@code end} (the newest frame, or now while listening live), or everything when the
 * window is NaN; with a marked frame it centers the window on that frame instead.
 */
public final class PointGraph extends JComponent {

    public static final int L = 70,
        R = 18,
        TOP = 30,
        B = 28;
    public List<double[]> hist;
    public int col;
    public PointMeta pm;
    public double t0,
        markT = Double.NaN,
        window = Double.NaN;
    public java.util.function.DoubleSupplier end = () -> Double.NaN;
    public int hoverX = -1;

    public PointGraph() {
        setPreferredSize(new Dimension(360, 200));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverX = e.getX();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverX = -1;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void set(List<double[]> hist, int col, PointMeta pm, double t0, double markT) {
        this.hist = hist;
        this.col = col;
        this.pm = pm;
        this.t0 = t0;
        this.markT = markT;
        repaint();
    }

    /** A round step (1, 2 or 5 times a power of ten) giving about target divisions of range; whole numbers if asked. */
    public static double niceStep(double range, int target, boolean whole) {
        if (!(range > 0)) return 1;
        double raw = range / target,
            mag = Math.pow(10, Math.floor(Math.log10(raw))),
            f = raw / mag,
            step = (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * mag;
        return whole ? Math.max(1, Math.round(step)) : step;
    }

    public static String fmtTime(double s) {
        return fmtD(Math.round(s * 1000) / 1000.0) + " s";
    }

    public boolean usable(double[] h) {
        return col < h.length && !Double.isNaN(h[0]) && Double.isFinite(h[col]);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setColor(T.panel());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font f = Theme.UI,
            small = f.deriveFont(f.getSize2D() - 1.5f);
        int n = hist == null || pm == null ? 0 : hist.size();
        // newest and oldest usable samples
        int last = n - 1;
        while (last >= 0 && !usable(hist.get(last))) last--;
        if (last < 0) {
            g.setFont(f);
            g.setColor(T.muted());
            g.drawString(
                pm == null
                    ? "Pick a point on the left to graph it."
                    : "No timed values to graph for " + pm.name() + ".",
                16,
                TOP
            );
            g.dispose();
            return;
        }
        double lastT = hist.get(last)[0],
            endT = end.getAsDouble();
        if (Double.isNaN(endT) || endT < lastT) endT = lastT;
        // the time span shown
        double tStart, tEnd;
        if (Double.isNaN(window)) {
            int first = 0;
            while (!usable(hist.get(first))) first++;
            tStart = hist.get(first)[0];
            tEnd = endT;
        } else if (!Double.isNaN(markT)) {
            tStart = markT - window / 2;
            tEnd = markT + window / 2;
        } else {
            tEnd = endT;
            tStart = tEnd - window; // always the full window, so the axis scrolls instead of rescaling
        }
        if (tEnd <= tStart) tEnd = tStart + 1;
        // samples in view: walk back from the newest to the last one before the window (its value holds into view)
        int from = last;
        while (from > 0 && (!usable(hist.get(from)) || hist.get(from)[0] > tStart)) from--;
        int to = last;
        while (to > from && hist.get(to)[0] > tEnd) to--;
        double vmin = Double.POSITIVE_INFINITY,
            vmax = Double.NEGATIVE_INFINITY;
        boolean integer = true,
            any = false;
        for (int i = from; i <= to; i++) {
            double[] h = hist.get(i);
            if (!usable(h) || h[0] > tEnd) continue;
            if (h[0] < tStart && i != from) continue;
            any = true;
            vmin = Math.min(vmin, h[col]);
            vmax = Math.max(vmax, h[col]);
            if (h[col] != Math.rint(h[col])) integer = false;
        }
        String unit = pm.unit().isEmpty() ? "" : " " + pm.unit();
        g.setFont(f.deriveFont(Font.BOLD));
        g.setColor(T.bright());
        g.drawString(pm.name() + (unit.isEmpty() ? "" : "  (" + pm.unit() + ")"), L, 20);
        if (!any || hist.get(from)[0] > tEnd) {
            g.setFont(f);
            g.setColor(T.muted());
            g.drawString("No frames in this time window.", L, TOP + 24);
            g.dispose();
            return;
        }
        boolean bit = pm.size() == 1;
        double ystep;
        if (bit) {
            vmin = -0.1;
            vmax = 1.1;
            ystep = 1;
        } else {
            if (vmin == vmax) {
                vmin -= 1;
                vmax += 1;
            }
            ystep = niceStep(vmax - vmin, 4, integer);
            vmin = Math.floor(vmin / ystep) * ystep;
            vmax = Math.ceil(vmax / ystep) * ystep;
        }
        int x0 = L,
            x1 = getWidth() - R,
            y0 = TOP,
            y1 = getHeight() - B;
        if (x1 - x0 < 40 || y1 - y0 < 30) {
            g.dispose();
            return;
        }
        double ft0 = tStart,
            ft1 = tEnd,
            fv0 = vmin,
            fv1 = vmax;
        java.util.function.DoubleUnaryOperator X = t -> x0 + ((t - ft0) / (ft1 - ft0)) * (x1 - x0);
        java.util.function.DoubleUnaryOperator Y = v -> y1 - ((v - fv0) / (fv1 - fv0)) * (y1 - y0);
        // grid and labels, on round steps (1, 2 or 5 times a power of ten)
        g.setFont(small);
        FontMetrics sm = g.getFontMetrics();
        double ylo = bit ? 0 : vmin,
            yhi = bit ? 1 : vmax;
        for (int k = 0; k <= 50; k++) {
            double v = ylo + k * ystep;
            if (v > yhi + ystep * 1e-9) break;
            v = Math.round(v / ystep) * ystep;
            int y = (int) Math.round(Y.applyAsDouble(v));
            g.setColor(T.hairline());
            g.drawLine(x0, y, x1, y);
            g.setColor(T.muted());
            String s = fmtD(v);
            g.drawString(s, x0 - 8 - sm.stringWidth(s), y + sm.getAscent() / 2 - 1);
        }
        double tstep = niceStep(tEnd - tStart, 5, false),
            firstTick = Math.ceil((tStart - t0) / tstep) * tstep;
        for (int k = 0; k <= 50; k++) {
            double rel = Math.round((firstTick + k * tstep) / tstep) * tstep,
                t = rel + t0;
            if (t > tEnd + tstep * 1e-9) break;
            String s = fmtTime(rel);
            int x = (int) Math.round(X.applyAsDouble(t)),
                w = sm.stringWidth(s);
            g.setColor(T.hairline());
            g.drawLine(x, y1, x, y1 + 3);
            g.setColor(T.muted());
            g.drawString(s, Math.clamp(x - w / 2, x0 - w / 2, x1 - w), y1 + sm.getAscent() + 6);
        }
        // the series as a step line, clipped to the window; the last value holds until the end of the data
        Shape oldClip = g.getClip();
        g.clipRect(x0, y0 - 2, x1 - x0 + 1, y1 - y0 + 4);
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        double px = Double.NaN,
            py = Double.NaN;
        for (int i = from; i <= to; i++) {
            double[] h = hist.get(i);
            if (!usable(h)) continue;
            double x = X.applyAsDouble(Math.max(h[0], tStart)),
                y = Y.applyAsDouble(h[col]);
            if (Double.isNaN(px)) path.moveTo(x, y);
            else if (y != py || x - px >= 1) {
                path.lineTo(x, py);
                path.lineTo(x, y);
            } else continue;
            px = x;
            py = y;
        }
        double holdTo = X.applyAsDouble(Math.min(tEnd, endT));
        if (!Double.isNaN(px) && holdTo > px) path.lineTo(holdTo, py);
        g.setColor(T.accent());
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setClip(oldClip);
        if (!Double.isNaN(markT) && markT >= tStart && markT <= tEnd) {
            int x = (int) Math.round(X.applyAsDouble(markT));
            g.setColor(T.orange());
            g.setStroke(
                new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 4f, 4f }, 0f)
            );
            g.drawLine(x, y0, x, y1);
        }
        // hover readout: the value in effect at the mouse's time
        if (hoverX >= x0 && hoverX <= x1) {
            double t = tStart + ((hoverX - x0) / (double) (x1 - x0)) * (tEnd - tStart),
                v = Double.NaN,
                vt = Double.NaN;
            if (t <= endT) for (int i = from; i <= to; i++) {
                double[] h = hist.get(i);
                if (!usable(h)) continue;
                if (h[0] > t) break;
                v = h[col];
                vt = h[0];
            }
            if (!Double.isNaN(v)) {
                int y = (int) Math.round(Y.applyAsDouble(v));
                g.setStroke(new BasicStroke(1f));
                g.setColor(T.border());
                g.drawLine(hoverX, y0, hoverX, y1);
                g.setColor(T.accent());
                g.fillOval(hoverX - 3, y - 3, 7, 7);
                String s = fmtD(v) + unit + "  since " + fmtTime(vt - t0);
                g.setFont(small);
                int w = g.getFontMetrics().stringWidth(s) + 12,
                    bx = Math.min(hoverX + 8, x1 - w);
                g.setColor(T.alt());
                g.fillRoundRect(bx, y0 - 2, w, sm.getHeight() + 4, 8, 8);
                g.setColor(T.bright());
                g.drawString(s, bx + 6, y0 + sm.getAscent());
            }
        }
        g.dispose();
    }
}
