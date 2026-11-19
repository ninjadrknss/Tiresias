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
// Message editor
// ======================================================================================

public final class MessageEditor extends JPanel {

    /** The narrowest the editor gets when the tree's divider is dragged right (its contents would ask for ~880). */
    public static final int MIN_WIDTH = 620;

    public final App app;
    public SpecFile file;
    public Map<String, Object> msg;
    public boolean loading;
    public final CardLayout cards = new CardLayout();
    public final JPanel cardPanel = new JPanel(cards);
    public final FadeUI fadeUI = new FadeUI();
    public final JLayer<JComponent> fadeLayer = new JLayer<>(cardPanel, fadeUI);
    public final JLabel placeholder = new JLabel("", SwingConstants.CENTER);
    public Runnable placeholderRefresh;
    public final MessageGrid grid;
    public final FileGrid fileGrid;
    public final JLabel overviewTitle = new JLabel(" ");
    public final JLabel overviewInfo = new JLabel(" ");
    public final JLabel fileTitle = new JLabel(" ");
    public final JLabel fileInfo = new JLabel(" ");
    public final HintField gridFilter = new HintField(
        "Filter by ID, description, topic or point…  (↓ to pick, Enter to open)",
        30
    );
    public final JScrollPane gridScroll;
    public JButton fileNew;
    public SpecFile gridFile;
    public boolean fileShown;

    public final JTextField idField = new JTextField(9),
        descField = new JTextField(22),
        simFreqField = new JTextField(5),
        keyField = new JTextField(10),
        clientsField = new JTextField(7);
    public final JCheckBox extBox = new JCheckBox("Extended ID");
    public final JComboBox<String> bidirBox = new JComboBox<>(new String[] { "", "broadcast", "oneshot" });
    public final JLabel summary = new JLabel(" ");
    public final JLabel msgProblems = new JLabel();
    /** After a points edit leaves the message unaligned, resize or add the trailing padding point (pref autoPad). */
    public final JCheckBox autoPadBox = new JCheckBox("Keep byte aligned");
    /** The points layout after the last edit: total bits and the trailing padding point's size (-1 if none). */
    public int layoutTotal = -1,
        layoutPad = -1;
    public final JTabbedPane tabs = new JTabbedPane();

    public final PointsModel pointsModel = new PointsModel();
    public final JTable pointsTable = new JTable(pointsModel);
    public final FieldsModel fieldsModel = new FieldsModel();
    public final JTable fieldsTable = new JTable(fieldsModel);
    public final JLabel fieldMapping = new JLabel(" ");
    public final JTextArea fieldDesc = new JTextArea(3, 40);
    public final JLabel fieldDetailLabel = new MutedLabel("Notes (desc)");
    public Map<String, Object> detailField;
    /** The key the box under the Fields table edits: "doc" while a Doc cell is selected, else "desc". */
    public String detailKey = "desc";
    /** The text the detail box was loaded with, so a refresh never writes stale box text over a newer cell edit. */
    public String detailLoaded = "";

    public final BitLayoutPanel layoutPanel = new BitLayoutPanel();
    public final JTextField decodeInput = new JTextField();
    public final DefaultTableModel decodeModel = new DefaultTableModel(
        new Object[] { "#", "Name", "Bits", "Raw", "Value" },
        0
    ) {
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    public final JTextArea decodeOut = new JTextArea(6, 40);
    public final ToneLabel decodeStatus = new ToneLabel(" ");
    public final JsonPane jsonArea = new JsonPane();
    public final ToneLabel jsonStatus = new ToneLabel(" ");

    public final Map<Integer, Severity> pointSev = new HashMap<>(),
        fieldSev = new HashMap<>();
    public final Map<Integer, List<Issue>> pointIssues = new HashMap<>(),
        fieldIssues = new HashMap<>();
    public String highlight = "";
    public int highlightMode = 0;
    public final JComboBox<String> unitCombo = editableCombo(COMMON_UNITS);
    public final JComboBox<String> formatterCombo = editableCombo(List.of("", "divide"));

    // A small fixed minimum, so the split pane lets the tree grow; wide rows are clipped instead.
    @Override
    public Dimension getMinimumSize() {
        return isMinimumSizeSet() ? super.getMinimumSize() : new Dimension(MIN_WIDTH, 200);
    }

    public MessageEditor(App app) {
        super(new BorderLayout());
        this.app = app;

        JPanel ph = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        ph.add(placeholder, g);
        cardPanel.add(ph, "empty");
        grid = new MessageGrid(app.msgSev::get, m -> {
            if (file != null) app.selectMessage(file, m);
        });
        gridScroll = new JScrollPane(grid);
        cardPanel.add(buildFileCard(), "file");
        fileGrid = new FileGrid(app, app::openFile);
        cardPanel.add(buildOverviewCard(), "files");
        cardPanel.add(buildEditor(), "editor");
        add(fadeLayer, BorderLayout.CENTER);
        cards.show(cardPanel, "empty");
        if (isShowing()) fadeUI.fadeIn(fadeLayer);
    }

    public JComponent buildEditor() {
        idField.setFont(mono(Font.BOLD, idField.getFont().getSize() + 3));
        descField.setFont(descField.getFont().deriveFont(descField.getFont().getSize2D() + 2));
        idField.setToolTipText("Hex CAN ID, e.g. 0x1A0");
        descField.setToolTipText(
            "Letters, whitespace and _ only. Containing \"overflow\" makes it the node's overflow broadcast."
        );
        simFreqField.setToolTipText("Simulation period/frequency (sim_freq). Leave empty to omit.");
        keyField.setToolTipText(
            "Makes this an encodable message: Calypso sends it when it receives Calypso/Bidir/Command/{key}. Leave empty for decode-only."
        );
        clientsField.setToolTipText("Extra MQTT client ports, comma separated (supported: 1882)");
        extBox.setToolTipText("is_ext — required for IDs above 0x7FF and for all encodable messages to be correct");
        bidirBox.setToolTipText("broadcast: sent ~1 s with latest/default value. oneshot: sent once per command.");

        bind(idField, t -> {
            if (putStr(msg, "id", t.strip(), false)) changed(true);
        });
        bind(descField, t -> {
            if (putStr(msg, "desc", t.strip(), false)) changed(true);
        });
        bind(simFreqField, t -> {
            BigDecimal b = null;
            if (!t.isBlank()) {
                b = parseNum(t);
                if (b == null) {
                    bad(simFreqField, "sim_freq must be a number");
                    return;
                }
            }
            if (putNum(msg, "sim_freq", b)) changed(false);
        });
        bind(keyField, t -> {
            if (putStr(msg, "key", t.strip(), true)) changed(true);
        });
        bind(clientsField, t -> {
            List<Object> l = new ArrayList<>();
            for (String s : t.split("[,\\s]+")) {
                if (s.isEmpty()) continue;
                Integer v = tryInt(s);
                if (v == null) {
                    bad(clientsField, "clients must be port numbers separated by commas");
                    return;
                }
                l.add(BigDecimal.valueOf(v));
            }
            Object cur = msg.get("clients");
            if (l.isEmpty()) {
                if (cur != null) {
                    msg.remove("clients");
                    changed(false);
                }
                return;
            }
            if (cur == null || !Json.write(cur).equals(Json.write(l))) {
                msg.put("clients", l);
                changed(false);
            }
        });
        extBox.addActionListener(e -> {
            if (!loading && msg != null && putBool(msg, "is_ext", extBox.isSelected(), false)) changed(false);
        });
        bidirBox.addActionListener(e -> {
            if (loading || msg == null) return;
            if (putStr(msg, "bidir_mode", (String) bidirBox.getSelectedItem(), true)) changed(false);
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 14, 8, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int x = 0;
        c.gridy = 0;
        c.gridx = x++;
        form.add(new MutedLabel("ID"), c);
        c.gridx = x++;
        form.add(idField, c);
        c.gridx = x++;
        form.add(button("Next free", "Set the ID to the next unused ID after this one", this::nextFreeId), c);
        c.gridx = x++;
        form.add(new MutedLabel("  Description"), c);
        c.gridx = x++;
        c.weightx = 1;
        c.gridwidth = 3;
        form.add(descField, c);
        c.weightx = 0;
        c.gridwidth = 1;
        x += 2;
        c.gridx = x;
        form.add(extBox, c);
        x = 0;
        c.gridy = 1;
        c.gridx = x++;
        form.add(new MutedLabel("Key"), c);
        c.gridx = x++;
        form.add(keyField, c);
        c.gridx = x++;
        form.add(bidirBox, c);
        c.gridx = x++;
        form.add(new MutedLabel("  Sim freq"), c);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.setOpaque(false);
        extBox.setOpaque(false);
        row2.add(simFreqField);
        row2.add(new MutedLabel("   Clients"));
        row2.add(clientsField);
        c.gridx = x;
        c.gridwidth = 4;
        form.add(row2, c);
        c.gridwidth = 1;
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 8;
        form.add(buildFamilyRow(), c);
        c.gridy = 3;
        form.add(summary, c);
        c.gridy = 4;
        form.add(msgProblems, c);
        // When the editor is narrower than the form wants, GridBagLayout falls back to minimum sizes, which for text
        // fields is nearly nothing; keep the short boxes at their normal width and let Description give way.
        for (JComponent f : new JComponent[] { idField, keyField, bidirBox, simFreqField, clientsField })
            f.setMinimumSize(f.getPreferredSize());
        descField.setMinimumSize(new Dimension(140, descField.getPreferredSize().height));
        msgProblems.setToolTipText("Right-click for fixes");
        addFixMenu(msgProblems, r -> messageIssues());

        tabs.addTab("Points", buildPointsTab());
        tabs.addTab("Fields (MQTT topics)", buildFieldsTab());
        tabs.addTab("Bit layout", new JScrollPane(layoutPanel));
        tabs.setBorder(new EmptyBorder(0, 6, 0, 6));
        tabs.addTab("Decode test", buildDecodeTab());
        tabs.addTab("cansend", buildSendTab());
        tabs.addTab("JSON", buildJsonTab());
        tabs.addChangeListener(e -> {
            Component sel = tabs.getSelectedComponent();
            if (tabs.getSelectedIndex() == TAB_JSON) loadJson();
            if (tabs.getSelectedIndex() == TAB_DECODE && sel != null) decode();
            if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
        });

        JPanel p = new JPanel(new BorderLayout());
        p.add(form, BorderLayout.NORTH);
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    public void bind(JTextField tf, Consumer<String> commit) {
        Runnable r = () -> {
            if (loading || msg == null) return;
            String t = tf.getText();
            if (t.equals(tf.getClientProperty("orig"))) return; // untouched: never write defaults into the file
            tf.putClientProperty("orig", t);
            commit.accept(t);
        };
        tf.addActionListener(e -> r.run());
        tf.addFocusListener(
            new FocusAdapter() {
                public void focusLost(FocusEvent e) {
                    r.run();
                }
            }
        );
    }

    public void bad(JComponent c, String text) {
        Toolkit.getDefaultToolkit().beep();
        app.status.setText(text);
        if (c != null) c.requestFocusInWindow();
    }

    // ---------- points tab

    public static final int P_NUM = 0,
        P_BITS = 1,
        P_SIZE = 2,
        P_NAME = 3,
        P_CTYPE = 4,
        P_SIGNED = 5,
        P_ENDIAN = 6,
        P_PARSE = 7,
        P_FMT = 8,
        P_ARG = 9,
        P_IEEE = 10,
        P_DEFAULT = 11,
        P_SIM = 12;

    public JComponent buildPointsTab() {
        pointsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        pointsTable.setSurrendersFocusOnKeystroke(true);
        styleTable(pointsTable);
        RowStyle rs = (t, row, col, sel, comp) -> {
            Map<String, Object> p = pointsModel.pt(row);
            boolean pad = p != null && !isParsed(p);
            if (pad && !sel) comp.setForeground(T.muted());
            Severity s = pointSev.get(row);
            if (s != null && !sel) comp.setBackground(s.bg());
            else if (!sel && rowMatches(p, true)) comp.setBackground(T.tint(T.accent()));
            if (col == P_NAME && !pad) comp.setFont(t.getFont().deriveFont(Font.BOLD));
            if (p != null && !pad && comp instanceof JLabel lb) colorPointCell(t, row, col, p, lb);
            List<Issue> l = pointIssues.get(row);
            if (l != null) comp.setToolTipText(issueTip(l));
            else if (col == P_SIM) comp.setToolTipText("Double-click to edit the simulation");
        };
        pointsTable.setDefaultRenderer(Object.class, new StyledRenderer(rs));
        pointsTable.setDefaultRenderer(String.class, new StyledRenderer(rs));
        pointsTable.setDefaultRenderer(Integer.class, new StyledRenderer(rs));
        pointsTable.setDefaultRenderer(Boolean.class, new StyledBoolRenderer(rs));
        TableColumnModel cm = pointsTable.getColumnModel();
        // the Type column shows what firmware-rs generates, but editing it edits c_type, so start from c_type's name
        cm.getColumn(P_CTYPE).setCellEditor(
            new DefaultCellEditor(editableCombo(RUST_TYPE_CHOICES)) {
                public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int r, int c) {
                    Map<String, Object> p = pointsModel.pt(r);
                    return super.getTableCellEditorComponent(t, p == null ? v : rustType(str(p, "c_type")), sel, r, c);
                }
            }
        );
        cm.getColumn(P_ENDIAN).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(new String[] { "", "big", "little" }))
        );
        cm.getColumn(P_FMT).setCellEditor(new DefaultCellEditor(formatterCombo));
        int[] widths = { 34, 64, 44, 170, 80, 52, 62, 48, 72, 52, 60, 58, 220 };
        for (int i = 0; i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);
        fitColumn(pointsTable, P_NUM, "888"); // three digits: 64 one-bit points is the most a frame holds
        fitColumn(pointsTable, P_BITS, "88–88"); // the widest bit range, such as 48–63
        fitColumn(pointsTable, P_SIZE, "Size");
        pointsTable.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int r = pointsTable.rowAtPoint(e.getPoint()),
                        col = pointsTable.columnAtPoint(e.getPoint());
                    if (e.getClickCount() != 2 || r < 0) return;
                    if (col == P_SIM) editSim(r);
                }
            }
        );
        addFixMenu(pointsTable, r -> pointIssues.getOrDefault(r, List.of()), this::pointMenuItems);

        Bar tb = new Bar();
        tb.add(button("Add point", "Append a new 8-bit point", this::addPoint));
        tb.add(
            button(
                "Insert above",
                "Insert a point above the selected one (field references are renumbered)",
                this::insertPoint
            )
        );
        tb.add(button("Duplicate", null, this::duplicatePoint));
        tb.add(button("Remove", "Remove selected points (field references are renumbered)", this::removePoints));
        tb.add(button("▲", "Move up (field references follow)", () -> movePoint(-1)));
        tb.add(button("▼", "Move down (field references follow)", () -> movePoint(1)));
        tb.sep();
        tb.add(
            button("Publish to MQTT", "Open the field that publishes the selected point, or create one", () -> {
                int r = pointsTable.getSelectedRow();
                if (r >= 0) openOrCreateField(r);
            })
        );
        tb.add(
            button(
                "Pad to byte",
                "Grow the last padding point (or add one) so the message is byte aligned",
                this::padToByte
            )
        );
        autoPadBox.setOpaque(false);
        autoPadBox.setFocusable(false);
        autoPadBox.setSelected(app.prefs.getBoolean("autoPad", true));
        autoPadBox.setToolTipText(
            "<html>When a points edit leaves the message unaligned, resize, add or remove the last padding point" +
                "<br>so the message is the smallest whole number of bytes that holds its points</html>"
        );
        autoPadBox.addActionListener(e -> app.prefs.putBoolean("autoPad", autoPadBox.isSelected()));
        tb.add(autoPadBox);
        tb.add(
            button("Edit sim…", "Edit the selected point's simulation", () -> {
                int r = pointsTable.getSelectedRow();
                if (r >= 0) editSim(r);
            })
        );
        JPanel p = new JPanel(new BorderLayout());
        p.add(tb, BorderLayout.NORTH);
        p.add(new JScrollPane(pointsTable), BorderLayout.CENTER);
        JLabel hint = new MutedLabel(
            "Points are indexed from 1. Topics are edited in the Fields tab; right-click a point for the fields that publish it, or use Publish to MQTT."
        );
        hint.setBorder(new EmptyBorder(6, 10, 6, 10));
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    /** Jump to the field that publishes point r, or create one for it. */
    /** The Points tab's Type: the Rust type firmware-rs generates, or the c_type's Rust name for reserved bits. */
    public static String shownType(Map<String, Object> p) {
        String gen = rustGenType(p);
        return gen.isEmpty() ? rustType(str(p, "c_type")) : gen;
    }

    /** Why firmware-rs picks the point's type (it ignores c_type for integers). */
    public static String typeTip(Map<String, Object> p) {
        String gen = rustGenType(p),
            c = str(p, "c_type");
        String json = c == null || c.isEmpty() ? "No c_type in the JSON." : "In the JSON: \"c_type\": \"" + c + "\".";
        if (gen.isEmpty()) return (
            "<html>Reserved bits: firmware-rs generates no accessor for unparsed or unnamed points.<br>" +
            json +
            "</html>"
        );
        int size = sizeOf(p);
        String why = Boolean.TRUE.equals(p.get("ieee754_f32"))
            ? "ieee754_f32 reads the raw bits as an f32"
            : rustScaled(p)
                ? "its " + formatterKey(p) + " formatter scales it to an f32"
                : "float".equals(c)
                    ? "c_type float makes it an f32"
                    : "bool".equals(c) && size == 1
                        ? "c_type bool on a 1-bit point makes it a bool"
                        : "integers are sized from the bits (" +
                          size +
                          ")" +
                          (Boolean.TRUE.equals(p.get("signed")) ? " and signed" : "") +
                          "; c_type doesn't change them";
        return "<html>firmware-rs generates <b>" + gen + "</b>: " + escHtml(why) + ".<br>" + json + "</html>";
    }

    /** Colors a fields cell: topic levels, the unit, the point references, and muted numbers and notes. */
    public void colorFieldCell(int col, Map<String, Object> f, JLabel lb) {
        switch (col) {
            case 0, 5 -> lb.setForeground(T.muted());
            case 1 -> lb.setText(topicHtml(strOr(f, "name", "")));
            case 2 -> lb.setForeground(T.green());
            case 3 -> {
                String text = lb.getText();
                if (text == null || text.isEmpty()) return;
                String html = escHtml(text)
                    .replaceAll("#(\\d+)", "<font color='" + hx(T.orange()) + "'>#$1</font>")
                    .replace("(missing)", "<font color='" + hx(T.red()) + "'>(missing)</font>");
                lb.setText("<html><nobr>" + html + "</nobr></html>");
            }
            default -> {
            }
        }
    }

    /** Colors a points cell by what it holds (numbers, types, formatter, topics); padding rows stay muted. */
    public void colorPointCell(JTable t, int row, int col, Map<String, Object> p, JLabel lb) {
        switch (col) {
            case P_NUM, P_SIM -> lb.setForeground(T.muted());
            case P_BITS -> {
                lb.setForeground(T.cyan());
                lb.setFont(mono(Font.PLAIN, t.getFont().getSize()));
            }
            case P_SIZE, P_ARG, P_DEFAULT -> lb.setForeground(T.orange());
            case P_FMT -> lb.setForeground(T.cyan());
            case P_ENDIAN -> {
                if ("little".equals(str(p, "endianness"))) lb.setForeground(T.yellow());
            }
            case P_CTYPE -> {
                String c = str(p, "c_type"),
                    gen = rustGenType(p),
                    shown = shownType(p);
                lb.setForeground(typeColor(shown));
                boolean differs = !gen.isEmpty() && c != null && !c.isEmpty() && !rustType(c).equals(gen);
                if (differs && !t.isRowSelected(row)) lb.setText(
                    "<html><font color='" +
                        hx(typeColor(gen)) +
                        "'>" +
                        escHtml(gen) +
                        "</font> <font color='" +
                        hx(T.muted()) +
                        "'>(c_type " +
                        escHtml(c) +
                        ")</font></html>"
                );
                else if (differs) lb.setText(gen + " (c_type " + c + ")");
                lb.setToolTipText(typeTip(p));
            }
            default -> {
            }
        }
    }

    public void openOrCreateField(int r) {
        if (msg == null) return;
        stopEditing(pointsTable);
        Map<String, Object> p = pointsModel.pt(r);
        if (!isParsed(p)) {
            app.status.setText("Padding points aren't published");
            return;
        }
        List<Object> fds = fields();
        for (int j = 0; j < fds.size(); j++) {
            Map<String, Object> f = asMap(fds.get(j));
            if (f == null) continue;
            if (
                containsIdx(listOrEmpty(f.get("values")), r + 1) || strOr(f, "name", "").contains("{" + (r + 1) + "}")
            ) {
                tabs.setSelectedIndex(1);
                selectRow(fieldsTable, j);
                return;
            }
        }
        String nm = strOr(p, "name", "");
        String topic = topicPrefix() + "/" + (nm.isEmpty() ? "point" + (r + 1) : nm);
        List<Object> l = ensureList(msg, "fields");
        l.add(newField(topic, r + 1));
        changed(false);
        tabs.setSelectedIndex(1);
        selectRow(fieldsTable, l.size() - 1);
        app.status.setText("Point " + (r + 1) + " is now published as " + topic + ". Check the unit and doc.");
    }

    /** Move point `from` to insertion slot `slot` (0..n), renumbering every field reference. */
    public void movePointTo(int from, int slot) {
        layoutPanel.animateChange(() -> doMovePointTo(from, slot));
    }

    public void doMovePointTo(int from, int slot) {
        if (msg == null) return;
        List<Object> pts = ensureList(msg, "points");
        int n = pts.size();
        if (from < 0 || from >= n || slot < 0 || slot > n) return;
        int to = slot > from ? slot - 1 : slot;
        if (to == from) return;
        stopEditing(pointsTable);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.remove(Integer.valueOf(from));
        order.add(to, from);
        List<Object> reordered = new ArrayList<>();
        for (int i : order) reordered.add(pts.get(i));
        pts.clear();
        pts.addAll(reordered);
        int[] newPos = new int[n + 1];
        for (int k = 0; k < n; k++) newPos[order.get(k) + 1] = k + 1;
        remapPointRefs(msg, k -> k >= 1 && k <= n ? newPos[k] : k);
        changed(false);
        selectRow(pointsTable, to);
        app.status.setText(
            "Moved " +
                pointLabel(asMap(pts.get(to)), to).replaceFirst("Point \\d+", "point") +
                " to position " +
                (to + 1) +
                "; field references were renumbered"
        );
    }

    public void selectPoint(int i, boolean open) {
        selectRow(pointsTable, i);
        if (open) tabs.setSelectedIndex(0);
    }

    /** Cell editor for a field's points: a checklist popup of the message's points. */
    public final class PointPicker extends AbstractCellEditor implements TableCellEditor {

        public List<Object> result = new ArrayList<>();
        public final JLabel label = new JLabel();
        public int row = -1;

        public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int r, int c) {
            row = r;
            Map<String, Object> f = fieldsModel.fd(r);
            result = new ArrayList<>(listOrEmpty(f == null ? null : f.get("values")));
            label.setText(String.valueOf(v));
            label.setOpaque(true);
            label.setBackground(T.selection());
            label.setForeground(T.bright());
            label.setBorder(new EmptyBorder(0, 8, 0, 8));
            SwingUtilities.invokeLater(() -> show(t, r, c));
            return label;
        }

        public Object getCellEditorValue() {
            return new ArrayList<>(result);
        }

        public void show(JTable t, int r, int c) {
            if (msg == null) return;
            JPopupMenu pm = new JPopupMenu();
            pm.setFocusable(false);
            JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
            list.setOpaque(false);
            list.setBorder(new EmptyBorder(4, 10, 4, 10));
            List<Object> pts = points();
            String self = strOr(fieldsModel.fd(r), "name", "");
            for (int i = 0; i < pts.size(); i++) {
                Map<String, Object> p = asMap(pts.get(i));
                if (!isParsed(p)) continue;
                final int k = i + 1;
                List<String> other = fieldsReferencing(msg, k);
                other.remove(self);
                String nm = strOr(p, "name", "");
                JCheckBox cb = new JCheckBox(
                    "<html><b>#" +
                        k +
                        "</b>&nbsp; " +
                        escHtml(nm.isEmpty() ? "unnamed" : nm) +
                        "&nbsp;&nbsp;<font color='" +
                        hx(T.muted()) +
                        "'>" +
                        sizeOf(p) +
                        " bits" +
                        (other.isEmpty()
                            ? ""
                            : ", also in " +
                                  escHtml(other.getFirst()) +
                                  (other.size() > 1 ? " +" + (other.size() - 1) : "")) +
                        "</font></html>"
                );
                cb.setFocusable(false);
                cb.setOpaque(false);
                cb.setSelected(containsIdx(result, k));
                cb.addActionListener(e -> {
                    if (cb.isSelected()) {
                        if (!containsIdx(result, k)) result.add(BigDecimal.valueOf(k));
                    } else result.removeIf(o -> {
                        Long l = longVal(o);
                        return l != null && l == k;
                    });
                });
                list.add(cb);
            }
            if (list.getComponentCount() == 0) list.add(new MutedLabel("This message has no parsed points yet"));
            JPanel wrap = new JPanel(new BorderLayout(0, 4));
            wrap.setOpaque(false);
            JLabel head = new MutedLabel("Points this topic publishes, in the order you tick them");
            head.setBorder(new EmptyBorder(6, 12, 2, 12));
            wrap.add(head, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(list);
            sp.setBorder(null);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            Dimension pref = list.getPreferredSize();
            sp.setPreferredSize(new Dimension(Math.max(300, pref.width + 24), Math.min(pref.height + 6, 340)));
            wrap.add(sp, BorderLayout.CENTER);
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            south.setOpaque(false);
            JButton done = button("Done", null, () -> pm.setVisible(false));
            done.putClientProperty("primary", Boolean.TRUE);
            south.add(done);
            wrap.add(south, BorderLayout.SOUTH);
            pm.add(wrap);
            pm.addPopupMenuListener(
                new PopupMenuListener() {
                    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

                    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                        SwingUtilities.invokeLater(PointPicker.this::finish);
                    }

                    public void popupMenuCanceled(PopupMenuEvent e) {}
                }
            );
            Rectangle rc = t.getCellRect(r, c, true);
            pm.show(t, rc.x, rc.y + rc.height);
        }

        public void finish() {
            if (fieldsTable.isEditing() && fieldsTable.getCellEditor() == this) stopCellEditing();
            else if (row >= 0) fieldsModel.setValueAt(new ArrayList<>(result), row, 3);
        }
    }

    public void themeChanged() {
        if (msg != null) {
            refreshSummary();
            issuesChanged();
            loadFieldDetail();
            layoutPanel.repaint();
        } else if (placeholderRefresh != null) placeholderRefresh.run();
    }

    public boolean rowMatches(Map<String, Object> m, boolean point) {
        if (highlight.isEmpty() || m == null) return false;
        String q = highlight.toLowerCase(Locale.ROOT);
        if (point) return (highlightMode == 0 || highlightMode == 4) && lc(str(m, "name")).contains(q);
        if ((highlightMode == 0 || highlightMode == 3) && lc(str(m, "name")).contains(q)) return true;
        if ((highlightMode == 0 || highlightMode == 5) && lc(str(m, "unit")).equals(q)) return true;
        return (
            (highlightMode == 0 || highlightMode == 6) &&
            (lc(str(m, "doc")).contains(q) || lc(str(m, "desc")).contains(q))
        );
    }

    public static String issueTip(List<Issue> l) {
        StringBuilder sb = new StringBuilder("<html><body style='width:380px'>");
        for (Issue is : l)
            sb.append("<font color='")
                .append(hx(is.sev().color()))
                .append("'>")
                .append(is.sev().label)
                .append("</font>: ")
                .append(escHtml(is.text()))
                .append("<br>");
        return sb.append("</body></html>").toString();
    }

    public List<Object> points() {
        return msg == null ? List.of() : listOrEmpty(msg.get("points"));
    }

    public List<Object> fields() {
        return msg == null ? List.of() : listOrEmpty(msg.get("fields"));
    }

    public final class PointsModel extends AbstractTableModel {

        public final String[] cols = {
            "#",
            "Bits",
            "Size",
            "Name",
            "Type",
            "Signed",
            "Endian",
            "Parse",
            "Formatter",
            "Arg",
            "IEEE754",
            "Default",
            "Simulation",
        };

        public Map<String, Object> pt(int r) {
            List<Object> l = points();
            return r >= 0 && r < l.size() ? asMap(l.get(r)) : null;
        }

        public int getRowCount() {
            return points().size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case P_NUM, P_SIZE -> Integer.class;
                case P_SIGNED, P_PARSE, P_IEEE -> Boolean.class;
                default -> String.class;
            };
        }

        public boolean isCellEditable(int r, int c) {
            return pt(r) != null && c >= P_SIZE && c <= P_DEFAULT;
        }

        public Object getValueAt(int r, int c) {
            Map<String, Object> p = pt(r);
            if (c == P_NUM) return r + 1;
            if (p == null) return c == P_NAME ? "(not an object)" : null;
            return switch (c) {
                case P_BITS -> {
                    int[] off = offsets(points());
                    int sz = sizeOf(p);
                    yield sz <= 1 ? String.valueOf(off[r]) : off[r] + "–" + (off[r] + sz - 1);
                }
                case P_SIZE -> longVal(p.get("size")) instanceof Long l ? (Object) (int) (long) l : null;
                case P_NAME -> strOr(p, "name", "");
                case P_CTYPE -> shownType(p); // what firmware-rs generates; edits still write c_type
                case P_SIGNED -> Boolean.TRUE.equals(p.get("signed"));
                case P_ENDIAN -> strOr(p, "endianness", "");
                case P_PARSE -> isParsed(p);
                case P_FMT -> formatterKey(p);
                case P_ARG -> formatterArg(p);
                case P_IEEE -> Boolean.TRUE.equals(p.get("ieee754_f32"));
                case P_DEFAULT -> plain(num(p, "default"));
                case P_SIM -> simSummary(p.get("sim"));
                default -> null;
            };
        }

        public void setValueAt(Object v, int r, int c) {
            Map<String, Object> p = pt(r);
            if (p == null) return;
            // (Type shows the generated type but edits c_type, so it compares with c_type in its own case below)
            if (c != P_CTYPE && Objects.equals(v instanceof String str ? str.strip() : v, getValueAt(r, c))) return;
            String s = v == null ? "" : String.valueOf(v).strip();
            BigDecimal num = s.isEmpty() ? null : parseNum(s);
            if ((c == P_ARG || c == P_DEFAULT) && !s.isEmpty() && num == null) {
                bad(null, (c == P_ARG ? "Formatter arg" : "default") + " must be a number");
                return;
            }
            Integer newSize = v instanceof Integer n && n >= 1 ? n : null;
            if (c == P_SIZE && newSize == null) {
                bad(null, "Size must be a whole number of bits, 1 or more");
                return;
            }
            boolean ch = switch (c) {
                case P_SIZE -> putNum(p, "size", BigDecimal.valueOf(newSize));
                case P_NAME -> putStr(p, "name", s, true);
                case P_CTYPE -> !cTypeFor(s).equals(strOr(p, "c_type", "")) && putStr(p, "c_type", cTypeFor(s), true);
                case P_SIGNED -> putBool(p, "signed", Boolean.TRUE.equals(v), false);
                case P_ENDIAN -> putStr(p, "endianness", s, true);
                case P_PARSE -> putBool(p, "parse", Boolean.TRUE.equals(v), true);
                case P_FMT -> setFormatterKey(p, s);
                case P_ARG -> setFormatterArg(p, num);
                case P_IEEE -> putBool(p, "ieee754_f32", Boolean.TRUE.equals(v), false);
                case P_DEFAULT -> putNum(p, "default", num);
                default -> false;
            };
            if (ch) changed(false);
        }
    }

    public boolean setFormatterKey(Map<String, Object> p, String k) {
        if (p.containsKey("format") && !p.containsKey("formatter")) return putStr(p, "format", k, true);
        if (k.isEmpty()) {
            if (p.containsKey("formatter")) {
                p.remove("formatter");
                return true;
            }
            return false;
        }
        Map<String, Object> f = asMap(p.get("formatter"));
        if (f == null) {
            f = new LinkedHashMap<>();
            p.put("formatter", f);
        }
        if (k.equals(f.get("key"))) return false;
        f.put("key", k);
        return true;
    }

    public boolean setFormatterArg(Map<String, Object> p, BigDecimal b) {
        Map<String, Object> f = asMap(p.get("formatter"));
        if (f == null) {
            if (b == null) return false;
            if (p.containsKey("format")) {
                bad(null, "This point uses the legacy \"format\" string; clear it before adding a formatter arg");
                return false;
            }
            f = new LinkedHashMap<>();
            f.put("key", "divide");
            p.put("formatter", f);
        }
        return putNum(f, "arg", b);
    }

    public void addPoint() {
        if (msg == null) return;
        stopEditing(pointsTable);
        List<Object> pts = ensureList(msg, "points");
        if (autoPadBox.isSelected() && Fixes.trailingPadding(pts) != null) {
            // keep the padding last: the new point goes in front of it, and auto padding shrinks it
            int at = pts.size() - 1;
            pts.add(at, newPoint("point" + (pts.size() + 1)));
            final int k1 = at + 1;
            remapPointRefs(msg, k -> k >= k1 ? k + 1 : k);
            changed(false);
            selectRow(pointsTable, at);
            return;
        }
        pts.add(newPoint("point" + (pts.size() + 1)));
        changed(false);
        selectRow(pointsTable, pts.size() - 1);
    }

    public void insertPoint() {
        if (msg == null) return;
        stopEditing(pointsTable);
        int r = pointsTable.getSelectedRow();
        if (r < 0) {
            addPoint();
            return;
        }
        List<Object> pts = ensureList(msg, "points");
        pts.add(r, newPoint("point" + (pts.size() + 1)));
        final int at = r + 1;
        remapPointRefs(msg, k -> k >= at ? k + 1 : k);
        changed(false);
        selectRow(pointsTable, r);
    }

    public void duplicatePoint() {
        int r = pointsTable.getSelectedRow();
        if (msg == null || r < 0) return;
        stopEditing(pointsTable);
        List<Object> pts = ensureList(msg, "points");
        pts.add(r + 1, Json.deepCopy(pts.get(r)));
        final int at = r + 2;
        remapPointRefs(msg, k -> k >= at ? k + 1 : k);
        changed(false);
        selectRow(pointsTable, r + 1);
    }

    public void removePoints() {
        int[] rows = pointsTable.getSelectedRows();
        if (msg == null || rows.length == 0) return;
        stopEditing(pointsTable);
        Set<String> refs = new LinkedHashSet<>();
        for (int r : rows) refs.addAll(fieldsReferencing(msg, r + 1));
        String q = "Remove " + rows.length + " point(s)?";
        if (!refs.isEmpty()) q +=
            "\nThese fields reference them and will lose those references:\n   " + String.join("\n   ", refs);
        if (
            JOptionPane.showConfirmDialog(app, q, "Remove points", JOptionPane.OK_CANCEL_OPTION) !=
            JOptionPane.OK_OPTION
        ) return;
        List<Object> pts = ensureList(msg, "points");
        Arrays.sort(rows);
        for (int i = rows.length - 1; i >= 0; i--) {
            final int k1 = rows[i] + 1;
            pts.remove(rows[i]);
            remapPointRefs(msg, k -> k == k1 ? -1 : (k > k1 ? k - 1 : k));
        }
        changed(false);
        selectRow(pointsTable, Math.min(rows[0], pts.size() - 1));
    }

    public void movePoint(int dir) {
        int r = pointsTable.getSelectedRow();
        if (msg == null || r < 0) return;
        int t = r + dir;
        if (t < 0 || t >= points().size()) return;
        movePointTo(r, dir > 0 ? t + 1 : t);
    }

    public void padToByte() {
        if (msg == null) return;
        stopEditing(pointsTable);
        List<Object> pts = ensureList(msg, "points");
        int total = offsets(pts)[pts.size()];
        int pad = Fixes.padToByte(msg);
        if (pad == 0) {
            app.status.setText("Already byte aligned (" + total + " bits)");
            return;
        }
        changed(false);
        selectRow(pointsTable, pts.size() - 1);
        app.status.setText("Added " + pad + " padding bit(s); the message is now " + (total + pad) + " bits");
    }

    public void editSim(int row) {
        Map<String, Object> p = pointsModel.pt(row);
        if (p == null) return;
        stopEditing(pointsTable);
        Map<String, Object> cur = asMap(p.get("sim"));
        JDialog d = new JDialog(app, "Simulation for " + pointLabel(p, row), true);
        JRadioButton rNone = new JRadioButton("None"),
            rSweep = new JRadioButton("Sweep (range)"),
            rEnum = new JRadioButton("Enum (choices)");

        ButtonGroup bg = new ButtonGroup();
        bg.add(rNone);
        bg.add(rSweep);
        bg.add(rEnum);
        JTextField fMin = new JTextField(8),
            fMax = new JTextField(8),
            fIncMin = new JTextField(8),
            fIncMax = new JTextField(8);
        JCheckBox fRound = new JCheckBox("Round to whole numbers");
        DefaultTableModel em = new DefaultTableModel(new Object[] { "Value", "Probability" }, 0);
        JTable et = new JTable(em);
        styleTable(et);
        et.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        ToneLabel sumLabel = new ToneLabel(" ");
        Runnable updSum = () -> {
            BigDecimal s = BigDecimal.ZERO;
            boolean ok = true;
            for (int i = 0; i < em.getRowCount(); i++) {
                BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                if (b == null) ok = false;
                else s = s.add(b);
            }
            boolean one = ok && s.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) <= 0;
            sumLabel.set(
                "Probabilities add up to " + (ok ? plain(s) : "?") + (one ? "" : ". They must add up to 1"),
                one ? ToneLabel.OK : ToneLabel.ERR
            );
        };
        em.addTableModelListener(e -> updSum.run());

        if (cur != null && cur.containsKey("options")) {
            rEnum.setSelected(true);
            for (Object o : listOrEmpty(cur.get("options"))) {
                List<Object> pr = asList(o);
                if (pr != null && pr.size() == 2) em.addRow(new Object[] {
                    Json.writeCompact(pr.getFirst()),
                    Json.writeCompact(pr.getLast()),
                });
            }
        } else if (cur != null) {
            rSweep.setSelected(true);
            fMin.setText(plain(num(cur, "min")));
            fMax.setText(plain(num(cur, "max")));
            fIncMin.setText(plain(num(cur, "inc_min")));
            fIncMax.setText(plain(num(cur, "inc_max")));
            fRound.setSelected(Boolean.TRUE.equals(cur.get("round")));
        } else rNone.setSelected(true);
        if (em.getRowCount() == 0) {
            em.addRow(new Object[] { "0", "0.5" });
            em.addRow(new Object[] { "1", "0.5" });
        }
        updSum.run();

        JPanel sweepP = new JPanel(new GridLayout(0, 2, 6, 4));
        sweepP.add(new JLabel("Min value"));
        sweepP.add(fMin);
        sweepP.add(new JLabel("Max value"));
        sweepP.add(fMax);
        sweepP.add(new JLabel("Min increment"));
        sweepP.add(fIncMin);
        sweepP.add(new JLabel("Max increment"));
        sweepP.add(fIncMax);
        sweepP.add(fRound);
        JPanel enumP = new JPanel(new BorderLayout(0, 4));
        JScrollPane esp = new JScrollPane(et);
        esp.setPreferredSize(new Dimension(320, 150));
        enumP.add(esp, BorderLayout.CENTER);
        JPanel eb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        eb.add(button("Add", null, () -> em.addRow(new Object[] { String.valueOf(em.getRowCount()), "0" })));
        eb.add(
            button("Remove", null, () -> {
                stopEditing(et);
                int r = et.getSelectedRow();
                if (r >= 0) em.removeRow(r);
            })
        );
        eb.add(
            button("Equal split", "Give every choice the same probability", () -> {
                stopEditing(et);
                int n = em.getRowCount();
                if (n == 0) return;
                BigDecimal each = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.DOWN);
                BigDecimal rest = BigDecimal.ONE.subtract(each.multiply(BigDecimal.valueOf(n - 1)));
                for (int i = 0; i < n; i++) em.setValueAt(plain(i == n - 1 ? rest : each), i, 1);
            })
        );
        eb.add(
            button("Normalize", "Scale probabilities so they add up to 1", () -> {
                stopEditing(et);
                BigDecimal s = BigDecimal.ZERO;
                for (int i = 0; i < em.getRowCount(); i++) {
                    BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                    if (b != null) s = s.add(b);
                }
                if (s.signum() == 0) return;
                BigDecimal acc = BigDecimal.ZERO;
                for (int i = 0; i < em.getRowCount(); i++) {
                    BigDecimal b = parseNum(String.valueOf(em.getValueAt(i, 1)));
                    if (b == null) b = BigDecimal.ZERO;
                    BigDecimal nb =
                        i == em.getRowCount() - 1
                            ? BigDecimal.ONE.subtract(acc)
                            : b.divide(s, 6, java.math.RoundingMode.HALF_UP);
                    acc = acc.add(nb);
                    em.setValueAt(plain(nb), i, 1);
                }
            })
        );
        JPanel south = new JPanel(new BorderLayout());
        south.add(eb, BorderLayout.NORTH);
        south.add(sumLabel, BorderLayout.SOUTH);
        enumP.add(south, BorderLayout.SOUTH);

        CardLayout cl = new CardLayout();
        JPanel opts = new JPanel(cl);
        opts.add(new JLabel("This point is not simulated."), "none");
        opts.add(sweepP, "sweep");
        opts.add(enumP, "enum");
        ActionListener sw = e -> cl.show(opts, rSweep.isSelected() ? "sweep" : rEnum.isSelected() ? "enum" : "none");
        rNone.addActionListener(sw);
        rSweep.addActionListener(sw);
        rEnum.addActionListener(sw);
        sw.actionPerformed(null);

        double[] range = physRange(p);
        JLabel rangeL = new MutedLabel(
            range == null
                ? "Values are in physical units (after the formatter)."
                : "Representable range after the formatter: " + fmtD(range[0]) + " … " + fmtD(range[1])
        );

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(rNone);
        top.add(rSweep);
        top.add(rEnum);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(10, 12, 10, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(opts, BorderLayout.CENTER);
        content.add(rangeL, BorderLayout.SOUTH);

        JButton ok = new JButton("Apply");
        ok.putClientProperty("primary", Boolean.TRUE);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            stopEditing(et);
            Object newSim;
            if (rNone.isSelected()) newSim = null;
            else if (rSweep.isSelected()) {
                BigDecimal a = parseNum(fMin.getText()),
                    b = parseNum(fMax.getText()),
                    c1 = parseNum(fIncMin.getText()),
                    c2 = parseNum(fIncMax.getText());
                if (a == null || b == null || c1 == null || c2 == null) {
                    JOptionPane.showMessageDialog(d, "Enter numbers for min, max and both increments.");
                    return;
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("min", a);
                s.put("max", b);
                s.put("inc_min", c1);
                s.put("inc_max", c2);
                s.put("round", fRound.isSelected());
                newSim = s;
            } else {
                List<Object> optsL = new ArrayList<>();
                for (int i = 0; i < em.getRowCount(); i++) {
                    BigDecimal v = parseNum(String.valueOf(em.getValueAt(i, 0))),
                        f = parseNum(String.valueOf(em.getValueAt(i, 1)));
                    if (v == null || f == null) {
                        JOptionPane.showMessageDialog(d, "Row " + (i + 1) + " needs a numeric value and probability.");
                        return;
                    }
                    optsL.add(new ArrayList<Object>(Arrays.asList(v, f)));
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("options", optsL);
                newSim = s;
            }
            boolean ch;
            if (newSim == null) {
                ch = p.containsKey("sim");
                p.remove("sim");
            } else {
                ch = !p.containsKey("sim") || !Json.write(p.get("sim")).equals(Json.write(newSim));
                p.put("sim", newSim);
            }
            d.dispose();
            if (ch) changed(false);
        });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btns.add(cancel);
        btns.add(ok);
        d.getRootPane().setDefaultButton(ok);
        d.add(content, BorderLayout.CENTER);
        d.add(btns, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    // ---------- fields tab

    public JComponent buildFieldsTab() {
        fieldsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        fieldsTable.setSurrendersFocusOnKeystroke(true);
        addFixMenu(fieldsTable, r -> fieldIssues.getOrDefault(r, List.of()), this::fieldMenuItems);
        styleTable(fieldsTable);
        RowStyle rs = (t, row, col, sel, comp) -> {
            Map<String, Object> f = fieldsModel.fd(row);
            Severity s = fieldSev.get(row);
            if (s != null && !sel) comp.setBackground(s.bg());
            else if (!sel && rowMatches(f, false)) comp.setBackground(T.tint(T.accent()));
            List<Issue> l = fieldIssues.get(row);
            if (l != null) comp.setToolTipText(issueTip(l));
            else if (col == 3 && f != null) comp.setToolTipText(valuesTip(f));
            if (col == 1) comp.setFont(mono(Font.PLAIN, t.getFont().getSize()));
            if (f != null && comp instanceof JLabel lb) colorFieldCell(col, f, lb);
            if (col == 3 && f != null && listOrEmpty(f.get("values")).isEmpty() && !sel) comp.setForeground(T.yellow());
        };
        fieldsTable.setDefaultRenderer(Object.class, new StyledRenderer(rs));
        fieldsTable.setDefaultRenderer(String.class, new StyledRenderer(rs));
        fieldsTable.setDefaultRenderer(Integer.class, new StyledRenderer(rs));
        TableColumnModel cm = fieldsTable.getColumnModel();
        cm.getColumn(2).setCellEditor(new DefaultCellEditor(unitCombo));
        // topics: suggestions from the spec's existing topic levels as you type
        JTextField topicEditor = new JTextField();
        topicEditor.setFont(mono(Font.PLAIN, fieldsTable.getFont().getSize()));
        Suggest.attach(topicEditor, app::topicSuggestions);
        cm.getColumn(1).setCellEditor(new DefaultCellEditor(topicEditor));
        cm.getColumn(3).setCellEditor(new PointPicker());
        int[] widths = { 34, 320, 60, 190, 240, 200 };
        for (int i = 0; i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);
        fitColumn(fieldsTable, 0, "888");
        fieldsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadFieldDetail();
        });
        fieldsTable
            .getColumnModel()
            .getSelectionModel()
            .addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && !fieldDetailKey().equals(detailKey)) loadFieldDetail();
            });

        fieldDesc.setLineWrap(true);
        fieldDesc.setWrapStyleWord(true);
        fieldDesc.addFocusListener(
            new FocusAdapter() {
                public void focusLost(FocusEvent e) {
                    commitFieldDetail();
                }
            }
        );

        Bar tb = new Bar();
        tb.add(button("Add field", null, this::addField));
        tb.add(button("Duplicate", null, this::duplicateField));
        tb.add(button("Remove", null, this::removeFields));
        tb.add(button("▲", "Move up", () -> moveField(-1)));
        tb.add(button("▼", "Move down", () -> moveField(1)));
        tb.sep();
        tb.add(
            button(
                "Add fields for unused points",
                "Create one field per parsed point that no field publishes yet",
                this::generateFields
            )
        );

        JPanel detail = new JPanel(new BorderLayout(0, 6));
        detail.setBorder(new EmptyBorder(8, 10, 8, 10));
        detail.add(fieldMapping, BorderLayout.NORTH);
        JPanel dRow = new JPanel(new BorderLayout(8, 0));
        fieldDetailLabel.setPreferredSize(fieldDetailLabel.getPreferredSize());
        dRow.add(fieldDetailLabel, BorderLayout.WEST);
        dRow.add(new JScrollPane(fieldDesc), BorderLayout.CENTER);
        detail.add(dRow, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout());
        p.add(tb, BorderLayout.NORTH);
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(fieldsTable), detail);
        sp.setResizeWeight(0.8);
        sp.setBorder(null);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    public String valuesTip(Map<String, Object> f) {
        StringBuilder sb = new StringBuilder("<html>");
        List<Object> pts = points();
        for (Object v : listOrEmpty(f.get("values"))) {
            Long k = longVal(v);
            if (k == null || k < 1 || k > pts.size()) {
                sb.append(escHtml(String.valueOf(v))).append(": (invalid)<br>");
                continue;
            }
            Map<String, Object> p = asMap(pts.get((int) (k - 1)));
            sb.append(k)
                .append(": ")
                .append(escHtml(strOr(p, "name", "?")))
                .append(" (")
                .append(sizeOf(p))
                .append(" bits)<br>");
        }
        return sb.append("</html>").toString();
    }

    public final class FieldsModel extends AbstractTableModel {

        public final String[] cols = { "#", "Topic", "Unit", "Maps to points", "Doc", "Notes (desc)" };

        public Map<String, Object> fd(int r) {
            List<Object> l = fields();
            return r >= 0 && r < l.size() ? asMap(l.get(r)) : null;
        }

        public int getRowCount() {
            return fields().size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 0 ? Integer.class : String.class;
        }

        public boolean isCellEditable(int r, int c) {
            return c > 0 && fd(r) != null;
        }

        public Object getValueAt(int r, int c) {
            if (c == 0) return r + 1;
            Map<String, Object> f = fd(r);
            if (f == null) return c == 1 ? "(not an object)" : "";
            return switch (c) {
                case 1 -> strOr(f, "name", "");
                case 2 -> strOr(f, "unit", "");
                case 3 -> {
                    StringBuilder sb = new StringBuilder();
                    List<Object> pts = points();
                    for (Object v : listOrEmpty(f.get("values"))) {
                        if (!sb.isEmpty()) sb.append(", ");
                        Long k = longVal(v);
                        if (k == null || k < 1 || k > pts.size()) {
                            sb.append(Json.writeCompact(v)).append(" (missing)");
                            continue;
                        }
                        String nm = strOr(asMap(pts.get((int) (k - 1))), "name", "");
                        sb.append('#')
                            .append(k)
                            .append(nm.isEmpty() ? "" : " " + nm);
                    }
                    yield sb.isEmpty() ? "choose points…" : sb.toString();
                }
                case 4 -> strOr(f, "doc", "");
                default -> strOr(f, "desc", "");
            };
        }

        public void setValueAt(Object v, int r, int c) {
            Map<String, Object> f = fd(r);
            if (f == null) return;
            String s = v == null ? "" : String.valueOf(v);
            if (s.strip().equals(getValueAt(r, c))) return;
            List<Object> newValues = new ArrayList<>();
            if (c == 3) {
                if (v instanceof List<?> list) newValues.addAll(list);
                else for (String tok : s.split("[,\\s]+")) {
                    if (tok.isEmpty()) continue;
                    Integer k = tryInt(tok);
                    if (k == null) {
                        bad(null, "Points must be 1-based point numbers separated by commas, e.g. 1, 2, 3");
                        return;
                    }
                    newValues.add(BigDecimal.valueOf(k));
                }
            }
            boolean ch = switch (c) {
                case 1 -> putStr(f, "name", s.strip(), false);
                case 2 -> putStr(f, "unit", s.strip(), false);
                case 3 -> {
                    Object cur = f.get("values");
                    if (cur != null && Json.write(cur).equals(Json.write(newValues))) yield false;
                    f.put("values", newValues);
                    yield true;
                }
                case 4 -> putStr(f, "doc", s.strip(), false);
                case 5 -> putStr(f, "desc", s.strip(), false);
                default -> false;
            };
            if (ch) changed(false);
        }
    }

    public void loadFieldDetail() {
        commitFieldDetail();
        int r = fieldsTable.getSelectedRow();
        detailField = fieldsModel.fd(r);
        detailKey = fieldDetailKey();
        fieldDetailLabel.setText(detailKey.equals("doc") ? "Doc" : "Notes (desc)");
        detailLoaded = detailField == null ? "" : strOr(detailField, detailKey, "");
        if (detailField == null) {
            fieldMapping.setText(" ");
            fieldDesc.setText("");
            fieldDesc.setEnabled(false);
            return;
        }
        fieldDesc.setEnabled(true);
        fieldDesc.setText(strOr(detailField, detailKey, ""));
        fieldDesc.setCaretPosition(0);
        StringBuilder sb = new StringBuilder("<html><b>Publishes:</b> ");
        List<Object> pts = points();
        int n = 0;
        for (Object v : listOrEmpty(detailField.get("values"))) {
            Long k = longVal(v);
            if (n++ > 0) sb.append(", &nbsp;");
            if (k == null || k < 1 || k > pts.size()) {
                sb.append("<font color='")
                    .append(hx(T.red()))
                    .append("'>")
                    .append(escHtml(Json.writeCompact(v)))
                    .append(" (no such point)</font>");
                continue;
            }
            Map<String, Object> p = asMap(pts.get((int) (k - 1)));
            sb.append('#')
                .append(k)
                .append(' ')
                .append(escHtml(strOr(p, "name", "?")))
                .append(" <font color='")
                .append(hx(T.muted()))
                .append("'>(")
                .append(sizeOf(p))
                .append(" bits");
            if (p != null && !formatterKey(p).isEmpty()) sb.append(", ")
                .append(escHtml(formatterKey(p)))
                .append(' ')
                .append(formatterArg(p));
            sb.append(")</font>");
        }
        if (n == 0) sb.append("<font color='")
            .append(hx(T.yellow()))
            .append("'>nothing yet. Click the Maps to points cell to choose.</font>");
        fieldMapping.setText(sb.append("</html>").toString());
    }

    public void commitFieldDetail() {
        if (detailField == null || loading) return;
        String t = fieldDesc.getText().strip();
        if (t.equals(detailLoaded.strip()) || t.equals(strOr(detailField, detailKey, ""))) return;
        detailLoaded = t;
        if (putStr(detailField, detailKey, t, false)) {
            changed(false);
            fieldsTable.repaint();
        }
    }

    /** Which key the detail box should show for the selected Fields cell: the Doc column shows doc, any other desc. */
    public String fieldDetailKey() {
        int c = fieldsTable.getSelectedColumn();
        return c < 0 ? detailKey : c == 4 ? "doc" : "desc";
    }

    public String topicPrefix() {
        String key = str(msg, "key");
        if (key != null && !key.isEmpty()) return "Calypso/Bidir/State/" + key;
        List<String[]> segs = new ArrayList<>();
        for (Object o : fields()) {
            String nm = str(asMap(o), "name");
            if (nm != null && nm.contains("/")) segs.add(nm.split("/"));
        }
        if (segs.isEmpty()) return (
            app.predominantNode(file) + "/" + strOr(msg, "desc", "Message").replaceAll("[^A-Za-z0-9_]", "")
        );
        int len = segs.getFirst().length - 1;
        for (String[] s : segs) {
            len = Math.min(len, s.length - 1);
            for (int i = 0; i < len; i++) if (!s[i].equals(segs.getFirst()[i])) {
                len = i;
                break;
            }
        }
        if (len <= 0) return app.predominantNode(file);
        return String.join("/", Arrays.copyOf(segs.getFirst(), len));
    }

    public void addField() {
        if (msg == null) return;
        stopEditing(fieldsTable);
        List<Object> fds = ensureList(msg, "fields");
        fds.add(newField(topicPrefix() + "/new_field", 0));
        changed(false);
        selectRow(fieldsTable, fds.size() - 1);
        fieldsTable.editCellAt(fds.size() - 1, 1);
        Component ed = fieldsTable.getEditorComponent();
        if (ed != null) ed.requestFocusInWindow();
    }

    public void duplicateField() {
        int r = fieldsTable.getSelectedRow();
        if (msg == null || r < 0) return;
        stopEditing(fieldsTable);
        List<Object> fds = ensureList(msg, "fields");
        fds.add(r + 1, Json.deepCopy(fds.get(r)));
        changed(false);
        selectRow(fieldsTable, r + 1);
    }

    public void removeFields() {
        int[] rows = fieldsTable.getSelectedRows();
        if (msg == null || rows.length == 0) return;
        stopEditing(fieldsTable);
        commitFieldDetail();
        detailField = null;
        List<Object> fds = ensureList(msg, "fields");
        Arrays.sort(rows);
        for (int i = rows.length - 1; i >= 0; i--) fds.remove(rows[i]);
        changed(false);
        selectRow(fieldsTable, Math.min(rows[0], fds.size() - 1));
    }

    public void moveField(int dir) {
        int r = fieldsTable.getSelectedRow();
        if (msg == null || r < 0) return;
        List<Object> fds = ensureList(msg, "fields");
        int t = r + dir;
        if (t < 0 || t >= fds.size()) return;
        stopEditing(fieldsTable);
        commitFieldDetail();
        Collections.swap(fds, r, t);
        changed(false);
        selectRow(fieldsTable, t);
    }

    public void generateFields() {
        if (msg == null) return;
        stopEditing(fieldsTable);
        List<Object> pts = points();
        boolean[] ref = new boolean[pts.size()];
        for (Object o : fields()) {
            Map<String, Object> f = asMap(o);
            if (f == null) continue;
            for (Object v : listOrEmpty(f.get("values"))) {
                Long k = longVal(v);
                if (k != null && k >= 1 && k <= pts.size()) ref[(int) (k - 1)] = true;
            }
            Matcher mm = PLACEHOLDER_NUM.matcher(strOr(f, "name", ""));
            while (mm.find()) {
                int k = Integer.parseInt(mm.group(1));
                if (k >= 1 && k <= pts.size()) ref[k - 1] = true;
            }
        }
        String prefix = topicPrefix();
        List<Object> fds = ensureList(msg, "fields");
        int added = 0;
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            if (!isParsed(p) || ref[i]) continue;
            String nm = strOr(p, "name", "");
            fds.add(newField(prefix + "/" + (nm.isEmpty() ? "point" + (i + 1) : nm), i + 1));
            added++;
        }
        if (added == 0) {
            app.status.setText("Every parsed point is already published by a field");
            return;
        }
        changed(false);
        app.status.setText("Added " + added + " field(s) under " + prefix + "/ — review the names, units and docs");
    }

    // ---------- message families

    public final JPanel familyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    public final JComboBox<String> familyCombo = new JComboBox<>();
    public final ToneLabel familyInfo = new ToneLabel(" ");
    public List<Map<String, Object>> familyMembers = new ArrayList<>();

    public JComponent buildFamilyRow() {
        familyRow.setOpaque(false);
        familyRow.add(new MutedLabel("Family"));
        familyRow.add(familyCombo);
        familyRow.add(
            button(
                "Add member…",
                "Copy this message as a new member with its own name and the next free ID",
                this::addFamilyMember
            )
        );
        familyRow.add(
            button(
                "Copy layout to family…",
                "Give other members this message's points and fields, keeping their own names",
                this::copyLayoutToFamily
            )
        );
        familyRow.add(familyInfo);
        familyCombo.setToolTipText("Messages whose topics differ only in one level. Pick one to open it.");
        familyCombo.addActionListener(e -> {
            if (loading || msg == null) return;
            int i = familyCombo.getSelectedIndex();
            if (i >= 0 && i < familyMembers.size() && familyMembers.get(i) != msg) app.selectMessage(
                file,
                familyMembers.get(i)
            );
        });
        familyRow.setVisible(false);
        return familyRow;
    }

    public static String replaceWord(String s, String from, String to) {
        if (s == null || from == null || from.isEmpty()) return s;
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(from) + "(?![A-Za-z0-9_])")
            .matcher(s)
            .replaceAll(Matcher.quoteReplacement(to));
    }

    public static void renameInField(Map<String, Object> fd, int seg, String from, String to) {
        if (fd == null) return;
        String n = str(fd, "name");
        if (n != null) {
            String[] parts = n.split("/", -1);
            if (seg < parts.length && parts[seg].equals(from)) {
                parts[seg] = to;
                fd.put("name", String.join("/", parts));
            }
        }
        for (String k : new String[] { "doc", "desc" })
            if (fd.get(k) instanceof String text) fd.put(k, replaceWord(text, from, to));
    }

    /** src's points and fields, renamed for target: what "copy layout" would give target. */
    public static Map<String, Object> layoutFor(Map<String, Object> src, Map<String, Object> target, int seg) {
        String from = memberToken(src, seg),
            to = memberToken(target, seg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", Json.deepCopy(src.containsKey("points") ? src.get("points") : new ArrayList<>()));
        List<Object> fds = Json.deepCopy(src.containsKey("fields") ? asList(src.get("fields")) : new ArrayList<>());
        if (from != null && to != null) for (Object o : fds) renameInField(asMap(o), seg, from, to);
        out.put("fields", fds);
        return out;
    }

    /** Short description of what copying `lay` onto `s` would change, or null if nothing. */
    public static String describeDiff(Map<String, Object> s, Map<String, Object> lay) {
        List<String> parts = new ArrayList<>();
        for (String what : new String[] { "points", "fields" }) {
            List<Object> a = listOrEmpty(s.get(what)),
                b = listOrEmpty(lay.get(what));
            String one = what.equals("points") ? "point" : "field";
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                if (i >= a.size()) {
                    parts.add("adds " + one + " #" + (i + 1));
                    continue;
                }
                if (i >= b.size()) {
                    parts.add("removes " + one + " #" + (i + 1));
                    continue;
                }
                Map<String, Object> x = asMap(a.get(i)),
                    y = asMap(b.get(i));
                if (Json.write(a.get(i)).equals(Json.write(b.get(i)))) continue;
                if (x == null || y == null) {
                    parts.add(one + " #" + (i + 1));
                    continue;
                }
                Set<String> keys = new LinkedHashSet<>(x.keySet());
                keys.addAll(y.keySet());
                List<String> changedKeys = new ArrayList<>();
                for (String k : keys) if (!Json.write(x.get(k)).equals(Json.write(y.get(k)))) changedKeys.add(k);
                parts.add(one + " #" + (i + 1) + " " + String.join(", ", changedKeys));
            }
        }
        if (parts.isEmpty()) return null;
        String t = String.join("; ", parts);
        return t.length() > 160 ? t.substring(0, 157) + "…" : t;
    }

    public void refreshFamily() {
        Family fam = msg == null ? null : app.families.get(msg);
        familyRow.setVisible(fam != null);
        if (fam == null) return;
        loading = true;
        try {
            familyMembers = new ArrayList<>(fam.members());
            DefaultComboBoxModel<String> mdl = new DefaultComboBoxModel<>();
            for (Map<String, Object> m : familyMembers)
                mdl.addElement(strOr(m, "id", "?") + "  " + memberToken(m, fam.seg()));
            familyCombo.setModel(mdl);
            for (int i = 0; i < familyMembers.size(); i++) if (
                familyMembers.get(i) == msg
            ) familyCombo.setSelectedIndex(i);
        } finally {
            loading = false;
        }
        int differ = 0;
        for (Map<String, Object> s : fam.members())
            if (s != msg && describeDiff(s, layoutFor(msg, s, fam.seg())) != null) differ++;
        familyInfo.set(
            fam.members().size() +
                " messages under " +
                fam.template() +
                (differ == 0 ? ", all with the same layout" : ", " + differ + " laid out differently from this one"),
            differ == 0 ? ToneLabel.MUTED : ToneLabel.WARN
        );
    }

    public void addFamilyMember() {
        Family fam = msg == null ? null : app.families.get(msg);
        if (fam == null) return;
        commitAll();
        String from = memberToken(msg, fam.seg());
        if (from == null) {
            bad(null, "This message's topics no longer match its family, so it can't be copied as a member");
            return;
        }
        Object in = JOptionPane.showInputDialog(
            app,
            "Name for the new member. It replaces \"" + from + "\" in topics, docs and the description.",
            "Add family member",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            from + "2"
        );
        if (in == null) return;
        String name = in.toString().strip();
        if (name.isEmpty() || name.contains("/") || name.contains(" ")) {
            bad(null, "Use one word without / or spaces");
            return;
        }
        for (Map<String, Object> m : fam.members())
            if (name.equals(memberToken(m, fam.seg()))) {
                bad(null, "There's already a member named " + name);
                return;
            }
        Map<String, Object> copy = Json.deepCopy(msg);
        for (Object o : listOrEmpty(copy.get("fields"))) renameInField(asMap(o), fam.seg(), from, name);
        String d = strOr(copy, "desc", "");
        String nd = replaceWord(d, from, name);
        copy.put("desc", nd.equals(d) ? (d + " " + name).strip() : nd);
        long maxId = -1;
        boolean ext = false;
        for (Map<String, Object> m : fam.members()) {
            Long v = parseId(str(m, "id"));
            if (v != null && v > maxId) {
                maxId = v;
                ext = Boolean.TRUE.equals(m.get("is_ext"));
            }
        }
        String nid = app.nextFreeId(maxId, ext || maxId > 0x7FF);
        if (nid != null) copy.put("id", nid);
        SpecFile f = file;
        int at = 0;
        for (Map<String, Object> m : fam.members()) at = Math.max(at, indexOfIdentity(f.messages, m) + 1);
        f.messages.add(at, copy);
        fam.members().add(copy);
        app.families.put(copy, fam);
        app.nextUndoLabel = "add family member " + name;
        app.changed(f, false);
        app.rebuildTree();
        app.selectMessage(f, copy);
        app.status.setText("Added " + name + " as " + str(copy, "id") + ". Check its description and ID.");
    }

    public void copyLayoutToFamily() {
        Family fam = msg == null ? null : app.families.get(msg);
        if (fam == null) return;
        commitAll();
        List<Map<String, Object>> sibs = new ArrayList<>(),
            lays = new ArrayList<>();
        List<JCheckBox> boxes = new ArrayList<>();
        JPanel list = new JPanel(new GridLayout(0, 1, 0, 4));
        list.setOpaque(false);
        for (Map<String, Object> s : fam.members()) {
            if (s == msg) continue;
            Map<String, Object> lay = layoutFor(msg, s, fam.seg());
            String diff = describeDiff(s, lay);
            if (diff == null) continue;
            JCheckBox cb = new JCheckBox(
                "<html><b>" +
                    escHtml(strOr(s, "id", "?") + "  " + memberToken(s, fam.seg())) +
                    "</b>&nbsp;&nbsp;<font color='" +
                    hx(T.muted()) +
                    "'>" +
                    escHtml(diff) +
                    "</font></html>",
                true
            );
            cb.setOpaque(false);
            list.add(cb);
            sibs.add(s);
            lays.add(lay);
            boxes.add(cb);
        }
        if (sibs.isEmpty()) {
            app.status.setText("Every family member already has this layout");
            return;
        }
        String from = Objects.requireNonNullElse(memberToken(msg, fam.seg()), "?");
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(
            new JLabel(
                "<html><body style='width:520px'>Replace the points and fields of the ticked messages with those of <b>" +
                    escHtml(strOr(msg, "id", "") + " " + from) +
                    "</b>. \"" +
                    escHtml(from) +
                    "\" becomes each member's own name in topics and docs. " +
                    "IDs, descriptions and other settings stay as they are. Untick members whose differences are intentional. You can undo this.</body></html>"
            ),
            BorderLayout.NORTH
        );
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(620, Math.min(360, list.getPreferredSize().height + 10)));
        content.add(sp, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(
            app,
            content,
            "Copy layout to family",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        if (r != JOptionPane.OK_OPTION) return;
        int n = 0;
        for (int i = 0; i < sibs.size(); i++) {
            if (!boxes.get(i).isSelected()) continue;
            sibs.get(i).put("points", lays.get(i).get("points"));
            sibs.get(i).put("fields", lays.get(i).get("fields"));
            n++;
        }
        if (n == 0) return;
        app.nextUndoLabel = "copy layout to " + n + " family member" + (n == 1 ? "" : "s");
        changed(false);
        refreshFamily();
        app.status.setText(
            "Copied this layout to " + n + " member" + (n == 1 ? "" : "s") + ". Undo (Ctrl+Z) reverts it."
        );
    }

    /** Drop the current message without committing pending edits (used when undo replaces the data). */
    public void detach() {
        if (pointsTable.isEditing()) pointsTable.getCellEditor().cancelCellEditing();
        if (fieldsTable.isEditing()) fieldsTable.getCellEditor().cancelCellEditing();
        msg = null;
        file = null;
        detailField = null;
    }

    // ---------- cansend tab

    public static final int TAB_DECODE = 3,
        TAB_SEND = 4,
        TAB_JSON = 5;
    public final JComboBox<String> sendIface = editableCombo(List.of("can0", "can1", "vcan0"));
    public final JPanel sendRows = new JPanel(new GridBagLayout());
    public final JTextField sendCmd = new JTextField();
    public final JLabel sendBytes = new JLabel(" ");
    public final ToneLabel sendStatus = new ToneLabel(" ");
    public final Map<Map<String, Object>, Map<Integer, String>> sendValues = new IdentityHashMap<>();
    public final Map<Integer, JTextField> sendInputs = new HashMap<>();
    public final Map<Integer, ToneLabel> sendRaw = new HashMap<>();
    public boolean sendBuilding;

    public JComponent buildSendTab() {
        sendCmd.setEditable(false);
        sendCmd.setFont(mono(Font.BOLD, sendCmd.getFont().getSize() + 2));
        sendBytes.setFont(mono(Font.PLAIN, sendBytes.getFont().getSize()));
        sendRows.setOpaque(false);
        sendRows.setBorder(new EmptyBorder(6, 14, 6, 14));
        JTextField ifaceEditor = (JTextField) sendIface.getEditor().getEditorComponent();
        ifaceEditor.getDocument().addDocumentListener(docListener(this::updateSend));
        sendIface.addActionListener(e -> updateSend());
        sendIface.setSelectedItem(app.prefs.get("canIface", "can0"));

        Bar top = new Bar();
        top.add(new MutedLabel("  Interface "));
        sendIface.setPreferredSize(new Dimension(110, sendIface.getPreferredSize().height));
        top.add(sendIface);
        top.sep();
        top.add(new MutedLabel("Fill with "));
        top.add(button("Defaults", "Each point's default where set, otherwise 0", () -> fillSend("default")));
        top.add(button("Zeros", null, () -> fillSend("zero")));
        top.add(button("Maximums", "Largest value each point can hold", () -> fillSend("max")));
        top.add(button("Random sim values", "Pick values the way the simulator would", () -> fillSend("sim")));

        JScrollPane sp = new JScrollPane(sendRows);
        sp.getVerticalScrollBar().setUnitIncrement(16);

        JPanel out = new JPanel(new BorderLayout(0, 6));
        out.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(10, 14, 10, 14)));
        JPanel cmdRow = new JPanel(new BorderLayout(8, 0));
        cmdRow.setOpaque(false);
        cmdRow.add(sendCmd, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        JButton copy = button("Copy command", null, () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sendCmd.getText()), null);
            app.prefs.put("canIface", ifaceName());
            app.status.setText("Copied: " + sendCmd.getText());
        });
        copy.putClientProperty("primary", Boolean.TRUE);
        btns.add(
            button("Check in Decode test", "Decode this payload to confirm it reads back as entered", () -> {
                String cmd = sendCmd.getText();
                int h = cmd.indexOf('#');
                if (h < 0) return;
                decodeInput.setText(
                    cmd
                        .substring(h + 1)
                        .replaceAll("(..)", "$1 ")
                        .strip()
                );
                tabs.setSelectedIndex(TAB_DECODE);
            })
        );
        btns.add(copy);
        cmdRow.add(btns, BorderLayout.EAST);
        out.add(cmdRow, BorderLayout.NORTH);
        out.add(sendBytes, BorderLayout.CENTER);
        out.add(sendStatus, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(out, BorderLayout.SOUTH);
        return p;
    }

    public static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    public String ifaceName() {
        Object o = sendIface.getEditor().getItem();
        String s = o == null ? "" : o.toString().strip();
        return s.isEmpty() ? "can0" : s;
    }

    public Map<Integer, String> sendMap() {
        return sendValues.computeIfAbsent(msg, k -> new HashMap<>());
    }

    public String unitFor(int pointIndex1) {
        for (Object o : fields()) {
            Map<String, Object> f = asMap(o);
            if (f != null && containsIdx(listOrEmpty(f.get("values")), pointIndex1)) {
                String u = strOr(f, "unit", "");
                if (!u.isEmpty()) return u;
            }
        }
        return "";
    }

    public void buildSend() {
        if (msg == null) return;
        sendBuilding = true;
        try {
            sendRows.removeAll();
            sendInputs.clear();
            sendRaw.clear();
            Map<Integer, String> vals = sendMap();
            List<Object> pts = points();
            int[] off = offsets(pts);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 10);
            c.anchor = GridBagConstraints.WEST;
            c.gridy = 0;
            String[] heads = { "Point", "Value", "", "Range", "Raw bits" };
            for (int i = 0; i < heads.length; i++) {
                c.gridx = i;
                sendRows.add(new MutedLabel(heads[i]), c);
            }
            for (int i = 0; i < pts.size(); i++) {
                c.gridy = i + 1;
                Map<String, Object> p = asMap(pts.get(i));
                int size = sizeOf(pts.get(i));
                String nm = p == null ? "(invalid)" : strOr(p, "name", "");
                JLabel name = new JLabel("#" + (i + 1) + "  " + (nm.isEmpty() ? "unnamed" : nm));
                c.gridx = 0;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                if (!isParsed(p)) {
                    name = new MutedLabel("#" + (i + 1) + "  padding");
                    sendRows.add(name, c);
                    c.gridx = 1;
                    sendRows.add(new MutedLabel("always 0"), c);
                    c.gridx = 3;
                    sendRows.add(new MutedLabel(size + " bit" + (size == 1 ? "" : "s") + " at " + off[i]), c);
                    continue;
                }
                name.setFont(name.getFont().deriveFont(Font.BOLD));
                sendRows.add(name, c);

                final int idx = i;
                String init = vals.containsKey(i)
                    ? vals.get(i)
                    : plain(num(p, "default")).isEmpty()
                        ? "0"
                        : plain(num(p, "default"));
                vals.put(i, init);
                JTextField input;
                JComponent editorComp;
                List<String> choices = sendChoices(p);
                if (!choices.isEmpty()) {
                    JComboBox<String> cb = editableCombo(choices);
                    cb.setSelectedItem(init);
                    input = (JTextField) cb.getEditor().getEditorComponent();
                    cb.addActionListener(e -> {
                        if (!sendBuilding) {
                            vals.put(idx, String.valueOf(cb.getEditor().getItem()));
                            updateSend();
                        }
                    });
                    editorComp = cb;
                } else {
                    input = new JTextField(init, 12);
                    editorComp = input;
                }
                input.setFont(mono(Font.PLAIN, input.getFont().getSize()));
                input.getDocument().addDocumentListener(
                    docListener(() -> {
                        if (!sendBuilding) {
                            vals.put(idx, input.getText());
                            updateSend();
                        }
                    })
                );
                sendInputs.put(i, input);
                editorComp.setPreferredSize(new Dimension(150, editorComp.getPreferredSize().height));
                c.gridx = 1;
                sendRows.add(editorComp, c);

                c.gridx = 2;
                sendRows.add(new MutedLabel(unitFor(i + 1)), c);

                double[] range = physRange(p);
                StringBuilder info = new StringBuilder();
                if (Boolean.TRUE.equals(p.get("ieee754_f32"))) info.append("32-bit float");
                else if (range != null) info.append(fmtD(range[0])).append(" … ").append(fmtD(range[1]));
                else info.append("raw value (formatter \"").append(formatterKey(p)).append("\" not applied)");
                info.append("   ")
                    .append(size)
                    .append(size == 1 ? " bit" : " bits");
                BigDecimal div = divisor(p);
                if (div != null && div.compareTo(BigDecimal.ONE) != 0) info.append(", sent ×").append(plain(div));
                c.gridx = 3;
                sendRows.add(new MutedLabel(info.toString()), c);

                ToneLabel raw = new ToneLabel(" ");
                raw.setFont(mono(Font.PLAIN, raw.getFont().getSize()));
                sendRaw.put(i, raw);
                c.gridx = 4;
                c.weightx = 1;
                sendRows.add(raw, c);
                c.weightx = 0;
            }
            c.gridy = pts.size() + 1;
            c.gridx = 0;
            c.weighty = 1;
            c.gridwidth = 5;
            sendRows.add(Box.createVerticalGlue(), c);
        } finally {
            sendBuilding = false;
        }
        sendRows.revalidate();
        sendRows.repaint();
        updateSend();
    }

    /** Preset values for the dropdown: enum sim options, or 0/1 for single-bit and bool points. */
    public static List<String> sendChoices(Map<String, Object> p) {
        List<String> out = new ArrayList<>();
        Map<String, Object> sim = asMap(p.get("sim"));
        if (sim != null) for (Object o : listOrEmpty(sim.get("options"))) {
            List<Object> pr = asList(o);
            if (pr != null && pr.size() == 2 && pr.getFirst() instanceof BigDecimal v) out.add(plain(v));
        }
        if (out.isEmpty() && (sizeOf(p) == 1 || "bool".equals(str(p, "c_type")))) {
            out.add("0");
            out.add("1");
        }
        return out;
    }

    public static Double parseSendValue(String s) {
        s = s == null ? "" : s.strip();
        if (s.isEmpty()) return 0.0;
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on")) return 1.0;
        if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off")) return 0.0;
        try {
            if (s.matches("-?0[xX][0-9a-fA-F]+")) {
                boolean neg = s.startsWith("-");
                long v = Long.parseLong(s.substring(neg ? 3 : 2), 16);
                return (double) (neg ? -v : v);
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Raw (pre-shift) bits for a physical value, or null with a reason in err[0]. */
    public static Long encodePoint(Map<String, Object> p, double v, String[] err) {
        int size = sizeOf(p);
        if (Boolean.TRUE.equals(p.get("ieee754_f32"))) return Float.floatToRawIntBits((float) v) & 0xFFFFFFFFL;
        BigDecimal div = divisor(p);
        double scaled = div == null ? v : v * div.doubleValue();
        long r = Math.round(scaled);
        boolean signed = Boolean.TRUE.equals(p.get("signed"));
        long lo = signed ? -(1L << (size - 1)) : 0,
            hi = signed ? (1L << (size - 1)) - 1 : (1L << size) - 1;
        if (r < lo || r > hi) {
            err[0] =
                "doesn't fit in " +
                size +
                (signed ? " signed" : "") +
                " bits (raw " +
                r +
                ", allowed " +
                lo +
                "…" +
                hi +
                "); clamped";
            r = Math.clamp(r, lo, hi);
        } else if (div != null && Math.abs(scaled - r) > 1e-6) {
            err[0] = "rounded to " + fmtD(r / div.doubleValue());
        }
        long mask = size >= 64 ? -1L : (1L << size) - 1;
        r &= mask;
        if ("little".equals(p.get("endianness")) && size % 8 == 0) {
            long s2 = 0;
            for (int k = 0; k < size / 8; k++) s2 = (s2 << 8) | ((r >>> (8 * k)) & 0xFF);
            r = s2;
        }
        return r;
    }

    public static void writeBits(byte[] data, int off, int size, long raw) {
        for (int b = 0; b < size; b++) {
            if (((raw >>> (size - 1 - b)) & 1) == 0) continue;
            int pos = off + b;
            if (pos / 8 < data.length) data[pos / 8] |= (byte) (1 << (7 - (pos % 8)));
        }
    }

    public static String cansendId(Map<String, Object> msg) {
        Long id = parseId(str(msg, "id"));
        if (id == null) return null;
        boolean ext = Boolean.TRUE.equals(msg.get("is_ext")) || id > 0x7FF;
        return ext ? "%08X".formatted(id) : "%03X".formatted(id);
    }

    public void updateSend() {
        if (msg == null || sendBuilding) return;
        List<Object> pts = points();
        int[] off = offsets(pts);
        int total = off[pts.size()];
        byte[] data = new byte[(total + 7) / 8];
        Map<Integer, String> vals = sendMap();
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            if (!isParsed(p)) continue;
            int size = sizeOf(p);
            ToneLabel raw = sendRaw.get(i);
            if (size < 1 || size > 64) {
                problems.add("#" + (i + 1) + " has an invalid size");
                continue;
            }
            Double v = parseSendValue(vals.get(i));
            if (v == null) {
                problems.add("#" + (i + 1) + " \"" + vals.get(i) + "\" isn't a number");
                if (raw != null) raw.set("not a number", ToneLabel.ERR);
                continue;
            }
            String[] err = new String[1];
            long r = encodePoint(p, v, err);
            writeBits(data, off[i], size, r);
            String bits = Long.toBinaryString(r);
            while (bits.length() < size) bits = "0" + bits;
            String text = (size <= 16 ? bits + "   " : "") + "0x" + Long.toHexString(r).toUpperCase(Locale.ROOT);
            if (err[0] != null) {
                boolean clamped = err[0].contains("clamped");
                (clamped ? problems : notes).add("#" + (i + 1) + " " + err[0]);
                if (raw != null) raw.set(text + "   " + err[0], clamped ? ToneLabel.ERR : ToneLabel.WARN);
            } else if (raw != null) raw.set(text, ToneLabel.MUTED);
        }
        StringBuilder hexP = new StringBuilder(),
            spaced = new StringBuilder();
        for (byte b : data) {
            hexP.append("%02X".formatted(b & 0xFF));
            spaced.append("%02X ".formatted(b & 0xFF));
        }
        String id = cansendId(msg);
        if (id == null) {
            sendCmd.setText("");
            sendStatus.set("This message's id isn't a valid hex ID, so no command can be built.", ToneLabel.ERR);
            return;
        }
        sendCmd.setText("cansend " + ifaceName() + " " + id + "#" + hexP);
        boolean ext = id.length() == 8;
        sendBytes.setText(
            "<html><font color='" +
                hx(T.muted()) +
                "'>" +
                data.length +
                " byte" +
                (data.length == 1 ? "" : "s") +
                ":</font>&nbsp; " +
                spaced.toString().strip() +
                "&nbsp;&nbsp;<font color='" +
                hx(T.muted()) +
                "'>" +
                (ext ? "extended (29-bit) ID" : "standard (11-bit) ID") +
                "</font></html>"
        );
        if (total > 64) problems.add(0, "The frame is " + total + " bits; classic CAN carries at most 8 bytes");
        else if (total % 8 != 0) notes.add(
            0,
            "Frame isn't byte aligned; the last " + (8 - (total % 8)) + " bits are sent as 0"
        );
        if (!problems.isEmpty()) sendStatus.set(String.join("   ·   ", problems), ToneLabel.ERR);
        else if (!notes.isEmpty()) sendStatus.set(String.join("   ·   ", notes), ToneLabel.WARN);
        else sendStatus.set("Values are in physical units; the formatter is reversed before sending.", ToneLabel.MUTED);
    }

    public void fillSend(String mode) {
        if (msg == null) return;
        List<Object> pts = points();
        Map<Integer, String> vals = sendMap();
        Random rnd = new Random();
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            if (!isParsed(p)) continue;
            double[] range = physRange(p);
            String v = switch (mode) {
                case "default" -> plain(num(p, "default")).isEmpty() ? "0" : plain(num(p, "default"));
                case "max" -> range == null ? "0" : fmtD(range[1]);
                case "sim" -> {
                    String pickV = "0";
                    Map<String, Object> sim = asMap(p.get("sim"));
                    if (sim != null && sim.containsKey("options")) {
                        double pick = rnd.nextDouble(),
                            acc = 0;
                        for (Object o : listOrEmpty(sim.get("options"))) {
                            List<Object> pr = asList(o);
                            if (
                                pr == null ||
                                pr.size() != 2 ||
                                !(pr.getFirst() instanceof BigDecimal value) ||
                                !(pr.getLast() instanceof BigDecimal freq)
                            ) continue;
                            pickV = plain(value);
                            acc += freq.doubleValue();
                            if (pick < acc) break;
                        }
                    } else if (sim != null && num(sim, "min") != null && num(sim, "max") != null) {
                        double lo = num(sim, "min").doubleValue(),
                            hi = num(sim, "max").doubleValue();
                        double x = lo + rnd.nextDouble() * (hi - lo);
                        pickV = Boolean.TRUE.equals(sim.get("round"))
                            ? String.valueOf(Math.round(x))
                            : fmtD(Math.round(x * 1000) / 1000.0);
                    } else if (range != null) {
                        pickV = fmtD(range[0] + Math.floor(rnd.nextDouble() * (range[1] - range[0] + 1e-9)));
                    }
                    yield pickV;
                }
                default -> "0";
            };
            vals.put(i, v);
        }
        buildSend();
    }

    // ---------- decode tab

    public JComponent buildDecodeTab() {
        decodeInput.setFont(mono(Font.PLAIN, decodeInput.getFont().getSize() + 1));
        decodeInput.setToolTipText("Hex bytes, e.g. 01 A2 FF 00 or 01A2FF00");
        decodeInput.addActionListener(e -> decode());
        decodeInput.getDocument().addDocumentListener(
            new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    decode();
                }

                public void removeUpdate(DocumentEvent e) {
                    decode();
                }

                public void changedUpdate(DocumentEvent e) {
                    decode();
                }
            }
        );
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(new EmptyBorder(8, 8, 4, 8));
        top.add(new JLabel("Frame data (hex)"), BorderLayout.WEST);
        top.add(decodeInput, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        b.add(
            button("Random", "Fill with random bytes of the right length", () -> {
                int bytes = (offsets(points())[points().size()] + 7) / 8;
                Random rnd = new Random();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < bytes; i++) sb.append("%02X ".formatted(rnd.nextInt(256)));
                decodeInput.setText(sb.toString().strip());
            })
        );
        b.add(
            button("Zeros", null, () -> {
                int bytes = (offsets(points())[points().size()] + 7) / 8;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < bytes; i++) sb.append("00 ");
                decodeInput.setText(sb.toString().strip());
            })
        );
        top.add(b, BorderLayout.EAST);
        JTable t = new JTable(decodeModel);
        styleTable(t);
        t.setDefaultRenderer(Object.class, new StyledRenderer(null));
        fitColumn(t, 0, "888");
        t.getColumnModel()
            .getColumn(3)
            .setCellRenderer(
                new StyledRenderer((tb, row, col, sel, c) -> c.setFont(mono(Font.PLAIN, tb.getFont().getSize())))
            );
        decodeOut.setEditable(false);
        decodeOut.setFont(mono(Font.PLAIN, decodeOut.getFont().getSize()));
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(t), new JScrollPane(decodeOut));
        sp.setResizeWeight(0.55);
        sp.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        decodeStatus.setBorder(new EmptyBorder(2, 8, 4, 8));
        p.add(decodeStatus, BorderLayout.SOUTH);
        return p;
    }

    public void decode() {
        decodeModel.setRowCount(0);
        decodeOut.setText("");
        if (msg == null) return;
        byte[] data = parseHexBytes(decodeInput.getText());
        if (data == null) {
            decodeStatus.set("Couldn't read those bytes. Use hex pairs such as 01 A2 FF.", ToneLabel.ERR);
            return;
        }
        List<Object> pts = points();
        int need = (offsets(pts)[pts.size()] + 7) / 8;
        if (data.length < need) {
            decodeStatus.set(
                "Frame has " +
                    data.length +
                    " byte(s); this message needs " +
                    need +
                    ". firmware-rs drops short frames; missing bytes are shown as 0 here.",
                ToneLabel.WARN
            );
        } else if (data.length > need) {
            decodeStatus.set(
                "Frame has " + data.length + " bytes; this message only uses " + need + ".",
                ToneLabel.WARN
            );
        } else {
            decodeStatus.set("Decoded with big-endian bit order (MSB first), matching Calypso.", ToneLabel.MUTED);
        }
        Decoded d = decodeFrame(msg, data);
        for (String[] row : d.points()) decodeModel.addRow(row);
        decodeOut.setText(String.join("\n", d.topics()));
        decodeOut.setCaretPosition(0);
    }

    // ---------- JSON tab

    public final javax.swing.undo.UndoManager jsonUndo = jsonArea.history;

    public JComponent buildJsonTab() {
        jsonArea.setFont(mono(Font.PLAIN, jsonArea.getFont().getSize()));
        // Ctrl+Z inside the JSON editor undoes typing there (JsonPane.history), not the last change to the spec
        int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        jsonArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey), "jsonUndo");
        jsonArea
            .getInputMap()
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey | InputEvent.SHIFT_DOWN_MASK), "jsonRedo");
        jsonArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuKey), "jsonRedo");
        jsonArea.getActionMap().put(
            "jsonUndo",
            new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    if (jsonUndo.canUndo()) jsonUndo.undo();
                }
            }
        );
        jsonArea.getActionMap().put(
            "jsonRedo",
            new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    if (jsonUndo.canRedo()) jsonUndo.redo();
                }
            }
        );
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton apply = button("Apply JSON", "Replace this message with the edited JSON", this::applyJson);
        apply.putClientProperty("primary", Boolean.TRUE);
        b.add(apply);
        b.add(button("Revert", null, this::loadJson));
        b.add(
            button("Copy", "Copy this message's JSON to the clipboard", () ->
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(jsonArea.getText()), null)
            )
        );
        b.add(jsonStatus);
        JPanel p = new JPanel(new BorderLayout());
        p.add(b, BorderLayout.NORTH);
        p.add(new JScrollPane(jsonArea), BorderLayout.CENTER);
        return p;
    }

    public void loadJson() {
        if (msg == null) return;
        jsonArea.setText(Json.write(msg));
        jsonArea.setCaretPosition(0);
        jsonUndo.discardAllEdits();
        jsonStatus.set("Edit anything here, then Apply. Keys the forms don't show are kept.", ToneLabel.MUTED);
    }

    public void applyJson() {
        if (msg == null) return;
        Object v;
        try {
            v = Json.parse(jsonArea.getText());
        } catch (JsonException ex) {
            jsonStatus.set(ex.getMessage(), ToneLabel.ERR);
            return;
        }
        Map<String, Object> nm = asMap(v);
        if (nm == null) {
            jsonStatus.set("The JSON must be a single message object { … }", ToneLabel.ERR);
            return;
        }
        msg.clear();
        msg.putAll(nm);
        rememberLayout(); // JSON the user applies is taken as written: Keep byte aligned doesn't touch it
        changed(true);
        refreshHeader();
        loadJson();
        jsonStatus.set("Applied", ToneLabel.OK);
    }

    // ---------- load / refresh

    public void showWelcome() {
        placeholderRefresh = this::showWelcome;
        commitAll();
        msg = null;
        file = null;
        placeholder.setText(
            "<html><div style='text-align:center'><b>Open a folder of CAN spec JSON files</b><br><br>" +
                "File &gt; Open folder… (Ctrl+O), or drop files or a folder onto this window.</div></html>"
        );
        fileShown = false;
        cards.show(cardPanel, "empty");
        if (isShowing()) fadeUI.fadeIn(fadeLayer);
    }

    public void showOverview() {
        placeholderRefresh = this::showOverview;
        commitAll();
        msg = null;
        file = null;
        fileShown = false;
        int msgs = 0;
        for (SpecFile f : app.files) msgs += f.messages.size();
        overviewTitle.setText(app.files.size() + (app.files.size() == 1 ? " file, " : " files, ") + msgs + " messages");
        overviewInfo.setText(
            "Pick a file to see its messages, or search on the left by ID, description, topic, point name or unit."
        );
        fileGrid.setItems(app.files, "No files loaded.");
        cards.show(cardPanel, "files");
        if (isShowing()) fadeUI.fadeIn(fadeLayer);
        app.issuesPanel.refilter();
    }

    public JComponent buildOverviewCard() {
        overviewTitle.setFont(overviewTitle.getFont().deriveFont(Font.BOLD, overviewTitle.getFont().getSize2D() + 4));
        JScrollPane sp = new JScrollPane(fileGrid);
        sp.setBorder(null);
        JPanel head = new JPanel(new BorderLayout(0, 3));
        head.setBorder(new EmptyBorder(16, 16, 4, 16));
        head.add(overviewTitle, BorderLayout.NORTH);
        head.add(overviewInfo, BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout());
        p.add(head, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    public void showFile(SpecFile f) {
        placeholderRefresh = () -> showFile(f);
        commitAll();
        msg = null;
        file = f;
        app.visit(f, null);
        fileShown = true;
        if (gridFile != f) {
            gridFile = f;
            grid.sel = -1;
            gridFilter.setText("");
            gridScroll.getViewport().setViewPosition(new Point(0, 0));
        }
        refreshFileCard();
        cards.show(cardPanel, "file");
        if (isShowing()) fadeUI.fadeIn(fadeLayer);
        app.issuesPanel.refilter();
    }

    // ---------- file overview: every message in the selected file as a grid

    public JComponent buildFileCard() {
        fileTitle.setFont(fileTitle.getFont().deriveFont(Font.BOLD, fileTitle.getFont().getSize2D() + 4));
        fileNew = button("New message", "Add a message to this file", app::newMessage);
        gridFilter.getDocument().addDocumentListener(docListener(this::refreshFileCard));
        gridFilter.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ENTER -> {
                            if (grid.first() != null && file != null) app.selectMessage(file, grid.first());
                        }
                        case KeyEvent.VK_DOWN -> {
                            grid.requestFocusInWindow();
                            grid.select(0);
                        }
                        case KeyEvent.VK_ESCAPE -> gridFilter.setText("");
                        default -> {
                            return;
                        }
                    }
                    e.consume();
                }
            }
        );
        // typing while the grid has focus goes into the filter
        grid.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (c <= ' ' || c == 127 || e.isControlDown() || e.isMetaDown() || e.isAltDown()) return;
                    gridFilter.requestFocusInWindow();
                    gridFilter.setText(gridFilter.getText() + c);
                    e.consume();
                }
            }
        );
        gridScroll.setBorder(null);
        JPanel titles = new JPanel(new BorderLayout(0, 3));
        titles.setOpaque(false);
        titles.add(fileTitle, BorderLayout.NORTH);
        titles.add(fileInfo, BorderLayout.CENTER);
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(gridFilter, BorderLayout.CENTER);
        row.add(fileNew, BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 12));
        head.setBorder(new EmptyBorder(16, 16, 4, 16));
        head.add(titles, BorderLayout.NORTH);
        head.add(row, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(head, BorderLayout.NORTH);
        p.add(gridScroll, BorderLayout.CENTER);
        return p;
    }

    /** Re-reads the file card: header counts, and the tiles that match the filter (sorted like the tree). */
    public void refreshFileCard() {
        SpecFile f = file;
        if (!fileShown || f == null || msg != null) return;
        int e = 0,
            w = 0;
        for (Issue is : app.issues)
            if (is.file() == f) {
                if (is.sev() == Severity.ERROR) e++;
                else if (is.sev() == Severity.WARN) w++;
            }
        fileTitle.setText(app.relName(f));
        int n = f.messages.size();
        fileInfo.setText(
            "<html>" +
                n +
                (n == 1 ? " message, " : " messages, ") +
                "<font color='" +
                hx(e > 0 ? T.red() : T.muted()) +
                "'>" +
                e +
                (e == 1 ? " error" : " errors") +
                "</font>, " +
                "<font color='" +
                hx(w > 0 ? T.yellow() : T.muted()) +
                "'>" +
                w +
                (w == 1 ? " warning" : " warnings") +
                "</font>" +
                (f.dirty ? ", <font color='" + hx(T.orange()) + "'>unsaved changes</font>" : "") +
                "&nbsp;&nbsp;<font color='" +
                hx(T.muted()) +
                "'>" +
                escHtml(f.path.toString()) +
                "</font>" +
                (f.loadError != null
                    ? "<br><font color='" + hx(T.red()) + "'>" + escHtml(f.loadError) + "</font>"
                    : "") +
                "</html>"
        );
        fileNew.setEnabled(f.loadError == null);
        gridFilter.setEnabled(f.loadError == null);
        String q = gridFilter.getText().strip().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Object o : f.messages) if (asMap(o) instanceof Map<String, Object> m && App.matches(m, q, 0)) ms.add(m);
        if (app.sortById.isSelected()) sortMessagesById(ms);
        grid.setItems(
            ms,
            f.loadError != null
                ? "This file couldn't be loaded, so it has no messages to show."
                : q.isEmpty()
                    ? "This file has no messages yet. Add one with New message."
                    : "No messages in this file match \"" + gridFilter.getText().strip() + "\"."
        );
    }

    public void clear() {
        commitAll();
        msg = null;
        file = null;
        detailField = null;
        showOverview();
    }

    public void load(SpecFile f, Map<String, Object> m) {
        if (m == msg) {
            refresh();
            return;
        }
        commitAll();
        fileShown = false;
        file = f;
        msg = m;
        app.visit(f, m);
        detailField = null;
        unitCombo.setModel(new DefaultComboBoxModel<>(app.units().toArray(new String[0])));
        formatterCombo.setModel(new DefaultComboBoxModel<>(app.formatterKeys().toArray(new String[0])));
        refreshHeader();
        rememberLayout();
        issuesChanged();
        pointsModel.fireTableDataChanged();
        fieldsModel.fireTableDataChanged();
        loadFieldDetail();
        layoutPanel.setMessage(this);
        decodeInput.setText("");
        if (tabs.getSelectedIndex() == TAB_JSON) loadJson();
        if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
        refreshSummary();
        refreshFamily();
        cards.show(cardPanel, "editor");
        if (isShowing()) fadeUI.fadeIn(fadeLayer);
        app.issuesPanel.refilter();
    }

    public void commitAll() {
        if (msg == null) return;
        stopEditing(pointsTable);
        stopEditing(fieldsTable);
        if (loading) return;
        // flush header fields (their focus-lost may arrive after selection changes)
        for (JTextField tf : new JTextField[] { idField, descField, simFreqField, keyField, clientsField })
            for (ActionListener al : tf.getActionListeners())
                al.actionPerformed(new ActionEvent(tf, ActionEvent.ACTION_PERFORMED, ""));
        commitFieldDetail();
    }

    public void refreshHeader() {
        loading = true;
        try {
            idField.setText(strOr(msg, "id", ""));
            descField.setText(strOr(msg, "desc", ""));
            extBox.setSelected(Boolean.TRUE.equals(msg.get("is_ext")));
            keyField.setText(strOr(msg, "key", ""));
            bidirBox.setSelectedItem(strOr(msg, "bidir_mode", ""));
            simFreqField.setText(plain(num(msg, "sim_freq")));
            StringBuilder cl = new StringBuilder();
            for (Object o : listOrEmpty(msg.get("clients"))) {
                if (!cl.isEmpty()) cl.append(", ");
                cl.append(Json.writeCompact(o));
            }
            clientsField.setText(cl.toString());
            for (JTextField tf : new JTextField[] { idField, descField, simFreqField, keyField, clientsField })
                tf.putClientProperty("orig", tf.getText());
        } finally {
            loading = false;
        }
    }

    public void refreshSummary() {
        if (msg == null) return;
        List<Object> pts = points();
        int total = offsets(pts)[pts.size()];
        boolean aligned = total % 8 == 0 && total <= 64 && total > 0;
        int parsed = 0;
        for (Object o : pts) if (isParsed(asMap(o))) parsed++;
        String kind = msg.containsKey("key")
            ? "encodable (" + strOr(msg, "bidir_mode", "broadcast") + ")"
            : "decode only";
        Long id = parseId(str(msg, "id"));
        String dec = id == null ? "" : " = " + id + " decimal";
        summary.setText(
            "<html>" +
                "<font color='" +
                hx(aligned ? T.green() : T.red()) +
                "'>" +
                total +
                " bits (" +
                (total / 8.0 == total / 8 ? String.valueOf(total / 8) : fmtD(total / 8.0)) +
                " bytes)</font>&nbsp;&nbsp; " +
                pts.size() +
                " points (" +
                parsed +
                " parsed), " +
                fields().size() +
                " fields, " +
                kind +
                "&nbsp;&nbsp;<font color='" +
                hx(T.muted()) +
                "'>" +
                escHtml(app.relName(file)) +
                dec +
                "</font></html>"
        );
    }

    public void refresh() {
        if (msg == null) return;
        rememberLayout();
        refreshTable(pointsTable, pointsModel);
        refreshTable(fieldsTable, fieldsModel);
        layoutPanel.setMessage(this);
        refreshSummary();
        refreshFamily();
        if (tabs.getSelectedIndex() == TAB_DECODE) decode();
        if (tabs.getSelectedIndex() == TAB_SEND) buildSend();
    }

    public void changed(boolean structural) {
        String padNote = autoPad();
        app.changed(file, structural);
        refresh();
        if (padNote != null) app.status.setText(padNote + ". Untick \"Keep byte aligned\" to stop this.");
    }

    /**
     * Keeps the message byte aligned after an edit that changed its total size (see Fixes.keepAligned), unless the
     * edit was to the trailing padding point itself. Returns a status line when it changed something.
     */
    public String autoPad() {
        String note = null;
        List<Object> pts = msg == null ? null : asList(msg.get("points"));
        if (pts != null && !pts.isEmpty() && autoPadBox.isSelected() && layoutTotal >= 0) {
            int total = offsets(pts)[pts.size()];
            Map<String, Object> pad = Fixes.trailingPadding(pts);
            boolean padEdited = pad != null && layoutPad >= 0 && sizeOf(pad) != layoutPad;
            if (total != layoutTotal && !padEdited) note = Fixes.keepAligned(msg, layoutTotal);
        }
        rememberLayout();
        return note;
    }

    public void rememberLayout() {
        List<Object> pts = msg == null ? null : asList(msg.get("points"));
        if (pts == null) {
            layoutTotal = layoutPad = -1;
            return;
        }
        Map<String, Object> pad = Fixes.trailingPadding(pts);
        layoutTotal = offsets(pts)[pts.size()];
        layoutPad = pad == null ? -1 : sizeOf(pad);
    }

    public void addFixMenu(JComponent c, java.util.function.IntFunction<List<Issue>> issuesAtRow) {
        addFixMenu(c, issuesAtRow, r -> List.of());
    }

    /**
     * Right-click on a row of the table (or on the component) shows the fixes for the problems there, then any
     * other items for that row (find usages, rename).
     */
    public void addFixMenu(
        JComponent c,
        java.util.function.IntFunction<List<Issue>> issuesAtRow,
        java.util.function.IntFunction<List<JMenuItem>> moreAtRow
    ) {
        c.addMouseListener(
            new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) show(e);
                }

                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) show(e);
                }

                void show(MouseEvent e) {
                    if (msg == null) return;
                    app.validateNow();
                    int r = -1;
                    if (c instanceof JTable t) {
                        r = t.rowAtPoint(e.getPoint());
                        if (r < 0) return;
                        t.setRowSelectionInterval(r, r);
                    }
                    List<Issue> l = issuesAtRow.apply(r);
                    JPopupMenu pm = new JPopupMenu();
                    app.fillFixMenu(pm, l);
                    List<JMenuItem> more = moreAtRow.apply(r);
                    if (!more.isEmpty() && pm.getComponentCount() > 0) pm.addSeparator();
                    for (JMenuItem it : more) pm.add(it);
                    if (pm.getComponentCount() > 0) pm.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        );
    }

    /** Right-click items for a points row: which fields publish the point (click one to open it). */
    public List<JMenuItem> pointMenuItems(int r) {
        if (msg == null || r < 0 || r >= points().size()) return List.of();
        JMenu used = new JMenu("Used by");
        List<Object> fds = listOrEmpty(msg.get("fields"));
        for (int j = 0; j < fds.size(); j++) {
            Map<String, Object> fd = asMap(fds.get(j));
            if (fd == null) continue;
            String nm = strOr(fd, "name", "");
            if (!containsIdx(listOrEmpty(fd.get("values")), r + 1) && !nm.contains("{" + (r + 1) + "}")) continue;
            final int fj = j;
            JMenuItem it = new JMenuItem("Field " + (j + 1) + ": " + (nm.isEmpty() ? "(no topic)" : nm));
            it.addActionListener(e -> app.openField(new FieldRef(file, msg, fj)));
            used.add(it);
        }
        if (used.getItemCount() == 0) {
            JMenuItem none = new JMenuItem(
                isParsed(asMap(points().get(r))) ? "Not used by any field" : "Padding: not published"
            );
            none.setEnabled(false);
            return List.of(none);
        }
        return List.of(used);
    }

    /** Right-click items for a fields row: find the usages of any level of its topic, and rename a level. */
    public List<JMenuItem> fieldMenuItems(int r) {
        Map<String, Object> fd = fieldsModel.fd(r);
        String tp = Topics.level(strOr(fd, "name", ""));
        if (fd == null || tp.isEmpty()) return List.of();
        String[] parts = tp.split("/");
        JMenu find = new JMenu("Find usages of");
        String parent = null;
        for (int i = 0; i < parts.length; i++) {
            String lv = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));
            if (i == parts.length - 2) parent = lv;
            int n = Topics.usages(app.files, lv).size();
            JMenuItem it = new JMenuItem(lv + "  (" + n + ")");
            it.addActionListener(e -> app.showTopics(lv, false));
            find.add(it);
        }
        String start = parent != null ? parent : tp;
        JMenuItem ren = new JMenuItem("Rename topic level " + start + "…");
        ren.addActionListener(e -> app.showTopics(start, true));
        return List.of(find, ren);
    }

    /** The current message's own problems (not a point's or a field's), as listed under its header. */
    public List<Issue> messageIssues() {
        List<Issue> l = new ArrayList<>();
        for (Issue is : app.issues) if (is.msg() == msg && is.point() < 0 && is.field() < 0) l.add(is);
        return l;
    }

    public void issuesChanged() {
        pointSev.clear();
        fieldSev.clear();
        pointIssues.clear();
        fieldIssues.clear();
        if (msg == null) {
            msgProblems.setText("");
            refreshFileCard();
            return;
        }
        List<Issue> header = new ArrayList<>();
        for (Issue is : app.issues) {
            if (is.msg() != msg) continue;
            if (is.point() >= 0) {
                pointIssues.computeIfAbsent(is.point(), k -> new ArrayList<>()).add(is);
                if (is.sev() != Severity.INFO) pointSev.merge(is.point(), is.sev(), Severity::worse);
            } else if (is.field() >= 0) {
                fieldIssues.computeIfAbsent(is.field(), k -> new ArrayList<>()).add(is);
                if (is.sev() != Severity.INFO) fieldSev.merge(is.field(), is.sev(), Severity::worse);
            } else if (is.sev() != Severity.INFO) header.add(is);
        }
        if (header.isEmpty()) msgProblems.setText("");
        else {
            StringBuilder sb = new StringBuilder("<html>");
            int n = 0;
            for (Issue is : header) {
                if (n++ == 4) {
                    sb.append("<font color='")
                        .append(hx(T.muted()))
                        .append("'>…and ")
                        .append(header.size() - 4)
                        .append(" more in Problems below</font>");
                    break;
                }
                sb.append("<font color='")
                    .append(hx(is.sev().color()))
                    .append("'>● </font>")
                    .append(escHtml(is.text()))
                    .append("<br>");
            }
            msgProblems.setText(sb.append("</html>").toString());
        }
        int pe = 0,
            fe = 0;
        for (Severity s : pointSev.values()) if (s == Severity.ERROR) pe++;
        for (Severity s : fieldSev.values()) if (s == Severity.ERROR) fe++;
        tabs.setTitleAt(0, "Points" + (pointSev.isEmpty() ? "" : " (" + pointSev.size() + (pe > 0 ? "!" : "") + ")"));
        tabs.setTitleAt(
            1,
            "Fields (MQTT topics)" + (fieldSev.isEmpty() ? "" : " (" + fieldSev.size() + (fe > 0 ? "!" : "") + ")")
        );
        tabs.setForegroundAt(0, pe > 0 ? new Color(T.red().getRGB()) : null);
        tabs.setForegroundAt(1, fe > 0 ? new Color(T.red().getRGB()) : null);
        pointsTable.repaint();
        fieldsTable.repaint();
        layoutPanel.repaint();
    }

    public void setHighlight(String q, int mode) {
        highlight = q;
        highlightMode = mode;
        pointsTable.repaint();
        fieldsTable.repaint();
    }

    public void focusIssue(Issue is) {
        if (is.point() >= 0) {
            tabs.setSelectedIndex(0);
            selectRow(pointsTable, is.point());
        } else if (is.field() >= 0) {
            tabs.setSelectedIndex(1);
            selectRow(fieldsTable, is.field());
        }
    }

    public void focusId() {
        idField.requestFocusInWindow();
        idField.selectAll();
    }

    public void nextFreeId() {
        if (msg == null) return;
        Long v = parseId(str(msg, "id"));
        boolean ext = Boolean.TRUE.equals(msg.get("is_ext"));
        String nid = app.nextFreeId(v == null ? -1 : v, ext);
        if (nid == null) {
            app.status.setText("No free ID found");
            return;
        }
        idField.setText(nid);
        idField.putClientProperty("orig", nid);
        if (putStr(msg, "id", nid, false)) changed(true);
        app.status.setText("ID set to " + nid);
    }
}
