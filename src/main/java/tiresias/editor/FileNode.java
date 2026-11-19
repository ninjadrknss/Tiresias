package tiresias.editor;

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
import tiresias.spec.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;
import tiresias.ui.*;

// ======================================================================================
// Main window
// ======================================================================================

public record FileNode(App app, SpecFile f) {
    @Override
    public String toString() {
        return app.relName(f) + (f.dirty ? " *" : "") + "   (" + f.messages.size() + ")";
    }
}
