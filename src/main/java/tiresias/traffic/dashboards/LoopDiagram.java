package tiresias.traffic.dashboards;

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
import tiresias.ui.*;

/** The loop as a loop: stages left to right along the top, back along the bottom, closed by the GLV battery. */
public final class LoopDiagram extends JComponent {

    public static final int R = 14,
        LABEL_H = 30,
        ROW_GAP = 100,
        LEFT = 84,
        RIGHT = 64,
        BAT_X = 30;
    public ShutdownLoop.Reading reading = ShutdownLoop.read(null);

    public LoopDiagram() {
        setToolTipText("");
    }

    public void setReading(ShutdownLoop.Reading r) {
        reading = r;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(760, topY() + ROW_GAP + R + 14 + LABEL_H + 22);
    }

    public static int n() {
        return ShutdownLoop.STAGES.size();
    }

    public static int topCount() {
        return (n() + 1) / 2;
    }

    public int topY() {
        return 10 + LABEL_H + R;
    }

    public int botY() {
        return topY() + ROW_GAP;
    }

    public double span() {
        return Math.max(100, getWidth() - LEFT - RIGHT);
    }

    public java.awt.geom.Point2D.Double center(int i) {
        int k = topCount(),
            b = n() - k;
        if (i < k) return new java.awt.geom.Point2D.Double(LEFT + (k == 1 ? 0 : (span() * i) / (k - 1)), topY());
        int m = i - k;
        return new java.awt.geom.Point2D.Double(LEFT + span() - (b <= 1 ? 0 : (span() * m) / (b - 1)), botY());
    }

    /** Room for a stage's label: the smaller of the two rows' spacings. */
    public double slot() {
        int k = topCount(),
            b = n() - k;
        return Math.min(span() / Math.max(1, k - 1), span() / Math.max(1, b - 1));
    }

    public Color wire(int leaving) {
        List<ShutdownLoop.State> s = reading.states();
        if (leaving < 0) return reading.tone() == ToneLabel.MUTED ? T.border() : T.green();
        if (s.get(leaving) == ShutdownLoop.State.CLOSED) return T.green();
        if (
            s.get(leaving) == ShutdownLoop.State.BREAK &&
            leaving + 1 < n() &&
            s.get(leaving + 1) == ShutdownLoop.State.BREAK
        ) return T.red();
        return T.border();
    }

    public void stroke(Graphics2D g, Color c) {
        boolean live = c.equals(T.green()) || c.equals(T.red());
        g.setColor(live ? c : T.border());
        g.setStroke(
            live
                ? new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 5f, 5f }, 0f)
        );
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (T == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        Font f = Theme.UI;
        Font small = f.deriveFont(f.getSize2D() - 1.5f),
            bold = f.deriveFont(Font.BOLD, f.getSize2D() - 1f);
        int n = n(),
            k = topCount(),
            top = topY(),
            bot = botY(),
            mid = (top + bot) / 2;
        List<ShutdownLoop.State> st = reading.states();
        double rr = (bot - top) / 2.0,
            xEnd = LEFT + span();

        // break highlight behind the stages where the break can be
        List<Integer> brk = new ArrayList<>();
        for (int i = 0; i < n; i++) if (st.get(i) == ShutdownLoop.State.BREAK) brk.add(i);
        boolean labelled = false;
        for (int row = 0; row < 2; row++) {
            double lo = Double.MAX_VALUE,
                hi = -Double.MAX_VALUE;
            for (int i : brk)
                if ((i < k) == (row == 0)) {
                    lo = Math.min(lo, center(i).x);
                    hi = Math.max(hi, center(i).x);
                }
            if (lo > hi) continue;
            int y = row == 0 ? top : bot;
            g.setColor(alpha(T.red(), T.dark() ? 46 : 34));
            g.fill(
                new java.awt.geom.RoundRectangle2D.Double(
                    lo - R - 9,
                    y - R - 9,
                    hi - lo + 2 * R + 18,
                    2 * R + 18,
                    2 * R + 18,
                    2 * R + 18
                )
            );
            if (!labelled) {
                labelled = true;
                String t = brk.size() == 1 ? "the break is here" : "the break is at one of these";
                g.setFont(bold);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(T.red());
                int ty = row == 0 ? top + R + 26 : bot - R - 16;
                g.drawString(
                    t,
                    (float) Math.clamp(
                        (lo + hi) / 2 - fm.stringWidth(t) / 2.0,
                        4,
                        getWidth() - fm.stringWidth(t) - 4.0
                    ),
                    ty
                );
            }
        }

        // wires: GLV battery to the first stage, stage to stage (an arc at the turn), last stage back to GLV
        stroke(g, wire(-1));
        java.awt.geom.Point2D.Double c0 = center(0);
        g.draw(new java.awt.geom.Line2D.Double(BAT_X, mid - 10, BAT_X, top));
        g.draw(new java.awt.geom.Line2D.Double(BAT_X, top, c0.x, top));
        for (int i = 0; i + 1 < n; i++) {
            stroke(g, wire(i));
            java.awt.geom.Point2D.Double a = center(i),
                b = center(i + 1);
            if (i == k - 1) g.draw(
                new java.awt.geom.Arc2D.Double(xEnd - rr, top, 2 * rr, 2 * rr, 90, -180, java.awt.geom.Arc2D.OPEN)
            );
            else g.draw(new java.awt.geom.Line2D.Double(a, b));
        }
        stroke(g, wire(n - 1));
        java.awt.geom.Point2D.Double cl = center(n - 1);
        g.draw(new java.awt.geom.Line2D.Double(cl.x, bot, BAT_X, bot));
        g.draw(new java.awt.geom.Line2D.Double(BAT_X, bot, BAT_X, mid + 10));
        // GLV battery symbol
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(T.fg());
        g.draw(new java.awt.geom.Line2D.Double(BAT_X - 11, mid - 4, BAT_X + 11, mid - 4));
        g.draw(new java.awt.geom.Line2D.Double(BAT_X - 6, mid + 4, BAT_X + 6, mid + 4));
        g.setFont(small);
        g.setColor(T.muted());
        g.drawString("GLV", BAT_X + 16, mid + 5);

        // stages
        FontMetrics sfm = g.getFontMetrics(small);
        double slot = slot();
        for (int i = 0; i < n; i++) {
            java.awt.geom.Point2D.Double c = center(i);
            ShutdownLoop.Stage stage = ShutdownLoop.STAGES.get(i);
            ShutdownLoop.State s = st.get(i);
            Boolean val = reading.values().get(i);
            boolean sensed = stage.point() != null,
                topRow = i < k;
            Color fill, ring, text;
            switch (s) {
                case CLOSED -> {
                    fill = sensed ? T.green() : blend(T.green(), T.panel(), 0.35);
                    ring = T.green();
                    text = sensed ? onColor(T.green()) : T.bright();
                }
                case BREAK -> {
                    fill = blend(T.red(), T.panel(), 0.32);
                    ring = T.red();
                    text = T.bright();
                }
                default -> {
                    fill = T.panel();
                    ring = T.border();
                    text = T.muted();
                }
            }
            var circle = new java.awt.geom.Ellipse2D.Double(c.x - R, c.y - R, 2 * R, 2 * R);
            if (s == ShutdownLoop.State.BREAK) {
                g.setColor(alpha(T.red(), 90));
                g.setStroke(new BasicStroke(2f));
                g.draw(new java.awt.geom.Ellipse2D.Double(c.x - R - 5, c.y - R - 5, 2 * R + 10, 2 * R + 10));
            }
            g.setColor(fill);
            g.fill(circle);
            g.setColor(ring);
            g.setStroke(
                s == ShutdownLoop.State.UNKNOWN
                    ? new BasicStroke(
                          1.5f,
                          BasicStroke.CAP_BUTT,
                          BasicStroke.JOIN_MITER,
                          1f,
                          new float[] { 3f, 3f },
                          0f
                      )
                    : new BasicStroke(s == ShutdownLoop.State.BREAK ? 2.5f : 1.5f)
            );
            g.draw(circle);
            g.setFont(bold);
            FontMetrics bfm = g.getFontMetrics();
            String num = String.valueOf(i + 1);
            g.setColor(text);
            g.drawString(num, (float) (c.x - bfm.stringWidth(num) / 2.0), (float) (c.y + bfm.getAscent() / 2.0 - 2));
            // pin reading chip, on the inside of the loop
            if (sensed && val != null) {
                int cx = (int) Math.round(c.x + R - 5),
                    cy = (int) Math.round(topRow ? c.y + R - 9 : c.y - R - 5);
                g.setColor(val ? T.green() : T.red());
                g.fillRoundRect(cx, cy, 14, 14, 6, 6);
                g.setColor(T.panel());
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cx, cy, 14, 14, 6, 6);
                g.setFont(small);
                g.setColor(onColor(val ? T.green() : T.red()));
                String v = val ? "1" : "0";
                g.drawString(v, cx + 7 - sfm.stringWidth(v) / 2f, cy + 11);
            }
            // name: above the top row, below the bottom row, wrapped to two lines
            g.setFont(s == ShutdownLoop.State.BREAK ? bold : small);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(stage.name(), fm, (int) slot - 6);
            g.setColor(s == ShutdownLoop.State.BREAK ? T.red() : s == ShutdownLoop.State.CLOSED ? T.fg() : T.muted());
            int lh = fm.getHeight() - 2;
            for (int li = 0; li < lines.size(); li++) {
                String t = lines.get(li);
                float y = topRow
                    ? (float) (c.y - R - 7 - (lines.size() - 1 - li) * lh)
                    : (float) (c.y + R + 4 + fm.getAscent() + li * lh);
                g.drawString(t, (float) (c.x - fm.stringWidth(t) / 2.0), y);
            }
        }

        // legend
        g.setFont(small);
        int y = getHeight() - 8,
            x = BAT_X - 10;
        x = legend(g, x, y, T.green(), true, "closed");
        x = legend(g, x, y, T.red(), false, "the break is at one of these");
        x = legend(g, x, y, T.border(), false, "no power (after the break) or not sensed");
        g.setColor(T.green());
        g.fillRoundRect(x, y - 10, 12, 12, 5, 5);
        g.setColor(T.red());
        g.fillRoundRect(x + 14, y - 10, 12, 12, 5, 5);
        g.setColor(T.muted());
        g.drawString("pin reads 1 / 0", x + 32, y);
        g.dispose();
    }

    public static int legend(Graphics2D g, int x, int y, Color c, boolean filled, String text) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.5f));
        if (filled) g.fillOval(x, y - 10, 11, 11);
        else g.drawOval(x, y - 10, 10, 10);
        g.setColor(T.muted());
        g.drawString(text, x + 16, y);
        return x + 16 + g.getFontMetrics().stringWidth(text) + 18;
    }

    /** Word-wraps to at most two lines of the given width, ellipsizing the second. */
    public static List<String> wrap(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return List.of(s);
        String[] words = s.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String t = first.isEmpty() ? words[i] : first + " " + words[i];
            if (fm.stringWidth(t) > w && !first.isEmpty()) break;
            first.setLength(0);
            first.append(t);
            i++;
        }
        String rest = String.join(" ", Arrays.copyOfRange(words, i, words.length));
        if (rest.isEmpty()) return List.of(ellipsize(first.toString(), fm, w));
        return List.of(ellipsize(first.toString(), fm, w), ellipsize(rest, fm, w));
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        for (int i = 0; i < n(); i++) {
            if (center(i).distance(e.getPoint()) > R + 5) continue;
            ShutdownLoop.Stage s = ShutdownLoop.STAGES.get(i);
            Boolean v = reading.values().get(i);
            String pin =
                s.point() != null
                    ? "Pin " + s.point() + (v == null ? ": not in this frame" : " reads " + (v ? "1" : "0"))
                    : s.powered()
                        ? "Powers the VCU, so it's closed whenever the VCU is sending"
                        : "Not sensed by " + hex(ShutdownLoop.MESSAGE_ID);
            String state = switch (reading.states().get(i)) {
                case CLOSED -> s.point() == null && !s.powered() ? "Closed, because a later pin reads 1" : "Closed";
                case BREAK -> "The break is here, or at another highlighted stage";
                case DEAD -> "After the break, so it has no power and its own state can't be told";
                case UNKNOWN -> "Can't be told from this message";
            };
            return (
                "<html><b>" +
                (i + 1) +
                ". " +
                escHtml(s.name()) +
                "</b><br>" +
                escHtml(pin) +
                "<br>" +
                escHtml(state) +
                "</html>"
            );
        }
        return null;
    }
}
