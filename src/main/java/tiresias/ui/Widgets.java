package tiresias.ui;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.ui.Look.*;
import static tiresias.ui.Ui.*;

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
 * Small reusable components: panels, labels, renderers, the hint field and tab extras.
 */
public final class Widgets {

    private Widgets() {}

    /** Panel whose background follows the theme: role 0 = window chrome, 1 = content panel. */
    public static class Surface extends JPanel {

        public final int role;

        public Surface(LayoutManager lm, int role) {
            super(lm);
            this.role = role;
        }

        @Override
        public Color getBackground() {
            return T == null ? super.getBackground() : role == 0 ? T.window() : T.panel();
        }
    }

    /** A row of flat buttons (replaces JToolBar, whose Metal borders don't theme). */
    public static final class Bar extends JPanel {

        public Bar() {
            super(new FlowLayout(FlowLayout.LEFT, 2, 4));
            setOpaque(false);
            setBorder(new EmptyBorder(2, 6, 2, 6));
        }

        public void sep() {
            JComponent s = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(T.border());
                    g.fillRect(getWidth() / 2, 2, 1, getHeight() - 4);
                }
            };
            s.setPreferredSize(new Dimension(11, 20));
            add(s);
        }
    }

    public static class MutedLabel extends JLabel {

        public MutedLabel(String s) {
            super(s);
        }

        @Override
        public Color getForeground() {
            return T == null ? super.getForeground() : T.muted();
        }
    }

    /** Label whose color is a semantic tone, so it re-colors when the theme changes. */
    public static final class ToneLabel extends JLabel {

        public static final int MUTED = 0,
            ERR = 1,
            WARN = 2,
            OK = 3;
        public int tone;

        public ToneLabel(String s) {
            super(s);
        }

        public void set(String text, int tone) {
            setText(text);
            this.tone = tone;
            repaint();
        }

        @Override
        public Color getForeground() {
            if (T == null) return super.getForeground();
            return switch (tone) {
                case ERR -> T.red();
                case WARN -> T.yellow();
                case OK -> T.green();
                default -> T.muted();
            };
        }
    }

    /** A tabbed pane with a component laid over the right end of its tab row (controls that belong to the tabs). */
    public static final class TabsWithTrailing extends JPanel {

        public final JTabbedPane tabs;
        public final JComponent trailing;

        public TabsWithTrailing(JTabbedPane tabs, JComponent trailing) {
            super(null);
            this.tabs = tabs;
            this.trailing = trailing;
            setOpaque(false);
            add(trailing); // first child paints on top
            add(tabs);
        }

        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false; // the children overlap
        }

        @Override
        public void doLayout() {
            int w = getWidth(),
                h = getHeight();
            tabs.setBounds(0, 0, w, h);
            Dimension p = trailing.getPreferredSize();
            int row = p.height;
            if (tabs.getTabCount() > 0) {
                Rectangle r = tabs.getBoundsAt(0);
                if (r != null) row = r.y + r.height;
            }
            trailing.setBounds(Math.max(0, w - p.width - 8), Math.max(0, (row - p.height) / 2), p.width, p.height);
        }

        @Override
        public Dimension getPreferredSize() {
            return tabs.getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return tabs.getMinimumSize();
        }
    }

    /** Wraps content so it can fade in, e.g. when the editor switches to another message. */
    public static final class FadeUI extends javax.swing.plaf.LayerUI<JComponent> {

        public float alpha = 1f;
        public javax.swing.Timer timer;

        public void fadeIn(JLayer<?> layer) {
            if (timer != null) timer.stop();
            timer = animate(
                200,
                t -> {
                    alpha = (float) (0.2 + 0.8 * t);
                    layer.repaint();
                },
                () -> alpha = 1f
            );
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            if (alpha >= 1f) {
                super.paint(g, c);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
            super.paint(g2, c);
            g2.dispose();
        }
    }

    // ======================================================================================
    // Small UI helpers
    // ======================================================================================

    public record SevIcon(Severity s) implements Icon {
        public static final SevIcon ERR = new SevIcon(Severity.ERROR),
            WRN = new SevIcon(Severity.WARN),
            INF = new SevIcon(Severity.INFO),
            OK = new SevIcon(null);

        public static Icon of(Severity s) {
            if (s == null) return OK;
            return switch (s) {
                case ERROR -> ERR;
                case WARN -> WRN;
                case INFO -> INF;
            };
        }

        public void paintIcon(Component comp, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            if (s == null) {
                g2.setColor(alpha(T.green(), 150));
                g2.fillOval(x + 3, y + 3, 6, 6);
            } else {
                g2.setColor(s.color());
                g2.fillOval(x + 1, y + 1, 10, 10);
            }
            g2.dispose();
        }

        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }
    }

    public static final class HintField extends JTextField {

        public final String hint;

        public HintField(String hint, int cols) {
            super(cols);
            this.hint = hint;
            addFocusListener(
                new FocusAdapter() {
                    public void focusGained(FocusEvent e) {
                        repaint();
                    }

                    public void focusLost(FocusEvent e) {
                        repaint();
                    }
                }
            );
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(T.muted());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint, in.left + 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        }
    }

    public interface RowStyle {
        void style(JTable t, int row, int col, boolean sel, JComponent c);
    }

    public static final class StyledRenderer extends DefaultTableCellRenderer {

        public final RowStyle rs;

        public StyledRenderer(RowStyle rs) {
            this.rs = rs;
        }

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            setToolTipText(null);
            setFont(t.getFont());
            setForeground(sel ? T.bright() : T.fg());
            setBackground(sel ? T.selection() : row % 2 == 1 ? T.alt() : T.panel());
            if (rs != null) rs.style(t, row, col, sel, this);
            return this;
        }
    }

    public static final class StyledBoolRenderer extends JCheckBox implements TableCellRenderer {

        public final RowStyle rs;

        public StyledBoolRenderer(RowStyle rs) {
            this.rs = rs;
            setHorizontalAlignment(CENTER);
            setBorderPainted(false);
            setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            setSelected(Boolean.TRUE.equals(v));
            setToolTipText(null);
            setForeground(sel ? T.bright() : T.fg());
            setBackground(sel ? T.selection() : row % 2 == 1 ? T.alt() : T.panel());
            rs.style(t, row, col, sel, this);
            return this;
        }
    }
}
