package tiresias.editor;

import static tiresias.spec.Spec.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import tiresias.spec.*;

/**
 * ReviewDialog: what saving would change, file by file, as a line diff of the file on disk against what Tiresias
 * would write. It warns when a file changed on disk after it was opened (git, another editor), since saving would
 * overwrite that. Shown before saving (pref reviewBeforeSave) and from File > Review unsaved changes.
 */
public final class ReviewDialog extends JDialog {

    /** One file's change: the text on disk now, what saving writes, and whether disk moved since it was opened. */
    public record Change(SpecFile file, String disk, String ours, boolean changedOnDisk, List<Diff.Line> lines) {}

    public static final int CONTEXT = 3;

    public final App app;
    public final List<Change> changes;
    public final DefaultListModel<Change> fileModel = new DefaultListModel<>();
    public final JList<Change> fileList = new JList<>(fileModel);
    public final JTextPane text = new JTextPane();
    public final JLabel warning = new JLabel();
    public final JCheckBox always = new JCheckBox("Show this before every save");
    public boolean confirmed;

    public ReviewDialog(App app, List<SpecFile> files, boolean forSave) {
        super(app, forSave ? "Review changes before saving" : "Unsaved changes", true);
        this.app = app;
        this.changes = changesOf(app, files);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        for (Change c : changes) fileModel.addElement(c);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(
            new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                    super.getListCellRendererComponent(l, "", i, sel, false);
                    Change c = (Change) v;
                    int[] n = Diff.counts(c.lines());
                    setText(
                        "<html>" +
                            (c.changedOnDisk() ? "<font color='" + hx(T.yellow()) + "'>⚠ </font>" : "") +
                            escHtml(app.relName(c.file())) +
                            "&nbsp;&nbsp;<font color='" +
                            hx(T.green()) +
                            "'>+" +
                            n[0] +
                            "</font> <font color='" +
                            hx(T.red()) +
                            "'>−" +
                            n[1] +
                            "</font></html>"
                    );
                    setBorder(new EmptyBorder(3, 8, 3, 8));
                    return this;
                }
            }
        );
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) show(fileList.getSelectedValue());
        });
        JScrollPane files_ = new JScrollPane(fileList);
        files_.setPreferredSize(new Dimension(230, 400));

        text.setEditable(false);
        text.setFont(mono(Font.PLAIN, 12));
        text.setBackground(T.field());
        JScrollPane diff = new JScrollPane(text);
        diff.setPreferredSize(new Dimension(760, 520));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, files_, diff);
        split.setBorder(null);
        split.setResizeWeight(0.2);
        root.add(split, BorderLayout.CENTER);

        warning.setForeground(T.yellow());
        root.add(warning, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        always.setOpaque(false);
        always.setSelected(app.prefs.getBoolean("reviewBeforeSave", true));
        always.addActionListener(e -> app.prefs.putBoolean("reviewBeforeSave", always.isSelected()));
        bottom.add(always, BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton(forSave ? "Cancel" : "Close");
        cancel.addActionListener(e -> dispose());
        btns.add(cancel);
        JButton save = new JButton(changes.size() == 1 ? "Save" : "Save " + changes.size() + " files");
        save.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        btns.add(save);
        bottom.add(btns, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(
            e -> dispose(),
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        setContentPane(root);
        pack();
        setLocationRelativeTo(app);
        if (!fileModel.isEmpty()) fileList.setSelectedIndex(0);
        long moved = changes.stream().filter(Change::changedOnDisk).count();
        if (moved > 0) warning.setText(
            "<html>⚠ " +
                (moved == 1 ? "1 file" : moved + " files") +
                " changed on disk after being opened (by git or another program). Saving overwrites those changes;" +
                " the diff is against the file as it is on disk now. Use File > Reload from disk to keep them instead.</html>"
        );
    }

    /** Each file's diff against disk. A file that can't be read is compared with an empty text. */
    public static List<Change> changesOf(App app, List<SpecFile> files) {
        List<Change> out = new ArrayList<>();
        for (SpecFile f : files) {
            String disk;
            try {
                disk = Files.exists(f.path) ? Files.readString(f.path) : "";
            } catch (IOException e) {
                disk = "";
            }
            String ours = Json.write(f.messages);
            String opened = app.savedText.get(f);
            boolean moved = opened != null && !opened.equals(disk) && !opened.equals(rewritten(disk));
            out.add(new Change(f, disk, ours, moved, Diff.lines(disk, ours)));
        }
        return out;
    }

    /** The disk text as Tiresias would write it (so CRLF or other formatting isn't taken for an outside change). */
    public static String rewritten(String disk) {
        try {
            return Json.write(Json.parse(disk));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What saving changes that the line diff doesn't show: line endings and the newline at the end. */
    public static String endingsNote(String disk, String ours) {
        List<String> n = new ArrayList<>();
        if (disk.contains("\r\n") && !ours.contains("\r\n")) n.add(
            "Saving also changes the line endings from Windows (CRLF) to LF."
        );
        String d = disk.replace("\r\n", "\n");
        if (d.endsWith("\n") != ours.endsWith("\n")) n.add(
            ours.endsWith("\n")
                ? "Saving adds a newline at the end of the file."
                : "Saving removes the newline at the end of the file."
        );
        return String.join(" ", n);
    }

    public void show(Change c) {
        StyledDocument doc = text.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            if (c == null) return;
            List<Diff.Hunk> hunks = Diff.hunks(c.lines(), CONTEXT);
            String note = endingsNote(c.disk(), c.ours());
            if (!note.isEmpty()) doc.insertString(0, note + "\n\n", style(T.yellow(), null));
            if (hunks.isEmpty()) {
                if (note.isEmpty()) doc.insertString(
                    0,
                    "No changes: saving writes the same text that's on disk.\n",
                    style(T.muted(), null)
                );
                return;
            }
            int width = String.valueOf(Math.max(Diff.split(c.disk()).length, Diff.split(c.ours()).length)).length();
            for (Diff.Hunk h : hunks) {
                doc.insertString(doc.getLength(), h.header() + "\n", style(T.cyan(), null));
                for (Diff.Line l : h.lines()) {
                    Color bg = l.kind() == '+' ? T.tint(T.green()) : l.kind() == '-' ? T.tint(T.red()) : null;
                    Color fg = l.kind() == ' ' ? T.muted() : T.bright();
                    String no =
                        pad(l.kind() == '+' ? "" : String.valueOf(l.oldNo()), width) +
                        " " +
                        pad(l.kind() == '-' ? "" : String.valueOf(l.newNo()), width);
                    doc.insertString(doc.getLength(), no + " " + l.kind() + " " + l.text() + "\n", style(fg, bg));
                }
                doc.insertString(doc.getLength(), "\n", style(T.fg(), null));
            }
            text.setCaretPosition(0);
        } catch (BadLocationException ignored) {}
    }

    public static String pad(String s, int w) {
        return " ".repeat(Math.max(0, w - s.length())) + s;
    }

    public static AttributeSet style(Color fg, Color bg) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, fg);
        if (bg != null) StyleConstants.setBackground(a, bg);
        return a;
    }

    /** Shows the review for these files. For a save, returns true if the user chose to save. */
    public static boolean review(App app, List<SpecFile> files, boolean forSave) {
        ReviewDialog d = new ReviewDialog(app, files, forSave);
        d.setVisible(true);
        return d.confirmed;
    }
}
