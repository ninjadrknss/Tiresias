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

/** Glass pane shown while files load: blocks input and, if loading takes a moment, fades in a spinner. */
public final class LoadingOverlay extends JComponent {

    public String text = "";
    public double angle, alpha;
    public final javax.swing.Timer spin = new javax.swing.Timer(16, e -> {
        angle += 0.11;
        repaint();
    });
    public final javax.swing.Timer reveal = new javax.swing.Timer(150, e ->
        animate(
            220,
            t -> {
                alpha = t;
                repaint();
            },
            null
        )
    );

    public LoadingOverlay() {
        setOpaque(false);
        setVisible(false); // a visible glass pane would swallow every click; it's only shown while loading
        addMouseListener(new MouseAdapter() {});
        addMouseMotionListener(new MouseMotionAdapter() {});
        addKeyListener(new KeyAdapter() {});
        setFocusTraversalKeysEnabled(false);
        reveal.setRepeats(false);
    }

    public void start(String message) {
        text = message;
        alpha = 0;
        setVisible(true);
        requestFocusInWindow();
        spin.start();
        reveal.restart();
    }

    public void stop() {
        reveal.stop();
        double from = alpha;
        animate(
            180,
            t -> {
                alpha = from * (1 - t);
                repaint();
            },
            () -> {
                spin.stop();
                setVisible(false);
            }
        );
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (alpha <= 0 || T == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
        g.setColor(alpha(T.panel(), 190));
        g.fillRect(0, 0, getWidth(), getHeight());
        int w = 240,
            h = 132,
            x = (getWidth() - w) / 2,
            y = (getHeight() - h) / 2;
        g.setColor(T.alt());
        g.fillRoundRect(x, y, w, h, 16, 16);
        g.setColor(T.border());
        g.drawRoundRect(x, y, w - 1, h - 1, 16, 16);
        int d = 36,
            cx = x + (w - d) / 2,
            cy = y + 26;
        g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(T.border());
        g.drawOval(cx, cy, d, d);
        g.setColor(T.accent());
        int startDeg = (int) Math.toDegrees(-angle) % 360;
        g.drawArc(cx, cy, d, d, startDeg, 100 + (int) (60 * Math.sin(angle * 0.7)));
        g.setFont(getFont() == null ? Theme.UI : getFont());
        g.setColor(T.fg());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + h - 30);
        g.dispose();
    }
}
