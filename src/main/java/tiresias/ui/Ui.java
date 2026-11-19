package tiresias.ui;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.ui.Look.*;
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

/**
 * Shared UI helpers: the current theme T, color math, painting, small component factories and animations.
 */
public final class Ui {

    private Ui() {}

    public static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ======================================================================================
    // Theming: a flat look built on Metal, with switchable palettes (One Dark by default)
    // ======================================================================================

    public static Theme T;

    public static Color blend(Color a, Color b, double t) {
        return new Color(
            (int) Math.round(a.getRed() * t + b.getRed() * (1 - t)),
            (int) Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
            (int) Math.round(a.getBlue() * t + b.getBlue() * (1 - t))
        );
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    public static String hx(Color c) {
        return "#%06X".formatted(c.getRGB() & 0xFFFFFF);
    }

    public static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    public static Color onColor(Color bg) {
        return luminance(bg) > 0.55 ? new Color(0x1B1D22) : Color.WHITE;
    }

    public static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
        else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    public static String ellipsize(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n) + "…";
            if (fm.stringWidth(t) <= w) return t;
        }
        return "";
    }

    /** Accepts files dropped onto a window and hands them to open. */
    public static TransferHandler fileDrop(Consumer<List<Path>> open) {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @SuppressWarnings("unchecked")
            @Override
            public boolean importData(TransferSupport s) {
                try {
                    open.accept(
                        ((List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor))
                            .stream()
                            .map(File::toPath)
                            .toList()
                    );
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
    }

    // ---------- data colors (the editor's tables color values by kind, always from the theme)

    /**
     * A topic as HTML with its parts colored: the node (first level) blue, the last level bright, levels between in
     * the normal text color, slashes muted and {n} placeholders orange.
     */
    public static String topicHtml(String topic) {
        if (topic == null || topic.isEmpty()) return "";
        String[] lv = topic.split("/", -1);
        StringBuilder b = new StringBuilder("<html><nobr>");
        for (int i = 0; i < lv.length; i++) {
            if (i > 0) b.append(font(T.muted(), "/"));
            Color c = i == 0 && lv.length > 1 ? T.blue() : i == lv.length - 1 ? T.bright() : T.fg();
            // placeholders such as {1} stand out in orange
            java.util.regex.Matcher m = TOPIC_PLACEHOLDER.matcher(lv[i]);
            int at = 0;
            while (m.find()) {
                if (m.start() > at) b.append(font(c, lv[i].substring(at, m.start())));
                b.append(font(T.orange(), m.group()));
                at = m.end();
            }
            if (at < lv[i].length()) b.append(font(c, lv[i].substring(at)));
        }
        return b.append("</nobr></html>").toString();
    }

    public static final java.util.regex.Pattern TOPIC_PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\d+\\}");

    /** Escaped text in a color, for building HTML labels. */
    public static String font(Color c, String text) {
        return "<font color='" + hx(c) + "'>" + escHtml(text) + "</font>";
    }

    /** A Rust type's color: bool green, unsigned blue, signed purple, floats orange, anything else yellow. */
    public static Color typeColor(String rust) {
        if (rust == null || rust.isEmpty()) return T.fg();
        if (rust.equals("bool")) return T.green();
        if (rust.matches("u\\d+")) return T.blue();
        if (rust.matches("i\\d+")) return T.yellow();
        if (rust.matches("f\\d+")) return T.purple();
        return T.fg();
    }

    /** Sizes a narrow column (such as a row number) to fit the sample text plus the cell padding, at any font size. */
    public static void fitColumn(JTable t, int col, String sample) {
        int w = t.getFontMetrics(t.getFont()).stringWidth(sample) + 26;
        javax.swing.table.TableColumn c = t.getColumnModel().getColumn(col);
        c.setMinWidth(w);
        c.setPreferredWidth(w);
        c.setMaxWidth(w + 8);
    }

    public static void styleTable(JTable t) {
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setRowHeight(Math.max(26, t.getFontMetrics(t.getFont()).getHeight() + 10));
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
    }

    /** Calls frame(t) as t eases from 0 to 1 over ms milliseconds (ease-out cubic), then done. Runs on the Swing thread. */
    public static javax.swing.Timer animate(int ms, DoubleConsumer frame, Runnable done) {
        javax.swing.Timer timer = new javax.swing.Timer(15, null);
        if (!Settings.animations) {
            frame.accept(1);
            if (done != null) done.run();
            return timer;
        }
        long start = System.nanoTime();
        timer.addActionListener(e -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1e6 / ms);
            frame.accept(1 - Math.pow(1 - t, 3));
            if (t >= 1) {
                timer.stop();
                if (done != null) done.run();
            }
        });
        timer.setInitialDelay(0);
        timer.start();
        return timer;
    }

    /** Animates a 0..1 client property toward target and repaints: used for hover fades. */
    public static void fadeTo(JComponent c, String key, double target) {
        double from = fade(c, key);
        if (from == target) return;
        if (c.getClientProperty(key + "#timer") instanceof javax.swing.Timer t) t.stop();
        c.putClientProperty(
            key + "#timer",
            animate(
                150,
                p -> {
                    c.putClientProperty(key, from + (target - from) * p);
                    c.repaint();
                },
                null
            )
        );
    }

    public static double fade(JComponent c, String key) {
        return c.getClientProperty(key) instanceof Double d ? d : 0;
    }

    /** Runs change, fading a snapshot of the window's old look out over the new one. */
    public static void crossfade(JRootPane root, int ms, Runnable change) {
        JLayeredPane lp = root.getLayeredPane();
        if (!Settings.animations || !root.isShowing() || lp.getWidth() <= 0) {
            change.run();
            return;
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            lp.getWidth(),
            lp.getHeight(),
            java.awt.image.BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = img.createGraphics();
        lp.paint(g);
        g.dispose();
        float[] alpha = { 1f };
        JComponent ghost = new JComponent() {
            @Override
            protected void paintComponent(Graphics gg) {
                Graphics2D g2 = (Graphics2D) gg.create();
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha[0]));
                g2.drawImage(img, 0, 0, null);
                g2.dispose();
            }
        };
        ghost.setBounds(0, 0, lp.getWidth(), lp.getHeight());
        lp.add(ghost, JLayeredPane.DRAG_LAYER);
        change.run();
        animate(
            ms,
            t -> {
                alpha[0] = (float) (1 - t);
                ghost.repaint();
            },
            () -> {
                lp.remove(ghost);
                lp.repaint();
            }
        );
    }

    public static Font mono(int style, float size) {
        Theme.initFonts();
        return Theme.MONO.deriveFont(style, size);
    }

    public static JButton button(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        if (tip != null) b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    public static JComboBox<String> editableCombo(Collection<String> items) {
        JComboBox<String> c = new JComboBox<>(items.toArray(new String[0]));
        c.setEditable(true);
        return c;
    }

    /** Redraws a table's data, keeping its selected rows and selected column. */
    public static void refreshTable(JTable t, AbstractTableModel m) {
        int[] sel = t.getSelectedRows();
        ListSelectionModel cols = t.getColumnModel().getSelectionModel();
        int col = cols.getLeadSelectionIndex();
        m.fireTableDataChanged();
        if (col >= 0 && col < t.getColumnCount()) cols.setSelectionInterval(col, col);
        for (int r : sel) if (r < t.getRowCount()) t.addRowSelectionInterval(r, r);
    }

    public static void selectRow(JTable t, int r) {
        if (r < 0 || r >= t.getRowCount()) return;
        t.setRowSelectionInterval(r, r);
        t.scrollRectToVisible(t.getCellRect(r, 0, true));
    }

    public static void stopEditing(JTable t) {
        if (
            t.isEditing() && t.getCellEditor() != null && !t.getCellEditor().stopCellEditing()
        ) t.getCellEditor().cancelCellEditing();
    }
}
