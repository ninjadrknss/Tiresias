package tiresias.ui;

import static tiresias.ui.Ui.*;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;

/**
 * Suggest: a drop-down of suggestions under a text field that updates as you type. Down/Up choose, Enter or Tab
 * accept, Escape closes. It lives in a non-focusable window, so typing focus never leaves the field (which also
 * makes it work inside a table cell editor).
 */
public final class Suggest {

    /** A suggestion: the text it puts in the field, and a muted note shown beside it. */
    public record Item(String text, String note) {}

    public final JTextField field;
    public final Function<String, List<Item>> source;
    public final DefaultListModel<Item> model = new DefaultListModel<>();
    public final JList<Item> list = new JList<>(model);
    public JWindow window;
    public boolean applying;

    private Suggest(JTextField field, Function<String, List<Item>> source) {
        this.field = field;
        this.source = source;
    }

    public static Suggest attach(JTextField field, Function<String, List<Item>> source) {
        Suggest s = new Suggest(field, source);
        s.install();
        return s;
    }

    public void install() {
        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(
            new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                    Item it = (Item) v;
                    super.getListCellRendererComponent(l, "", i, sel, false);
                    setText(
                        "<html>" +
                            escHtml(it.text()) +
                            (it.note() == null || it.note().isEmpty()
                                ? ""
                                : "&nbsp;&nbsp;<font color='" + hx(T.muted()) + "'>" + escHtml(it.note()) + "</font>") +
                            "</html>"
                    );
                    setFont(mono(Font.PLAIN, field.getFont().getSize()));
                    setBorder(new EmptyBorder(2, 8, 2, 8));
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
                    if (i >= 0) accept(model.get(i));
                }
            }
        );
        field.getDocument().addDocumentListener(
            new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    later();
                }

                public void removeUpdate(DocumentEvent e) {
                    later();
                }

                public void changedUpdate(DocumentEvent e) {}
            }
        );
        field.addKeyListener(
            new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    if (!showing()) {
                        if (
                            e.getKeyCode() == KeyEvent.VK_DOWN ||
                            (e.getKeyCode() == KeyEvent.VK_SPACE && e.isControlDown())
                        ) {
                            update(true);
                            e.consume();
                        }
                        return;
                    }
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_DOWN -> move(1);
                        case KeyEvent.VK_UP -> move(-1);
                        case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            Item it = list.getSelectedValue();
                            if (it == null) {
                                hide();
                                return;
                            }
                            accept(it);
                        }
                        case KeyEvent.VK_ESCAPE -> hide();
                        default -> {
                            return;
                        }
                    }
                    e.consume();
                }
            }
        );
        field.addFocusListener(
            new FocusAdapter() {
                public void focusGained(FocusEvent e) {
                    if (field.getText().isEmpty()) later();
                }

                public void focusLost(FocusEvent e) {
                    hide();
                }
            }
        );
        field.addAncestorListener(
            new AncestorListener() {
                public void ancestorAdded(AncestorEvent e) {}

                public void ancestorRemoved(AncestorEvent e) {
                    hide();
                }

                public void ancestorMoved(AncestorEvent e) {
                    if (showing()) place();
                }
            }
        );
    }

    public boolean showing() {
        return window != null && window.isVisible();
    }

    /** Updates after the document event, when the field's text and caret are final. */
    public void later() {
        if (applying) return;
        SwingUtilities.invokeLater(() -> update(false));
    }

    public void update(boolean force) {
        if (!field.isShowing() || (!field.hasFocus() && !force)) {
            hide();
            return;
        }
        List<Item> items = source.apply(field.getText());
        model.clear();
        for (Item it : items) model.addElement(it);
        if (items.isEmpty()) {
            hide();
            return;
        }
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(10, items.size()));
        if (window == null) {
            window = new JWindow(SwingUtilities.getWindowAncestor(field));
            window.setFocusableWindowState(false);
            JScrollPane sp = new JScrollPane(list);
            sp.setBorder(new LineBorder(T.border()));
            window.add(sp);
        }
        list.setBackground(T.field());
        place();
        window.setVisible(true);
        setTabCapture(true);
    }

    public void place() {
        Point p = field.getLocationOnScreen();
        Dimension pref = list.getPreferredScrollableViewportSize();
        int w = Math.max(field.getWidth(), Math.min(560, pref.width + 24));
        window.setBounds(p.x, p.y + field.getHeight(), w, pref.height + 4);
        window.revalidate();
    }

    public void move(int d) {
        int n = model.size(),
            i = list.getSelectedIndex();
        int j = Math.floorMod(i + d, n);
        list.setSelectedIndex(j);
        list.ensureIndexIsVisible(j);
    }

    public void accept(Item it) {
        applying = true;
        try {
            field.setText(it.text());
            field.setCaretPosition(it.text().length());
        } finally {
            applying = false;
        }
        hide();
        // a level ending in '/' has more under it: show the next level straight away
        if (it.text().endsWith("/")) SwingUtilities.invokeLater(() -> update(true));
    }

    public void hide() {
        if (window != null) window.setVisible(false);
        setTabCapture(false);
    }

    /** While the list shows, Tab accepts a suggestion instead of moving focus. */
    public void setTabCapture(boolean on) {
        field.setFocusTraversalKeysEnabled(!on);
    }
}
