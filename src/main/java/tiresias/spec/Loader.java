package tiresias.spec;

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
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

public final class Loader {

    public static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", "build", "dist", "__pycache__");

    public static List<Path> collect(List<Path> inputs) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                Files.walkFileTree(
                    in,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            String n = d.getFileName() == null ? "" : d.getFileName().toString();
                            if (
                                !d.equals(in) && (n.startsWith(".") || SKIP_DIRS.contains(n))
                            ) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) out.add(f);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                );
            } else if (Files.isRegularFile(in)) out.add(in);
            else throw new IOException("Not found: " + in);
        }
        List<Path> norm = new ArrayList<>();
        for (Path p : out) {
            Path n = p.toAbsolutePath().normalize();
            if (!norm.contains(n)) norm.add(n);
        }
        Collections.sort(norm);
        return norm;
    }

    public static SpecFile load(Path p) {
        SpecFile f = new SpecFile(p);
        try {
            String t = Files.readString(p);
            if (t.startsWith("\uFEFF")) t = t.substring(1);
            Json.Parser ps = new Json.Parser(t);
            Object root = ps.parseAll();
            f.parseWarnings.addAll(ps.warnings);
            List<Object> l = asList(root);
            if (l == null) {
                f.notSpec = true;
                f.loadError = "Root is not a JSON array of messages";
            } else f.messages = l;
        } catch (JsonException e) {
            f.loadError = "JSON syntax error: " + e.getMessage();
        } catch (IOException e) {
            f.loadError = "Cannot read file: " + e.getMessage();
        }
        return f;
    }

    public static void save(SpecFile f) throws IOException {
        String text = Json.write(f.messages);
        Path tmp = f.path.resolveSibling(f.path.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        try {
            Files.move(tmp, f.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f.path, StandardCopyOption.REPLACE_EXISTING);
        }
        f.dirty = false;
    }

    /** Everything opening files produces. Built off the Swing thread so the window stays responsive. */
    public record Loaded(
        List<SpecFile> added,
        List<SpecFile> all,
        int skipped,
        String error,
        List<Issue> issues,
        Map<SpecFile, String> snapshots,
        Map<Map<String, Object>, Family> families
    ) {
        public static Loaded failed(String error) {
            return new Loaded(List.of(), List.of(), 0, error, List.of(), Map.of(), Map.of());
        }
    }

    /** Collects, parses (in parallel), snapshots, validates and groups families for inputs plus already-open files. */
    public static Loaded loadAll(List<Path> inputs, List<SpecFile> already) {
        List<Path> paths;
        try {
            paths = collect(inputs);
        } catch (IOException e) {
            return Loaded.failed(e.getMessage());
        }
        Set<Path> open = new HashSet<>();
        for (SpecFile f : already) open.add(f.path);
        List<SpecFile> loaded = paths
            .parallelStream()
            .filter(p -> !open.contains(p))
            .map(Loader::load)
            .toList();
        List<SpecFile> added = loaded
            .stream()
            .filter(f -> !f.notSpec)
            .toList();
        List<SpecFile> all = new ArrayList<>(already);
        all.addAll(added);
        Map<SpecFile, String> snaps = new IdentityHashMap<>();
        for (SpecFile f : added) if (f.loadError == null) snaps.put(f, Json.write(f.messages));
        Map<Map<String, Object>, Family> fams = new IdentityHashMap<>();
        findFamilies(all, null, fams);
        return new Loaded(added, all, loaded.size() - added.size(), null, Validator.validate(all), snaps, fams);
    }

    public static Path commonParent(List<Path> paths) {
        Path common = null;
        for (Path p : paths) {
            Path d = p.toAbsolutePath().getParent();
            if (d == null) continue;
            if (common == null) {
                common = d;
                continue;
            }
            while (common != null && !d.startsWith(common)) common = common.getParent();
        }
        return common;
    }
}
