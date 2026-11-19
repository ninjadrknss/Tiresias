package tiresias.ui;

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
import tiresias.traffic.dashboards.*;

/** Tiles in a wrapping grid: hover, click, arrow keys and Enter; compact tiles when full ones don't fit. */
public abstract class TileGrid<T> extends JComponent implements Scrollable {

    public static final int MIN_W = 200,
        FULL_H = 70,
        COMPACT_H = 48,
        GAP = 10,
        PAD = 16;
    public final Function<T, Severity> severity;
    public final Consumer<T> open;
    public List<T> shown = new ArrayList<>();
    public int hover = -1,
        sel = -1,
        lastPrefH = -1;
    public String empty = "";

    // The subclasses (MessageGrid, FileGrid) don't override the Swing setters called here, so they're safe to call.
    @SuppressWarnings("this-escape")
    protected TileGrid(Function<T, Severity> severity, Consumer<T> open) {
        this.severity = severity;
        this.open = open;
        setFocusable(true);
        setToolTipText("");
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setHover(hit(e.getPoint()));
            }

            // Tooltips here are the tile's contents, so show them at once and keep them up while hovering;
            // the app-wide delays go back when the mouse leaves the grid.
            int savedInitial = -1,
                savedDismiss = -1;

            @Override
            public void mouseEntered(MouseEvent e) {
                ToolTipManager t = ToolTipManager.sharedInstance();
                if (savedInitial < 0) {
                    savedInitial = t.getInitialDelay();
                    savedDismiss = t.getDismissDelay();
                }
                t.setInitialDelay(0);
                t.setDismissDelay(60_000);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(-1);
                if (savedInitial >= 0) {
                    ToolTipManager t = ToolTipManager.sharedInstance();
                    t.setInitialDelay(savedInitial);
                    t.setDismissDelay(savedDismiss);
                    savedInitial = savedDismiss = -1;
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = hit(e.getPoint());
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e)) {
                    sel = i;
                    open.accept(shown.get(i));
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT -> move(-1);
                        case KeyEvent.VK_RIGHT -> move(1);
                        case KeyEvent.VK_UP -> move(-cols());
                        case KeyEvent.VK_DOWN -> move(cols());
                        case KeyEvent.VK_HOME -> select(0);
                        case KeyEvent.VK_END -> select(shown.size() - 1);
                        case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                            if (sel >= 0 && sel < shown.size()) open.accept(shown.get(sel));
                        }
                        default -> {
                            return;
                        }
                    }
                    e.consume();
                }
            }
        );
        addFocusListener(
            new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    if (sel < 0 && !shown.isEmpty()) sel = 0;
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            }
        );
        addComponentListener(
            new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    int h = getPreferredSize().height;
                    if (h != lastPrefH) {
                        lastPrefH = h;
                        revalidate();
                    }
                }
            }
        );
    }

    public void setItems(List<T> ms, String emptyText) {
        T keep = sel >= 0 && sel < shown.size() ? shown.get(sel) : null;
        shown = new ArrayList<>(ms);
        empty = emptyText;
        sel = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i) == keep) sel = i;
        hover = -1;
        revalidate();
        repaint();
    }

    public T first() {
        return shown.isEmpty() ? null : shown.getFirst();
    }

    public static int colsFor(int width) {
        return Math.max(1, (Math.max(width, MIN_W + 2 * PAD) - 2 * PAD + GAP) / (MIN_W + GAP));
    }

    public int cols() {
        return colsFor(getWidth());
    }

    public static int heightFor(int count, int cols, int tileH) {
        int rows = Math.max(1, (count + cols - 1) / cols);
        return 2 * PAD + rows * tileH + (rows - 1) * GAP;
    }

    /** Two-line tiles when the full-size ones wouldn't all fit, so a whole file usually shows at once. */
    public boolean compact() {
        return (
            getParent() instanceof JViewport v &&
            v.getHeight() > 0 &&
            heightFor(shown.size(), colsFor(v.getWidth()), FULL_H) > v.getHeight()
        );
    }

    public int tileH() {
        return compact() ? COMPACT_H : FULL_H;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (getParent() instanceof JViewport v && v.getClientProperty("grid#listener") == null) {
            v.putClientProperty("grid#listener", Boolean.TRUE);
            // Scrolling moves the tiles under a still mouse without any mouseMoved, so re-hit-test
            // and tell the tooltip manager the mouse "moved" so it shows the new tile's tooltip.
            v.addChangeListener(e -> {
                Point p = getMousePosition();
                setHover(p == null ? -1 : hit(p));
                if (p != null) {
                    ToolTipManager.sharedInstance().mouseMoved(
                        new MouseEvent(this, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, p.x, p.y, 0, false)
                    );
                }
            });
            v.addComponentListener(
                new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        revalidate();
                        repaint();
                    }
                }
            );
        }
    }

    public Rectangle tile(int i) {
        int c = cols(),
            w = (getWidth() - 2 * PAD - (c - 1) * GAP) / c,
            h = tileH();
        return new Rectangle(PAD + (i % c) * (w + GAP), PAD + (i / c) * (h + GAP), w, h);
    }

    public int hit(Point p) {
        int c = cols(),
            w = (getWidth() - 2 * PAD - (c - 1) * GAP) / c;
        if (p.x < PAD || p.y < PAD || w <= 0) return -1;
        int col = (p.x - PAD) / (w + GAP),
            row = (p.y - PAD) / (tileH() + GAP);
        int i = row * c + col;
        return col < c && i < shown.size() && tile(i).contains(p) ? i : -1;
    }

    public void select(int i) {
        if (shown.isEmpty()) return;
        sel = Math.clamp(i, 0, shown.size() - 1);
        scrollRectToVisible(tile(sel));
        repaint();
    }

    public void move(int d) {
        select(sel < 0 ? 0 : sel + d);
    }

    public void setHover(int i) {
        if (i == hover) return;
        hover = i;
        setCursor(i >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        repaint();
    }

    // ---- what a tile shows; subclasses fill these in

    /** Bold mono text at the top left (a message ID, a file name). */
    public abstract String key(T item);

    /** True when the key is a placeholder and should be drawn muted. */
    public boolean keyMuted(T item) {
        return false;
    }

    /** Small muted note after the key on full-size tiles, or null. */
    public String badge(T item) {
        return null;
    }

    public abstract String title(T item);

    public abstract String detail(T item);

    public abstract String tooltip(T item);

    @Override
    protected void paintComponent(Graphics g0) {
        if (T == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setColor(T.panel());
        g.fillRect(0, 0, getWidth(), getHeight());
        Font f = Theme.UI;
        Font bold = f.deriveFont(Font.BOLD),
            small = f.deriveFont(f.getSize2D() - 1.5f),
            id = mono(Font.BOLD, f.getSize2D());
        if (shown.isEmpty()) {
            g.setFont(f);
            g.setColor(T.muted());
            g.drawString(empty, PAD + 4, PAD + 20);
            g.dispose();
            return;
        }
        Rectangle clip = g.getClipBounds();
        boolean compact = compact();
        for (int i = 0; i < shown.size(); i++) {
            Rectangle r = tile(i);
            if (clip != null && !clip.intersects(r)) continue;
            T m = shown.get(i);
            boolean hot = i == hover,
                focused = i == sel && isFocusOwner();
            Severity s = severity.apply(m);
            g.setColor(hot ? blend(T.fg(), T.alt(), 0.07) : T.alt());
            g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setStroke(new BasicStroke(focused ? 2f : 1f));
            g.setColor(focused ? T.accent() : hot ? T.border() : T.hairline());
            g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 12, 12);
            if (s != null) {
                g.setColor(s.color());
                g.fillRoundRect(r.x, r.y + 10, 3, r.height - 20, 3, 3);
            }
            int x = r.x + 13,
                w = r.width - 26;
            g.setFont(id);
            FontMetrics fm = g.getFontMetrics();
            String idText = ellipsize(key(m), fm, w - 16);
            g.setColor(keyMuted(m) ? T.muted() : T.accent());
            g.drawString(idText, x, r.y + 21);
            if (compact) {
                int dx = x + fm.stringWidth(idText) + 8;
                g.setFont(bold);
                g.setColor(T.bright());
                g.drawString(ellipsize(title(m), g.getFontMetrics(), r.x + r.width - dx - 24), dx, r.y + 21);
                g.setFont(small);
                g.setColor(T.muted());
                g.drawString(ellipsize(detail(m), g.getFontMetrics(), w), x, r.y + 39);
                if (s != null) {
                    g.setColor(s.color());
                    g.fillOval(r.x + r.width - 18, r.y + 13, 8, 8);
                }
                continue;
            }
            String badge = badge(m);
            if (badge != null) {
                g.setFont(small);
                g.setColor(T.muted());
                g.drawString(badge, x + fm.stringWidth(idText) + 8, r.y + 21);
            }
            if (s != null) {
                g.setColor(s.color());
                g.fillOval(r.x + r.width - 18, r.y + 12, 8, 8);
            }
            g.setFont(bold);
            g.setColor(T.bright());
            g.drawString(ellipsize(title(m), g.getFontMetrics(), w), x, r.y + 42);
            g.setFont(small);
            g.setColor(T.muted());
            g.drawString(ellipsize(detail(m), g.getFontMetrics(), w), x, r.y + 60);
        }
        g.dispose();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int i = hit(e.getPoint());
        return i < 0 ? null : tooltip(shown.get(i));
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getParent() instanceof JViewport v ? v.getWidth() : Math.max(getWidth(), 640);
        return new Dimension(MIN_W + 2 * PAD, heightFor(shown.size(), colsFor(w), tileH()));
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
        return Math.max(tileH() + GAP, r.height - tileH());
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
