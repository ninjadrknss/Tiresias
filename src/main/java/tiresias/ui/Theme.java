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

/** A color palette. selection is derived from accent and panel by the factories. */
public record Theme(
    String name,
    boolean dark,
    boolean nativeLaf,
    Color window,
    Color panel,
    Color field,
    Color alt,
    Color border,
    Color hairline,
    Color fg,
    Color bright,
    Color muted,
    Color accent,
    Color selection,
    Color red,
    Color orange,
    Color yellow,
    Color green,
    Color cyan,
    Color blue,
    Color purple
) {
    public static final String NATIVE = "Native (OS)";
    public static Font UI, MONO;

    public static Theme of(String name, boolean dark, int... c) {
        return of(name, dark, false, toColors(c));
    }

    public static Theme of(String name, boolean dark, boolean nativeLaf, Color[] c) {
        return new Theme(
            name,
            dark,
            nativeLaf,
            c[0],
            c[1],
            c[2],
            c[3],
            c[4],
            c[5],
            c[6],
            c[7],
            c[8],
            c[9],
            blend(c[9], c[1], dark ? 0.30 : 0.20),
            c[10],
            c[11],
            c[12],
            c[13],
            c[14],
            c[15],
            c[16]
        );
    }

    public static Color[] toColors(int[] c) {
        Color[] out = new Color[c.length];
        for (int i = 0; i < c.length; i++) out[i] = new Color(c[i]);
        return out;
    }

    //                                  window    panel     field     alt       border    hairline  fg        bright    muted     accent    red       orange    yellow    green     cyan      blue      purple
    public static final List<Theme> ALL = List.of(
        Theme.of(
            "One Dark",
            true,
            0x21252B,
            0x282C34,
            0x1E2227,
            0x2C313A,
            0x3E4451,
            0x181A1F,
            0xABB2BF,
            0xE6E9EF,
            0x7F848E,
            0x61AFEF,
            0xE06C75,
            0xD19A66,
            0xE5C07B,
            0x98C379,
            0x56B6C2,
            0x61AFEF,
            0xC678DD
        ),
        Theme.of(
            "Dracula",
            true,
            0x21222C,
            0x282A36,
            0x1E1F29,
            0x2D2F3D,
            0x44475A,
            0x191A21,
            0xF8F8F2,
            0xFFFFFF,
            0x7E87AE,
            0xBD93F9,
            0xFF5555,
            0xFFB86C,
            0xF1FA8C,
            0x50FA7B,
            0x8BE9FD,
            0x82AAFF,
            0xFF79C6
        ),
        Theme.of(
            "Nord",
            true,
            0x272C36,
            0x2E3440,
            0x242933,
            0x333A47,
            0x434C5E,
            0x1F232B,
            0xD8DEE9,
            0xECEFF4,
            0x7B88A1,
            0x88C0D0,
            0xBF616A,
            0xD08770,
            0xEBCB8B,
            0xA3BE8C,
            0x8FBCBB,
            0x81A1C1,
            0xB48EAD
        ),
        Theme.of(
            "One Light",
            false,
            0xF0F0F1,
            0xFAFAFA,
            0xFFFFFF,
            0xF3F3F4,
            0xD4D4D8,
            0xE2E2E4,
            0x383A42,
            0x1F2024,
            0x868891,
            0x4078F2,
            0xE45649,
            0xC18401,
            0x986801,
            0x50A14F,
            0x0184BC,
            0x4078F2,
            0xA626A4
        )
    );

    public static List<String> names() {
        List<String> n = new ArrayList<>();
        for (Theme t : ALL) n.add(t.name());
        n.add(NATIVE);
        return n;
    }

    public Color button() {
        return blend(fg, panel, dark ? 0.08 : 0.05);
    }

    public Color tint(Color c) {
        return blend(c, panel, dark ? 0.20 : 0.15);
    }

    public Color chart(int i) {
        Color[] c = { blue, green, purple, orange, cyan, red, yellow };
        return c[Math.floorMod(i, c.length)];
    }

    public static String pick(Set<String> fams, String... wanted) {
        for (String w : wanted) if (fams.contains(w)) return w;
        return null;
    }

    public static synchronized void initFonts() {
        if (UI != null) return;
        Set<String> fams = new HashSet<>(
            Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
        );
        String ui = pick(
            fams,
            "Inter",
            "Segoe UI",
            "SF Pro Text",
            "Helvetica Neue",
            "Ubuntu",
            "Cantarell",
            "Noto Sans",
            "DejaVu Sans"
        );
        String mo = pick(
            fams,
            "JetBrains Mono",
            "Cascadia Mono",
            "Cascadia Code",
            "SF Mono",
            "Menlo",
            "Consolas",
            "Fira Code",
            "Ubuntu Mono",
            "DejaVu Sans Mono"
        );
        UI = new Font(ui == null ? Font.DIALOG : ui, Font.PLAIN, 13);
        MONO = new Font(mo == null ? Font.MONOSPACED : mo, Font.PLAIN, 13);
    }

    public static Color ui(String key, Color def) {
        Color c = UIManager.getColor(key);
        return c == null ? def : c;
    }

    public static Theme nativeTheme() {
        Color panel = ui("Panel.background", new Color(0xEEEEEE));
        Color field = ui("Table.background", Color.WHITE);
        Color fg = ui("Label.foreground", Color.BLACK);
        boolean dark = luminance(panel) < 0.5;
        Theme p = dark ? ALL.getFirst() : ALL.getLast();
        return Theme.of(NATIVE, dark, true, new Color[] {
            blend(fg, panel, 0.04),
            panel,
            field,
            blend(fg, field, 0.035),
            blend(fg, panel, 0.25),
            blend(fg, panel, 0.14),
            fg,
            fg,
            blend(fg, panel, 0.55),
            p.accent(),
            p.red(),
            p.orange(),
            p.yellow(),
            p.green(),
            p.cyan(),
            p.blue(),
            p.purple(),
        });
    }

    public static void apply(String name) {
        initFonts();
        try {
            if (NATIVE.equals(name)) {
                setNativeLookAndFeel();
                T = nativeTheme();
            } else {
                Theme t = ALL.getFirst();
                for (Theme x : ALL) if (x.name().equals(name)) t = x;
                T = t;
                if (!applyFlatLaf(t)) {
                    UIManager.put("swing.boldMetal", Boolean.FALSE);
                    javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(new PaletteMetalTheme(t));
                    UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
                    install(UIManager.getLookAndFeelDefaults(), t);
                }
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Couldn't apply theme " + name, e);
            if (T == null) T = ALL.getFirst();
        }
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            w.repaint();
        }
    }

    public static void reg(UIDefaults d, String id, Class<?> c) {
        d.put(id, c.getName());
        d.put(c.getName(), c);
    }

    /** Puts each role/value pair for every space-separated component prefix ("" for global keys). */
    public static void put(UIDefaults d, String prefixes, Object... roleValues) {
        for (String prefix : prefixes.isEmpty() ? new String[] { "" } : prefixes.split(" "))
            for (int i = 0; i < roleValues.length; i += 2) d.put(
                prefix.isEmpty() ? roleValues[i] : prefix + "." + roleValues[i],
                roleValues[i + 1]
            );
    }

    /**
     * Uses FlatLaf when the Gradle build has bundled it, tinted with this palette. FlatLaf draws text fields, menus,
     * dialogs and file choosers; the animated buttons, sliding tab underline and hairline split panes stay ours.
     * Returns false (so the built-in look is used) when FlatLaf isn't on the classpath (a plain javac build).
     */
    public static boolean applyFlatLaf(Theme t) {
        try {
            Class<?> flat = Class.forName("com.formdev.flatlaf.FlatLaf");
            Map<String, String> vars = Map.of(
                "@background",
                hx(t.panel()),
                "@foreground",
                hx(t.fg()),
                "@accentColor",
                hx(t.accent())
            );
            try {
                flat.getMethod("setGlobalExtraDefaults", Map.class).invoke(null, vars);
            } catch (ReflectiveOperationException ignored) {}
            UIManager.setLookAndFeel(t.dark() ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf");
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.log(
                System.Logger.Level.WARNING,
                "FlatLaf is bundled but couldn't be applied; using the built-in look",
                e
            );
            return false;
        }
        UIDefaults d = UIManager.getLookAndFeelDefaults();
        ColorUIResource window = new ColorUIResource(t.window()),
            panel = new ColorUIResource(t.panel()),
            field = new ColorUIResource(t.field()),
            sel = new ColorUIResource(t.selection());
        Object[] kv = {
            "defaultFont",
            new FontUIResource(UI),
            "Component.arc",
            8,
            "Button.arc",
            8,
            "TextComponent.arc",
            6,
            "ScrollBar.thumbArc",
            999,
            "ScrollBar.thumbInsets",
            new InsetsUIResource(2, 2, 2, 2),
            "ScrollBar.width",
            10,
            "ScrollBar.showButtons",
            false,
            "ScrollPane.smoothScrolling",
            true,
            "Component.focusWidth",
            1,
            "Panel.background",
            panel,
            "Viewport.background",
            panel,
            "ScrollPane.background",
            panel,
            "TabbedPane.background",
            panel,
            "Table.background",
            panel,
            "Table.selectionBackground",
            sel,
            "TableHeader.background",
            window,
            "Tree.background",
            window,
            "Tree.selectionBackground",
            sel,
            "SplitPane.background",
            window,
            "MenuBar.background",
            window,
            "TextField.background",
            field,
            "TextArea.background",
            field,
            "ComboBox.background",
            field,
            "List.background",
            field,
            "Table.cellNoFocusBorder",
            new BorderUIResource(new EmptyBorder(0, 8, 0, 8)),
            "Table.focusCellHighlightBorder",
            new BorderUIResource(new EmptyBorder(0, 8, 0, 8)),
            "SplitPane.dividerSize",
            5,
            "Tree.rowHeight",
            26,
            "Tree.rendererFillBackground",
            Boolean.FALSE,
            "Tree.expandedIcon",
            new ChevronIcon(true),
            "Tree.collapsedIcon",
            new ChevronIcon(false),
        };
        for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
        reg(d, "ButtonUI", FlatButtonUI.class);
        reg(d, "TabbedPaneUI", FlatTabbedPaneUI.class);
        reg(d, "SplitPaneUI", FlatSplitPaneUI.class);
        return true;
    }

    public static void install(UIDefaults d, Theme t) {
        FontUIResource font = new FontUIResource(UI);
        for (Object k : new ArrayList<>(d.keySet()))
            if (k instanceof String key && key.endsWith(".font")) d.put(k, font);
        ColorUIResource window = new ColorUIResource(t.window()),
            panel = new ColorUIResource(t.panel()),
            field = new ColorUIResource(t.field()),
            fg = new ColorUIResource(t.fg()),
            bright = new ColorUIResource(t.bright()),
            muted = new ColorUIResource(t.muted()),
            sel = new ColorUIResource(t.selection()),
            border = new ColorUIResource(t.border()),
            accent = new ColorUIResource(t.accent()),
            button = new ColorUIResource(t.button()),
            tip = new ColorUIResource(blend(t.fg(), t.field(), t.dark() ? 0.10 : 0.04));
        Border fieldBorder = new FieldBorder();
        Border cell = new BorderUIResource(new EmptyBorder(0, 8, 0, 8));
        Border item = new BorderUIResource(new EmptyBorder(5, 8, 5, 12));
        Border popup = new BorderUIResource(
            new CompoundBorder(new LineBorder(t.border()), new EmptyBorder(4, 0, 4, 0))
        );
        Object none = new BorderUIResource(new EmptyBorder(0, 0, 0, 0)),
            hairline = new ColorUIResource(t.hairline());
        // Each call gives every listed component the same roles, e.g. all text components share caret/selection colors.
        put(
            d,
            "",
            "control",
            panel,
            "controlText",
            fg,
            "text",
            fg,
            "textText",
            fg,
            "window",
            field,
            "info",
            tip,
            "infoText",
            fg
        );
        put(
            d,
            "Panel Label OptionPane ToolBar CheckBox RadioButton MenuBar PopupMenu Tree TabbedPane",
            "foreground",
            fg
        );
        put(
            d,
            "Panel Viewport ScrollPane OptionPane ToolBar CheckBox RadioButton PopupMenu TabbedPane",
            "background",
            panel
        );
        put(
            d,
            "TextField FormattedTextField PasswordField TextArea EditorPane TextPane",
            "background",
            field,
            "foreground",
            fg,
            "caretForeground",
            bright,
            "selectionBackground",
            sel,
            "selectionForeground",
            bright,
            "inactiveForeground",
            muted
        );
        put(d, "TextField FormattedTextField PasswordField ComboBox", "border", fieldBorder);
        put(d, "ComboBox List Spinner FileChooser", "background", field, "foreground", fg);
        put(
            d,
            "ComboBox List Table Tree Menu MenuItem CheckBoxMenuItem RadioButtonMenuItem",
            "selectionBackground",
            sel,
            "selectionForeground",
            bright
        );
        put(d, "List Table", "focusCellHighlightBorder", cell, "cellNoFocusBorder", cell);
        put(
            d,
            "Menu MenuItem CheckBoxMenuItem RadioButtonMenuItem",
            "foreground",
            fg,
            "border",
            item,
            "acceleratorForeground",
            muted
        );
        put(d, "MenuItem CheckBoxMenuItem RadioButtonMenuItem", "background", panel);
        put(d, "CheckBox RadioButton", "focus", panel, "disabledText", muted);
        put(
            d,
            "TabbedPane",
            "selected",
            panel,
            "contentAreaColor",
            panel,
            "tabAreaBackground",
            panel,
            "focus",
            panel,
            "light",
            panel,
            "highlight",
            panel,
            "shadow",
            panel,
            "darkShadow",
            panel
        );
        put(d, "Tree MenuBar Menu SplitPane TableHeader", "background", window);
        put(d, "ScrollPane ToolBar SplitPane SplitPaneDivider", "border", none);
        Object[] kv = {
            "Label.disabledForeground",
            muted,
            "TextField.inactiveBackground",
            panel,
            "TextField.margin",
            new InsetsUIResource(0, 0, 0, 0),
            "TextArea.margin",
            new InsetsUIResource(6, 8, 6, 8),
            "ComboBox.disabledBackground",
            panel,
            "ComboBox.disabledForeground",
            muted,
            "ComboBox.buttonBackground",
            field,
            "FileChooser.listViewBackground",
            field,
            "Table.background",
            panel,
            "Table.foreground",
            fg,
            "Table.gridColor",
            hairline,
            "Table.scrollPaneBorder",
            none,
            "Table.dropLineColor",
            accent,
            "TableHeader.foreground",
            muted,
            "TableHeader.cellBorder",
            new BorderUIResource(new CompoundBorder(new Line(0, 0, 1, 0), new EmptyBorder(6, 8, 6, 8))),
            "Tree.textForeground",
            fg,
            "Tree.textBackground",
            window,
            "Tree.selectionBorderColor",
            sel,
            "Tree.hash",
            window,
            "Tree.line",
            window,
            "Tree.paintLines",
            Boolean.FALSE,
            "Tree.rendererFillBackground",
            Boolean.FALSE,
            "Tree.leftChildIndent",
            10,
            "Tree.rightChildIndent",
            12,
            "Tree.rowHeight",
            26,
            "Tree.drawsFocusBorderAroundIcon",
            Boolean.FALSE,
            "Tree.drawDashedFocusIndicator",
            Boolean.FALSE,
            "Tree.expandedIcon",
            new ChevronIcon(true),
            "Tree.collapsedIcon",
            new ChevronIcon(false),
            "SplitPane.dividerSize",
            5,
            "SplitPaneDivider.draggingColor",
            accent,
            "MenuBar.border",
            new BorderUIResource(new Line(0, 0, 1, 0)),
            "MenuItem.acceleratorSelectionForeground",
            bright,
            "MenuItem.disabledForeground",
            muted,
            "PopupMenu.border",
            popup,
            "Separator.foreground",
            border,
            "Separator.background",
            panel,
            "Separator.highlight",
            panel,
            "Separator.shadow",
            border,
            "PopupMenuSeparator.foreground",
            border,
            "PopupMenuSeparator.background",
            panel,
            "ToolTip.background",
            tip,
            "ToolTip.foreground",
            bright,
            "ToolTip.border",
            new BorderUIResource(new CompoundBorder(new LineBorder(t.border()), new EmptyBorder(5, 8, 5, 8))),
            "Button.background",
            button,
            "Button.foreground",
            fg,
            "Button.select",
            button,
            "Button.focus",
            accent,
            "Button.disabledText",
            muted,
            "Button.margin",
            new InsetsUIResource(4, 12, 4, 12),
            "CheckBox.icon",
            new CheckIcon(false),
            "RadioButton.icon",
            new CheckIcon(true),
            "OptionPane.messageForeground",
            fg,
            "TitledBorder.titleColor",
            fg,
        };
        for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
        for (String g : "Button CheckBox RadioButton ToggleButton ScrollBar MenuBar ToolBar Slider CheckBoxMenuItem RadioButtonMenuItem".split(
            " "
        ))
            d.remove(g + ".gradient");
        d.remove("Slider.focusGradient");
        d.remove("InternalFrame.activeTitleGradient");
        reg(d, "ScrollBarUI", FlatScrollBarUI.class);
        reg(d, "ButtonUI", FlatButtonUI.class);
        reg(d, "ComboBoxUI", FlatComboBoxUI.class);
        reg(d, "TabbedPaneUI", FlatTabbedPaneUI.class);
        reg(d, "SplitPaneUI", FlatSplitPaneUI.class);
    }
}
