package tiresias.editor;

import static tiresias.spec.Spec.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import tiresias.spec.*;

/**
 * GoToDialog: Go > Go to message (Cmd/Ctrl+P). Type part of an ID, description, key, topic or file name; every
 * word has to match somewhere. Up/Down choose, Enter opens, Escape closes.
 */
public final class GoToDialog extends JDialog {

    /** A place to go: a message, or a whole file when msg is null. what says which part matched, if not obvious. */
    public record Hit(SpecFile file, Map<String, Object> msg, int score, String what) {}

    public static final int MAX_HITS = 200;

    public final App app;
    public final HintField query = new HintField("Go to a message by ID, name, key, topic or file…", 40);
    public final HitsModel model = new HitsModel();
    public final JList<Hit> list = new JList<>(model);
    public boolean refreshQueued;
    /** Set once the dialog has the focus after opening, so a late lost-focus event from its last showing is ignored. */
    public boolean focusedSinceShow;

    /**
     * The result rows, replaced in one go: adding rows one at a time made the list re-measure every row's HTML after
     * each one, which took most of a second per keystroke.
     */
    public static final class HitsModel extends AbstractListModel<Hit> {

        public List<Hit> items = List.of();

        public int getSize() {
            return items.size();
        }

        public Hit getElementAt(int i) {
            return items.get(i);
        }

        public int size() {
            return items.size();
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public void set(List<Hit> hits) {
            int old = items.size();
            items = List.copyOf(hits);
            if (old > 0) fireIntervalRemoved(this, 0, old - 1);
            if (!items.isEmpty()) fireIntervalAdded(this, 0, items.size() - 1);
        }
    }

    public GoToDialog(App app) {
        super(app, "Go to message", false);
        this.app = app;
        setUndecorated(true);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(new LineBorder(T.border()), new EmptyBorder(8, 8, 8, 8)));
        p.setBackground(T.panel());
        query.setFont(query.getFont().deriveFont(query.getFont().getSize2D() + 2f));
        p.add(query, BorderLayout.NORTH);
        list.setFocusable(false);
        list.setVisibleRowCount(14);
        // fixed row size, so the list only lays out the rows it shows instead of measuring all of them
        list.setFixedCellHeight(list.getFontMetrics(list.getFont()).getHeight() + 8);
        list.setFixedCellWidth(100); // rows still span the list's width
        list.setBackground(T.field());
        list.setCellRenderer(
            new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                    super.getListCellRendererComponent(l, "", i, sel, false);
                    Hit h = (Hit) v;
                    String muted = hx(T.muted());
                    String text =
                        h.msg() == null
                            ? "<b>" +
                              escHtml(app.relName(h.file())) +
                              "</b>&nbsp;&nbsp;<font color='" +
                              muted +
                              "'>file</font>"
                            : "<b>" +
                              escHtml(strOr(h.msg(), "id", "(meta)")) +
                              "</b>&nbsp;&nbsp;" +
                              escHtml(strOr(h.msg(), "desc", "")) +
                              "&nbsp;&nbsp;<font color='" +
                              muted +
                              "'>" +
                              escHtml(app.relName(h.file())) +
                              (h.what() == null ? "" : " · " + escHtml(h.what())) +
                              "</font>";
                    setText("<html>" + text + "</html>");
                    setBorder(new EmptyBorder(3, 8, 3, 8));
                    setBackground(sel ? T.selection() : T.field());
                    setForeground(sel ? T.bright() : T.fg());
                    return this;
                }
            }
        );
        list.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if (i < 0) return;
                    list.setSelectedIndex(i);
                    if (e.getClickCount() == 2) open();
                }
            }
        );
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(new LineBorder(T.hairline()));
        sp.setPreferredSize(new Dimension(720, 380));
        p.add(sp, BorderLayout.CENTER);
        p.add(new MutedLabel("↑↓ choose   Enter open   Esc close   Back: " + App.BACK_KEYS), BorderLayout.SOUTH);
        setContentPane(p);

        // setText fires a remove and an insert; refresh once after both
        query.getDocument().addDocumentListener(docListener(this::refreshLater));
        query.addKeyListener(
            new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_DOWN -> move(1);
                        case KeyEvent.VK_UP -> move(-1);
                        case KeyEvent.VK_PAGE_DOWN -> move(10);
                        case KeyEvent.VK_PAGE_UP -> move(-10);
                        case KeyEvent.VK_ENTER -> open();
                        case KeyEvent.VK_ESCAPE -> setVisible(false);
                        default -> {
                            return;
                        }
                    }
                    e.consume();
                }
            }
        );
        addWindowFocusListener(
            new WindowAdapter() {
                public void windowGainedFocus(WindowEvent e) {
                    focusedSinceShow = true;
                }

                public void windowLostFocus(WindowEvent e) {
                    if (focusedSinceShow) setVisible(false);
                }
            }
        );
        // Escape and clicks outside close it even when it never got the keyboard focus (which can happen on macOS
        // when it opens from a menu shortcut): otherwise it would stay up with typing going to the main window.
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (!isVisible() || !(e instanceof InputEvent ie) || ie.isConsumed()) return;
            Window from = e.getSource() instanceof Component c ? SwingUtilities.getWindowAncestor(c) : null;
            if (e.getSource() instanceof Window w) from = w;
            if (e.getID() == MouseEvent.MOUSE_PRESSED && from != this) setVisible(false);
            else if (
                e.getID() == KeyEvent.KEY_PRESSED && ((KeyEvent) e).getKeyCode() == KeyEvent.VK_ESCAPE && from != this
            ) {
                ie.consume();
                setVisible(false);
            }
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        pack();
    }

    public static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            public void changedUpdate(DocumentEvent e) {}
        };
    }

    public void refreshLater() {
        if (refreshQueued) return;
        refreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            refreshQueued = false;
            refresh();
        });
    }

    public void showFor() {
        focusedSinceShow = false;
        query.setText("");
        refreshQueued = false;
        refresh();
        Rectangle r = app.getBounds();
        setLocation(r.x + (r.width - getWidth()) / 2, r.y + Math.max(60, r.height / 8));
        setVisible(true);
        takeFocus();
        // if the window system handed the focus back to the main window (macOS does after a menu shortcut), try once more
        javax.swing.Timer again = new javax.swing.Timer(250, e -> {
            if (isVisible() && !isFocused()) takeFocus();
        });
        again.setRepeats(false);
        again.start();
    }

    public void takeFocus() {
        toFront();
        requestFocus();
        query.requestFocusInWindow();
    }

    public void refresh() {
        List<Hit> hits = search(app.files, query.getText(), MAX_HITS);
        if (query.getText().isBlank()) {
            // nothing typed: recently opened messages first (the one open now is the first of them; skip it)
            Set<Map<String, Object>> recent = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Hit> front = new ArrayList<>();
            for (App.Place p : app.recentPlaces(12)) {
                if (p.msg() == app.editor.msg) continue;
                recent.add(p.msg());
                front.add(new Hit(p.file(), p.msg(), 0, "recent"));
            }
            hits.removeIf(h -> h.msg() != null && recent.contains(h.msg()));
            hits.addAll(0, front);
        }
        model.set(hits);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
    }

    public void move(int d) {
        if (model.isEmpty()) return;
        int j = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + d));
        list.setSelectedIndex(j);
        list.ensureIndexIsVisible(j);
    }

    public void open() {
        Hit h = list.getSelectedValue();
        if (h == null) return;
        setVisible(false);
        if (h.msg() == null) app.selectFile(h.file());
        else app.selectMessage(h.file(), h.msg());
    }

    // ---------- matching

    /** Messages and files matching every word of the query, best first; an empty query lists every message. */
    public static List<Hit> search(List<SpecFile> files, String q, int limit) {
        String[] words = q.strip().toLowerCase(Locale.ROOT).split("\\s+");
        boolean empty = q.isBlank();
        List<Hit> out = new ArrayList<>();
        for (SpecFile f : files) {
            String fname = f.path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!empty) {
                int s = 0;
                for (String w : words) {
                    int ws = score(w, fname);
                    if (ws < 0) {
                        s = -1;
                        break;
                    }
                    s += ws;
                }
                if (s >= 0) out.add(new Hit(f, null, s + 5, null));
            }
            for (Object o : f.messages) {
                Map<String, Object> m = asMap(o);
                if (m == null) continue;
                if (empty) {
                    out.add(new Hit(f, m, 0, null));
                    continue;
                }
                int total = 0;
                String what = null;
                boolean all = true;
                for (String w : words) {
                    int best = -1;
                    String bestWhat = null;
                    Long id = parseId(str(m, "id")),
                        qv = parseLooseNumber(w);
                    if (id != null && id.equals(qv)) best = 120;
                    String idText = lc(strOr(m, "id", ""));
                    best = Math.max(best, score(w, idText));
                    best = Math.max(best, score(w, idText.startsWith("0x") ? idText.substring(2) : idText));
                    int d = score(w, lc(strOr(m, "desc", "")));
                    if (d > best) best = d;
                    int k = score(w, lc(strOr(m, "key", "")));
                    if (k > best) {
                        best = k;
                        bestWhat = "key " + strOr(m, "key", "");
                    }
                    for (Object fo : listOrEmpty(m.get("fields"))) {
                        String tp = strOr(asMap(fo), "name", "");
                        int t = score(w, lc(tp)) - 5; // topics rank just below the message's own name
                        if (t > best) {
                            best = t;
                            bestWhat = tp;
                        }
                    }
                    int fs = score(w, fname) - 30;
                    if (fs > best) best = fs;
                    if (best < 0) {
                        all = false;
                        break;
                    }
                    total += best;
                    if (bestWhat != null) what = bestWhat;
                }
                if (all) out.add(new Hit(f, m, total, what));
            }
        }
        if (!empty) out.sort(Comparator.comparingInt((Hit h) -> -h.score()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** How well a lowercase word matches a lowercase text: exact, prefix, word start, substring, then in order. */
    public static int score(String w, String text) {
        if (w.isEmpty() || text.isEmpty()) return -1;
        if (text.equals(w)) return 100;
        if (text.startsWith(w)) return 80;
        int at = text.indexOf(w);
        if (at > 0) {
            char before = text.charAt(at - 1);
            return Character.isLetterOrDigit(before) ? 40 : 60;
        }
        // letters in order (like "shpn" for "shutdown pins"), worth less the more spread out they are
        int i = 0,
            first = -1,
            last = -1;
        for (int j = 0; j < text.length() && i < w.length(); j++) if (text.charAt(j) == w.charAt(i)) {
            if (first < 0) first = j;
            last = j;
            i++;
        }
        if (i < w.length() || w.length() < 2) return -1;
        return Math.max(1, 20 - (last - first - w.length()));
    }
}
