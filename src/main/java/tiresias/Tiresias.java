package tiresias;

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

/**
 * Tiresias: browse, search, edit and validate Calypso / cangen JSON spec files, and foresee what will break the
 * firmware build before it happens (named for the prophet who warns Odysseus of the dangers ahead).
 *
 *   Run it:                     java -jar Tiresias.jar [folder-or-files...]   (or ./gradlew runApp)
 *   Headless / CI validation:   java -jar Tiresias.jar --validate <folder> [--strict] [--info]
 *        (exit code 1 when errors are found; --strict also fails on warnings)
 *
 * This class is the entry point: argument handling and the parallel startup.
 */
public final class Tiresias {

    public static final System.Logger LOG = System.getLogger("Tiresias");

    // ======================================================================================
    // Entry point
    // ======================================================================================

    public static void main(String[] args) {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        a.removeIf(x -> x.startsWith("-psn_")); // macOS Finder may pass a process serial number
        if (a.contains("--askpass")) {
            // SSH_ASKPASS helper for live capture: hand ssh the password
            String pw = System.getenv("TIRESIAS_SSH_PW");
            System.out.println(pw == null ? "" : pw);
            System.exit(0);
            return;
        }
        if (a.remove("--candump")) {
            System.exit(Cli.candump(a));
            return;
        }
        if (a.remove("--export-icons")) {
            System.exit(Icons.export(a));
            return;
        }
        boolean validate = a.remove("--validate") | a.remove("-v");
        if (validate) {
            boolean strict = a.remove("--strict");
            boolean info = a.remove("--info");
            System.exit(Cli.run(a, strict, info));
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available. For command-line validation use: --validate <folder|files...>");
            System.exit(2);
        }
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        // Start slow one-time work in parallel with building the window: spec files, the font system, Swing classes.
        List<Path> initial = App.initialPaths(a);
        CompletableFuture<Loader.Loaded> preload = initial.isEmpty()
            ? null
            : CompletableFuture.supplyAsync(() -> Loader.loadAll(initial, List.of()));
        Thread.ofPlatform().daemon().start(Theme::initFonts);
        Thread.ofPlatform().daemon().start(Tiresias::preloadClasses);
        SwingUtilities.invokeLater(() -> {
            Theme.apply(Settings.NODE.get("theme", "One Dark"));
            App app = new App();
            app.setVisible(true);
            app.openInitial(initial, preload);
        });
    }

    /** Loads (and statically initializes) the heavier Swing classes on a spare core while the main thread starts up. */
    public static void preloadClasses() {
        for (String c : List.of(
            "javax.swing.JTable",
            "javax.swing.JTree",
            "javax.swing.JTabbedPane",
            "javax.swing.JSplitPane",
            "javax.swing.JComboBox",
            "javax.swing.JTextArea",
            "javax.swing.plaf.metal.MetalLookAndFeel",
            "javax.swing.plaf.basic.BasicTableUI",
            "javax.swing.plaf.basic.BasicTreeUI",
            "javax.swing.plaf.basic.BasicTabbedPaneUI",
            "javax.swing.text.html.HTMLEditorKit",
            "javax.swing.plaf.basic.BasicHTML",
            "javax.swing.table.DefaultTableCellRenderer"
        )) {
            try {
                Class.forName(c, true, Tiresias.class.getClassLoader());
            } catch (Throwable ignored) {}
        }
        try {
            new javax.swing.text.html.HTMLEditorKit().getStyleSheet();
        } catch (Throwable ignored) {} // parses Swing's default HTML CSS
    }

    public static void setNativeLookAndFeel() throws Exception {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(i.getName())) {
                        UIManager.setLookAndFeel(i.getClassName());
                        return;
                    }
                }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (UnsupportedLookAndFeelException ignored) {}
    }
}
