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

/** Tree with full-width selection and hover rows. */
public final class FullRowTree extends JTree {

    public int hoverRow = -1,
        leavingRow = -1;
    public double hoverT = 1;
    public javax.swing.Timer hoverAnim;

    public void setHover(int r) {
        if (r == hoverRow) return;
        leavingRow = hoverRow;
        hoverRow = r;
        if (hoverAnim != null) hoverAnim.stop();
        hoverAnim = animate(
            160,
            t -> {
                hoverT = t;
                repaint();
            },
            () -> leavingRow = -1
        );
    }

    public void paintHover(Graphics g, int row, double amount) {
        Rectangle r = row < 0 || amount <= 0 ? null : getRowBounds(row);
        if (r == null) return;
        g.setColor(blend(T.fg(), T.window(), 0.06 * amount));
        g.fillRect(0, r.y, getWidth(), r.height);
    }

    public FullRowTree(TreeModel m) {
        super(m);
        setOpaque(false);
        addMouseMotionListener(
            new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    int r = getClosestRowForLocation(e.getX(), e.getY());
                    Rectangle b = r < 0 ? null : getRowBounds(r);
                    if (b == null || e.getY() < b.y || e.getY() >= b.y + b.height) r = -1;
                    setHover(r);
                }
            }
        );
        addMouseListener(
            new MouseAdapter() {
                public void mouseExited(MouseEvent e) {
                    setHover(-1);
                }
            }
        );
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
    public void updateUI() {
        super.updateUI();
        setOpaque(false);
        setRowHeight(26);
        putClientProperty("JTree.lineStyle", "None");
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(T.window());
        g.fillRect(0, 0, getWidth(), getHeight());
        paintHover(g, hoverRow, hoverT);
        paintHover(g, leavingRow, 1 - hoverT);
        int[] rows = getSelectionRows();
        if (rows != null) for (int row : rows) {
            Rectangle r = getRowBounds(row);
            if (r == null) continue;
            g.setColor(isFocusOwner() ? T.selection() : blend(T.selection(), T.window(), 0.6));
            g.fillRect(0, r.y, getWidth(), r.height);
            g.setColor(T.accent());
            g.fillRect(0, r.y, 3, r.height);
        }
        super.paintComponent(g);
    }
}
