package tiresias.spec;

import static tiresias.Tiresias.*;
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
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

/**
 * Families: messages with the same layout whose topics differ at one level (for example the 11 VCU eFuses).
 */
public final class Families {

    private Families() {}

    // ======================================================================================
    // Message families: same layout, topics that differ at one level (recognized, never stored)
    // ======================================================================================

    /** Messages whose topics differ only at level `seg`; members is mutable and compared by identity. */
    public record Family(SpecFile file, int seg, List<Map<String, Object>> members) {
        public Family(SpecFile file, int seg) {
            this(file, seg, new ArrayList<>());
        }

        public String template() {
            if (members.isEmpty()) return "";
            String n = str(asMap(listOrEmpty(members.getFirst().get("fields")).getFirst()), "name");
            String[] s = n.split("/", -1);
            s[seg] = "*";
            return String.join("/", Arrays.copyOf(s, seg + 1));
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    public static String familyShape(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Object o : listOrEmpty(m.get("points")))
            b.append(sizeOf(o))
                .append(isParsed(asMap(o)) ? "" : "p")
                .append(',');
        return b
            .append('|')
            .append(listOrEmpty(m.get("fields")).size())
            .toString();
    }

    public static String memberToken(Map<String, Object> m, int seg) {
        for (Object o : listOrEmpty(m.get("fields"))) {
            String n = str(asMap(o), "name");
            if (n == null) return null;
            String[] s = n.split("/", -1);
            return seg < s.length ? s[seg] : null;
        }
        return null;
    }

    public static void findFamilies(List<SpecFile> files, SpecFile only, Map<Map<String, Object>, Family> families) {
        families.values().removeIf(fam -> only == null || fam.file() == only);
        for (SpecFile f : files) {
            if (only != null && f != only) continue;
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            Map<String, Integer> segOf = new HashMap<>();
            for (Object o : f.messages) {
                Map<String, Object> m = asMap(o);
                if (m == null) continue;
                List<Object> fds = listOrEmpty(m.get("fields"));
                if (fds.isEmpty()) continue;
                List<String[]> names = new ArrayList<>();
                int minLen = Integer.MAX_VALUE;
                boolean ok = true;
                for (Object fo : fds) {
                    String n = str(asMap(fo), "name");
                    if (n == null || n.isEmpty()) {
                        ok = false;
                        break;
                    }
                    String[] sp = n.split("/", -1);
                    names.add(sp);
                    minLen = Math.min(minLen, sp.length);
                }
                if (!ok || minLen < 2) continue;
                String shape = familyShape(m);
                for (int k = 0; k < minLen - 1; k++) {
                    String tok = names.getFirst()[k];
                    if (tok.isEmpty()) continue;
                    boolean same = true;
                    for (String[] sp : names)
                        if (!sp[k].equals(tok)) {
                            same = false;
                            break;
                        }
                    if (!same) continue;
                    StringBuilder key = new StringBuilder(shape).append('|').append(k);
                    for (String[] sp : names) {
                        String[] c = sp.clone();
                        c[k] = "*";
                        key.append('|').append(String.join("/", c));
                    }
                    groups.computeIfAbsent(key.toString(), x -> new ArrayList<>()).add(m);
                    segOf.put(key.toString(), k);
                }
            }
            List<Map.Entry<String, List<Map<String, Object>>>> sorted = new ArrayList<>(groups.entrySet());
            sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
            for (Map.Entry<String, List<Map<String, Object>>> e : sorted) {
                List<Map<String, Object>> free = new ArrayList<>();
                for (Map<String, Object> m : e.getValue()) if (!families.containsKey(m)) free.add(m);
                if (free.size() < 2) continue;
                int seg = segOf.get(e.getKey());
                Set<String> toks = new HashSet<>();
                boolean distinct = true;
                for (Map<String, Object> m : free) if (!toks.add(String.valueOf(memberToken(m, seg)))) distinct = false;
                if (!distinct) continue;
                Family fam = new Family(f, seg);
                fam.members().addAll(free);
                for (Map<String, Object> m : free) families.put(m, fam);
            }
        }
    }
}
