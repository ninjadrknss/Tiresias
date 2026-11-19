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

public final class App extends JFrame {

    public final List<SpecFile> files = new ArrayList<>();
    public List<Path> lastInputs = new ArrayList<>();
    public Path baseDir;
    public List<Issue> issues = new ArrayList<>();
    public final Map<Map<String, Object>, Severity> msgSev = new IdentityHashMap<>();
    public final Map<SpecFile, Severity> fileSev = new IdentityHashMap<>();
    public final DefaultMutableTreeNode root = new DefaultMutableTreeNode("spec");
    public final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    public final JTree tree = new FullRowTree(treeModel);
    public final HintField search = new HintField("Search messages…  (Ctrl+F)", 18);
    public final JComboBox<String> searchMode = new JComboBox<>(SEARCH_MODES);
    public final JCheckBox onlyIssues = new JCheckBox("Only with problems");
    public final JCheckBox sortById = new JCheckBox("Sort by ID");
    public final MessageEditor editor;
    public final IssuesPanel issuesPanel;
    public final JLabel status = new JLabel(" ");
    public final JLabel statusRight = new JLabel(" ");
    public final Preferences prefs = Settings.NODE;
    public final LoadingOverlay overlay = new LoadingOverlay();
    public final javax.swing.Timer searchTimer;
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    /** How the Back shortcut is written in hints. */
    public static final String BACK_KEYS = MAC ? "⌘[" : "Alt+←";
    public GoToDialog goTo;
    public TopicsDialog topicsDialog;

    /** Edits check the whole spec once typing pauses for this long, not on every keystroke. */
    public static final int VALIDATE_DELAY_MS = 300;
    public final javax.swing.Timer validateTimer;
    public boolean rebuilding, rebuildScheduled;
    public SpecFile selectedFileOnly;

    public App() {
        super("Tiresias");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        editor = new MessageEditor(this);
        issuesPanel = new IssuesPanel(this);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        // Painted directly rather than with <html> labels: an HTML label re-parses and lays out its
        // text every time the tree measures or paints a row, which froze the UI with every file expanded.
        tree.setCellRenderer(
            new TreeRow() {
                public Component getTreeCellRendererComponent(
                    JTree t,
                    Object v,
                    boolean sel,
                    boolean exp,
                    boolean leaf,
                    int row,
                    boolean foc
                ) {
                    Object u = ((DefaultMutableTreeNode) v).getUserObject();
                    Font base = t.getFont();
                    if (u instanceof FileNode fn) {
                        SpecFile f = fn.f();
                        set(
                            SevIcon.of(fileSev.get(f)),
                            relName(f),
                            base.deriveFont(Font.BOLD),
                            sel ? T.bright() : T.fg(),
                            String.valueOf(f.messages.size()),
                            base,
                            T.muted(),
                            f.dirty ? "● unsaved" : null,
                            T.orange()
                        );
                        setToolTipText(f.path.toString() + (f.loadError != null ? " — " + f.loadError : ""));
                    } else if (u instanceof MsgNode mn) {
                        set(
                            SevIcon.of(msgSev.get(mn.m())),
                            strOr(mn.m(), "id", "(no id)"),
                            mono(Font.PLAIN, base.getSize2D()),
                            sel ? T.bright() : T.accent(),
                            strOr(mn.m(), "desc", ""),
                            base,
                            sel ? T.bright() : T.fg(),
                            null,
                            null
                        );
                        setToolTipText(null);
                    } else {
                        set(null, String.valueOf(u), base, T.fg(), null, base, T.fg(), null, null);
                        setToolTipText(null);
                    }
                    return this;
                }
            }
        );
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(e -> {
            if (rebuilding) return;
            Object u = selectedUserObject();
            if (u instanceof MsgNode(SpecFile f, Map<String, Object> m)) {
                selectedFileOnly = null;
                editor.load(f, m);
            } else if (u instanceof FileNode fn) {
                selectedFileOnly = fn.f();
                editor.showFile(selectedFileOnly);
            }
        });
        tree.addMouseListener(
            new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    popup(e);
                }

                public void mouseReleased(MouseEvent e) {
                    popup(e);
                }

                void popup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
                    if (tp != null) tree.setSelectionPath(tp);
                    JPopupMenu pm = new JPopupMenu();
                    pm.add(item("New message", null, App.this::newMessage));
                    pm.add(item("Duplicate message", null, App.this::duplicateMessage));
                    pm.add(item("Delete message", null, App.this::deleteMessage));
                    pm.addSeparator();
                    pm.add(item("Save this file", null, App.this::saveCurrent));
                    pm.show(tree, e.getX(), e.getY());
                }
            }
        );
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteMsg");
        tree.getActionMap().put(
            "deleteMsg",
            new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    deleteMessage();
                }
            }
        );

        searchTimer = new javax.swing.Timer(180, e -> {
            editor.setHighlight(search.getText().strip(), searchMode.getSelectedIndex());
            rebuildTree();
        });
        searchTimer.setRepeats(false);
        validateTimer = new javax.swing.Timer(VALIDATE_DELAY_MS, e -> validateEdits());
        validateTimer.setRepeats(false);
        search.getDocument().addDocumentListener(
            new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }

                public void removeUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }

                public void changedUpdate(DocumentEvent e) {
                    searchTimer.restart();
                }
            }
        );
        search.addKeyListener(
            new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) search.setText("");
                    if (e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_ENTER) {
                        tree.requestFocusInWindow();
                        if (tree.getSelectionCount() == 0) selectFirstMessage();
                    }
                }
            }
        );
        searchMode.addActionListener(e -> searchTimer.restart());
        onlyIssues.addActionListener(e -> rebuildTree());
        sortById.addActionListener(e -> {
            rebuildTree();
            editor.refreshFileCard();
        });

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.setOpaque(false);
        onlyIssues.setOpaque(false);
        sortById.setOpaque(false);
        searchRow.add(searchMode, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        searchRow.add(button("×", "Clear search", () -> search.setText("")), BorderLayout.EAST);
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.setOpaque(false);
        opts.add(onlyIssues);
        opts.add(sortById);
        JPanel leftTop = new JPanel(new BorderLayout(0, 6));
        leftTop.setOpaque(false);
        leftTop.add(searchRow, BorderLayout.NORTH);
        leftTop.add(opts, BorderLayout.SOUTH);
        JPanel left = new Surface(new BorderLayout(0, 6), 0);
        left.setBorder(new EmptyBorder(10, 10, 6, 4));
        left.add(leftTop, BorderLayout.NORTH);
        JScrollPane treeScroll = new JScrollPane(tree) {
            @Override
            public Color getBackground() {
                return T == null ? super.getBackground() : T.window();
            }
        };
        left.add(treeScroll, BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(220, 100));

        JSplitPane h = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, editor);
        h.setDividerLocation(prefs.getInt("hdiv", 340));
        h.setContinuousLayout(true);
        JSplitPane v = new JSplitPane(JSplitPane.VERTICAL_SPLIT, h, issuesPanel);
        v.setResizeWeight(0.75);
        v.setDividerLocation(prefs.getInt("vdiv", 560));
        v.setContinuousLayout(true);

        JPanel statusBar = new Surface(new BorderLayout(), 0);
        statusBar.setBorder(new CompoundBorder(new Line(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
        status.setForeground(null);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", e -> {
            for (Object o : new Object[] { e.getOldValue(), e.getNewValue() }) {
                if (!(o instanceof Component c)) continue;
                c.repaint();
                Container par = c.getParent();
                if (par instanceof JComboBox) par.repaint();
            }
        });
        statusBar.add(status, BorderLayout.CENTER);
        statusBar.add(statusRight, BorderLayout.EAST);

        getContentPane().add(v, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());
        setGlassPane(overlay);
        Icons.install(this);

        setTransferHandler(fileDrop(ps -> openPaths(ps, files.isEmpty())));

        addWindowListener(
            new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    editor.commitAll();
                    if (discardCancelled()) return;
                    Rectangle r = getBounds();
                    prefs.putInt("x", r.x);
                    prefs.putInt("y", r.y);
                    prefs.putInt("w", r.width);
                    prefs.putInt("h", r.height);
                    prefs.putInt("hdiv", h.getDividerLocation());
                    prefs.putInt("vdiv", v.getDividerLocation());
                    dispose();
                    System.exit(0);
                }
            }
        );

        int w = prefs.getInt("w", 1280),
            hh = prefs.getInt("h", 820);
        setSize(w, hh);
        int x = prefs.getInt("x", -1),
            y = prefs.getInt("y", -1);
        if (x >= 0 && y >= 0 && isOnScreen(new Rectangle(x, y, w, hh))) setLocation(x, y);
        else setLocationRelativeTo(null);
        updateTitle();
        updateUndoItems();
    }

    public static boolean isOnScreen(Rectangle r) {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            if (gd.getDefaultConfiguration().getBounds().intersects(r)) return true;
        return false;
    }

    public JMenuItem item(String text, KeyStroke ks, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        if (ks != null) i.setAccelerator(ks);
        i.addActionListener(e -> r.run());
        return i;
    }

    public JMenuBar buildMenu() {
        int M = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("Open folder…", KeyStroke.getKeyStroke(KeyEvent.VK_O, M), this::chooseFolder));
        file.add(
            item("Add files…", KeyStroke.getKeyStroke(KeyEvent.VK_O, M | InputEvent.SHIFT_DOWN_MASK), this::chooseFiles)
        );
        file.add(item("New spec file…", null, this::newSpecFile));
        file.add(item("Reload from disk", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), this::reload));
        file.addSeparator();
        file.add(item("Save file", KeyStroke.getKeyStroke(KeyEvent.VK_S, M), this::saveCurrent));
        file.add(item("Review unsaved changes…", null, this::reviewChanges));
        file.add(
            item("Save all", KeyStroke.getKeyStroke(KeyEvent.VK_S, M | InputEvent.SHIFT_DOWN_MASK), this::saveAll)
        );
        file.addSeparator();
        file.add(item("Exit", null, () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        JMenu edit = new JMenu("Edit");
        undoItem = item("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, M), this::undo);
        redoItem = item("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, M | InputEvent.SHIFT_DOWN_MASK), this::redo);
        edit.add(undoItem);
        edit.add(redoItem);
        getRootPane()
            .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, M), "redoY");
        getRootPane().getActionMap().put(
            "redoY",
            new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    redo();
                }
            }
        );
        edit.addSeparator();
        edit.add(item("New message", KeyStroke.getKeyStroke(KeyEvent.VK_N, M), this::newMessage));
        edit.add(item("Duplicate message", KeyStroke.getKeyStroke(KeyEvent.VK_D, M), this::duplicateMessage));
        edit.add(item("Delete message", null, this::deleteMessage));
        edit.add(item("Rename topic level…", null, () -> showTopics(null, true)));
        edit.addSeparator();
        edit.add(
            item("Find", KeyStroke.getKeyStroke(KeyEvent.VK_F, M), () -> {
                search.requestFocusInWindow();
                search.selectAll();
            })
        );
        JMenu view = new JMenu("View");
        JMenu themes = new JMenu("Theme");
        ButtonGroup tg = new ButtonGroup();
        String cur = prefs.get("theme", "One Dark");
        for (String nm : Theme.names()) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(nm, nm.equals(cur));
            mi.addActionListener(e -> setTheme(nm));
            tg.add(mi);
            themes.add(mi);
        }
        view.add(themes);
        JCheckBoxMenuItem anim = new JCheckBoxMenuItem("Animations", Settings.animations);
        anim.addActionListener(e -> {
            Settings.animations = anim.isSelected();
            prefs.putBoolean("animations", Settings.animations);
        });
        view.add(anim);
        JMenu go = new JMenu("Go");
        go.add(item("Go to message…", KeyStroke.getKeyStroke(KeyEvent.VK_P, M), this::showGoTo));
        int histMask = MAC ? M : InputEvent.ALT_DOWN_MASK;
        go.add(
            item("Back", KeyStroke.getKeyStroke(MAC ? KeyEvent.VK_OPEN_BRACKET : KeyEvent.VK_LEFT, histMask), () ->
                history(-1)
            )
        );
        go.add(
            item("Forward", KeyStroke.getKeyStroke(MAC ? KeyEvent.VK_CLOSE_BRACKET : KeyEvent.VK_RIGHT, histMask), () ->
                history(1)
            )
        );
        go.addSeparator();
        go.add(
            item("Find topic usages…", KeyStroke.getKeyStroke(KeyEvent.VK_F, M | InputEvent.SHIFT_DOWN_MASK), () ->
                showTopics(null, false)
            )
        );
        JMenu tools = new JMenu("Tools");
        tools.add(
            item("Validate now", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), () -> {
                editor.commitAll();
                revalidateAll();
                rebuildTree();
            })
        );
        tools.add(item("Find free CAN IDs…", null, this::findFreeIds));
        tools.add(item("Fix outdated keys in all files", null, this::fixOutdatedKeys));
        tools.add(item("ID map (all messages)…", KeyStroke.getKeyStroke(KeyEvent.VK_I, M), this::showIdMap));
        tools.add(
            item(
                "Decode CAN traffic (candump)…",
                KeyStroke.getKeyStroke(KeyEvent.VK_T, M | InputEvent.SHIFT_DOWN_MASK),
                this::openTraffic
            )
        );
        JMenu help = new JMenu("Help");
        help.add(item("Spec rules checked", null, this::showRules));
        help.add(
            item("About", null, () ->
                JOptionPane.showMessageDialog(
                    this,
                    "Tiresias\nBrowse, search, edit and validate Calypso CAN spec JSON,\nand see what will break the firmware build before it does.\n\nNamed for the prophet who warns Odysseus of the dangers ahead.\n\nJava " +
                        System.getProperty("java.version"),
                    "About",
                    JOptionPane.INFORMATION_MESSAGE
                )
            )
        );
        mb.add(file);
        mb.add(edit);
        mb.add(view);
        mb.add(go);
        mb.add(tools);
        mb.add(help);
        return mb;
    }

    public void setTheme(String name) {
        editor.commitAll();
        crossfade(getRootPane(), 320, () -> {
            Theme.apply(name);
            prefs.put("theme", name);
            revalidateAll();
            editor.themeChanged();
            rebuildTree();
        });
        status.setText("Theme: " + name);
    }

    // ---------- opening / saving

    public static List<Path> initialPaths(List<String> args) {
        List<Path> ps = new ArrayList<>();
        if (!args.isEmpty()) for (String a : args) ps.add(Paths.get(a));
        else for (String s : Settings.NODE.get("lastPaths", "").split(Pattern.quote(File.pathSeparator))) {
            if (!s.isEmpty() && Files.exists(Paths.get(s))) ps.add(Paths.get(s));
        }
        return ps;
    }

    public void openInitial(List<Path> ps, CompletableFuture<Loader.Loaded> preload) {
        if (preload == null) {
            editor.showWelcome();
            return;
        }
        overlay.start("Loading spec files…");
        preload.whenComplete((r, ex) ->
            SwingUtilities.invokeLater(() -> {
                overlay.stop();
                applyLoaded(r != null ? r : Loader.Loaded.failed(String.valueOf(ex)), ps, true);
            })
        );
    }

    public void chooseFolder() {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Open a folder of CAN spec JSON files");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openPaths(
            Collections.singletonList(fc.getSelectedFile().toPath()),
            true
        );
    }

    public void chooseFiles() {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON spec files", "json"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> ps = new ArrayList<>();
            for (File f : fc.getSelectedFiles()) ps.add(f.toPath());
            openPaths(ps, files.isEmpty());
        }
    }

    public void openPaths(List<Path> paths, boolean replace) {
        openPaths(paths, replace, null);
    }

    /** Loads files on a background thread behind the loading overlay, then runs after (if any) on the Swing thread. */
    public void openPaths(List<Path> paths, boolean replace, Runnable after) {
        editor.commitAll();
        if (replace && discardCancelled()) return;
        List<SpecFile> keep = replace ? List.of() : List.copyOf(files);
        overlay.start(replace ? "Loading spec files…" : "Adding files…");
        Thread.ofVirtual()
            .name("spec-loader")
            .start(() -> {
                Loader.Loaded r = Loader.loadAll(paths, keep);
                SwingUtilities.invokeLater(() -> {
                    overlay.stop();
                    if (applyLoaded(r, paths, replace) && after != null) after.run();
                });
            });
    }

    public boolean applyLoaded(Loader.Loaded r, List<Path> paths, boolean replace) {
        if (r.error() != null) {
            JOptionPane.showMessageDialog(this, r.error(), "Cannot open", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (replace) {
            files.clear();
            lastInputs = new ArrayList<>();
            editor.clear();
            undoStack.clear();
            redoStack.clear();
            snap.clear();
            savedText.clear();
        }
        for (Path p : paths) {
            Path n = p.toAbsolutePath().normalize();
            if (!lastInputs.contains(n)) lastInputs.add(n);
        }
        files.addAll(r.added());
        snap.putAll(r.snapshots());
        savedText.putAll(r.snapshots());
        baseDir =
            lastInputs.size() == 1 && Files.isDirectory(lastInputs.getFirst())
                ? lastInputs.getFirst()
                : Loader.commonParent(
                      files
                          .stream()
                          .map(f -> f.path)
                          .toList()
                  );
        files.sort(Comparator.comparing(f -> relName(f).toLowerCase(Locale.ROOT)));
        prefs.put("lastPaths", String.join(File.pathSeparator, lastInputs.stream().map(Path::toString).toList()));
        Path first = lastInputs.isEmpty() ? null : lastInputs.getFirst();
        if (first != null) prefs.put("lastDir", (Files.isDirectory(first) ? first : first.getParent()).toString());
        families.clear();
        families.putAll(r.families());
        specEdited();
        applyIssues(r.issues());
        updateUndoItems();
        rebuildTree();
        if (editor.msg == null) editor.showOverview();
        int msgs = files
            .stream()
            .mapToInt(f -> f.messages.size())
            .sum();
        status.setText(
            "Loaded " +
                files.size() +
                " file(s), " +
                msgs +
                " messages" +
                (r.added().isEmpty() && !replace ? " (nothing new)" : "") +
                (r.skipped() > 0 ? " · skipped " + r.skipped() + " JSON file(s) that aren't message lists" : "")
        );
        updateTitle();
        return true;
    }

    public void reload() {
        if (lastInputs.isEmpty()) return;
        editor.commitAll();
        if (discardCancelled()) return;
        for (SpecFile f : files) f.dirty = false;
        Map<String, Object> sel = editor.msg;
        String selId = sel == null ? null : str(sel, "id");
        Path selPath = editor.file == null ? null : editor.file.path;
        openPaths(new ArrayList<>(lastInputs), true, () -> {
            if (selId != null) for (SpecFile f : files)
                if (f.path.equals(selPath)) for (Object o : f.messages)
                    if (selId.equals(str(asMap(o), "id"))) {
                        selectMessage(f, asMap(o));
                        return;
                    }
        });
    }

    /** Asks what to do with unsaved changes; true if the user cancelled (or saving failed), so the caller should stop. */
    public boolean discardCancelled() {
        List<SpecFile> dirty = new ArrayList<>();
        for (SpecFile f : files) if (f.dirty) dirty.add(f);
        if (dirty.isEmpty()) return false;
        StringBuilder sb = new StringBuilder("Unsaved changes in:\n");
        for (SpecFile f : dirty) sb.append("   ").append(relName(f)).append('\n');
        Object[] options = { "Save all", "Discard changes", "Cancel" };
        int r = JOptionPane.showOptionDialog(
            this,
            sb.toString(),
            "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );
        if (r == 0) return !saveAll();
        return r != 1;
    }

    public boolean save(SpecFile f) {
        editor.commitAll();
        if (f.loadError != null) {
            JOptionPane.showMessageDialog(
                this,
                relName(f) + " could not be loaded, so it can't be saved:\n" + f.loadError,
                "Cannot save",
                JOptionPane.ERROR_MESSAGE
            );
            return false;
        }
        validateNow();
        long errs = issues
            .stream()
            .filter(i -> i.file() == f && i.sev() == Severity.ERROR)
            .count();
        if (errs > 0) {
            int r = JOptionPane.showConfirmDialog(
                this,
                relName(f) + " has " + errs + " error(s) that will fail the spec build.\nSave anyway?",
                "File has errors",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (r != JOptionPane.YES_OPTION) return false;
        }
        try {
            Loader.save(f);
            savedText.put(f, Json.write(f.messages));
            snap.put(f, savedText.get(f));
            status.setText("Saved " + relName(f));
            tree.repaint();
            updateTitle();
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                this,
                "Could not save " + f.path + ":\n" + ex.getMessage(),
                "Save failed",
                JOptionPane.ERROR_MESSAGE
            );
            return false;
        }
    }

    public void saveCurrent() {
        SpecFile f = currentFile();
        if (f == null && files.size() == 1) f = files.getFirst();
        if (f == null) {
            status.setText("Select a file or message to save");
            return;
        }
        editor.commitAll();
        if (f.dirty && reviewBeforeSave() && !ReviewDialog.review(this, List.of(f), true)) {
            status.setText("Not saved");
            return;
        }
        save(f);
    }

    public boolean saveAll() {
        editor.commitAll();
        List<SpecFile> dirty = new ArrayList<>();
        for (SpecFile f : files) if (f.dirty) dirty.add(f);
        if (dirty.isEmpty()) {
            status.setText("No unsaved changes");
            return true;
        }
        if (reviewBeforeSave() && !ReviewDialog.review(this, dirty, true)) {
            status.setText("Not saved");
            return false;
        }
        boolean ok = true;
        for (SpecFile f : dirty) ok &= save(f);
        return ok;
    }

    public boolean reviewBeforeSave() {
        return prefs.getBoolean("reviewBeforeSave", true);
    }

    /** File > Review unsaved changes: the diff of every unsaved file, with a button to save them all. */
    public void reviewChanges() {
        editor.commitAll();
        List<SpecFile> dirty = new ArrayList<>();
        for (SpecFile f : files) if (f.dirty) dirty.add(f);
        if (dirty.isEmpty()) {
            status.setText("No unsaved changes");
            return;
        }
        if (ReviewDialog.review(this, dirty, false)) for (SpecFile f : dirty) save(f);
    }

    // ---------- go to, history, topics

    public void showGoTo() {
        editor.commitAll();
        if (goTo == null) goTo = new GoToDialog(this);
        // open it once the menu shortcut has finished, so the menu doesn't take the focus back from it
        SwingUtilities.invokeLater(goTo::showFor);
    }

    /** A place in the navigation history: a message (or a whole file when msg is null) and its index as a fallback. */
    public record Place(SpecFile file, Map<String, Object> msg, int index) {}

    public static final int HISTORY_MAX = 100;
    public final List<Place> places = new ArrayList<>();
    public int placeAt = -1;
    public boolean travelling;

    /** Records that a message or file was opened (called by the editor), dropping any forward history. */
    public void visit(SpecFile f, Map<String, Object> m) {
        if (travelling || restoring || f == null) return; // undo reloading a message isn't a visit
        if (placeAt >= 0 && placeAt < places.size()) {
            Place cur = places.get(placeAt);
            if (cur.file() == f && cur.msg() == m) return;
        }
        while (places.size() > placeAt + 1) places.removeLast();
        places.add(new Place(f, m, m == null ? -1 : indexOfIdentity(f.messages, m)));
        while (places.size() > HISTORY_MAX) places.removeFirst();
        placeAt = places.size() - 1;
    }

    /** Go > Back (-1) and Forward (+1), skipping places whose file has closed. */
    public void history(int dir) {
        editor.commitAll();
        for (int i = placeAt + dir; i >= 0 && i < places.size(); i += dir) {
            Place p = places.get(i);
            if (!files.contains(p.file())) continue;
            Map<String, Object> m = p.msg();
            // undo and reload replace message objects, so fall back to the same position in the file
            if (m != null && indexOfIdentity(p.file().messages, m) < 0) m =
                p.index() >= 0 && p.index() < p.file().messages.size() ? asMap(p.file().messages.get(p.index())) : null;
            if (p.msg() != null && m == null) continue;
            placeAt = i;
            travelling = true;
            try {
                if (m == null) selectFile(p.file());
                else selectMessage(p.file(), m);
            } finally {
                travelling = false;
            }
            return;
        }
        status.setText(dir < 0 ? "Nothing to go back to" : "Nothing to go forward to");
    }

    /** The most recently visited messages, newest first, for Go to's empty list. */
    public List<Place> recentPlaces(int limit) {
        List<Place> out = new ArrayList<>();
        for (int i = places.size() - 1; i >= 0 && out.size() < limit; i--) {
            Place p = places.get(i);
            if (
                p.msg() == null || !files.contains(p.file()) || indexOfIdentity(p.file().messages, p.msg()) < 0
            ) continue;
            boolean dup = false;
            for (Place q : out) dup |= q.msg() == p.msg();
            if (!dup) out.add(p);
        }
        return out;
    }

    /** Topic suggestions for a topic text field (see Suggest). */
    public List<Suggest.Item> topicSuggestions(String typed) {
        List<Suggest.Item> out = new ArrayList<>();
        for (Topics.Completion c : Topics.complete(files, typed, 12))
            out.add(
                new Suggest.Item(c.text(), c.complete() ? "topic" : c.uses() + (c.uses() == 1 ? " topic" : " topics"))
            );
        return out;
    }

    public void showTopics(String level, boolean renaming) {
        editor.commitAll();
        if (topicsDialog == null) topicsDialog = new TopicsDialog(this);
        SwingUtilities.invokeLater(() -> topicsDialog.open(level, renaming));
    }

    /** Opens a field: its message, the Fields tab, and the field's row. */
    public void openField(FieldRef r) {
        if (!files.contains(r.file()) || indexOfIdentity(r.file().messages, r.msg()) < 0) {
            status.setText("That field's message no longer exists");
            return;
        }
        selectMessage(r.file(), r.msg());
        editor.tabs.setSelectedIndex(1);
        if (r.field() < editor.fieldsTable.getRowCount()) {
            editor.fieldsTable.setRowSelectionInterval(r.field(), r.field());
            editor.fieldsTable.scrollRectToVisible(editor.fieldsTable.getCellRect(r.field(), 0, true));
        }
    }

    /** Renames a topic level in every topic under it, one undo step per file. Returns false if nothing changed. */
    public boolean renameTopicLevel(String from, String to) {
        editor.commitAll();
        Map<SpecFile, Integer> changedFiles = Topics.rename(files, from, to);
        if (changedFiles.isEmpty()) {
            status.setText("No topics are at or under " + from);
            return false;
        }
        int n = 0;
        for (Map.Entry<SpecFile, Integer> e : changedFiles.entrySet()) {
            n += e.getValue();
            nextUndoLabel = "rename topic level " + from + " to " + to;
            changed(e.getKey(), false);
        }
        revalidateAll();
        rebuildTree();
        if (editor.msg != null) {
            editor.refresh();
            editor.loadFieldDetail();
        }
        status.setText("Renamed " + n + " topic(s) in " + changedFiles.size() + " file(s): " + from + " → " + to);
        return true;
    }

    public void newSpecFile() {
        JFileChooser fc = new JFileChooser(
            baseDir != null ? baseDir.toFile() : new File(prefs.get("lastDir", System.getProperty("user.home")))
        );
        fc.setDialogTitle("Create a new spec file");
        fc.setSelectedFile(new File("new_node.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) p = p.resolveSibling(
            p.getFileName() + ".json"
        );
        if (Files.exists(p)) {
            JOptionPane.showMessageDialog(this, p + " already exists.", "File exists", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Files.writeString(p, "[]\n");
            Path created = p.toAbsolutePath().normalize();
            openPaths(List.of(p), false, () ->
                files
                    .stream()
                    .filter(f -> f.path.equals(created))
                    .findFirst()
                    .ifPresent(this::selectFile)
            );
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Could not create file", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------- message operations

    public SpecFile currentFile() {
        if (editor.file != null) return editor.file;
        return selectedFileOnly;
    }

    public Set<Long> usedIds() {
        Set<Long> s = new HashSet<>();
        for (SpecFile f : files)
            for (Object o : f.messages) {
                Long v = parseId(str(asMap(o), "id"));
                if (v != null) s.add(v);
            }
        return s;
    }

    public String nextFreeId(long after, boolean ext) {
        Set<Long> used = usedIds();
        long max = ext ? 0x1FFFFFFFL : 0x7FFL;
        for (long v = after + 1; v <= max; v++) if (!used.contains(v)) return hex(v);
        for (long v = 0; v <= Math.min(after, max); v++) if (!used.contains(v)) return hex(v);
        return null;
    }

    public String predominantNode(SpecFile f) {
        Map<String, Integer> c = new HashMap<>();
        for (Object o : f.messages)
            for (Object fo : listOrEmpty(asMap(o) == null ? null : asMap(o).get("fields"))) {
                String nm = str(asMap(fo), "name");
                if (nm == null || nm.isEmpty()) continue;
                String node = nm.split("/")[0];
                if (!node.equals("Calypso") && !node.isEmpty()) c.merge(node, 1, Integer::sum);
            }
        return c.isEmpty() ? "Node" : Collections.max(c.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    public void newMessage() {
        editor.commitAll();
        SpecFile f = currentFile();
        if (f == null) {
            if (files.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Open a folder of spec files first (File > Open folder…).");
                return;
            }
            f = pickFile();
            if (f == null) return;
        }
        if (f.loadError != null) {
            JOptionPane.showMessageDialog(this, "That file failed to load and can't be edited.");
            return;
        }
        long maxStd = -1;
        for (Object o : f.messages) {
            Long v = parseId(str(asMap(o), "id"));
            if (v != null && v <= 0x7FF) maxStd = Math.max(maxStd, v);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String id = nextFreeId(maxStd, false);
        m.put("id", id == null ? "0x0" : id);
        m.put("desc", "New Message");
        List<Object> pts = new ArrayList<>();
        pts.add(newPoint("value"));
        m.put("points", pts);
        List<Object> fds = new ArrayList<>();
        fds.add(newField(predominantNode(f) + "/NewMessage/value", 1));
        m.put("fields", fds);
        int idx =
            editor.msg != null && editor.file == f ? indexOfIdentity(f.messages, editor.msg) + 1 : f.messages.size();
        f.messages.add(idx, m);
        nextUndoLabel = "new message";
        changed(f, false);
        rebuildTree();
        selectMessage(f, m);
        editor.focusId();
    }

    public void duplicateMessage() {
        editor.commitAll();
        if (editor.msg == null) {
            status.setText("Select a message to duplicate");
            return;
        }
        SpecFile f = editor.file;
        Map<String, Object> copy = Json.deepCopy(editor.msg);
        Long v = parseId(str(copy, "id"));
        boolean ext = Boolean.TRUE.equals(copy.get("is_ext"));
        if (v != null) {
            String nid = nextFreeId(v, ext || v > 0x7FF);
            if (nid != null) copy.put("id", nid);
        }
        copy.put("desc", strOr(copy, "desc", "") + " Copy");
        f.messages.add(indexOfIdentity(f.messages, editor.msg) + 1, copy);
        nextUndoLabel = "duplicate " + strOr(editor.msg, "id", "message");
        changed(f, false);
        rebuildTree();
        selectMessage(f, copy);
        status.setText("Duplicated as " + str(copy, "id") + ". Rename its topics: duplicate topics are flagged.");
    }

    public void deleteMessage() {
        editor.commitAll();
        if (editor.msg == null) return;
        SpecFile f = editor.file;
        Map<String, Object> m = editor.msg;
        int r = JOptionPane.showConfirmDialog(
            this,
            "Delete message " + strOr(m, "id", "?") + " \"" + strOr(m, "desc", "") + "\"?",
            "Delete message",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (r != JOptionPane.OK_OPTION) return;
        int idx = indexOfIdentity(f.messages, m);
        if (idx >= 0) f.messages.remove(idx);
        forgetFamilyMember(m);
        editor.detach();
        nextUndoLabel = "delete " + strOr(m, "id", "message");
        changed(f, false);
        rebuildTree();
        selectFile(f);
    }

    public SpecFile pickFile() {
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) names[i] = relName(files.get(i));
        Object r = JOptionPane.showInputDialog(
            this,
            "Add the new message to which file?",
            "Choose file",
            JOptionPane.QUESTION_MESSAGE,
            null,
            names,
            names[0]
        );
        if (r == null) return null;
        for (SpecFile f : files) if (relName(f).equals(r)) return f;
        return null;
    }

    // ---------- undo / redo
    // Each change stores the file's JSON before and after, so every kind of edit (tables, drag,
    // JSON tab, bulk fixes, family copies) can be undone without per-operation code.

    /** One undoable change: the file's JSON before and after, and where the user was. */
    public record UndoEntry(SpecFile f, String before, String after, String label, int msgIndex, int tab) {}

    public final Deque<UndoEntry> undoStack = new ArrayDeque<>(),
        redoStack = new ArrayDeque<>();
    public final Map<SpecFile, String> snap = new IdentityHashMap<>(),
        savedText = new IdentityHashMap<>();
    public String nextUndoLabel;
    public boolean restoring;
    public JMenuItem undoItem, redoItem;
    public TrafficWindow traffic;

    public void recordUndo(SpecFile f) {
        String label = nextUndoLabel;
        nextUndoLabel = null;
        if (f == null || f.loadError != null || restoring) return;
        String now = Json.write(f.messages);
        String before = snap.get(f);
        f.dirty = !now.equals(savedText.get(f));
        if (before == null || now.equals(before)) {
            snap.put(f, now);
            return;
        }
        if (label == null) label =
            editor.msg != null && editor.file == f
                ? "edit to " + strOr(editor.msg, "id", "message")
                : "edit in " + relName(f);
        int mi = editor.file == f && editor.msg != null ? indexOfIdentity(f.messages, editor.msg) : -1;
        undoStack.push(new UndoEntry(f, before, now, label, mi, editor.tabs.getSelectedIndex()));
        while (undoStack.size() > 200) undoStack.removeLast();
        redoStack.clear();
        snap.put(f, now);
        updateUndoItems();
    }

    public void undo() {
        editor.commitAll();
        UndoEntry e = undoStack.poll();
        if (e == null) {
            status.setText("Nothing to undo");
            return;
        }
        restore(e.f(), e.before(), e.msgIndex(), e.tab());
        redoStack.push(e);
        status.setText("Undid " + e.label());
        updateUndoItems();
    }

    public void redo() {
        editor.commitAll();
        UndoEntry e = redoStack.poll();
        if (e == null) {
            status.setText("Nothing to redo");
            return;
        }
        restore(e.f(), e.after(), e.msgIndex(), e.tab());
        undoStack.push(e);
        status.setText("Redid " + e.label());
        updateUndoItems();
    }

    public void restore(SpecFile f, String text, int msgIndex, int tab) {
        if (!files.contains(f)) {
            status.setText("That file is no longer open");
            return;
        }
        restoring = true;
        try {
            // remember family membership by position, since restoring creates new objects
            Map<Family, int[]> famIdx = new IdentityHashMap<>();
            for (Family fam : new HashSet<>(families.values())) {
                if (fam.file() != f) continue;
                int[] ix = new int[fam.members().size()];
                for (int i = 0; i < ix.length; i++) ix[i] = indexOfIdentity(f.messages, fam.members().get(i));
                famIdx.put(fam, ix);
            }
            int oldSize = f.messages.size();
            editor.detach();
            List<Object> l = asList(Json.parse(text));
            f.messages.clear();
            if (l != null) f.messages.addAll(l);
            snap.put(f, text);
            f.dirty = !text.equals(savedText.get(f));
            if (f.messages.size() == oldSize) {
                families.values().removeIf(fam -> fam.file() == f);
                for (Map.Entry<Family, int[]> e : famIdx.entrySet()) {
                    Family fam = e.getKey();
                    fam.members().clear();
                    for (int i : e.getValue())
                        if (i >= 0 && i < f.messages.size() && asMap(f.messages.get(i)) != null) fam.members().add(
                            asMap(f.messages.get(i))
                        );
                    if (fam.members().size() >= 2) for (Map<String, Object> m : fam.members()) families.put(m, fam);
                }
            } else computeFamilies(f);
            revalidateAll();
            rebuildTree();
            Map<String, Object> m =
                msgIndex >= 0 && msgIndex < f.messages.size() ? asMap(f.messages.get(msgIndex)) : null;
            if (m != null) {
                selectMessage(f, m);
                if (tab >= 0 && tab < editor.tabs.getTabCount()) editor.tabs.setSelectedIndex(tab);
            } else selectFile(f);
            specEdited();
            updateTitle();
        } finally {
            restoring = false;
        }
    }

    public void updateUndoItems() {
        if (undoItem == null) return;
        UndoEntry u = undoStack.peek(),
            r = redoStack.peek();
        undoItem.setEnabled(u != null);
        undoItem.setText(u == null ? "Undo" : "Undo " + u.label());
        redoItem.setEnabled(r != null);
        redoItem.setText(r == null ? "Redo" : "Redo " + r.label());
    }

    // ---------- message families
    // A family is a set of messages in one file with the same point sizes and field count, whose topics
    // are identical except for one level (e.g. VCU/eFuses/<Dashboard|Brake|...>/ADC). Nothing is
    // stored in the JSON: families are recognized when files are opened.

    public final Map<Map<String, Object>, Family> families = new IdentityHashMap<>();

    public void computeFamilies(SpecFile only) {
        findFamilies(files, only, families);
    }

    public void forgetFamilyMember(Map<String, Object> m) {
        Family fam = families.remove(m);
        if (fam == null) return;
        fam.members().removeIf(x -> x == m);
        if (fam.members().size() < 2) {
            for (Map<String, Object> x : fam.members()) families.remove(x);
            fam.members().clear();
        }
    }

    /** Tells the windows that show spec contents (traffic, topic usages) that it changed. */
    public void specEdited() {
        if (traffic != null) traffic.specChanged();
        if (topicsDialog != null && topicsDialog.isVisible()) {
            int sel = topicsDialog.list.getSelectedIndex();
            topicsDialog.refresh();
            if (sel >= 0 && sel < topicsDialog.model.size()) topicsDialog.list.setSelectedIndex(sel);
        }
    }

    public void openTraffic() {
        editor.commitAll();
        if (traffic == null) traffic = new TrafficWindow(this);
        traffic.setVisible(true);
        traffic.toFront();
    }

    // ---------- change propagation

    public void changed(SpecFile f, boolean structural) {
        if (f != null) {
            f.dirty = true;
            recordUndo(f);
            DefaultMutableTreeNode fn = findFileNode(f);
            if (fn != null) treeModel.nodeChanged(fn);
        }
        validateTimer.restart();
        if (structural) scheduleRebuild();
        else tree.repaint();
        specEdited();
        updateTitle();
    }

    public void scheduleRebuild() {
        if (rebuildScheduled) return;
        rebuildScheduled = true;
        SwingUtilities.invokeLater(() -> {
            rebuildScheduled = false;
            rebuildTree();
        });
    }

    /** Checks the whole spec now and shows the result, even if it hasn't changed (a theme change recolors it). */
    public void revalidateAll() {
        validateTimer.stop();
        applyIssues(Validator.validate(files));
    }

    /** Runs a check that edits scheduled but that hasn't run yet, so callers like save see current problems. */
    public void validateNow() {
        if (validateTimer.isRunning()) validateEdits();
    }

    /** The check after edits: the problem views only update when the problems differ, so their selection stays. */
    public void validateEdits() {
        validateTimer.stop();
        List<Issue> found = Validator.validate(files);
        if (sameIssues(found, issues)) return;
        applyIssues(found);
        tree.repaint();
    }

    // ---------- quick fixes (the changes themselves are in Fixes)

    /**
     * A menu of fixes for the given issues (for example a right-clicked row): one item per distinct fix, plus
     * "Fix all N …" when the spec has more problems of that kind. Null when none of them can be fixed.
     */
    public JPopupMenu fixMenu(List<Issue> here) {
        JPopupMenu pm = new JPopupMenu();
        fillFixMenu(pm, here);
        return pm.getComponentCount() == 0 ? null : pm;
    }

    /** Adds the fix items for these issues to a menu (see fixMenu). */
    public void fillFixMenu(JPopupMenu pm, List<Issue> here) {
        Set<String> labels = new HashSet<>();
        Set<FixKind> kinds = EnumSet.noneOf(FixKind.class);
        for (Issue is : here) {
            if (is.fix() == null || !labels.add(is.fix().label())) continue;
            kinds.add(is.fix().kind());
            JMenuItem it = new JMenuItem("Fix: " + is.fix().label());
            it.addActionListener(e -> applyFixes(List.of(is), is.fix().label()));
            pm.add(it);
        }
        for (FixKind k : kinds) {
            List<Issue> all = new ArrayList<>();
            for (Issue is : issues) if (is.fix() != null && is.fix().kind() == k) all.add(is);
            // count what would change, not issues: one topic can have three slash problems
            int targets = 0;
            for (int i = 0; i < all.size(); i++) {
                Issue x = all.get(i);
                boolean seen = false;
                for (int j = 0; j < i && !seen; j++) {
                    Issue y = all.get(j);
                    seen = y.msg() == x.msg() && y.point() == x.point() && y.field() == x.field();
                }
                if (!seen) targets++;
            }
            if (targets < 2) continue;
            if (pm.getComponentCount() == labels.size()) pm.addSeparator();
            JMenuItem it = new JMenuItem("Fix all " + targets + " " + k.what);
            it.addActionListener(e -> applyFixes(all, "fix " + k.what));
            pm.add(it);
        }
    }

    /** Shows the fix menu for these issues at a mouse event, or says there's nothing to fix. */
    public void showFixMenu(List<Issue> here, java.awt.event.MouseEvent e) {
        JPopupMenu pm = fixMenu(here);
        if (pm != null) pm.show(e.getComponent(), e.getX(), e.getY());
        else if (!here.isEmpty()) status.setText("Tiresias can't fix this problem by itself");
    }

    /** Applies the issues' fixes, with one undo step per changed file, then checks again at once. */
    public void applyFixes(List<Issue> list, String what) {
        editor.commitAll();
        Set<SpecFile> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        int n = 0;
        for (Issue is : list) {
            if (!files.contains(is.file()) || !Fixes.apply(is)) continue;
            n++;
            touched.add(is.file());
        }
        if (n == 0) {
            revalidateAll();
            status.setText("Nothing to change: that's already fixed");
            return;
        }
        for (SpecFile f : touched) {
            nextUndoLabel = what.startsWith("fix ") ? what : "fix: " + what;
            changed(f, false);
        }
        revalidateAll();
        rebuildTree();
        if (editor.msg != null) {
            editor.refreshHeader();
            editor.refresh();
            if (editor.tabs.getSelectedIndex() == MessageEditor.TAB_JSON) editor.loadJson();
        }
        status.setText(n == 1 ? "Fixed: " + what : "Fixed " + n + " problems (" + what.replaceFirst("^fix ", "") + ")");
    }

    /** Issue equality by identity of the file and message (records would compare the JSON trees deeply). */
    public static boolean sameIssues(List<Issue> a, List<Issue> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Issue x = a.get(i),
                y = b.get(i);
            if (
                x.sev() != y.sev() ||
                x.file() != y.file() ||
                x.msg() != y.msg() ||
                x.point() != y.point() ||
                x.field() != y.field() ||
                !Objects.equals(x.where(), y.where()) ||
                !Objects.equals(x.text(), y.text()) ||
                !Objects.equals(x.fix(), y.fix())
            ) return false;
        }
        return true;
    }

    public void applyIssues(List<Issue> found) {
        issues = found;
        msgSev.clear();
        fileSev.clear();
        int[] c = new int[3];
        for (Issue is : issues) {
            c[is.sev().ordinal()]++;
            if (is.sev() == Severity.INFO) continue;
            if (is.msg() != null) msgSev.merge(is.msg(), is.sev(), Severity::worse);
            fileSev.merge(is.file(), is.sev(), Severity::worse);
        }
        issuesPanel.setIssues(issues);
        editor.issuesChanged();
        statusRight.setText(
            "<html><font color='" +
                hx(T.red()) +
                "'>● " +
                c[0] +
                " errors</font>&nbsp;&nbsp;&nbsp;<font color='" +
                hx(T.yellow()) +
                "'>● " +
                c[1] +
                " warnings</font>&nbsp;&nbsp;&nbsp;<font color='" +
                hx(T.blue()) +
                "'>● " +
                c[2] +
                " info</font></html>"
        );
    }

    public void updateTitle() {
        boolean dirty = false;
        for (SpecFile f : files) dirty |= f.dirty;
        setTitle("Tiresias" + (baseDir != null ? " — " + baseDir : "") + (dirty ? "  (unsaved changes)" : ""));
    }

    public String relName(SpecFile f) {
        if (baseDir != null && f.path.startsWith(baseDir) && !f.path.equals(baseDir)) return baseDir
            .relativize(f.path)
            .toString();
        return f.path.getFileName().toString();
    }

    // ---------- tree

    public Object selectedUserObject() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        return ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
    }

    public boolean isProblem(Map<String, Object> m) {
        return msgSev.get(m) != null;
    }

    public void rebuildTree() {
        rebuilding = true;
        try {
            Set<SpecFile> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
                if (tree.isExpanded(new TreePath(c.getPath()))) expanded.add(((FileNode) c.getUserObject()).f());
            }
            root.removeAllChildren();
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            int mode = searchMode.getSelectedIndex();
            boolean filtering = !q.isEmpty() || onlyIssues.isSelected();
            int shown = 0,
                total = 0;
            for (SpecFile f : files) {
                DefaultMutableTreeNode fn = new DefaultMutableTreeNode(new FileNode(this, f));
                List<Map<String, Object>> ms = new ArrayList<>();
                for (Object o : f.messages) {
                    Map<String, Object> m = asMap(o);
                    if (m != null) ms.add(m);
                }
                total += ms.size();
                if (sortById.isSelected()) sortMessagesById(ms);
                for (Map<String, Object> m : ms) {
                    if (!matches(m, q, mode)) continue;
                    if (onlyIssues.isSelected() && !isProblem(m)) continue;
                    fn.add(new DefaultMutableTreeNode(new MsgNode(f, m), false));
                    shown++;
                }
                boolean fileProblem = f.loadError != null || !f.parseWarnings.isEmpty();
                if (
                    !filtering || fn.getChildCount() > 0 || (onlyIssues.isSelected() && q.isEmpty() && fileProblem)
                ) root.add(fn);
            }
            treeModel.reload();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
                if (filtering || expanded.contains(((FileNode) c.getUserObject()).f())) tree.expandPath(
                    new TreePath(c.getPath())
                );
            }
            DefaultMutableTreeNode sel = null;
            if (editor.msg != null) sel = findNode(editor.msg);
            else if (selectedFileOnly != null) sel = findFileNode(selectedFileOnly);
            if (sel != null) {
                TreePath tp = new TreePath(sel.getPath());
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
            }
            if (filtering) status.setText("Showing " + shown + " of " + total + " messages");
            else if (!files.isEmpty()) status.setText(files.size() + " file(s), " + total + " messages");
        } finally {
            rebuilding = false;
        }
    }

    public static boolean matches(Map<String, Object> m, String q, int mode) {
        if (q.isEmpty()) return true;
        boolean all = mode == 0;
        if (all || mode == 1) {
            if (lc(str(m, "id")).contains(q)) return true;
            Long v = parseId(str(m, "id")),
                qv = parseLooseNumber(q);
            if (v != null && v.equals(qv)) return true;
        }
        if ((all || mode == 2) && lc(str(m, "desc")).contains(q)) return true;
        if (all && lc(str(m, "key")).contains(q)) return true;
        for (Object o : listOrEmpty(m.get("fields"))) {
            Map<String, Object> f = asMap(o);
            if (f == null) continue;
            if ((all || mode == 3) && lc(str(f, "name")).contains(q)) return true;
            if ((all || mode == 5) && lc(str(f, "unit")).equals(q)) return true;
            if ((all || mode == 6) && (lc(str(f, "doc")).contains(q) || lc(str(f, "desc")).contains(q))) return true;
        }
        if (all || mode == 4) for (Object o : listOrEmpty(m.get("points")))
            if (lc(str(asMap(o), "name")).contains(q)) return true;
        return false;
    }

    public DefaultMutableTreeNode findNode(Map<String, Object> m) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < fn.getChildCount(); j++) {
                DefaultMutableTreeNode mn = (DefaultMutableTreeNode) fn.getChildAt(j);
                if (((MsgNode) mn.getUserObject()).m() == m) return mn;
            }
        }
        return null;
    }

    public DefaultMutableTreeNode findFileNode(SpecFile f) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
            if (((FileNode) fn.getUserObject()).f() == f) return fn;
        }
        return null;
    }

    public void selectMessage(SpecFile f, Map<String, Object> m) {
        DefaultMutableTreeNode n = findNode(m);
        if (n == null) {
            search.setText("");
            searchTimer.stop();
            onlyIssues.setSelected(false);
            editor.setHighlight("", 0);
            rebuildTree();
            n = findNode(m);
        }
        if (n != null) {
            TreePath tp = new TreePath(n.getPath());
            tree.setSelectionPath(tp);
            tree.scrollPathToVisible(tp);
        }
        if (editor.msg != m) editor.load(f, m);
    }

    public void selectFile(SpecFile f) {
        DefaultMutableTreeNode n = findFileNode(f);
        if (n != null) tree.setSelectionPath(new TreePath(n.getPath()));
        else {
            selectedFileOnly = f;
            editor.showFile(f);
        }
    }

    /** Opens a file from the overview grid: selects it in the tree and expands it there. */
    public void openFile(SpecFile f) {
        selectFile(f);
        DefaultMutableTreeNode n = findFileNode(f);
        if (n != null) {
            TreePath tp = new TreePath(n.getPath());
            tree.expandPath(tp);
            tree.scrollPathToVisible(tp);
        }
    }

    public void selectFirstMessage() {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
            if (fn.getChildCount() > 0) {
                tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode) fn.getChildAt(0)).getPath()));
                return;
            }
        }
    }

    public void navigate(Issue is) {
        if (is.msg() == null) {
            selectFile(is.file());
            return;
        }
        selectMessage(is.file(), is.msg());
        editor.focusIssue(is);
    }

    // ---------- vocab for dropdowns

    public Set<String> units() {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(COMMON_UNITS);
        for (SpecFile f : files)
            for (Object o : f.messages)
                for (Object fo : listOrEmpty(asMap(o) == null ? null : asMap(o).get("fields"))) {
                    String u = str(asMap(fo), "unit");
                    if (u != null) s.add(u);
                }
        return s;
    }

    public Set<String> formatterKeys() {
        Set<String> s = new TreeSet<>();
        s.add("");
        s.add("divide");
        for (SpecFile f : files)
            for (Object o : f.messages)
                for (Object po : listOrEmpty(asMap(o) == null ? null : asMap(o).get("points"))) {
                    Map<String, Object> p = asMap(po);
                    if (p == null) continue;
                    String k = formatterKey(p);
                    if (!k.isEmpty()) s.add(k);
                }
        return s;
    }

    // ---------- tools

    /** Renames default_value to default and converts "format": "divideN" to a formatter, keeping key order. */
    public void fixOutdatedKeys() {
        editor.commitAll();
        int defaults = 0,
            formats = 0,
            skipped = 0;
        for (SpecFile f : files) {
            if (f.loadError != null) continue;
            boolean changedFile = false;
            for (Object o : f.messages)
                for (Object po : listOrEmpty(asMap(o) == null ? null : asMap(o).get("points"))) {
                    Map<String, Object> p = asMap(po);
                    if (p == null) continue;
                    boolean hasDv = p.containsKey("default_value") && !p.containsKey("default");
                    String fmt = str(p, "format");
                    Matcher mm = fmt == null ? null : LEGACY_DIVIDE.matcher(fmt);
                    boolean hasFmt = mm != null && mm.matches() && !p.containsKey("formatter");
                    if (p.containsKey("format") && !hasFmt) skipped++;
                    if (!hasDv && !hasFmt) continue;
                    Map<String, Object> rebuilt = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : p.entrySet()) {
                        if (hasDv && e.getKey().equals("default_value")) {
                            rebuilt.put("default", e.getValue());
                            defaults++;
                        } else if (hasFmt && e.getKey().equals("format")) {
                            Map<String, Object> fm = new LinkedHashMap<>();
                            fm.put("key", "divide");
                            fm.put("arg", new BigDecimal(mm.group(1)));
                            rebuilt.put("formatter", fm);
                            formats++;
                        } else rebuilt.put(e.getKey(), e.getValue());
                    }
                    p.clear();
                    p.putAll(rebuilt);
                    changedFile = true;
                }
            if (changedFile) {
                nextUndoLabel = "fix outdated keys";
                recordUndo(f);
            }
        }
        revalidateAll();
        rebuildTree();
        editor.refresh();
        updateTitle();
        String msg =
            defaults +
            " default_value key(s) renamed to default, " +
            formats +
            " format string(s) converted to formatter." +
            (skipped > 0 ? " " + skipped + " format value(s) weren't divideN and need fixing by hand." : "") +
            (defaults + formats > 0 ? " Review, then save." : "");
        JOptionPane.showMessageDialog(this, msg, "Fix outdated keys", JOptionPane.INFORMATION_MESSAGE);
    }

    public void findFreeIds() {
        String start = editor.msg != null ? strOr(editor.msg, "id", "0x0") : "0x0";
        String in = JOptionPane.showInputDialog(this, "List free CAN IDs starting after (hex):", start);
        if (in == null) return;
        Long v = parseLooseNumber(in.strip());
        if (v == null) {
            JOptionPane.showMessageDialog(this, "Enter a hex ID such as 0x500");
            return;
        }
        Set<Long> used = usedIds();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (long k = v + 1; k <= 0x1FFFFFFFL && n < 16; k++) if (!used.contains(k)) {
            sb.append(hex(k))
                .append(k > 0x7FF ? "  (extended)" : "")
                .append('\n');
            n++;
        }
        JTextArea ta = new JTextArea(sb.toString(), 16, 24);
        ta.setFont(mono(Font.PLAIN, 13));
        ta.setEditable(false);
        JOptionPane.showMessageDialog(
            this,
            new JScrollPane(ta),
            "Free IDs after " + hex(v),
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    public void showIdMap() {
        editor.commitAll();
        record IdRow(
            long sortKey,
            String id,
            String frame,
            String desc,
            int bits,
            int fields,
            String fileName,
            SpecRef ref
        ) {
            Object column(int c) {
                return switch (c) {
                    case 0 -> id;
                    case 1 -> frame;
                    case 2 -> desc;
                    case 3 -> bits;
                    case 4 -> fields;
                    default -> fileName;
                };
            }
        }
        List<IdRow> rows = new ArrayList<>();
        for (SpecFile f : files)
            for (Object o : f.messages) {
                if (!(asMap(o) instanceof Map<String, Object> m)) continue;
                Long v = parseId(str(m, "id"));
                List<Object> pts = listOrEmpty(m.get("points"));
                rows.add(
                    new IdRow(
                        v == null ? Long.MAX_VALUE : v,
                        strOr(m, "id", "?"),
                        Boolean.TRUE.equals(m.get("is_ext")) ? "ext" : "std",
                        strOr(m, "desc", ""),
                        offsets(pts)[pts.size()],
                        listOrEmpty(m.get("fields")).size(),
                        relName(f),
                        new SpecRef(f, m)
                    )
                );
            }
        rows.sort(Comparator.comparingLong(IdRow::sortKey));
        String[] cols = { "ID", "Frame", "Description", "Bits", "Fields", "File" };
        AbstractTableModel tm = new AbstractTableModel() {
            public int getRowCount() {
                return rows.size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            public String getColumnName(int c) {
                return cols[c];
            }

            public Class<?> getColumnClass(int c) {
                return c == 3 || c == 4 ? Integer.class : String.class;
            }

            public Object getValueAt(int r, int c) {
                return rows.get(r).column(c);
            }
        };
        JTable t = new JTable(tm);
        styleTable(t);
        t.setDefaultRenderer(Object.class, new StyledRenderer(null));
        t.setDefaultRenderer(Integer.class, new StyledRenderer(null));
        t.setAutoCreateRowSorter(true);
        t.getColumnModel()
            .getColumn(0)
            .setCellRenderer(
                new StyledRenderer((tb, row, col, sel, c) -> c.setFont(mono(Font.PLAIN, tb.getFont().getSize())))
            );
        t.getColumnModel().getColumn(2).setPreferredWidth(320);
        JDialog d = new JDialog(this, "ID map: " + rows.size() + " messages (double-click to open)", false);
        t.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2 || t.getSelectedRow() < 0) return;
                    SpecRef ref = rows.get(t.convertRowIndexToModel(t.getSelectedRow())).ref();
                    selectMessage(ref.file(), ref.msg());
                }
            }
        );
        d.add(new JScrollPane(t));
        d.setSize(760, 520);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    public void showRules() {
        String html = """
            <html><body style='width:520px;font-family:sans-serif'>
            <p><b>Errors</b> break the spec build or code generation. <b>Warnings</b> break a "should" rule. \
            <b>Info</b> notes style and documentation gaps.</p>
            <p><b>Keys</b>: definition-rs rejects any key it doesn't know, in messages, points, fields and sims. \
            The point default is "default" (not default_value), and scaling uses "formatter" (not format).</p>
            <p><b>Messages</b>: id is a unique lowercase-0x hex string; IDs above 0x7FF need is_ext; \
            desc uses only letters, whitespace and _; total bits are a multiple of 8 and at most 64; \
            bidir_mode is broadcast or oneshot; clients are supported ports (1882).</p>
            <p><b>Points</b>: parsed points are 1–32 bits; padding points (parse:false) only contain size; \
            signed integers are 8/16/32 bits and byte aligned; IEEE754 floats are 32 bits; \
            points of 8 bits or fewer have no endianness; little-endian points are byte aligned and make the \
            whole message byte aligned; c_type is wide enough; byte-sized values don't start mid-byte; \
            sim is a sweep or an enum whose frequencies add up to 1; sim and default values fit after the formatter.</p>
            <p><b>Fields</b>: values and {n} topic placeholders reference existing parsed points (indexed from 1); \
            every field has unit and doc; topics have no trailing slash, whitespace or wildcards, and 3+ levels \
            are preferred; encodable topics live under Calypso/Bidir/State/{key}/; topics are unique across all files.</p>
            <p><b>firmware-rs cangen</b> also needs unique struct names (from desc) and point accessor names \
            (snake_case of name), no Rust keywords, divide/multiply formatters with a positive whole-number arg, \
            and no formatter, float or IEEE754 on little-endian points. Unnamed points become padding there.</p>
            <p>From the command line: <tt>java -jar Tiresias.jar --validate &lt;folder&gt; [--strict] [--info]</tt></p>
            </body></html>
            """;
        JOptionPane.showMessageDialog(this, new JLabel(html), "Spec rules checked", JOptionPane.INFORMATION_MESSAGE);
    }
}
