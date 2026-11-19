package tiresias.traffic;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.traffic.dashboards.EfuseBoard.*;
import static tiresias.traffic.dashboards.FaultsPanel.*;
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

public final class TrafficWindow extends JFrame {

    public final App app;
    public final List<CanFrame> frames = new ArrayList<>();
    public final List<Integer> view = new ArrayList<>();
    public final Map<String, Agg> aggs = new LinkedHashMap<>();
    public final List<Agg> aggView = new ArrayList<>();
    public final Map<String, String> searchText = new HashMap<>();
    public Map<String, SpecRef> index;
    public double t0 = Double.NaN;
    public int skippedLines;

    public final HintField filter = new HintField("Filter by ID, message, topic or bus…", 24);
    public final JCheckBox perId = new JCheckBox("One row per ID", true);
    public final JCheckBox problemsOnly = new JCheckBox("Problems only");
    public final JCheckBox follow = new JCheckBox("Follow new frames", true);
    public final JButton liveBtn;
    public final JTextArea detail = new JTextArea();
    /** The frame key whose details are showing, so a refresh of the same ID keeps the scroll position. */
    public Object detailKey;
    public final ToneLabel stat = new ToneLabel(" ");
    public final AbstractTableModel model;
    public final JTable table;
    public final java.util.concurrent.ConcurrentLinkedQueue<CanFrame> pending =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    public final javax.swing.Timer pump, specTimer;
    public final ShutdownPanel loop = new ShutdownPanel();
    /** The loop panel scrolls in its tab, so the diagram keeps its full height in a short detail area. */
    public final JScrollPane loopScroll = new JScrollPane(loop);
    public final JTabbedPane detailTabs = new JTabbedPane();
    /** The Shutdown loop tab: the panel, or a note until 0x123 frames arrive. */
    public final CardLayout loopCards = new CardLayout();
    public final JPanel loopPage = new JPanel(loopCards);
    public final FaultsPanel faultsPanel = new FaultsPanel(this, "VCU", VCU_FAULTS_ID),
        bmsFaultsPanel = new FaultsPanel(this, "BMS", BMS_FAULTS_ID);
    public final List<FaultsPanel> faultPanels = List.of(faultsPanel, bmsFaultsPanel);
    public final List<Object> decView = new ArrayList<>();
    public final AbstractTableModel decModel;
    public final JTable decTable;
    public final JTabbedPane mainTabs = new JTabbedPane();
    public final JScrollPane detailScroll;
    public final JComponent pointsPane;
    public final AbstractTableModel pointsModel;
    public final JTable pointsTable;
    public final PointGraph graph = new PointGraph();
    /** What the Points tab shows: the selected frame's values, and which of the message's points are rows. */
    public Agg pAgg;
    public double[] pVals;
    public long[] pRaw;
    public double pMarkT = Double.NaN;
    public final List<Integer> pRows = new ArrayList<>();
    public int graphPoint = -1;
    /** The graph starts hidden and isn't drawn until Show graph is clicked. */
    public boolean graphShown;
    public JPanel graphControls;
    public Map<String, Object> pMsg;
    /** Clock for staleness: the newest frame time, advanced by the wall clock while listening live. */
    public final long wallBase = System.nanoTime();
    public double latest = Double.NaN;
    public long latestWall;
    public static final String[] DEC_COLS = { "ID", "Message", "Rate (Hz)", "Last seen", "Values" };
    public static final String[] POINT_COLS = { "#", "Point", "Value", "Unit", "Raw", "Min", "Max" };
    public static final String[] GRAPH_WINDOWS = { "5 s", "10 s", "30 s", "1 min", "5 min", "All" };
    public static final Pattern WINDOW_LEN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|secs|seconds?|m|min|mins|minutes?|h|hours?)?",
        Pattern.CASE_INSENSITIVE
    );

    /** A graph window length in seconds: NaN for "All", -1 when the text isn't a length. */
    public static double parseWindow(String v) {
        v = v.strip();
        if (v.isEmpty() || v.equalsIgnoreCase("all")) return Double.NaN;
        Matcher m = WINDOW_LEN.matcher(v);
        if (!m.matches()) return -1;
        double n = Double.parseDouble(m.group(1));
        String u = m.group(2) == null ? "s" : m.group(2).toLowerCase(Locale.ROOT);
        double sec = u.equals("ms") ? n / 1000 : u.startsWith("m") ? n * 60 : u.startsWith("h") ? n * 3600 : n;
        return sec > 0 ? sec : -1;
    }

    public volatile Process proc;

    /** How to start the capture again: the command and environment, for reconnecting after a drop. */
    public record LiveSpec(List<String> cmd, Map<String, String> env, String where, String iface) {}

    /** A live session lasts from Listen live until Stop listening, across automatic reconnects. */
    public volatile boolean liveSession;
    public LiveSpec liveSpec;
    public int reconnectAttempt;
    public long lostAtNs;
    public javax.swing.Timer reconnectTimer;
    public final java.util.concurrent.atomic.AtomicLong sessionFrames = new java.util.concurrent.atomic.AtomicLong();
    public static final int[] RECONNECT_DELAYS_S = { 2, 4, 8, 10 };
    /** Recording: every frame of a live session written to a log file as it arrives (by the reader thread). */
    public final Object recLock = new Object();
    public java.io.BufferedWriter recorder;
    public Path recordPath;
    public long recorded, recFlushedNs;
    /** Pause: frames keep arriving into the queue; the views and the stale clock stop until Resume. */
    public boolean paused;
    public long pausedAtNs, droppedWhilePaused;
    public JButton pauseBtn;
    public final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    public static final int PAUSE_BUFFER = 600_000;
    /** True once the oldest frames were dropped to stay within memory (Save log then saves the newest ones). */
    public boolean droppedOld;
    public final EfuseBoard efuses = new EfuseBoard(this);
    /** Decoded-view groups, one per spec file (node); kept so the rows stay the same objects between refreshes. */
    public final Map<String, Group> groups = new HashMap<>();
    public final Set<String> collapsed = new HashSet<>();

    public static final class Group {

        public final String name;
        public int ids, stale;

        public Group(String name) {
            this.name = name;
        }
    }

    public String stopNote;
    public long noteUntilNs;

    /** How many recent frames the rate is averaged over, and when a message counts as stale. */
    public static final int RATE_WINDOW = 10,
        RATE_MIN_FRAMES = 3,
        HISTORY_CAP = 100_000;
    public static final double STALE_PERIODS = 3,
        STALE_MIN_S = 1.0,
        GLOW_S = 1.5;

    public static final class Agg {

        public CanFrame last;
        public int count, level;
        public double first = Double.NaN,
            lastTs = Double.NaN;
        public String text = "OK";
        /** The spec file (node) the ID belongs to, for grouping the decoded view; null when it's not in the spec. */
        public String group;
        /** Arrival times of the last few frames (a ring), for the rate and for staleness. */
        public final double[] recent = new double[RATE_WINDOW];
        public int recentN, recentHead;
        public double lastSeen = Double.NaN;
        /** Decoded values: the spec message they were read with, its points, and what's been seen. */
        public Map<String, Object> msg;
        public List<PointMeta> meta = List.of();
        public double[] cur, prev, min, max;
        public long[] changedAt;
        /** One entry per frame: time, then every point's value. */
        public final List<double[]> history = new ArrayList<>();

        public void addTime(double t) {
            recent[recentHead] = t;
            recentHead = (recentHead + 1) % RATE_WINDOW;
            if (recentN < RATE_WINDOW) recentN++;
            lastSeen = t;
        }

        /** Frames per second from the average gap between the last 3 to 10 frames; NaN with fewer. */
        public double rate() {
            if (recentN < RATE_MIN_FRAMES) return Double.NaN;
            double newest = recent[(recentHead - 1 + RATE_WINDOW) % RATE_WINDOW],
                oldest = recent[(recentHead - recentN + RATE_WINDOW) % RATE_WINDOW],
                span = newest - oldest;
            return span > 0 ? (recentN - 1) / span : Double.NaN;
        }

        public void resetDecoded() {
            msg = null;
            meta = List.of();
            cur = prev = min = max = null;
            changedAt = null;
            history.clear();
        }

        /** True when point i differs from the previous frame of this ID. */
        public boolean changed(int i) {
            return (
                prev != null && cur != null && i < cur.length && i < prev.length && Double.compare(cur[i], prev[i]) != 0
            );
        }
    }

    public static final String[] FRAME_COLS = {
        "#",
        "Time (s)",
        "Bus",
        "ID",
        "Message",
        "Len",
        "Data",
        "Decoded",
        "Status",
    };
    public static final String[] AGG_COLS = {
        "ID",
        "Message",
        "Frames",
        "Rate (Hz)",
        "Len",
        "Last data",
        "Last decoded",
        "Status",
    };

    public TrafficWindow(App app) {
        super("CAN traffic");
        this.app = app;
        Icons.install(this);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        index = specIndex(app.files);

        model = new AbstractTableModel() {
            public int getRowCount() {
                return perId.isSelected() ? aggView.size() : view.size();
            }

            public int getColumnCount() {
                return perId.isSelected() ? AGG_COLS.length : FRAME_COLS.length;
            }

            public String getColumnName(int c) {
                return perId.isSelected() ? AGG_COLS[c] : FRAME_COLS[c];
            }

            public Object getValueAt(int r, int c) {
                return perId.isSelected() ? aggValue(aggView.get(r), c) : frameValue(view.get(r), c);
            }
        };
        table = new JTable(model);
        styleTable(table);
        table.setDefaultRenderer(
            Object.class,
            new StyledRenderer((t, row, col, sel, comp) -> {
                int level = rowLevel(row);
                boolean statusCol = col == model.getColumnCount() - 1;
                if (statusCol && !sel) comp.setForeground(level == 2 ? T.red() : level == 1 ? T.yellow() : T.muted());
                if (level == 2 && !sel && !statusCol) comp.setBackground(T.tint(T.red()));
                String name = model.getColumnName(col);
                if (name.equals("ID") || name.contains("data") || name.equals("Data")) comp.setFont(
                    mono(Font.PLAIN, t.getFont().getSize())
                );
                if (statusCol || name.contains("ecoded")) comp.setToolTipText(
                    String.valueOf(model.getValueAt(row, col))
                );
            })
        );
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });
        table.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) openInEditor();
                }
            }
        );
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        decModel = new AbstractTableModel() {
            public int getRowCount() {
                return decView.size();
            }

            public int getColumnCount() {
                return DEC_COLS.length;
            }

            public String getColumnName(int c) {
                return DEC_COLS[c];
            }

            public Object getValueAt(int r, int c) {
                return decView.get(r) instanceof Group g ? groupValue(g, c) : decValue((Agg) decView.get(r), c);
            }
        };
        decTable = new JTable(decModel);
        styleTable(decTable);
        decTable.setDefaultRenderer(
            Object.class,
            new StyledRenderer((t, row, col, sel, comp) -> {
                Object o = row < decView.size() ? decView.get(row) : null;
                if (o instanceof Group g) {
                    comp.setFont(t.getFont().deriveFont(Font.BOLD));
                    if (!sel) comp.setBackground(blend(T.fg(), T.panel(), 0.07));
                    if (!sel && col == 1) comp.setForeground(T.muted());
                    if (!sel && col == 3 && g.stale > 0) comp.setForeground(T.yellow());
                    comp.setToolTipText(
                        collapsed.contains(g.name) ? "Click to show this node's IDs" : "Click to hide this node's IDs"
                    );
                    return;
                }
                if (!(o instanceof Agg a)) return;
                boolean stale = stale(a);
                if (a.level == 2 && !sel) comp.setBackground(T.tint(T.red()));
                if (col == 0) comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
                if (stale && !sel) comp.setForeground(col == 3 ? T.yellow() : T.muted());
                if (col == 3) comp.setToolTipText(
                    stale
                        ? "No frame for " +
                              fmtD(Math.round(age(a) * 10) / 10.0) +
                              " s, though it was arriving every " +
                              fmtD(Math.round(1000 / a.rate())) +
                              " ms"
                        : "Time since the last frame of this ID, counted to the newest frame in the log (or now, while listening live)"
                );
            })
        );
        decTable.getColumnModel().getColumn(4).setCellRenderer(new ValuesCell());
        int[] decW = { 130, 190, 80, 130, 600 };
        for (int i = 0; i < decW.length; i++) decTable.getColumnModel().getColumn(i).setPreferredWidth(decW[i]);
        decTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        decTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });
        for (JTable t : List.of(table, decTable))
            t.getActionMap().put(
                "copy",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        copyFrame();
                    }
                }
            );
        decTable.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int r = decTable.rowAtPoint(e.getPoint());
                    if (r >= 0 && r < decView.size() && decView.get(r) instanceof Group g) {
                        if (!collapsed.remove(g.name)) collapsed.add(g.name);
                        rebuildAggView();
                        return;
                    }
                    if (e.getClickCount() == 2) openInEditor();
                }
            }
        );

        pointsModel = new AbstractTableModel() {
            public int getRowCount() {
                return pRows.size();
            }

            public int getColumnCount() {
                return POINT_COLS.length;
            }

            public String getColumnName(int c) {
                return POINT_COLS[c];
            }

            public Object getValueAt(int r, int c) {
                int i = pRows.get(r);
                PointMeta pm = pAgg.meta.get(i);
                double v = pVals != null && i < pVals.length ? pVals[i] : Double.NaN;
                return switch (c) {
                    case 0 -> i + 1;
                    case 1 -> pm.name();
                    case 2 -> pm.size() == 1 ? (v != 0 ? "● 1" : "○ 0") : fmtD(v);
                    case 3 -> pm.unit();
                    case 4 -> pRaw == null ? "" : "0x" + Long.toHexString(pRaw[i]).toUpperCase(Locale.ROOT);
                    case 5 -> pAgg.min == null || Double.isInfinite(pAgg.min[i]) ? "" : fmtD(pAgg.min[i]);
                    default -> pAgg.max == null || Double.isInfinite(pAgg.max[i]) ? "" : fmtD(pAgg.max[i]);
                };
            }
        };
        pointsTable = new JTable(pointsModel);
        styleTable(pointsTable);
        pointsTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pointsTable.setDefaultRenderer(
            Object.class,
            new StyledRenderer((t, row, col, sel, comp) -> {
                if (row >= pRows.size() || pAgg == null) return;
                int i = pRows.get(row);
                PointMeta pm = pAgg.meta.get(i);
                if (col == 4) comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
                if (col == 2) {
                    comp.setFont(t.getFont().deriveFont(Font.BOLD));
                    double v = pVals != null && i < pVals.length ? pVals[i] : Double.NaN;
                    if (!sel) comp.setForeground(
                        pm.size() == 1
                            ? (v != 0 ? T.green() : T.muted())
                            : Double.isNaN(pMarkT) && pAgg.changed(i)
                                ? T.accent()
                                : T.bright()
                    );
                }
                if (col == 5 || col == 6) comp.setForeground(sel ? T.bright() : T.muted());
            })
        );
        int[] pw = { 36, 170, 90, 60, 80, 70, 70 };
        pointsTable.getColumnModel().getColumn(0).setMaxWidth(56);
        for (int i = 0; i < pw.length; i++) pointsTable.getColumnModel().getColumn(i).setPreferredWidth(pw[i]);
        pointsTable.getSelectionModel().addListSelectionListener(e -> {
            int r = pointsTable.getSelectedRow();
            if (r >= 0 && r < pRows.size()) graphPoint = pRows.get(r); // remembered at once, so a refresh keeps it
            if (!e.getValueIsAdjusting()) updateGraph();
        });
        JScrollPane pointsScroll = new JScrollPane(pointsTable);
        pointsScroll.setBorder(null);
        graph.end = this::now;
        JComboBox<String> windowBox = new JComboBox<>(GRAPH_WINDOWS);
        windowBox.setEditable(true);
        windowBox.setToolTipText(
            "How much of the graph to show, ending at the newest frame (or now, while listening live). " +
                "Type any length, such as 20 s or 2 min."
        );
        windowBox.setSelectedItem(app.prefs.get("graphWindow", "30 s"));
        graph.window = parseWindow(String.valueOf(windowBox.getSelectedItem()));
        if (graph.window == -1) graph.window = 30;
        windowBox.addActionListener(e -> {
            String v = String.valueOf(windowBox.getSelectedItem()).strip();
            double w = parseWindow(v);
            if (w == -1) {
                stat.set("\"" + v + "\" isn't a time length. Use something like 10 s, 2 min, or All.", ToneLabel.WARN);
                return;
            }
            graph.window = w;
            app.prefs.put("graphWindow", v);
            graph.repaint();
        });
        JLabel windowLabel = new JLabel("Show the last");
        windowLabel.setVisible(false);
        windowBox.setVisible(false);
        JSplitPane pointsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        pointsSplit.setResizeWeight(0.45);
        pointsSplit.setBorder(null);
        pointsSplit.setRightComponent(graph);
        JPanel pointsBox = new JPanel(new BorderLayout());
        JButton graphBtn = button("Show graph", "Graph the selected point over time", () -> {});
        graphBtn.addActionListener(e -> {
            graphShown = !graphShown;
            if (graphShown) {
                pointsBox.remove(pointsScroll);
                pointsSplit.setLeftComponent(pointsScroll);
                pointsBox.add(pointsSplit, BorderLayout.CENTER);
                updateGraph();
            } else {
                pointsBox.remove(pointsSplit);
                pointsBox.add(pointsScroll, BorderLayout.CENTER);
            }
            graphBtn.setText(graphShown ? "Hide graph" : "Show graph");
            windowLabel.setVisible(graphShown);
            windowBox.setVisible(graphShown);
            pointsBox.revalidate();
            pointsBox.repaint();
        });
        // shown at the right end of the Details / Points tab row, while the Points tab is open
        graphControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        graphControls.setOpaque(false);
        graphControls.add(windowLabel);
        graphControls.add(windowBox);
        graphControls.add(graphBtn);
        graphControls.setVisible(false);
        pointsBox.add(pointsScroll, BorderLayout.CENTER);
        pointsPane = pointsBox;

        detail.setEditable(false);
        detail.setFont(mono(Font.PLAIN, detail.getFont().getSize()));
        // Live refreshes rewrite the text; leave the scroll position where the user put it.
        if (detail.getCaret() instanceof javax.swing.text.DefaultCaret dc) dc.setUpdatePolicy(
            javax.swing.text.DefaultCaret.NEVER_UPDATE
        );

        Bar top = new Bar();
        top.add(button("Open log…", "Open candump output or a candump -L log", this::openLog));
        top.add(button("Paste…", "Paste candump or cansend lines", this::paste));
        top.add(button("Save log…", "Save the frames received so far as a candump -L log", this::saveLog));
        top.sep();
        liveBtn = button(
            "Listen live…",
            "Run candump on the car's computer over SSH (or on this computer) and decode as frames arrive",
            this::toggleLive
        );
        top.add(liveBtn);
        pauseBtn = button(
            "Pause",
            "Freeze the display to read it; frames keep arriving in the background and show up on Resume",
            this::togglePause
        );
        top.add(pauseBtn);
        top.sep();
        top.add(button("Clear", null, this::clear));
        for (JCheckBox b : new JCheckBox[] { perId, problemsOnly, follow }) {
            b.setOpaque(false);
            top.add(b);
        }
        perId.addActionListener(e -> {
            model.fireTableStructureChanged();
            rebuildView();
        });
        problemsOnly.addActionListener(e -> rebuildView());
        javax.swing.Timer filterTimer = new javax.swing.Timer(200, e -> rebuildView());
        filterTimer.setRepeats(false);
        filter.getDocument().addDocumentListener(MessageEditor.docListener(filterTimer::restart));

        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        JPanel fr = new JPanel(new BorderLayout());
        fr.setOpaque(false);
        fr.setBorder(new EmptyBorder(0, 10, 6, 10));
        fr.add(filter, BorderLayout.CENTER);
        north.add(fr, BorderLayout.SOUTH);

        JPanel detailPane = new JPanel(new BorderLayout());
        Bar db = new Bar();
        db.add(button("Open message in editor", "Double-clicking a row does the same", this::openInEditor));
        db.add(
            button(
                "Copy frame",
                "Copy the selected frame's decoded values, then its candump line (the latest frame for a row per ID); Cmd/Ctrl+C does the same",
                this::copyFrame
            )
        );
        detailPane.add(db, BorderLayout.NORTH);
        detailScroll = new JScrollPane(detail);
        loopScroll.setBorder(null);
        detailTabs.addTab("Details", detailScroll);
        detailTabs.addTab("Points", pointsPane);
        detailTabs.setToolTipTextAt(1, "Every point of the selected frame, with the range seen so far and a graph");
        detailTabs.addChangeListener(e -> graphControls.setVisible(detailTabs.getSelectedComponent() == pointsPane));
        detailPane.add(new TabsWithTrailing(detailTabs, graphControls), BorderLayout.CENTER);
        mainTabs.addTab("Raw frames", new JScrollPane(table));
        mainTabs.addTab("Decoded", new JScrollPane(decTable));
        JScrollPane efuseScroll = new JScrollPane(efuses);
        efuseScroll.setBorder(null);
        mainTabs.addTab("eFuses", efuseScroll);
        mainTabs.setToolTipTextAt(2, "The VCU's eFuses laid out like the board: current, voltage and state");
        mainTabs.setToolTipTextAt(0, "Frames as they came in, with their data bytes");
        mainTabs.setToolTipTextAt(1, "One row per ID with every point's latest value; changes are highlighted");
        JLabel noLoop = new JLabel(
            "<html><div style='text-align:center'><b>No shutdown pins frames (" +
                hex(ShutdownLoop.MESSAGE_ID) +
                ") yet</b><br><br>The loop appears here once the VCU's Shutdown Pins message arrives.</div></html>",
            SwingConstants.CENTER
        );
        noLoop.setForeground(T.muted());
        loopPage.add(noLoop, "empty");
        loopPage.add(loopScroll, "loop");
        mainTabs.addTab("Shutdown loop", loopPage);
        mainTabs.setToolTipTextAt(
            3,
            "Where the shutdown loop is open, from the VCU's " + hex(ShutdownLoop.MESSAGE_ID) + " frames"
        );
        for (FaultsPanel fp : faultPanels) {
            mainTabs.addTab(fp.who + " faults", fp);
            mainTabs.setToolTipTextAt(
                mainTabs.getTabCount() - 1,
                "Which of the " + fp.who + "'s faults (" + hex(fp.id) + ") are active, and every change"
            );
        }
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainTabs, detailPane);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        // the eFuses, Shutdown loop and fault pages are dashboards: they get the whole height, no detail pane
        int[] lastDivider = { -1 };
        mainTabs.addChangeListener(e -> {
            boolean dash = dashboardActive();
            if (dash && detailPane.isVisible()) {
                lastDivider[0] = split.getDividerLocation();
                detailPane.setVisible(false);
            } else if (!dash && !detailPane.isVisible()) {
                detailPane.setVisible(true);
                if (lastDivider[0] > 0) split.setDividerLocation(lastDivider[0]);
                else split.resetToPreferredSizes();
            }
            if (!dash) {
                detailTabs.setSelectedComponent(decodedActive() ? pointsPane : detailScroll);
                showDetail();
            }
        });

        JPanel statusBar = new Surface(new BorderLayout(), 0);
        statusBar.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
        statusBar.add(stat, BorderLayout.CENTER);

        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        pump = new javax.swing.Timer(150, e -> tick());
        pump.start();
        specTimer = new javax.swing.Timer(400, e -> applySpecChange());
        specTimer.setRepeats(false);
        addWindowListener(
            new WindowAdapter() {
                public void windowClosed(WindowEvent e) {
                    stopLive();
                    pump.stop();
                    app.traffic = null;
                }
            }
        );
        setTransferHandler(fileDrop(ps -> ps.forEach(this::readFile)));
        stat.set(
            "Open a candump log, paste lines, or listen live over SSH. You can also drop log files here.",
            ToneLabel.MUTED
        );
        setSize(1200, 760);
        setLocationRelativeTo(app);
    }

    // ---- data in

    public void openLog() {
        JFileChooser fc = new JFileChooser(app.prefs.get("lastLogDir", System.getProperty("user.home")));
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Open candump logs");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        app.prefs.put("lastLogDir", fc.getCurrentDirectory().getAbsolutePath());
        for (File f : fc.getSelectedFiles()) readFile(f.toPath());
    }

    public void readFile(Path p) {
        stat.set("Reading " + p.getFileName() + "…", ToneLabel.MUTED);
        Thread t = new Thread(() -> {
            int n = 0,
                skipped = 0;
            try (java.io.BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    CanFrame f = parseCandumpLine(line);
                    if (f == null) {
                        if (!line.isBlank()) skipped++;
                        continue;
                    }
                    enqueue(f);
                    n++;
                }
                final int fn = n,
                    fs = skipped;
                SwingUtilities.invokeLater(() -> {
                    skippedLines += fs;
                    stat.set(
                        "Read " +
                            fn +
                            " frames from " +
                            p.getFileName() +
                            (fs > 0 ? " (" + fs + " lines weren't CAN frames)" : ""),
                        ToneLabel.MUTED
                    );
                });
            } catch (IOException | RuntimeException ex) {
                SwingUtilities.invokeLater(() ->
                    stat.set("Couldn't read " + p.getFileName() + ": " + ex.getMessage(), ToneLabel.ERR)
                );
            }
        }, "candump-reader");
        t.setDaemon(true);
        t.start();
    }

    public void paste() {
        JTextArea ta = new JTextArea(18, 80);
        ta.setFont(mono(Font.PLAIN, ta.getFont().getSize()));
        String clip = "";
        try {
            clip = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (Exception ignored) {}
        if (clip != null && (clip.contains("#") || clip.contains("["))) ta.setText(clip);
        int r = JOptionPane.showConfirmDialog(
            this,
            new JScrollPane(ta),
            "Paste candump or cansend lines",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        if (r != JOptionPane.OK_OPTION) return;
        int n = 0,
            skipped = 0;
        for (String line : ta.getText().split("\\r?\\n")) {
            String l = line.strip();
            if (l.startsWith("cansend ")) l = l.replaceFirst("^cansend\\s+", "");
            CanFrame f = parseCandumpLine(l);
            if (f == null) {
                if (!l.isEmpty()) skipped++;
                continue;
            }
            enqueue(f);
            n++;
        }
        stat.set(
            "Added " + n + " frames" + (skipped > 0 ? " (" + skipped + " lines weren't CAN frames)" : ""),
            skipped > 0 ? ToneLabel.WARN : ToneLabel.MUTED
        );
    }

    // ---- live capture: candump on the car's computer over SSH (or on this computer)

    public static String sessionPassword; // kept in memory only, never saved

    // The car's network and computer. Change these if the car's setup changes.
    public static final String CAR_WIFI = "Hermesbay";
    public static final String CAR_HOST = "192.168.100.12";
    public static final String CAR_USER = "root";
    public static final List<String> WRONG_WIFI = List.of("NuWave", "eduroam");

    /** Name of the Wi-Fi network this computer is on, or null if it can't be told (wired, no Wi-Fi, or hidden by the OS). */
    public static String currentWifi() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String[]> cmds = new ArrayList<>();
        if (os.contains("mac")) {
            cmds.add(new String[] { "networksetup", "-getairportnetwork", "en0" });
            cmds.add(new String[] { "networksetup", "-getairportnetwork", "en1" });
            cmds.add(new String[] { "ipconfig", "getsummary", "en0" });
        } else if (os.contains("windows")) {
            cmds.add(new String[] { "netsh", "wlan", "show", "interfaces" });
        } else {
            cmds.add(new String[] { "nmcli", "-t", "-f", "active,ssid", "dev", "wifi" });
            cmds.add(new String[] { "iwgetid", "-r" });
        }
        for (String[] cmd : cmds) {
            String out = runQuick(cmd);
            if (out == null) continue;
            String ssid = parseSsid(cmd[0], out);
            if (ssid != null && !ssid.isEmpty() && !ssid.contains("<redacted>")) return ssid;
        }
        return null;
    }

    public static final Pattern SSID_LINE = Pattern.compile("^SSID\\s*:\\s*(.+)$");

    public static String parseSsid(String tool, String out) {
        for (String line : out.split("\r?\n")) {
            String l = line.strip();
            String found = switch (tool) {
                case "networksetup" -> l.startsWith("Current Wi-Fi Network:")
                    ? l.substring(l.indexOf(':') + 1).strip()
                    : null;
                case "ipconfig", "netsh" -> {
                    Matcher m = SSID_LINE.matcher(l);
                    yield m.matches() ? m.group(1).strip() : null;
                }
                case "nmcli" -> l.startsWith("yes:") ? l.substring(4).replace("\\:", ":") : null;
                default -> l.isEmpty() ? null : l;
            };
            if (found != null) return found;
        }
        return null;
    }

    public static String runQuick(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean wrongWifi(String ssid) {
        if (ssid == null) return false;
        for (String w : WRONG_WIFI) if (w.equalsIgnoreCase(ssid.strip())) return true;
        return false;
    }

    public void toggleLive() {
        if (liveSession) {
            stopLive();
            return;
        }
        Preferences pr = app.prefs;
        JRadioButton viaSsh = new JRadioButton("On the car's computer, over SSH", !pr.getBoolean("liveLocal", false));
        JRadioButton local = new JRadioButton(
            "On this computer (Linux with a CAN adapter)",
            pr.getBoolean("liveLocal", false)
        );
        ButtonGroup g = new ButtonGroup();
        g.add(viaSsh);
        g.add(local);
        JTextField host = new JTextField(pr.get("sshHost", CAR_HOST), 16);
        JTextField port = new JTextField(pr.get("sshPort", "22"), 4);
        JTextField user = new JTextField(pr.get("sshUser", CAR_USER), 12);
        String saved =
            sessionPassword != null
                ? sessionPassword
                : Keychain.get(
                      account(pr.get("sshUser", CAR_USER), pr.get("sshHost", CAR_HOST), pr.get("sshPort", "22"))
                  );
        JPasswordField pass = new JPasswordField(saved == null ? "" : saved, 16);
        JCheckBox remember = new JCheckBox(
            Keychain.available()
                ? "Remember the password on this computer (" + Keychain.where() + ")"
                : "Remember the password until I close the app",
            saved != null
        );
        JCheckBox record = new JCheckBox("Save every frame to a log file in", pr.getBoolean("liveRecord", false));
        Path[] recDir = {
            Path.of(
                pr.get(
                    "liveRecordDir",
                    Path.of(System.getProperty("user.home"), "Documents", "Tiresias logs").toString()
                )
            ),
        };
        JButton recWhere = button(recDir[0].toString(), "The folder for the log files. Click to change it.", () -> {});
        recWhere.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(recDir[0].toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showDialog(this, "Use this folder") == JFileChooser.APPROVE_OPTION) {
                recDir[0] = fc.getSelectedFile().toPath();
                recWhere.setText(recDir[0].toString());
            }
        });
        JComboBox<String> ifc = editableCombo(List.of("can0", "can1", "vcan0"));
        ifc.setSelectedItem(pr.get("liveIface", "can0"));
        JTextField command = new JTextField(pr.get("liveCommand", "candump -L {iface}"), 24);
        for (JComponent c : new JComponent[] { viaSsh, local, remember, record }) c.setOpaque(false);

        JPanel f = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        ToneLabel wifi = new ToneLabel("Checking which Wi-Fi network you're on…");
        final String[] ssid = new String[1];
        final boolean[] checked = new boolean[1];
        c.gridx = 0;
        c.gridy = y++;
        c.gridwidth = 4;
        f.add(wifi, c);
        Thread wt = new Thread(() -> {
            String found = currentWifi();
            SwingUtilities.invokeLater(() -> {
                ssid[0] = found;
                checked[0] = true;
                if (wrongWifi(found)) wifi.set(
                    "<html><b>You're on " +
                        escHtml(found) +
                        ".</b> The car's computer is only reachable on " +
                        CAR_WIFI +
                        ". Switch Wi-Fi to " +
                        CAR_WIFI +
                        " first.</html>",
                    ToneLabel.ERR
                );
                else if (found != null && found.equalsIgnoreCase(CAR_WIFI)) wifi.set(
                    "Connected to " + CAR_WIFI + ".",
                    ToneLabel.OK
                );
                else if (found != null) wifi.set(
                    "You're on \"" + found + "\". The car's computer is on " + CAR_WIFI + ".",
                    ToneLabel.WARN
                );
                else wifi.set("Make sure you're connected to " + CAR_WIFI + ".", ToneLabel.MUTED);
                Window w = SwingUtilities.getWindowAncestor(wifi);
                if (w != null) w.pack();
            });
        }, "wifi-check");
        wt.setDaemon(true);
        wt.start();
        c.gridy = y++;
        f.add(viaSsh, c);
        c.gridy = y++;
        f.add(local, c);
        c.gridwidth = 1;
        c.gridy = y;
        c.gridx = 0;
        f.add(new MutedLabel("Host"), c);
        c.gridx = 1;
        f.add(host, c);
        c.gridx = 2;
        f.add(new MutedLabel("Port"), c);
        c.gridx = 3;
        f.add(port, c);
        y++;
        c.gridy = y;
        c.gridx = 0;
        f.add(new MutedLabel("User"), c);
        c.gridx = 1;
        f.add(user, c);
        y++;
        c.gridy = y;
        c.gridx = 0;
        f.add(new MutedLabel("Password"), c);
        c.gridx = 1;
        f.add(pass, c);
        c.gridx = 2;
        c.gridwidth = 2;
        f.add(new MutedLabel("empty = use your SSH key"), c);
        c.gridwidth = 1;
        y++;
        c.gridy = y++;
        c.gridx = 1;
        c.gridwidth = 3;
        f.add(remember, c);
        c.gridwidth = 1;
        c.gridy = y;
        c.gridx = 0;
        f.add(new MutedLabel("Interface"), c);
        c.gridx = 1;
        f.add(ifc, c);
        y++;
        c.gridy = y;
        c.gridx = 0;
        f.add(new MutedLabel("Command"), c);
        c.gridx = 1;
        c.gridwidth = 3;
        f.add(command, c);
        y++;
        c.gridy = y;
        c.gridx = 1;
        f.add(
            new MutedLabel(
                "{iface} is replaced by the interface. The output must be candump -L or default candump lines."
            ),
            c
        );
        y++;
        c.gridy = y;
        c.gridx = 1;
        c.gridwidth = 3;
        JPanel recRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        recRow.setOpaque(false);
        recRow.add(record);
        recRow.add(recWhere);
        f.add(recRow, c);
        c.gridwidth = 1;
        Runnable sync = () -> {
            boolean s = viaSsh.isSelected();
            for (JComponent x : new JComponent[] { host, port, user, pass, remember }) x.setEnabled(s);
        };
        viaSsh.addActionListener(e -> sync.run());
        local.addActionListener(e -> sync.run());
        sync.run();

        while (true) {
            int r = JOptionPane.showConfirmDialog(
                this,
                f,
                "Listen to a CAN bus",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );
            if (r != JOptionPane.OK_OPTION) return;
            if (viaSsh.isSelected() && host.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter the car computer's host name or IP address.");
                continue;
            }
            if (!port.getText().strip().matches("\\d{1,5}")) {
                JOptionPane.showMessageDialog(this, "Port must be a number (usually 22).");
                continue;
            }
            String net = checked[0] ? ssid[0] : currentWifi();
            if (viaSsh.isSelected() && wrongWifi(net)) {
                Object[] opts = { "I've switched, connect", "Connect anyway", "Cancel" };
                int w = JOptionPane.showOptionDialog(
                    this,
                    "You're on " +
                        net +
                        ", but the car's computer is only reachable on " +
                        CAR_WIFI +
                        ".\nSwitch your Wi-Fi to " +
                        CAR_WIFI +
                        ", then connect.",
                    "Wrong Wi-Fi network",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    opts,
                    opts[0]
                );
                if (w == 2 || w == JOptionPane.CLOSED_OPTION) return;
                if (w == 0 && wrongWifi(currentWifi())) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Still on " + currentWifi() + ". Switch to " + CAR_WIFI + " and try again.",
                        "Wrong Wi-Fi network",
                        JOptionPane.WARNING_MESSAGE
                    );
                    continue;
                }
            }
            break;
        }
        String iface = String.valueOf(ifc.getEditor().getItem()).strip();
        if (iface.isEmpty()) iface = "can0";
        String remote = command.getText().isBlank()
            ? "candump -L " + iface
            : command.getText().strip().replace("{iface}", iface);
        String password = new String(pass.getPassword());
        pr.putBoolean("liveLocal", local.isSelected());
        pr.put("sshHost", host.getText().strip());
        pr.put("sshPort", port.getText().strip());
        pr.put("sshUser", user.getText().strip());
        pr.put("liveIface", iface);
        pr.put("liveCommand", command.getText().strip());
        pr.putBoolean("liveRecord", record.isSelected());
        pr.put("liveRecordDir", recDir[0].toString());
        String acct = account(user.getText(), host.getText(), port.getText());
        boolean keep = viaSsh.isSelected() && remember.isSelected() && !password.isEmpty(),
            kc = Keychain.available();
        sessionPassword = keep && !kc ? password : null;
        if (kc && viaSsh.isSelected()) Thread.ofPlatform()
            .daemon()
            .start(() -> {
                if (!keep) Keychain.delete(acct);
                else if (!Keychain.put(acct, password)) SwingUtilities.invokeLater(() ->
                    note(
                        "Couldn't save the password " + Keychain.where() + ", so you'll need to type it next time.",
                        ToneLabel.WARN,
                        8
                    )
                );
            });

        List<String> cmd = new ArrayList<>();
        Map<String, String> env = new HashMap<>();
        String where;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        if (viaSsh.isSelected()) {
            String target = (user.getText().isBlank() ? "" : user.getText().strip() + "@") + host.getText().strip();
            where = iface + " on " + target;
            // -tt gives the remote command a terminal: candump then prints each frame immediately,
            // and it's stopped when the connection closes.
            cmd.addAll(
                Arrays.asList(
                    "ssh",
                    "-tt",
                    "-p",
                    port.getText().strip(),
                    "-o",
                    "ConnectTimeout=8",
                    "-o",
                    "ServerAliveInterval=5",
                    "-o",
                    "ServerAliveCountMax=3",
                    "-o",
                    "StrictHostKeyChecking=accept-new"
                )
            );
            if (password.isEmpty()) cmd.addAll(Arrays.asList("-o", "BatchMode=yes"));
            else {
                cmd.addAll(Arrays.asList("-o", "NumberOfPasswordPrompts=1"));
                try {
                    Path askpass = askpassScript(windows);
                    env.put("SSH_ASKPASS", askpass.toString());
                    env.put("SSH_ASKPASS_REQUIRE", "force");
                    if (System.getenv("DISPLAY") == null) env.put("DISPLAY", ":0");
                    env.put("TIRESIAS_SSH_PW", password);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Couldn't prepare password login: " + ex.getMessage() + "\nUse an SSH key instead."
                    );
                    return;
                }
            }
            cmd.add(target);
            cmd.add(remote);
        } else {
            where = iface + " on this computer";
            if (windows) cmd.addAll(Arrays.asList("cmd", "/c", remote));
            else cmd.addAll(Arrays.asList("sh", "-c", remote));
        }
        liveSpec = new LiveSpec(List.copyOf(cmd), Map.copyOf(env), where, iface);
        liveSession = true;
        reconnectAttempt = 0;
        lostAtNs = 0;
        sessionFrames.set(0);
        liveBtn.setText("Stop listening");
        if (record.isSelected()) openRecorder(recDir[0]);
        connect(liveSpec, true);
    }

    /** A tiny SSH_ASKPASS helper that hands ssh the password from the environment (never written to disk). */
    public static Path askpassScript(boolean windows) throws IOException {
        Path p;
        if (windows) {
            p = Files.createTempFile("tiresias-askpass", ".cmd");
            String jar = ownJar();
            String java = new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath();
            String body =
                jar != null
                    ? "@\"" + java + "\" -cp \"" + jar + "\" tiresias.Tiresias --askpass\r\n"
                    : "@echo %TIRESIAS_SSH_PW%\r\n";
            Files.writeString(p, body);
        } else {
            p = Files.createTempFile("tiresias-askpass", ".sh");
            Files.writeString(
                p,
                """
                #!/bin/sh
                printf '%s\\n' "$TIRESIAS_SSH_PW"
                """
            );
            if (!p.toFile().setExecutable(true, true)) throw new IOException("Couldn't make " + p + " executable");
        }
        p.toFile().deleteOnExit();
        return p;
    }

    public static String ownJar() {
        try {
            Path p = Paths.get(Tiresias.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                ? p.toString()
                : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Turns ssh/candump error output into a sentence about what to fix. */
    public static String explain(String noise, String iface) {
        if (noise == null) return null;
        String n = noise.toLowerCase(Locale.ROOT);
        if (
            n.contains("permission denied")
        ) return "SSH login failed. Check the user and password, or set up a key once with: ssh-copy-id user@host";
        if (
            n.contains("could not resolve")
        ) return "Can't find that host name. Check the address, or use the car computer's IP.";
        if (n.contains("timed out") || n.contains("no route") || n.contains("unreachable")) {
            String net = currentWifi();
            return (
                "Can't reach the car's computer. " +
                (wrongWifi(net)
                    ? "You're on " + net + "; connect to " + CAR_WIFI + " instead."
                    : "Make sure you're on " + CAR_WIFI + " and the address is right.")
            );
        }
        if (
            n.contains("connection refused")
        ) return "The car's computer refused the connection. Is SSH running there, and is the port right?";
        if (
            n.contains("host key verification failed") || n.contains("remote host identification has changed")
        ) return "The car's SSH host key changed. If that's expected (reinstalled OS), remove the old key with: ssh-keygen -R host";
        if (
            n.contains("command not found") || n.contains("not found")
        ) return "candump isn't installed on the car's computer. Install can-utils there (sudo apt install can-utils).";
        if (n.contains("no such device") || n.contains("siocgifindex")) return (
            "There's no interface named " + iface + " there. Check with: ip link"
        );
        if (n.contains("network is down")) return (
            iface + " is down. Bring it up first, e.g. sudo ip link set " + iface + " up type can bitrate 500000"
        );
        return noise;
    }

    public void startReader(Process p, LiveSpec s) {
        String where = s.where(),
            iface = s.iface();
        if (lostAtNs == 0) stat.set("Connecting to " + where + "…", ToneLabel.MUTED);
        Thread t = new Thread(() -> {
            String lastNoise = null;
            int got = 0;
            try (
                java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
                )
            ) {
                String line;
                while ((line = r.readLine()) != null) {
                    CanFrame f = parseCandumpLine(line);
                    if (f != null) {
                        enqueue(f);
                        record(line);
                        sessionFrames.incrementAndGet();
                        if (got++ == 0) SwingUtilities.invokeLater(() -> connected(s));
                        continue;
                    }
                    String l = line.strip();
                    if (
                        l.isEmpty() ||
                        l.startsWith("Warning: Permanently added") ||
                        l.matches("(?i)connection to .* closed\\.?")
                    ) continue;
                    lastNoise = l;
                }
            } catch (IOException ignored) {}
            final String noise = lastNoise;
            final String why = explain(lastNoise, iface);
            final int frames = got;
            SwingUtilities.invokeLater(() -> {
                if (proc == p) proc = null;
                if (!liveSession) {
                    note(
                        "Stopped listening to " + where + (stopNote != null ? ". " + stopNote : ""),
                        ToneLabel.MUTED,
                        8
                    );
                    stopNote = null;
                    return;
                }
                // it had been working: a drop (Wi-Fi blip, car rebooted) is worth retrying
                if (sessionFrames.get() > 0 && retryable(noise)) {
                    scheduleReconnect(s, why);
                    return;
                }
                boolean wasReconnecting = lostAtNs != 0;
                String saved = endSession();
                if (frames == 0 && why != null && !wasReconnecting) {
                    note("Couldn't listen to " + where + ": " + why, ToneLabel.ERR, 15);
                    JOptionPane.showMessageDialog(
                        this,
                        why,
                        "Couldn't listen to " + where,
                        JOptionPane.WARNING_MESSAGE
                    );
                } else note(
                    "Connection to " +
                        where +
                        " ended" +
                        (why != null ? ": " + why : "") +
                        (saved != null ? ". " + saved : ""),
                    why != null ? ToneLabel.WARN : ToneLabel.MUTED,
                    15
                );
            });
        }, "candump-live");
        t.setDaemon(true);
        t.start();
    }

    /** The first frame of a connection: say so, and after a drop, how long the gap was. */
    public void connected(LiveSpec s) {
        if (lostAtNs != 0) {
            String gap = fmtD(Math.round((System.nanoTime() - lostAtNs) / 1e8) / 10.0);
            recordNote("reconnected after " + gap + " s without frames");
            note("Reconnected to " + s.where() + " after " + gap + " s without frames", ToneLabel.OK, 8);
            lostAtNs = 0;
        } else note("Listening to " + s.where(), ToneLabel.OK, 3);
        reconnectAttempt = 0;
    }

    /** Ends the live session (no more reconnects); returns where the recording went, if there was one. */
    public String endSession() {
        liveSession = false;
        if (reconnectTimer != null) reconnectTimer.stop();
        Process p = proc;
        proc = null;
        if (p != null) p.destroy();
        lostAtNs = 0;
        if (liveBtn != null) liveBtn.setText("Listen live…");
        return closeRecorder();
    }

    public void stopLive() {
        boolean readerWillReport = proc != null;
        String saved = endSession();
        if (readerWillReport) stopNote = saved;
        else if (saved != null) note(saved, ToneLabel.OK, 10);
    }

    public void clear() {
        pending.clear();
        queued.set(0);
        droppedOld = false;
        noteUntilNs = 0;
        frames.clear();
        aggs.clear();
        t0 = Double.NaN;
        latest = Double.NaN;
        skippedLines = 0;
        loop.reset();
        for (FaultsPanel fp : faultPanels) fp.reset();
        updateLoop();
        rebuildView();
        showDetail();
    }

    public void drain() {
        if (pending.isEmpty()) return;
        int start = view.size();
        int added = 0;
        CanFrame f;
        while (added < 20000 && (f = pending.poll()) != null) {
            queued.decrementAndGet();
            if (Double.isNaN(t0) && !Double.isNaN(f.ts())) t0 = f.ts();
            double t = Double.isNaN(f.ts()) ? wallNow() : f.ts();
            if (Double.isNaN(latest) || t > latest) latest = t;
            frames.add(f);
            FrameCheck c = checkFrame(f, index);
            loop.offer(f, c, t0);
            Agg a = aggs.computeIfAbsent(f.key(), k -> new Agg());
            a.count++;
            a.last = f;
            if (Double.isNaN(a.first)) a.first = f.ts();
            a.lastTs = f.ts();
            a.group = c.hit() == null ? null : groupLabel(c.hit().file());
            a.addTime(t);
            decodeInto(a, f, c, t, true);
            for (FaultsPanel fp : faultPanels) if (!f.ext() && f.id() == fp.id) fp.offer(a, t);
            if (c.level() >= a.level) {
                a.level = c.level();
                a.text = c.text();
            }
            if (!perId.isSelected() && passes(f, c)) view.add(frames.size() - 1);
            added++;
        }
        if (added > 0) latestWall = System.nanoTime();
        updateLoop();
        if (frames.size() > 300000) {
            frames.subList(0, 100000).clear();
            droppedOld = true;
            rebuildView();
            return;
        }
        if (perId.isSelected()) rebuildAggView();
        else if (view.size() > start) {
            model.fireTableRowsInserted(start, view.size() - 1);
            if (follow.isSelected()) table.scrollRectToVisible(table.getCellRect(view.size() - 1, 0, true));
        }
        if (graphShown && detailTabs.getSelectedComponent() == pointsPane) graph.repaint();
        if (latestView()) showDetail(); // rows updated in place: refresh the latest frame
        updateStat();
    }

    public void tick() {
        if (!paused) drain();
        if (decodedActive()) decTable.repaint();
        if (efusesActive()) efuses.repaint();
        if (mainTabs.getSelectedComponent() instanceof FaultsPanel fp) fp.tiles.repaint();
        if (liveSession || paused) {
            updateStat();
            if (graphShown && detailTabs.getSelectedComponent() == pointsPane) graph.repaint();
        }
    }

    // ---- filtering and views

    public String search(String key, SpecRef hit, CanFrame f) {
        return searchText.computeIfAbsent(key, k -> {
            StringBuilder b = new StringBuilder(f.idText())
                .append(' ')
                .append("0x")
                .append(Long.toHexString(f.id()))
                .append(' ');
            if (hit != null) {
                Map<String, Object> m = hit.msg();
                b.append(strOr(m, "desc", "")).append(' ');
                for (Object o : listOrEmpty(m.get("fields"))) b.append(strOr(asMap(o), "name", "")).append(' ');
            }
            return b.toString().toLowerCase(Locale.ROOT);
        });
    }

    public boolean passes(CanFrame f, FrameCheck c) {
        if (problemsOnly.isSelected() && c.level() == 0) return false;
        String q = filter.getText().strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        return search(f.key(), c.hit(), f).contains(q) || f.bus().toLowerCase(Locale.ROOT).contains(q);
    }

    public void rebuildView() {
        view.clear();
        if (!perId.isSelected()) for (int i = 0; i < frames.size(); i++) {
            CanFrame f = frames.get(i);
            if (passes(f, checkFrame(f, index))) view.add(i);
        }
        rebuildAggView();
        updateStat();
    }

    public void rebuildAggView() {
        List<Agg> newAgg = new ArrayList<>(),
            vis = new ArrayList<>();
        for (Agg a : aggs.values()) if (passes(a.last, checkFrame(a.last, index))) vis.add(a);
        vis.sort(Comparator.comparing((Agg a) -> a.last.ext()).thenComparingLong(a -> a.last.id()));
        if (perId.isSelected()) newAgg.addAll(vis);
        List<Object> newDec = groupRows(vis);
        // Same rows in the same order (the usual case while frames stream in): repaint the values in place, which
        // keeps the selection and any click in progress, instead of rebuilding the tables.
        if (newAgg.equals(aggView) && newDec.equals(decView)) {
            if (!aggView.isEmpty()) model.fireTableRowsUpdated(0, aggView.size() - 1);
            if (!decView.isEmpty()) decModel.fireTableRowsUpdated(0, decView.size() - 1);
            return;
        }
        Object selKey = null;
        int sr = table.getSelectedRow();
        if (perId.isSelected() && sr >= 0 && sr < aggView.size()) selKey = aggView.get(sr).last.key();
        aggView.clear();
        if (perId.isSelected()) {
            for (Agg a : aggs.values()) if (passes(a.last, checkFrame(a.last, index))) aggView.add(a);
            aggView.sort(Comparator.comparing((Agg a) -> a.last.ext()).thenComparingLong(a -> a.last.id()));
        }
        model.fireTableDataChanged();
        if (selKey != null) for (int i = 0; i < aggView.size(); i++) if (aggView.get(i).last.key().equals(selKey)) {
            table.setRowSelectionInterval(i, i);
            break;
        }
        Agg dSel = decAgg(decTable.getSelectedRow());
        Object dKey = dSel == null ? null : dSel.last.key();
        decView.clear();
        decView.addAll(newDec);
        decModel.fireTableDataChanged();
        if (dKey != null) for (int i = 0; i < decView.size(); i++) if (
            decAgg(i) != null && decAgg(i).last.key().equals(dKey)
        ) {
            decTable.setRowSelectionInterval(i, i);
            break;
        }
    }

    /** A status message that the per-refresh frame counts don't overwrite for a few seconds. */
    public void note(String msg, int tone, double seconds) {
        stat.set(msg, tone);
        noteUntilNs = System.nanoTime() + (long) (seconds * 1e9);
    }

    public void updateStat() {
        if (System.nanoTime() < noteUntilNs) return;
        int unknown = 0,
            problems = 0,
            stale = 0;
        for (Agg a : aggs.values()) {
            if (stale(a)) stale++;
            if (a.text.equals("Unknown ID")) unknown++;
            else if (a.level == 2) problems++;
        }
        stat.set(
            frames.size() +
                " frames, " +
                aggs.size() +
                " IDs" +
                (unknown > 0 ? ", " + unknown + " unknown IDs" : "") +
                (problems > 0 ? ", " + problems + " IDs with problems" : "") +
                (stale > 0 ? ", " + stale + (stale == 1 ? " stale ID" : " stale IDs") : "") +
                (paused
                    ? "   (paused: " + queued.get() + " frames waiting)"
                    : lostAtNs != 0
                        ? "   (connection lost " +
                          Math.round((System.nanoTime() - lostAtNs) / 1e9) +
                          " s ago, reconnecting…)"
                        : liveSession
                            ? "   (listening)"
                            : ""),
            unknown + problems > 0 ? ToneLabel.WARN : ToneLabel.MUTED
        );
    }

    public int rowLevel(int row) {
        if (perId.isSelected()) return row < aggView.size() ? aggView.get(row).level : 0;
        return row < view.size() ? checkFrame(frames.get(view.get(row)), index).level() : 0;
    }

    public Object frameValue(int i, int c) {
        CanFrame f = frames.get(i);
        FrameCheck ck = checkFrame(f, index);
        return switch (c) {
            case 0 -> i + 1;
            case 1 -> Double.isNaN(f.ts())
                ? ""
                : String.format(Locale.ROOT, "%.6f", Double.isNaN(t0) ? f.ts() : f.ts() - t0);
            case 2 -> f.bus();
            case 3 -> f.idText() + (f.fd() ? " FD" : "");
            case 4 -> ck.hit() == null ? "" : strOr(ck.hit().msg(), "desc", "");
            case 5 -> f.data().length;
            case 6 -> f.dataText();
            case 7 -> ck.hit() == null || f.rtr() ? "" : decodeFrame(ck.hit().msg(), f.data()).summary().toString();
            default -> ck.text();
        };
    }

    public Object aggValue(Agg a, int c) {
        CanFrame f = a.last;
        FrameCheck ck = checkFrame(f, index);
        return switch (c) {
            case 0 -> f.idText() + (f.fd() ? " FD" : "");
            case 1 -> ck.hit() == null ? "" : strOr(ck.hit().msg(), "desc", "");
            case 2 -> a.count;
            case 3 -> rateText(a);
            case 4 -> f.data().length;
            case 5 -> f.dataText();
            case 6 -> ck.hit() == null || f.rtr() ? "" : decodeFrame(ck.hit().msg(), f.data()).summary().toString();
            default -> a.text;
        };
    }

    public CanFrame selectedFrame() {
        if (decodedActive()) {
            Agg d = decAgg(decTable.getSelectedRow());
            return d == null ? null : d.last;
        }
        if (efusesActive()) {
            Agg d = efuses.selectedAgg();
            return d == null ? null : d.last;
        }
        int r = table.getSelectedRow();
        if (r < 0) return null;
        if (perId.isSelected()) return r < aggView.size() ? aggView.get(r).last : null;
        return r < view.size() ? frames.get(view.get(r)) : null;
    }

    public void showDetail() {
        CanFrame f = selectedFrame();
        showPoints(selectedAgg(), f);
        if (f == null) {
            detail.setText("");
            detailKey = null;
            return;
        }
        FrameCheck c = checkFrame(f, index);
        StringBuilder b = new StringBuilder();
        b.append("ID ")
            .append(f.idText())
            .append(f.ext() ? " (extended)" : " (standard)")
            .append(f.fd() ? ", CAN FD" : "")
            .append(f.bus().isEmpty() ? "" : ", " + f.bus())
            .append(", ")
            .append(f.data().length)
            .append(" bytes\n");
        b.append("Status: ").append(c.text()).append('\n');
        b.append("Line:   ").append(f.line()).append('\n');
        if (c.hit() != null && !f.rtr()) {
            Map<String, Object> m = c.hit().msg();
            b.append("Spec:   ")
                .append(strOr(m, "id", "?"))
                .append("  ")
                .append(strOr(m, "desc", ""))
                .append("  (")
                .append(app.relName(c.hit().file()))
                .append(")\n\n");
            Decoded d = decodeFrame(m, f.data());
            b.append("Points\n");
            for (String[] p : d.points()) b.append("  #%-4s %-28s %-8s %-22s %s%n".formatted((Object[]) p));
            b.append("\nMQTT\n");
            for (String t : d.topics()) b.append("  ").append(t).append('\n');
        }
        String text = b.toString();
        boolean same = f.key().equals(detailKey);
        detailKey = f.key();
        if (same && text.equals(detail.getText())) return;
        detail.setText(text);
        if (!same) {
            detail.setCaretPosition(0);
            detailScroll.getViewport().setViewPosition(new Point(0, 0));
        }
    }

    public void openInEditor() {
        CanFrame f = selectedFrame();
        if (f == null) return;
        FrameCheck c = checkFrame(f, index);
        if (c.hit() == null) {
            stat.set("No spec message has ID " + f.idText(), ToneLabel.WARN);
            return;
        }
        app.selectMessage(c.hit().file(), c.hit().msg());
        app.toFront();
    }

    // ---- spec changes: re-check every frame against the edited spec

    public void specChanged() {
        specTimer.restart();
    }

    public void applySpecChange() {
        index = specIndex(app.files);
        searchText.clear();
        for (Agg a : aggs.values()) {
            a.level = 0;
            a.text = "OK";
            a.resetDecoded();
        }
        loop.reset();
        for (FaultsPanel fp : faultPanels) fp.reset();
        for (CanFrame f : frames) {
            FrameCheck c = checkFrame(f, index);
            loop.offer(f, c, t0);
            Agg a = aggs.get(f.key());
            if (a != null) {
                decodeInto(a, f, c, f.ts(), false);
                for (FaultsPanel fp : faultPanels) if (!f.ext() && f.id() == fp.id) fp.offer(a, f.ts());
                a.group = c.hit() == null ? null : groupLabel(c.hit().file());
            }
            if (a != null && c.level() >= a.level) {
                a.level = c.level();
                a.text = c.text();
            }
        }
        updateLoop();
        rebuildView();
        showDetail();
    }

    // ---- decoded view: rate, staleness, values

    public double wallNow() {
        return (System.nanoTime() - wallBase) / 1e9;
    }

    /** "Now" for staleness: the newest frame's time, plus the time since it arrived while listening live. */
    public double now() {
        if (Double.isNaN(latest)) return Double.NaN;
        if (!liveSession) return latest;
        long at = paused ? pausedAtNs : System.nanoTime(); // the stale clock stops while paused
        return latest + Math.max(0, at - latestWall) / 1e9;
    }

    public double age(Agg a) {
        double n = now();
        return Double.isNaN(n) || Double.isNaN(a.lastSeen) ? Double.NaN : Math.max(0, n - a.lastSeen);
    }

    /** Stale: nothing for 3 of its usual periods (and at least a second), once its rate is known. */
    public boolean stale(Agg a) {
        double r = a.rate(),
            age = age(a);
        return !Double.isNaN(r) && !Double.isNaN(age) && age > Math.max(STALE_PERIODS / r, STALE_MIN_S);
    }

    public static String rateText(Agg a) {
        double r = a.rate();
        return Double.isNaN(r) ? "" : fmtD(Math.round(r * 10) / 10.0);
    }

    public String lastSeenText(Agg a) {
        double age = age(a);
        if (Double.isNaN(age)) return "";
        String s = age < 1 ? Math.round(age * 1000) + " ms ago" : fmtD(Math.round(age * 10) / 10.0) + " s ago";
        return stale(a) ? "Stale · " + s : s;
    }

    public Object decValue(Agg a, int c) {
        CanFrame f = a.last;
        return switch (c) {
            case 0 -> f.idText() + (f.fd() ? " FD" : "");
            case 1 -> a.msg == null ? "" : strOr(a.msg, "desc", "");
            case 2 -> rateText(a);
            case 3 -> lastSeenText(a);
            default -> a;
        };
    }

    /** Decodes a frame into its ID's running values: change marks, min/max and the graph history. */
    public void decodeInto(Agg a, CanFrame f, FrameCheck c, double t, boolean mark) {
        if (c.hit() == null || f.rtr()) return;
        Map<String, Object> m = c.hit().msg();
        if (a.msg != m) {
            a.resetDecoded();
            a.msg = m;
            a.meta = pointMeta(m);
        }
        double[] v = pointValues(m, f.data(), null);
        int n = v.length;
        if (a.min == null || a.min.length != n) {
            a.min = new double[n];
            a.max = new double[n];
            Arrays.fill(a.min, Double.POSITIVE_INFINITY);
            Arrays.fill(a.max, Double.NEGATIVE_INFINITY);
            a.changedAt = new long[n];
            a.cur = null;
        }
        if (a.cur != null && mark) {
            long nowNs = System.nanoTime();
            for (int i = 0; i < n; i++) if (Double.compare(v[i], a.cur[i]) != 0) a.changedAt[i] = nowNs;
        }
        a.prev = a.cur;
        a.cur = v;
        double[] h = new double[n + 1];
        h[0] = t;
        for (int i = 0; i < n; i++) {
            h[i + 1] = v[i];
            if (Double.isNaN(v[i])) continue;
            a.min[i] = Math.min(a.min[i], v[i]);
            a.max[i] = Math.max(a.max[i], v[i]);
        }
        a.history.add(h);
        if (a.history.size() > HISTORY_CAP) a.history.subList(0, HISTORY_CAP / 2).clear();
    }

    public boolean decodedActive() {
        return mainTabs.getSelectedIndex() == 1;
    }

    public Agg selectedAgg() {
        if (decodedActive()) return decAgg(decTable.getSelectedRow());
        if (efusesActive()) return efuses.selectedAgg();
        CanFrame f = selectedFrame();
        return f == null ? null : aggs.get(f.key());
    }

    /** Fills the Points tab from the selected frame (the latest one in the decoded view). */
    public void showPoints(Agg a, CanFrame f) {
        if (a != null && a == pAgg && a.msg != null && a.msg == pMsg && f != null && !f.rtr()) {
            // same message as before: update the values without rebuilding, so the selected point stays put
            pVals = pointValues(a.msg, f.data(), pRaw);
            pMarkT = latestView() ? Double.NaN : f.ts();
            if (!pRows.isEmpty()) pointsModel.fireTableRowsUpdated(0, pRows.size() - 1);
            graph.markT = pMarkT;
            return;
        }
        pAgg = null;
        pMsg = null;
        pVals = null;
        pRaw = null;
        pRows.clear();
        if (a != null && a.msg != null && f != null && !f.rtr()) {
            pAgg = a;
            pMsg = a.msg;
            pRaw = new long[a.meta.size()];
            pVals = pointValues(a.msg, f.data(), pRaw);
            for (int i = 0; i < a.meta.size(); i++) if (a.meta.get(i).parsed()) pRows.add(i);
            pMarkT = latestView() ? Double.NaN : f.ts();
        }
        pointsModel.fireTableDataChanged();
        int r = pRows.indexOf(graphPoint);
        if (r < 0 && !pRows.isEmpty()) r = 0;
        if (r >= 0) pointsTable.setRowSelectionInterval(r, r);
        else updateGraph();
    }

    public void updateGraph() {
        int r = pointsTable.getSelectedRow();
        if (pAgg == null || r < 0 || r >= pRows.size()) {
            graph.set(null, 0, null, 0, Double.NaN);
            return;
        }
        int i = pRows.get(r);
        graphPoint = i;
        graph.set(pAgg.history, i + 1, pAgg.meta.get(i), Double.isNaN(t0) ? 0 : t0, pMarkT);
    }

    /**
     * Copies the selected frame (the latest one, for a row per ID): its message, each point's decoded value and
     * unit, then the candump line, which pastes back into Paste lines on its own.
     */
    public void copyFrame() {
        CanFrame f = selectedFrame();
        if (f == null) {
            stat.set("Select a frame first, then Copy frame copies its decoded values.", ToneLabel.WARN);
            return;
        }
        FrameCheck c = checkFrame(f, index);
        Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new StringSelection(decodedText(f, c)), null);
        note(
            c.hit() != null && !f.rtr()
                ? "Copied " + f.idText() + "'s decoded values and its candump line"
                : "Copied " + f.idText() + "'s candump line (no spec message to decode it with)",
            ToneLabel.OK,
            4
        );
    }

    /** "0x123  Shutdown Pins  (vcu.json)", a line per parsed point (name, value, unit), then the candump line. */
    public String decodedText(CanFrame f, FrameCheck c) {
        StringBuilder b = new StringBuilder();
        if (c.hit() != null && !f.rtr()) {
            Map<String, Object> m = c.hit().msg();
            b.append(f.idText())
                .append("  ")
                .append(strOr(m, "desc", ""))
                .append("  (")
                .append(app.relName(c.hit().file()))
                .append(")\n");
            List<PointMeta> meta = pointMeta(m);
            double[] v = pointValues(m, f.data(), null);
            int w = 4;
            for (PointMeta pm : meta) if (pm.parsed()) w = Math.max(w, pm.name().length());
            for (int i = 0; i < meta.size() && i < v.length; i++) {
                PointMeta pm = meta.get(i);
                if (!pm.parsed()) continue;
                String line = "  %-" + w + "s  %s%s";
                b.append(line.formatted(pm.name(), fmtD(v[i]), pm.unit().isEmpty() ? "" : " " + pm.unit())).append(
                    '\n'
                );
            }
        }
        return b.append(f.line()).toString();
    }

    /** The Values cell of the decoded view: each point's name and value, changes highlighted, 1-bit points as chips. */
    public final class ValuesCell extends JComponent implements TableCellRenderer {

        public Agg a;
        public boolean sel;
        public Color bg;

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            a = v instanceof Agg g ? g : null;
            this.sel = sel;
            bg = sel ? T.selection() : a != null && a.level == 2 ? T.tint(T.red()) : row % 2 == 1 ? T.alt() : T.panel();
            if (v instanceof Group && !sel) bg = blend(T.fg(), T.panel(), 0.07);
            setFont(t.getFont());
            setToolTipText(tip());
            return this;
        }

        public String tip() {
            if (a == null || a.cur == null) return null;
            StringBuilder b = new StringBuilder("<html><table cellspacing=0 cellpadding=0>");
            for (int i = 0; i < a.meta.size(); i++) {
                PointMeta pm = a.meta.get(i);
                if (!pm.parsed()) continue;
                b.append("<tr><td><tt>")
                    .append(escHtml(pm.name()))
                    .append("</tt>&nbsp;&nbsp;</td><td align=right><b>")
                    .append(escHtml(fmtD(a.cur[i])))
                    .append("</b></td><td>&nbsp;")
                    .append(escHtml(pm.unit()))
                    .append("</td></tr>");
            }
            return b.append("</table></html>").toString();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(bg);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (a == null) {
                g.dispose();
                return;
            }
            Font f = getFont(),
                b = f.deriveFont(Font.BOLD);
            FontMetrics fm = g.getFontMetrics(f),
                bm = g.getFontMetrics(b);
            int lh = fm.getHeight(),
                top = (getHeight() - lh) / 2,
                base = top + fm.getAscent(),
                x = 8,
                w = getWidth() - 8;
            if (a.cur == null) {
                g.setFont(f);
                g.setColor(T.muted());
                g.drawString(a.last.rtr() ? "Remote request (no data)" : "Not in the spec", x, base);
                g.dispose();
                return;
            }
            boolean stale = stale(a);
            long nowNs = System.nanoTime();
            for (int i = 0; i < a.meta.size(); i++) {
                PointMeta pm = a.meta.get(i);
                if (!pm.parsed()) continue;
                double v = i < a.cur.length ? a.cur[i] : Double.NaN;
                boolean bit = pm.size() == 1;
                String name = pm.name(),
                    val = bit ? (v != 0 ? "1" : "0") : fmtD(v) + (pm.unit().isEmpty() ? "" : " " + pm.unit());
                int nw = fm.stringWidth(name),
                    vw = bm.stringWidth(val) + (bit ? 10 : 0);
                if (x + nw + 6 + vw > w) {
                    g.setFont(f);
                    g.setColor(T.muted());
                    g.drawString("…", x, base);
                    break;
                }
                g.setFont(f);
                g.setColor(sel ? T.fg() : T.muted());
                g.drawString(name, x, base);
                x += nw + 6;
                long ca = a.changedAt == null || i >= a.changedAt.length ? 0 : a.changedAt[i];
                double glow = ca == 0 ? 0 : 1 - (nowNs - ca) / (GLOW_S * 1e9);
                if (glow > 0 && !sel) {
                    g.setColor(blend(T.accent(), bg, 0.28 * glow));
                    g.fillRoundRect(x - 4, top - 1, vw + 8, lh + 2, 8, 8);
                }
                g.setFont(b);
                if (bit) {
                    Color c = v != 0 ? T.green() : T.muted();
                    g.setColor(blend(c, bg, sel ? 0.35 : 0.22));
                    g.fillRoundRect(x, top, vw, lh, 8, 8);
                    g.setColor(c);
                    g.drawString(val, x + 5, base);
                } else {
                    g.setColor(stale ? T.muted() : a.changed(i) ? T.accent() : sel ? T.bright() : T.fg());
                    g.drawString(val, x, base);
                }
                x += vw + 18;
            }
            g.dispose();
        }
    }

    // ---- live session: launching, reconnecting, recording

    public void enqueue(CanFrame f) {
        pending.add(f);
        if (queued.incrementAndGet() > PAUSE_BUFFER && pending.poll() != null) {
            queued.decrementAndGet();
            droppedWhilePaused++;
        }
    }

    public static String account(String user, String host, String port) {
        return (user.isBlank() ? "" : user.strip() + "@") + host.strip() + ":" + port.strip();
    }

    public Process launch(LiveSpec s) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(s.cmd());
        pb.environment().putAll(s.env());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** Starts (or restarts, after a drop) the capture; a first start that fails says why and ends the session. */
    public void connect(LiveSpec s, boolean first) {
        try {
            proc = launch(s);
        } catch (IOException ex) {
            proc = null;
            if (!first) {
                scheduleReconnect(s, ex.getMessage());
                return;
            }
            stopLive();
            JOptionPane.showMessageDialog(
                this,
                s.where().contains(" on this computer")
                    ? "Couldn't run the command: " +
                          ex.getMessage() +
                          "\n\ncandump is part of can-utils on Linux (sudo apt install can-utils)."
                    : "Couldn't run ssh: " +
                          ex.getMessage() +
                          "\n\nmacOS and Linux include ssh. On Windows, turn on\nSettings > System > Optional features > OpenSSH Client.",
                "Can't start",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        startReader(proc, s);
    }

    /** Errors that retrying can't fix: a wrong password, a changed host key, no candump, no such interface. */
    public static boolean retryable(String noise) {
        if (noise == null) return true;
        String n = noise.toLowerCase(Locale.ROOT);
        return !(
            n.contains("permission denied") ||
            n.contains("host key") ||
            n.contains("identification has changed") ||
            n.contains("command not found") ||
            n.contains("no such device") ||
            n.contains("siocgifindex")
        );
    }

    public void scheduleReconnect(LiveSpec s, String why) {
        if (!liveSession) return;
        if (lostAtNs == 0) {
            lostAtNs = System.nanoTime();
            recordNote("connection lost" + (why != null ? " (" + why + ")" : ""));
        }
        int delay = RECONNECT_DELAYS_S[Math.min(reconnectAttempt, RECONNECT_DELAYS_S.length - 1)];
        reconnectAttempt++;
        note(
            "Lost the connection to " +
                s.where() +
                (why != null ? " (" + why + ")" : "") +
                ". Reconnecting in " +
                delay +
                " s (attempt " +
                reconnectAttempt +
                ")…",
            ToneLabel.WARN,
            delay + 2
        );
        if (reconnectTimer != null) reconnectTimer.stop();
        reconnectTimer = new javax.swing.Timer(delay * 1000, e -> {
            if (liveSession && proc == null) connect(s, false);
        });
        reconnectTimer.setRepeats(false);
        reconnectTimer.start();
    }

    public void openRecorder(Path dir) {
        try {
            Files.createDirectories(dir);
            String stamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            );
            Path p = dir.resolve("tiresias-" + stamp + ".log");
            synchronized (recLock) {
                recorder = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                recordPath = p;
                recorded = 0;
            }
        } catch (IOException ex) {
            note("Couldn't start saving frames to " + dir + ": " + ex.getMessage(), ToneLabel.WARN, 8);
        }
    }

    /** Called by the reader thread for every frame line; flushes about once a second. */
    public void record(String line) {
        record(line, true);
    }

    public void record(String line, boolean isFrame) {
        synchronized (recLock) {
            if (recorder == null) return;
            try {
                recorder.write(line);
                recorder.write('\n');
                if (isFrame) recorded++;
                long now = System.nanoTime();
                if (now - recFlushedNs > 1_000_000_000L) {
                    recorder.flush();
                    recFlushedNs = now;
                }
            } catch (IOException ex) {
                recorder = null;
                SwingUtilities.invokeLater(() -> note("Stopped saving frames: " + ex.getMessage(), ToneLabel.WARN, 8));
            }
        }
    }

    /** A comment line in the recording (candump readers, Tiresias included, skip it). */
    public void recordNote(String what) {
        record(
            "# Tiresias " +
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) +
                ": " +
                what,
            false
        );
    }

    /** Closes the recording; returns a sentence about where it went, or null if nothing was recorded. */
    public String closeRecorder() {
        synchronized (recLock) {
            if (recorder == null) return null;
            try {
                recorder.close();
            } catch (IOException ignored) {}
            recorder = null;
            if (recorded == 0) {
                try {
                    Files.deleteIfExists(recordPath);
                } catch (IOException ignored) {}
                return null;
            }
            return "Saved " + recorded + (recorded == 1 ? " frame to " : " frames to ") + recordPath;
        }
    }

    // ---- pause and save

    public void togglePause() {
        paused = !paused;
        if (paused) pausedAtNs = System.nanoTime();
        pauseBtn.setText(paused ? "Resume" : "Pause");
        if (!paused) {
            drain();
            if (droppedWhilePaused > 0) note(
                "While paused, " +
                    droppedWhilePaused +
                    " of the oldest waiting frames were dropped to stay within memory" +
                    (recorder != null ? " (the recording has them all)." : "."),
                ToneLabel.WARN,
                8
            );
            droppedWhilePaused = 0;
        } else updateStat();
    }

    /** A frame as a candump -L line, the format candump, canplayer and Tiresias all read. */
    public static String candumpLine(CanFrame f) {
        if (Double.isNaN(f.ts()) || f.fd()) return f.line();
        StringBuilder d = new StringBuilder();
        if (f.rtr()) d.append('R');
        else for (byte b : f.data()) d.append(String.format("%02X", b & 0xFF));
        return String.format(
            Locale.ROOT,
            "(%.6f) %s %s#%s",
            f.ts(),
            f.bus().isEmpty() ? "can0" : f.bus(),
            f.ext() ? String.format("%08X", f.id()) : String.format("%03X", f.id()),
            d
        );
    }

    public void saveLog() {
        if (frames.isEmpty()) {
            stat.set("Nothing to save yet: open a log, paste lines or listen live first.", ToneLabel.WARN);
            return;
        }
        JFileChooser fc = new JFileChooser(app.prefs.get("lastLogDir", System.getProperty("user.home")));
        String stamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        );
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), "tiresias-" + stamp + ".log"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        if (
            out.exists() &&
            JOptionPane.showConfirmDialog(
                this,
                out.getName() + " already exists. Replace it?",
                "Save log",
                JOptionPane.OK_CANCEL_OPTION
            ) != JOptionPane.OK_OPTION
        ) return;
        app.prefs.put("lastLogDir", out.getParent());
        List<CanFrame> copy = new ArrayList<>(frames);
        boolean partial = droppedOld;
        stat.set("Saving " + copy.size() + " frames…", ToneLabel.MUTED);
        Thread.ofPlatform()
            .daemon()
            .start(() -> {
                String msg;
                int tone;
                try (var w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
                    for (CanFrame f : copy) {
                        w.write(candumpLine(f));
                        w.write('\n');
                    }
                    msg =
                        "Saved " +
                        copy.size() +
                        " frames to " +
                        out +
                        (partial ? " (the newest ones; older frames had been dropped to stay within memory)" : "");
                    tone = ToneLabel.OK;
                } catch (IOException ex) {
                    msg = "Couldn't save " + out + ": " + ex.getMessage();
                    tone = ToneLabel.ERR;
                }
                String m = msg;
                int tn = tone;
                SwingUtilities.invokeLater(() -> note(m, tn, 8));
            });
    }

    // ---- decoded view groups and the eFuse board

    public String groupName(Agg a) {
        return a.group == null ? "Not in the spec" : a.group;
    }

    /** The decoded rows: a header per node (spec file), then its IDs unless the group is collapsed. */
    public List<Object> groupRows(List<Agg> vis) {
        Map<String, List<Agg>> by = new TreeMap<>((x, y) -> {
            boolean xu = x.equals("Not in the spec"),
                yu = y.equals("Not in the spec");
            return xu != yu ? (xu ? 1 : -1) : x.compareToIgnoreCase(y);
        });
        for (Agg a : vis) by.computeIfAbsent(groupName(a), k -> new ArrayList<>()).add(a);
        List<Object> rows = new ArrayList<>();
        for (var e : by.entrySet()) {
            Group g = groups.computeIfAbsent(e.getKey(), Group::new);
            g.ids = e.getValue().size();
            g.stale = 0;
            for (Agg a : e.getValue()) if (stale(a)) g.stale++;
            rows.add(g);
            if (!collapsed.contains(g.name)) rows.addAll(e.getValue());
        }
        return rows;
    }

    public Agg decAgg(int r) {
        return r >= 0 && r < decView.size() && decView.get(r) instanceof Agg a ? a : null;
    }

    public Object groupValue(Group g, int c) {
        return switch (c) {
            case 0 -> (collapsed.contains(g.name) ? "▸  " : "▾  ") + g.name;
            case 1 -> g.ids + (g.ids == 1 ? " ID" : " IDs");
            case 3 -> g.stale > 0 ? g.stale + " stale" : "";
            case 4 -> g;
            default -> "";
        };
    }

    public boolean efusesActive() {
        return mainTabs.getSelectedIndex() == 2;
    }

    /** eFuses, Shutdown loop, VCU faults and BMS faults: whole-height pages without the frame detail pane. */
    public boolean dashboardActive() {
        return mainTabs.getSelectedIndex() >= 2;
    }

    /** Views that show each ID's latest frame (rather than one frame of the log). */
    public boolean latestView() {
        return decodedActive() || efusesActive() || perId.isSelected();
    }

    public String groupLabel(SpecFile f) {
        String n = app.relName(f);
        return n.toLowerCase(Locale.ROOT).endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    /** Keeps the Shutdown loop tab current: the loop once 0x123 frames have arrived, a note until then. */
    public void updateLoop() {
        loop.refresh();
        loop.setVisible(true);
        loopCards.show(loopPage, loop.hasData() ? "loop" : "empty");
    }
}
