package tiresias;

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
import tiresias.editor.*;
import tiresias.spec.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

// ======================================================================================
// Command-line validation
// ======================================================================================

public final class Cli {

    /** Decodes a candump log (or stdin with "-") against the spec: {@code --candump <log|-> [spec folders...]} */
    public static int candump(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("Usage: --candump <log-file|-> [spec folders or files...]");
            return 2;
        }
        String log = args.getFirst();
        List<Path> inputs = new ArrayList<>();
        for (String x : args.size() > 1 ? args.subList(1, args.size()) : List.of(".")) inputs.add(Paths.get(x));
        Loader.Loaded loaded = Loader.loadAll(inputs, List.of());
        if (loaded.error() != null) {
            System.err.println(loaded.error());
            return 2;
        }
        Map<String, SpecRef> idx = specIndex(loaded.all());
        int n = 0,
            unknown = 0,
            bad = 0,
            skipped = 0;
        boolean stdin = log.equals("-");
        try (
            java.io.BufferedReader in = stdin
                ? new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))
                : Files.newBufferedReader(Paths.get(log), StandardCharsets.UTF_8)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                CanFrame fr = parseCandumpLine(line);
                if (fr == null) {
                    if (!line.isBlank()) skipped++;
                    continue;
                }
                n++;
                FrameCheck c = checkFrame(fr, idx);
                StringBuilder b = new StringBuilder();
                if (!Double.isNaN(fr.ts())) b.append(String.format(Locale.ROOT, "(%.6f) ", fr.ts()));
                if (!fr.bus().isEmpty()) b.append(fr.bus()).append(' ');
                b.append(fr.idText()).append("  ");
                if (c.hit() != null && !fr.rtr()) {
                    Map<String, Object> m = c.hit().msg();
                    b.append(strOr(m, "desc", ""))
                        .append("  ")
                        .append(String.join("  ", decodeFrame(m, fr.data()).topics()));
                } else b.append(fr.dataText());
                if (c.level() > 0) b.append("  [").append(c.text()).append(']');
                if (c.text().equals("Unknown ID")) unknown++;
                else if (c.level() == 2) bad++;
                System.out.println(b);
                if (stdin) System.out.flush();
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        System.err.printf(
            "%n%d frames: %d with unknown IDs, %d with other problems, %d lines weren't CAN frames.%n",
            n,
            unknown,
            bad,
            skipped
        );
        return 0;
    }

    public static int run(List<String> args, boolean strict, boolean info) {
        if (args.isEmpty()) args = List.of(".");
        List<Path> inputs = new ArrayList<>();
        for (String a : args) inputs.add(Paths.get(a));
        Loader.Loaded r = Loader.loadAll(inputs, List.of());
        if (r.error() != null) {
            System.err.println(r.error());
            return 2;
        }
        List<SpecFile> files = r.all();
        int skipped = r.skipped();
        Path base =
            inputs.size() == 1 && Files.isDirectory(inputs.getFirst())
                ? inputs.getFirst().toAbsolutePath().normalize()
                : Loader.commonParent(
                      files
                          .stream()
                          .map(f -> f.path)
                          .toList()
                  );
        List<Issue> issues = r.issues();
        int[] count = new int[3];
        for (Issue is : issues) {
            count[is.sev().ordinal()]++;
            if (is.sev() == Severity.INFO && !info) continue;
            String fn =
                base != null && is.file().path.startsWith(base)
                    ? base.relativize(is.file().path).toString()
                    : is.file().path.toString();
            String id = is.msg() == null ? "-" : strOr(is.msg(), "id", "?");
            String text = is.text().replace("…", "...").replace("–", "-");
            System.out.printf(
                "%-7s %s  %s  %s: %s%n",
                is.sev().label.toUpperCase(Locale.ROOT),
                fn,
                id,
                is.where(),
                text
            );
        }
        int msgs = 0;
        for (SpecFile f : files) msgs += f.messages.size();
        System.out.printf(
            "%nChecked %d message(s) in %d file(s)%s: %d error(s), %d warning(s), %d info.%n",
            msgs,
            files.size(),
            skipped > 0 ? " (skipped " + skipped + " non-spec JSON file(s))" : "",
            count[0],
            count[1],
            count[2]
        );
        return count[0] > 0 || (strict && count[1] > 0) ? 1 : 0;
    }
}
