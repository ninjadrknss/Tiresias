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
// Animation: small timer-driven tweens (View > Animations turns them off)
// ======================================================================================

/** User settings, in a holder class so the command-line tools never touch preferences. */
public final class Settings {

    public static final Preferences NODE = migrate(Preferences.userRoot().node("tiresias"));

    /** Carries settings over from before the app was renamed (it was "CAN Spec Browser"), once. */
    public static Preferences migrate(Preferences node) {
        try {
            Preferences root = Preferences.userRoot();
            if (node.keys().length == 0 && root.nodeExists("ner-cangen-spec-browser")) {
                Preferences old = root.node("ner-cangen-spec-browser");
                for (String k : old.keys()) node.put(k, old.get(k, ""));
                node.flush();
            }
        } catch (java.util.prefs.BackingStoreException ignored) {} // nothing to carry over
        return node;
    }

    public static boolean animations = NODE.getBoolean("animations", true);
}
