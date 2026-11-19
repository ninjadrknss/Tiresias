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

// ======================================================================================
// Shutdown loop: where the loop is open, read from the VCU's shutdown pins message
// ======================================================================================

/**
 * The shutdown loop in the order current flows through it, and how to read the VCU's "Shutdown Pins" message
 * against it. Each pin samples the loop just after its stage: it reads 1 while the loop is closed up to there,
 * so where the loop breaks, that pin and every pin after it read 0.
 */
public final class ShutdownLoop {

    /** The message carrying the pins ("Shutdown Pins" in vcu.json). */
    public static final long MESSAGE_ID = 0x123;

    /**
     * THE LOOP ORDER. Edit this list when the loop changes; the schematic and the dashboard have disagreed
     * before, so check it against the car. Each line is one stage, first to last:
     *   pin(name, point)  a stage the VCU senses; point is that pin's point name in the 0x123 message
     *   unsensed(name)    a stage no pin reads
     *   powered(name)     a stage that must be closed whenever the VCU is sending at all (it powers the VCU)
     */
    public static final List<Stage> STAGES = List.of(
        powered("GLV master switch"),
        unsensed("VCU"),
        unsensed("Side BRB R"),
        unsensed("Side BRB L"),
        pin("BMS latch", "bms_gpio"),
        pin("IMD latch", "imd_gpio"),
        pin("BSPD latch", "bspd_gpio"),
        pin("MC HVIL interlock", "hv_c"),
        pin("BOTS", "bots_gpio"),
        pin("Inertia switch", "inertia_sw_gpio"),
        pin("Cockpit BRB", "ckpt_gpio"),
        pin("TSMS", "tsms_gpio"),
        pin("HVD", "hvd_gpio"),
        unsensed("Energy meter interlock"),
        unsensed("Accumulator sense"),
        unsensed("TSIP sense"),
        unsensed("TS- sense"),
        unsensed("TS+ sense"),
        unsensed("AIRs")
    );

    public record Stage(String name, String point, boolean powered) {}

    public static Stage pin(String name, String point) {
        return new Stage(name, point, false);
    }

    public static Stage unsensed(String name) {
        return new Stage(name, null, false);
    }

    public static Stage powered(String name) {
        return new Stage(name, null, true);
    }

    /** CLOSED: current gets past it. BREAK: the break is here or at another BREAK stage. DEAD: after the break. */
    public enum State {
        CLOSED,
        BREAK,
        DEAD,
        UNKNOWN,
    }

    /** What one frame says: a state and pin value (null when not sensed) per stage, and sentences about it. */
    public record Reading(
        List<State> states,
        List<Boolean> values,
        String headline,
        String brief,
        int tone,
        List<String> notes
    ) {}

    /** Each named, parsed point of the message, and whether it reads non-zero in this frame. */
    public static Map<String, Boolean> pins(Map<String, Object> msg, byte[] data) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        List<Object> pts = listOrEmpty(msg.get("points"));
        int[] off = offsets(pts);
        for (int i = 0; i < pts.size(); i++) {
            Map<String, Object> p = asMap(pts.get(i));
            String nm = str(p, "name");
            int size = sizeOf(p);
            if (p == null || !isParsed(p) || nm == null || size < 1 || size > 64) continue;
            out.put(nm, extractBits(data, off[i], size) != 0);
        }
        return out;
    }

    public static String label(int i) {
        return STAGES.get(i).name() + " (" + (i + 1) + ")";
    }

    public static String joinOr(List<String> l) {
        if (l.size() <= 1) return String.join("", l);
        return String.join(", ", l.subList(0, l.size() - 1)) + " or " + l.getLast();
    }

    /** Reads one frame's pins (null: no frame yet) against the loop order. */
    public static Reading read(Map<String, Boolean> pins) {
        int n = STAGES.size();
        List<Boolean> v = new ArrayList<>();
        for (Stage s : STAGES)
            v.add(pins == null ? null : s.powered() ? Boolean.TRUE : s.point() == null ? null : pins.get(s.point()));
        if (pins == null) return new Reading(
            Collections.nCopies(n, State.UNKNOWN),
            v,
            "No shutdown pins frames yet",
            "no data",
            ToneLabel.MUTED,
            List.of()
        );
        int firstLow = v.indexOf(Boolean.FALSE);
        int end = firstLow < 0 ? n : firstLow,
            lastHigh = -1;
        for (int i = 0; i < end; i++) if (Boolean.TRUE.equals(v.get(i))) lastHigh = i;
        List<State> st = new ArrayList<>();
        for (int i = 0; i < n; i++) st.add(
            i <= lastHigh ? State.CLOSED : firstLow < 0 ? State.UNKNOWN : i <= firstLow ? State.BREAK : State.DEAD
        );
        List<String> notes = new ArrayList<>();
        if (firstLow >= 0) {
            List<String> odd = new ArrayList<>();
            for (int i = firstLow + 1; i < n; i++) if (Boolean.TRUE.equals(v.get(i))) odd.add(label(i));
            if (!odd.isEmpty()) notes.add(
                String.join(", ", odd) +
                    (odd.size() == 1 ? " reads" : " read") +
                    " closed although the loop is already open at " +
                    STAGES.get(firstLow).name() +
                    ". Either the loop order in ShutdownLoop.STAGES is wrong, or a sense line is faulty."
            );
            List<String> suspects = new ArrayList<>();
            for (int i = lastHigh + 1; i <= firstLow; i++) suspects.add(STAGES.get(i).name());
            if (suspects.size() == 1) return new Reading(
                st,
                v,
                "Loop open at " + label(firstLow),
                "open at " + suspects.getFirst(),
                ToneLabel.ERR,
                notes
            );
            return new Reading(
                st,
                v,
                "Loop open: the break is at " +
                    joinOr(suspects) +
                    " (stages " +
                    (lastHigh + 2) +
                    "–" +
                    (firstLow + 1) +
                    ")",
                "open at " + suspects.getFirst() + " … " + suspects.getLast(),
                ToneLabel.ERR,
                notes
            );
        }
        if (lastHigh == n - 1) return new Reading(st, v, "Loop closed", "closed", ToneLabel.OK, notes);
        if (lastHigh >= 0) return new Reading(
            st,
            v,
            "Loop closed through " + label(lastHigh) + ". The stages after it aren't sensed by this message.",
            "closed",
            ToneLabel.OK,
            notes
        );
        return new Reading(st, v, "None of the loop's pins are in this frame", "no pins", ToneLabel.WARN, notes);
    }

    /** Mismatches between the loop order and the spec's message, so a renamed or added pin doesn't go unnoticed. */
    public static List<String> specNotes(Map<String, Object> msg) {
        Set<String> names = new LinkedHashSet<>();
        for (Object o : listOrEmpty(msg.get("points"))) {
            Map<String, Object> p = asMap(o);
            String nm = str(p, "name");
            if (p != null && isParsed(p) && nm != null) names.add(nm);
        }
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        String id = strOr(msg, "id", hex(MESSAGE_ID));
        for (Stage s : STAGES) {
            if (s.point() == null) continue;
            used.add(s.point());
            if (!names.contains(s.point())) out.add(
                "The loop order reads " +
                    s.name() +
                    " from pin \"" +
                    s.point() +
                    "\", but " +
                    id +
                    " has no such point (renamed in the spec?)."
            );
        }
        List<String> extra = names
            .stream()
            .filter(nm -> !used.contains(nm))
            .toList();
        if (!extra.isEmpty()) out.add(
            id +
                (extra.size() == 1 ? " has a pin" : " has pins") +
                " the loop order doesn't place: " +
                String.join(", ", extra) +
                ". Add " +
                (extra.size() == 1 ? "it" : "them") +
                " to ShutdownLoop.STAGES."
        );
        return out;
    }
}
