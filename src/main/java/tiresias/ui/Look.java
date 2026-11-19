package tiresias.ui;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
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
import tiresias.traffic.dashboards.*;

/**
 * The custom look: Swing UI delegates, borders and icons painted with the theme T.
 */
public final class Look {

    private Look() {}

    public static final class PaletteMetalTheme extends javax.swing.plaf.metal.DefaultMetalTheme {

        public final Theme t;

        public PaletteMetalTheme(Theme t) {
            this.t = t;
        }

        @Override
        public String getName() {
            return t.name();
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return new ColorUIResource(t.accent());
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return new ColorUIResource(t.accent());
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return new ColorUIResource(t.selection());
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return new ColorUIResource(t.border());
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return new ColorUIResource(t.border());
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return new ColorUIResource(t.panel());
        }

        @Override
        protected ColorUIResource getBlack() {
            return new ColorUIResource(t.fg());
        }

        @Override
        protected ColorUIResource getWhite() {
            return new ColorUIResource(t.field());
        }

        @Override
        public ColorUIResource getControlHighlight() {
            return new ColorUIResource(t.panel());
        }

        @Override
        public ColorUIResource getMenuBackground() {
            return new ColorUIResource(t.panel());
        }

        @Override
        public ColorUIResource getMenuSelectedBackground() {
            return new ColorUIResource(t.selection());
        }

        @Override
        public ColorUIResource getMenuSelectedForeground() {
            return new ColorUIResource(t.bright());
        }

        @Override
        public ColorUIResource getHighlightedTextColor() {
            return new ColorUIResource(t.bright());
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return new ColorUIResource(t.muted());
        }

        @Override
        public ColorUIResource getInactiveSystemTextColor() {
            return new ColorUIResource(t.muted());
        }

        public FontUIResource f() {
            return new FontUIResource(Theme.UI);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return f();
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return f();
        }

        @Override
        public FontUIResource getUserTextFont() {
            return f();
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return f();
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return new FontUIResource(Theme.UI.deriveFont(Font.BOLD));
        }

        @Override
        public FontUIResource getSubTextFont() {
            return new FontUIResource(Theme.UI.deriveFont(Theme.UI.getSize2D() - 2));
        }
    }

    public static final class FlatScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatScrollBarUI();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            scrollbar.setOpaque(false);
        }

        @Override
        protected void configureScrollBarColors() {}

        public static JButton none() {
            JButton b = new JButton();
            Dimension z = new Dimension(0, 0);
            b.setPreferredSize(z);
            b.setMinimumSize(z);
            b.setMaximumSize(z);
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return none();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return none();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            boolean hot = isThumbRollover() || isDragging;
            g2.setColor(alpha(T.fg(), hot ? 110 : 60));
            int pad = 3;
            int arc = Math.min(r.width, r.height) - 2 * pad;
            g2.fillRoundRect(r.x + pad, r.y + pad, r.width - 2 * pad, r.height - 2 * pad, arc, arc);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return scrollbar.getOrientation() == JScrollBar.VERTICAL ? new Dimension(12, 48) : new Dimension(48, 12);
        }
    }

    public static final class FlatButtonUI extends javax.swing.plaf.basic.BasicButtonUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatButtonUI();
        }

        @Override
        protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            LookAndFeel.installProperty(b, "opaque", Boolean.FALSE);
            b.setRolloverEnabled(true);
            if (b.getBorder() == null || b.getBorder() instanceof UIResource) b.setBorder(
                new BorderUIResource(new EmptyBorder(5, 12, 5, 12))
            );
        }

        /** Starts a hover fade whenever the button's rollover state changes. */
        public static final ChangeListener HOVER = e -> {
            AbstractButton b = (AbstractButton) e.getSource();
            double target = b.getModel().isRollover() && b.isEnabled() ? 1 : 0;
            if (!Objects.equals(b.getClientProperty("hover#target"), target)) {
                b.putClientProperty("hover#target", target);
                fadeTo(b, "hover", target);
            }
        };

        @Override
        protected void installListeners(AbstractButton b) {
            super.installListeners(b);
            b.addChangeListener(HOVER);
        }

        @Override
        protected void uninstallListeners(AbstractButton b) {
            b.removeChangeListener(HOVER);
            super.uninstallListeners(b);
        }

        public static boolean primary(AbstractButton b) {
            return Boolean.TRUE.equals(b.getClientProperty("primary"));
        }

        public static boolean flat(AbstractButton b) {
            return Boolean.TRUE.equals(b.getClientProperty("flat")) || b.getParent() instanceof Bar;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            boolean primary = primary(b),
                flat = !primary && flat(b);
            Color base = primary ? T.accent() : T.button();
            Color bg = base;
            Color hovered = primary ? blend(Color.WHITE, base, 0.14) : blend(T.fg(), base, 0.09);
            double hover = fade(b, "hover");
            if (!b.isEnabled()) bg = primary ? blend(T.accent(), T.panel(), 0.4) : (flat ? null : base);
            else if (m.isArmed() && m.isPressed()) bg = blend(T.fg(), base, primary ? 0.25 : 0.16);
            else if (flat) bg = hover > 0.01 ? alpha(hovered, (int) (255 * hover)) : null;
            else bg = blend(hovered, base, hover);
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int w = c.getWidth(),
                h = c.getHeight();
            if (bg != null) {
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w, h, 8, 8);
            }
            if (!flat && !primary) {
                g2.setColor(T.border());
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            }
            if (b.isFocusOwner()) {
                g2.setColor(T.accent());
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            }
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
            Graphics2D g2 = (Graphics2D) g;
            aa(g2);
            g2.setColor(!b.isEnabled() ? T.muted() : primary(b) ? onColor(T.accent()) : T.fg());
            g2.setFont(b.getFont());
            g2.drawString(text, r.x, r.y + g2.getFontMetrics().getAscent());
        }
    }

    public static final class ArrowButton extends JButton {

        public ArrowButton() {
            setFocusable(false);
            setBorder(new EmptyBorder(0, 4, 0, 8));
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        public void updateUI() {
            setUI(new javax.swing.plaf.basic.BasicButtonUI());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(22, 16);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(getModel().isRollover() ? T.fg() : T.muted());
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2 - 2,
                cy = getHeight() / 2;
            g2.drawPolyline(new int[] { cx - 4, cx, cx + 4 }, new int[] { cy - 2, cy + 2, cy - 2 }, 3);
            g2.dispose();
        }
    }

    public static final class FlatComboBoxUI extends javax.swing.plaf.basic.BasicComboBoxUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatComboBoxUI();
        }

        @Override
        protected JButton createArrowButton() {
            return new ArrowButton();
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            return new javax.swing.plaf.basic.BasicComboPopup(comboBox) {
                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setBorder(new LineBorder(T.border()));
                }
            };
        }

        @Override
        public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
            super.paintCurrentValue(g, bounds, false);
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            g.setColor(comboBox.isEnabled() ? comboBox.getBackground() : T.panel());
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    public static final class FlatTabbedPaneUI extends javax.swing.plaf.basic.BasicTabbedPaneUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatTabbedPaneUI();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(8, 14, 8, 14);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            tabAreaInsets = new Insets(0, 8, 0, 8);
            contentBorderInsets = new Insets(1, 0, 0, 0);
        }

        @Override
        protected void paintTabBackground(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {}

        @Override
        protected void paintTabBorder(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {}

        public float underX = -1,
            underW;
        public javax.swing.Timer slide;
        public final ChangeListener slideToSelected = e -> {
            JTabbedPane tp = tabPane;
            int i = tp.getSelectedIndex();
            if (i < 0 || underX < 0 || i >= tp.getTabCount()) return;
            Rectangle r = getTabBounds(tp, i);
            float fromX = underX,
                fromW = underW;
            if (slide != null) slide.stop();
            slide = animate(
                220,
                t -> {
                    underX = (float) (fromX + (r.x + 6 - fromX) * t);
                    underW = (float) (fromW + (r.width - 12 - fromW) * t);
                    tp.repaint();
                },
                null
            );
        };

        @Override
        protected void installListeners() {
            super.installListeners();
            tabPane.addChangeListener(slideToSelected);
        }

        @Override
        protected void uninstallListeners() {
            if (slide != null) slide.stop();
            tabPane.removeChangeListener(slideToSelected);
            super.uninstallListeners();
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            int i = tabPane.getSelectedIndex();
            if (i < 0 || i >= tabPane.getTabCount()) return;
            Rectangle r = getTabBounds(tabPane, i);
            if (underX < 0 || slide == null || !slide.isRunning()) {
                underX = r.x + 6;
                underW = r.width - 12;
            }
            g.setColor(T.accent());
            g.fillRect(Math.round(underX), r.y + r.height - 2, Math.round(underW), 2);
        }

        @Override
        protected void paintContentBorder(Graphics g, int placement, int selected) {
            int y = calculateTabAreaHeight(placement, runCount, maxTabHeight);
            g.setColor(T.hairline());
            g.fillRect(0, y, tabPane.getWidth(), 1);
        }

        @Override
        protected void paintFocusIndicator(
            Graphics g,
            int p,
            Rectangle[] r,
            int i,
            Rectangle ir,
            Rectangle tr,
            boolean sel
        ) {}

        @Override
        protected void paintText(
            Graphics g,
            int placement,
            Font font,
            FontMetrics m,
            int i,
            String title,
            Rectangle tr,
            boolean sel
        ) {
            Graphics2D g2 = (Graphics2D) g;
            aa(g2);
            Color custom = tabPane.getForegroundAt(i);
            boolean isCustom = custom != null && !(custom instanceof UIResource);
            g2.setColor(isCustom ? custom : sel ? T.bright() : T.muted());
            g2.setFont(font);
            g2.drawString(title, tr.x, tr.y + m.getAscent());
        }

        @Override
        protected int getTabLabelShiftY(int p, int i, boolean sel) {
            return 0;
        }

        @Override
        protected int getTabLabelShiftX(int p, int i, boolean sel) {
            return 0;
        }
    }

    public static final class FlatSplitPaneUI extends javax.swing.plaf.basic.BasicSplitPaneUI {

        @SuppressWarnings("unused") // called reflectively by Swing's UIManager
        public static ComponentUI createUI(JComponent c) {
            return new FlatSplitPaneUI();
        }

        @Override
        public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
            return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                @Override
                public void setBorder(Border b) {}

                @Override
                public void paint(Graphics g) {
                    g.setColor(T.window());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(T.hairline());
                    if (splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) g.fillRect(
                        getWidth() / 2,
                        0,
                        1,
                        getHeight()
                    );
                    else g.fillRect(0, getHeight() / 2, getWidth(), 1);
                }
            };
        }
    }

    /** Rounded input border that lights up in the accent color while focused. */
    public static final class FieldBorder extends AbstractBorder implements UIResource {

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean foc = fo != null && (fo == c || SwingUtilities.isDescendingFrom(fo, c));
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(foc ? T.accent() : T.border());
            g2.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(4, 7, 4, 7);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(4, 7, 4, 7);
            return i;
        }
    }

    /** Hairline separator drawn on the chosen sides in the current theme's line color. */
    public static final class Line extends AbstractBorder implements UIResource {

        public final int t, l, b, r;

        public Line(int t, int l, int b, int r) {
            this.t = t;
            this.l = l;
            this.b = b;
            this.r = r;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(T.hairline());
            if (t > 0) g.fillRect(x, y, w, t);
            if (b > 0) g.fillRect(x, y + h - b, w, b);
            if (l > 0) g.fillRect(x, y, l, h);
            if (r > 0) g.fillRect(x + w - r, y, r, h);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(t, l, b, r);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(t, l, b, r);
            return i;
        }
    }

    public record CheckIcon(boolean radio) implements Icon, UIResource {
        public int getIconWidth() {
            return 16;
        }

        public int getIconHeight() {
            return 16;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            boolean sel = b.getModel().isSelected(),
                en = b.isEnabled(),
                roll = b.getModel().isRollover();
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            int s = 14,
                ox = x + 1,
                oy = y + 1;
            Color fill = sel ? (en ? T.accent() : blend(T.accent(), T.panel(), 0.4)) : T.field();
            Color stroke = sel ? fill : roll ? T.muted() : blend(T.fg(), T.field(), 0.32);
            Color mark = onColor(T.accent());
            if (radio) {
                g2.setColor(fill);
                g2.fillOval(ox, oy, s, s);
                g2.setColor(stroke);
                g2.drawOval(ox, oy, s - 1, s - 1);
                if (sel) {
                    g2.setColor(mark);
                    g2.fillOval(ox + 4, oy + 4, s - 8, s - 8);
                }
            } else {
                g2.setColor(fill);
                g2.fillRoundRect(ox, oy, s, s, 5, 5);
                g2.setColor(stroke);
                g2.drawRoundRect(ox, oy, s - 1, s - 1, 5, 5);
                if (sel) {
                    g2.setColor(mark);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[] { ox + 3, ox + 6, ox + 11 }, new int[] { oy + 7, oy + 10, oy + 4 }, 3);
                }
            }
            g2.dispose();
        }
    }

    public record ChevronIcon(boolean down) implements Icon, UIResource {
        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 12;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            aa(g2);
            g2.setColor(T.muted());
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (down) g2.drawPolyline(new int[] { x + 3, x + 6, x + 9 }, new int[] { y + 4, y + 7, y + 4 }, 3);
            else g2.drawPolyline(new int[] { x + 4, x + 7, x + 4 }, new int[] { y + 3, y + 6, y + 9 }, 3);
            g2.dispose();
        }
    }
}
