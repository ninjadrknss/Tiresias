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

/** A node's active faults: a tile per fault (Critical first), and a table of every change in the log. */
public final class FaultsPanel extends JPanel {

    // ---- fault pages (VCU and BMS)

    /** The fault flag messages: one bit per fault, with Critical or Non-Critical in each field's topic. */
    public static final long VCU_FAULTS_ID = 0x502,
        BMS_FAULTS_ID = 0x89;
    public static final Set<String> ACRONYMS = Set.of(
        "CAN",
        "BMS",
        "IMU",
        "BSPD",
        "RTDS",
        "LV",
        "TS",
        "HV",
        "MC",
        "VCU",
        "IMD",
        "AMS",
        "DCL",
        "CCL"
    );

    /** ONBOARD_BRAKE_OPEN_CIRCUIT_FAULT -> "Onboard brake open circuit"; acronyms stay in capitals. */
    public static String faultLabel(String name) {
        List<String> w = new ArrayList<>(Arrays.asList(name.split("_+")));
        w.removeIf(String::isEmpty);
        if (w.size() > 1 && w.getLast().equalsIgnoreCase("fault")) w.removeLast();
        StringBuilder b = new StringBuilder();
        for (String x : w) {
            String up = x.toUpperCase(Locale.ROOT);
            String y = ACRONYMS.contains(up) ? up : x.toLowerCase(Locale.ROOT);
            if (b.isEmpty() && !ACRONYMS.contains(up)) y = Character.toUpperCase(y.charAt(0)) + y.substring(1);
            if (!b.isEmpty()) b.append(' ');
            b.append(y);
        }
        return b.isEmpty() ? name : b.toString();
    }

    public static final class FaultInfo {

        public final int index;
        public final String name, label, topic, doc;
        public final boolean critical;
        public boolean active;
        public double since = Double.NaN,
            lastEnd = Double.NaN;
        public int count;

        public FaultInfo(int index, String name, String topic, String doc, boolean critical) {
            this.index = index;
            this.name = name;
            // the topic's last part reads better than some point names (Cell_Voltage_Low, not low_cell_volt)
            String last = topic.substring(topic.lastIndexOf('/') + 1);
            this.label = faultLabel(last.isBlank() || last.contains("{") ? name : last);
            this.topic = topic;
            this.doc = doc;
            this.critical = critical;
        }
    }

    public record FaultEvent(double t, FaultInfo fault, boolean on) {}

    public static String ago(double s) {
        if (Double.isNaN(s)) return "";
        if (s < 1) return Math.round(s * 1000) + " ms";
        if (s < 120) return fmtD(Math.round(s * 10) / 10.0) + " s";
        if (s < 7200) return Math.round(s / 60) + " min";
        return fmtD(Math.round(s / 360) / 10.0) + " h";
    }

    public final TrafficWindow w;

    /** The node ("VCU") and its fault flags message ID (standard). */
    public final String who;
    public final long id;
    public final List<FaultInfo> faults = new ArrayList<>();
    public final List<FaultEvent> events = new ArrayList<>();
    public Map<String, Object> msg;
    public Agg agg;
    public final AbstractTableModel eventModel;
    public final FaultTiles tiles;
    public static final String[] EVENT_COLS = { "Time (s)", "Fault", "Change" };

    public FaultsPanel(TrafficWindow w, String who, long id) {
        super(new BorderLayout());
        this.w = w;
        this.who = who;
        this.id = id;
        tiles = new FaultTiles(this);
        eventModel = new AbstractTableModel() {
            public int getRowCount() {
                return events.size();
            }

            public int getColumnCount() {
                return EVENT_COLS.length;
            }

            public String getColumnName(int c) {
                return EVENT_COLS[c];
            }

            public Object getValueAt(int r, int c) {
                FaultEvent e = events.get(events.size() - 1 - r); // newest first
                return switch (c) {
                    case 0 -> String.format(Locale.ROOT, "%.3f", Double.isNaN(w.t0) ? e.t() : e.t() - w.t0);
                    case 1 -> e.fault().label + (e.fault().critical ? "  (critical)" : "");
                    default -> e.on() ? "Active" : "Cleared";
                };
            }
        };
        JTable et = new JTable(eventModel);
        styleTable(et);
        et.setDefaultRenderer(
            Object.class,
            new StyledRenderer((t, row, col, sel, comp) -> {
                if (row >= events.size()) return;
                FaultEvent e = events.get(events.size() - 1 - row);
                if (col == 0) comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
                if (col == 2 && !sel) comp.setForeground(
                    e.on() ? (e.fault().critical ? T.red() : T.orange()) : T.green()
                );
            })
        );
        et.getColumnModel().getColumn(0).setPreferredWidth(90);
        et.getColumnModel().getColumn(1).setPreferredWidth(260);
        et.getColumnModel().getColumn(2).setPreferredWidth(80);
        JScrollPane es = new JScrollPane(et);
        es.setBorder(new Line(0, 1, 0, 0));
        es.setPreferredSize(new Dimension(440, 200));
        JScrollPane ts = new JScrollPane(tiles);
        ts.setBorder(null);
        add(ts, BorderLayout.CENTER);
        add(es, BorderLayout.EAST);
    }

    public void reset() {
        faults.clear();
        events.clear();
        msg = null;
        agg = null;
        eventModel.fireTableDataChanged();
        tiles.revalidate();
        tiles.repaint();
    }

    /** A new frame of the faults message, already decoded into a: note which faults came on or cleared. */
    public void offer(Agg a, double t) {
        if (a.msg == null || a.cur == null) return;
        agg = a;
        if (a.msg != msg) {
            faults.clear();
            events.clear();
            msg = a.msg;
            List<Object> fields = listOrEmpty(msg.get("fields"));
            for (int i = 0; i < a.meta.size(); i++) {
                PointMeta pm = a.meta.get(i);
                if (!pm.parsed()) continue;
                String topic = "",
                    doc = "";
                for (Object o : fields)
                    if (
                        asMap(o) instanceof Map<String, Object> fm && containsIdx(listOrEmpty(fm.get("values")), i + 1)
                    ) {
                        topic = strOr(fm, "name", "");
                        doc = strOr(fm, "doc", "");
                    }
                String lt = topic.toLowerCase(Locale.ROOT);
                faults.add(
                    new FaultInfo(i, pm.name(), topic, doc, lt.contains("/critical/") && !lt.contains("non-critical"))
                );
            }
            faults.sort(Comparator.comparing((FaultInfo f) -> !f.critical));
            eventModel.fireTableDataChanged();
            tiles.revalidate();
        }
        int before = events.size();
        for (FaultInfo f : faults) {
            double v = f.index < a.cur.length ? a.cur[f.index] : Double.NaN;
            boolean on = !Double.isNaN(v) && v != 0;
            if (on == f.active) continue;
            f.active = on;
            if (on) {
                f.since = t;
                f.count++;
            } else f.lastEnd = t;
            events.add(new FaultEvent(t, f, on));
        }
        if (events.size() > 10_000) {
            events.subList(0, 5_000).clear();
            eventModel.fireTableDataChanged();
        } else if (events.size() > before) eventModel.fireTableRowsInserted(0, events.size() - before - 1);
    }

    /** The fault tiles: a summary line, then a section per kind (Critical, Non-critical). */
    public static final class FaultTiles extends JComponent implements Scrollable {

        public final TrafficWindow w;

        public static final int PAD = 16,
            GAP = 10,
            MIN_W = 250,
            TILE_H = 60,
            HEAD_H = 58,
            SECTION_H = 30;

        public final FaultsPanel p;

        public FaultTiles(FaultsPanel p) {
            this.p = p;
            this.w = p.w;
            setToolTipText("");
        }

        public int cols() {
            return Math.max(1, (getWidth() - 2 * PAD + GAP) / (MIN_W + GAP));
        }

        /** Tile rectangles in drawing order, with section headers at the start of each kind. */
        public List<Object[]> layoutTiles(int width) {
            List<Object[]> out = new ArrayList<>();
            int c = Math.max(1, (width - 2 * PAD + GAP) / (MIN_W + GAP)),
                w = (width - 2 * PAD - (c - 1) * GAP) / c,
                y = PAD + HEAD_H;
            List<FaultInfo> fs = p.faults;
            for (int pass = 0; pass < 2; pass++) {
                boolean crit = pass == 0;
                List<FaultInfo> part = new ArrayList<>();
                for (FaultInfo f : fs) if (f.critical == crit) part.add(f);
                if (part.isEmpty()) continue;
                out.add(new Object[] {
                    crit ? "Critical" : "Non-critical",
                    new Rectangle(PAD, y, width - 2 * PAD, SECTION_H),
                });
                y += SECTION_H;
                for (int i = 0; i < part.size(); i++) {
                    out.add(new Object[] {
                        part.get(i),
                        new Rectangle(PAD + (i % c) * (w + GAP), y + (i / c) * (TILE_H + GAP), w, TILE_H),
                    });
                }
                y += ((part.size() + c - 1) / c) * (TILE_H + GAP) + 6;
            }
            out.add(new Object[] { "end", new Rectangle(0, y, 0, 0) });
            return out;
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getParent() instanceof JViewport v ? Math.max(v.getWidth(), MIN_W + 2 * PAD) : 900;
            List<Object[]> l = layoutTiles(w);
            return new Dimension(MIN_W + 2 * PAD, ((Rectangle) l.getLast()[1]).y + PAD);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            for (Object[] o : layoutTiles(getWidth()))
                if (o[0] instanceof FaultInfo f && ((Rectangle) o[1]).contains(e.getPoint())) return (
                    "<html><b>" +
                    escHtml(f.label) +
                    "</b>" +
                    (f.doc.isBlank() || f.doc.equalsIgnoreCase(f.label) ? "" : "<br>" + escHtml(f.doc)) +
                    "<br><tt>" +
                    escHtml(f.name) +
                    "</tt><br>" +
                    escHtml(f.topic) +
                    "</html>"
                );
            return null;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(T.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
            Font f = Theme.UI,
                bold = f.deriveFont(Font.BOLD),
                small = f.deriveFont(f.getSize2D() - 1.5f),
                big = f.deriveFont(Font.BOLD, f.getSize2D() + 6);
            Agg a = p.agg;
            double now = w.now();
            if (a == null || p.faults.isEmpty()) {
                g.setFont(big);
                g.setColor(T.muted());
                g.drawString("No " + p.who + " fault frames (" + hex(p.id) + ") yet", PAD, PAD + 26);
                g.setFont(f);
                g.drawString(
                    "They appear here once the " +
                        p.who +
                        "'s " +
                        (p.msg == null ? "fault" : strOr(p.msg, "desc", "fault")) +
                        " message arrives.",
                    PAD,
                    PAD + 48
                );
                g.dispose();
                return;
            }
            int active = 0,
                crit = 0;
            for (FaultInfo fi : p.faults)
                if (fi.active) {
                    active++;
                    if (fi.critical) crit++;
                }
            boolean stale = w.stale(a);
            g.setFont(big);
            g.setColor(stale ? T.muted() : crit > 0 ? T.red() : active > 0 ? T.orange() : T.green());
            g.drawString(
                active == 0
                    ? "No active faults"
                    : active +
                          (active == 1 ? " active fault" : " active faults") +
                          (crit > 0 ? " (" + crit + " critical)" : ""),
                PAD,
                PAD + 26
            );
            g.setFont(f);
            g.setColor(stale ? T.yellow() : T.muted());
            g.drawString(
                (stale ? "Stale: " : "") + "last " + hex(p.id) + " frame " + w.lastSeenText(a).replace("Stale · ", ""),
                PAD,
                PAD + 48
            );
            for (Object[] o : layoutTiles(getWidth())) {
                Rectangle r = (Rectangle) o[1];
                if (o[0] instanceof String s) {
                    if (s.equals("end")) continue;
                    g.setFont(bold);
                    g.setColor(T.muted());
                    g.drawString(s, r.x, r.y + 20);
                    continue;
                }
                FaultInfo fi = (FaultInfo) o[0];
                Color kc = fi.critical ? T.red() : T.orange();
                g.setColor(fi.active ? T.tint(kc) : T.alt());
                g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
                g.setColor(fi.active ? kc : T.hairline());
                g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 12, 12);
                int x = r.x + 12;
                g.setFont(small.deriveFont(Font.BOLD));
                FontMetrics sm = g.getFontMetrics();
                String chip = fi.active ? "ACTIVE" : "CLEAR";
                Color cc = fi.active ? kc : T.muted();
                int cw = sm.stringWidth(chip) + 14,
                    cx = r.x + r.width - cw - 10;
                g.setColor(blend(cc, T.alt(), 0.22));
                g.fillRoundRect(cx, r.y + 9, cw, sm.getHeight() + 4, 10, 10);
                g.setColor(cc);
                g.drawString(chip, cx + 7, r.y + 11 + sm.getAscent());
                g.setFont(bold);
                g.setColor(fi.active ? T.bright() : T.fg());
                g.drawString(ellipsize(fi.label, g.getFontMetrics(), cx - x - 8), x, r.y + 24);
                g.setFont(small);
                g.setColor(T.muted());
                String times = fi.count + (fi.count == 1 ? " time" : " times");
                String sub = fi.active
                    ? "active for " + ago(now - fi.since) + " · " + times
                    : fi.count > 0
                        ? "last active " + ago(now - fi.lastEnd) + " ago · " + times
                        : "not active in this log";
                g.drawString(ellipsize(sub, g.getFontMetrics(), r.width - 24), x, r.y + 45);
            }
            g.dispose();
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
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
        }
    }
}
