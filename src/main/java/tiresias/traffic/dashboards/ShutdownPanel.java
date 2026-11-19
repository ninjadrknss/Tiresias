package tiresias.traffic.dashboards;

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
import tiresias.ui.*;

/** The shutdown loop above the traffic table: where it's open, every change in the log, and spec mismatches. */
public final class ShutdownPanel extends JPanel {

    public record Change(double ts, Map<String, Boolean> pins) {}

    public final List<Change> changes = new ArrayList<>();
    public final ToneLabel headline = new ToneLabel(" ");
    public final JLabel when = new MutedLabel(" ");
    public final JComboBox<String> history = new JComboBox<>();
    public final ToneLabel notes = new ToneLabel(" ");
    public final LoopDiagram diagram = new LoopDiagram();
    public Map<String, Object> msg;
    public Map<String, Boolean> latest;
    public double latestTs = Double.NaN,
        t0 = Double.NaN;
    public int frames,
        listed = -1;
    public boolean dirty, updating;

    public ShutdownPanel() {
        super(new BorderLayout(0, 2));
        setBorder(new CompoundBorder(new Line(0, 0, 1, 0), new EmptyBorder(8, 14, 6, 14)));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2));
        history.setToolTipText("Every time the loop's state changed. Pick one to see it; Latest follows new frames.");
        history.setPrototypeDisplayValue("00000.000 s   open at Energy meter interlock …");
        history.addActionListener(e -> {
            if (!updating) showState(true);
        });
        JPanel text = new JPanel(new GridLayout(0, 1, 0, 2));
        text.setOpaque(false);
        text.add(headline);
        text.add(when);
        JPanel pick = new JPanel(new GridBagLayout());
        pick.setOpaque(false);
        pick.add(history);
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.add(text, BorderLayout.CENTER);
        top.add(pick, BorderLayout.EAST);
        notes.tone = ToneLabel.WARN;
        add(top, BorderLayout.NORTH);
        add(diagram, BorderLayout.CENTER);
        add(notes, BorderLayout.SOUTH);
        setVisible(false);
    }

    public boolean hasData() {
        return latest != null;
    }

    public void reset() {
        changes.clear();
        msg = null;
        latest = null;
        latestTs = Double.NaN;
        frames = 0;
        listed = -1;
        dirty = true;
    }

    /** Takes every decoded frame; only the shutdown pins message is read. */
    public void offer(CanFrame f, FrameCheck c, double t0) {
        if (f.ext() || f.rtr() || f.id() != ShutdownLoop.MESSAGE_ID || c.hit() == null) return;
        this.t0 = t0;
        msg = c.hit().msg();
        Map<String, Boolean> pins = ShutdownLoop.pins(msg, f.data());
        if (!pins.equals(latest)) {
            changes.add(new Change(f.ts(), pins));
            if (changes.size() > 2000) changes.removeFirst();
        }
        latest = pins;
        latestTs = f.ts();
        frames++;
        dirty = true;
    }

    public String time(double ts) {
        return Double.isNaN(ts) ? "" : String.format(Locale.ROOT, "%.3f s", Double.isNaN(t0) ? ts : ts - t0);
    }

    /** Applies what offer() collected; cheap when nothing changed. */
    public void refresh() {
        if (!dirty) return;
        dirty = false;
        if (listed != changes.size()) {
            int sel = history.getSelectedIndex();
            Change pinned = sel > 0 && sel <= changes.size() ? changes.get(changes.size() - sel) : null;
            updating = true;
            try {
                DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
                m.addElement("Latest");
                for (int i = changes.size() - 1; i >= 0; i--) {
                    Change ch = changes.get(i);
                    m.addElement(time(ch.ts()) + "   " + ShutdownLoop.read(ch.pins()).brief());
                }
                history.setModel(m);
                int keep = pinned == null ? 0 : changes.size() - changes.indexOf(pinned);
                history.setSelectedIndex(pinned == null || keep > changes.size() ? 0 : keep);
            } finally {
                updating = false;
            }
            listed = changes.size();
        }
        showState(false);
    }

    public void showState(boolean picked) {
        int sel = history.getSelectedIndex();
        Change ch = sel > 0 && sel <= changes.size() ? changes.get(changes.size() - sel) : null;
        ShutdownLoop.Reading r = ShutdownLoop.read(ch == null ? latest : ch.pins());
        headline.set(r.headline(), r.tone());
        headline.setToolTipText(r.headline());
        int n = changes.size() - 1;
        when.setText(
            ch == null
                ? frames +
                      " frames, latest at " +
                      time(latestTs) +
                      (n < 1 ? ", no changes" : ", changed " + n + (n == 1 ? " time" : " times"))
                : "Showing the state from " + time(ch.ts()) + ". Pick Latest to follow new frames."
        );
        diagram.setReading(r);
        List<String> all = new ArrayList<>(r.notes());
        if (msg != null) all.addAll(ShutdownLoop.specNotes(msg));
        notes.setText(all.isEmpty() ? "" : "<html>" + escHtml(String.join("  ", all)) + "</html>");
        notes.setVisible(!all.isEmpty());
        if (picked) history.setToolTipText(
            ch == null ? "Following new frames" : "Showing the state at " + time(ch.ts())
        );
    }
}
