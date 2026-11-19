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
// Issues panel
// ======================================================================================

public final class IssuesPanel extends JPanel {

    public final App app;
    public List<Issue> all = new ArrayList<>(),
        shown = new ArrayList<>();
    public final JComboBox<String> filter = new JComboBox<>(new String[] {
        "Errors and warnings",
        "Errors only",
        "Everything (with info)",
    });
    public final JCheckBox currentOnly = new JCheckBox("Selected message only");
    public final JLabel title = new JLabel("Problems");
    public final AbstractTableModel model;
    public final JTable table;

    public IssuesPanel(App app) {
        super(new BorderLayout(0, 2));
        this.app = app;
        setBorder(new EmptyBorder(4, 8, 4, 8));
        String[] cols = { "Severity", "File", "ID", "Location", "Problem" };
        model = new AbstractTableModel() {
            public int getRowCount() {
                return shown.size();
            }

            public int getColumnCount() {
                return cols.length;
            }

            public String getColumnName(int c) {
                return cols[c];
            }

            public Object getValueAt(int r, int c) {
                Issue is = shown.get(r);
                return switch (c) {
                    case 0 -> is.sev().label;
                    case 1 -> app.relName(is.file());
                    case 2 -> is.msg() == null ? "" : strOr(is.msg(), "id", "?");
                    case 3 -> is.where();
                    default -> is.text();
                };
            }
        };
        table = new JTable(model);
        styleTable(table);
        table.setDefaultRenderer(Object.class, new StyledRenderer(null));
        table.setAutoCreateRowSorter(true);
        table
            .getColumnModel()
            .getColumn(0)
            .setCellRenderer(
                new StyledRenderer((t, row, col, sel, c) -> {
                    Issue is = shown.get(t.convertRowIndexToModel(row));
                    ((JLabel) c).setIcon(SevIcon.of(is.sev()));
                    if (!sel) c.setForeground(is.sev().color());
                })
            );
        table
            .getColumnModel()
            .getColumn(4)
            .setCellRenderer(
                new StyledRenderer((t, row, col, sel, c) ->
                    c.setToolTipText(
                        "<html><body style='width:420px'>" +
                            escHtml(shown.get(t.convertRowIndexToModel(row)).text()) +
                            "</body></html>"
                    )
                )
            );
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(0).setMaxWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(620);
        table.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && !e.isPopupTrigger()) go();
                }

                // Right-click: the row's fixes (macOS reports the popup trigger on press, Windows on release)
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) fixMenu(e);
                }

                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) fixMenu(e);
                }
            }
        );
        table
            .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "go");
        table.getActionMap().put(
            "go",
            new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    go();
                }
            }
        );

        filter.addActionListener(e -> refilter());
        currentOnly.addActionListener(e -> refilter());
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(4, 4, 4, 0));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        top.add(title, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        currentOnly.setOpaque(false);
        right.add(new MutedLabel("Double-click a row to jump to it"));
        right.add(currentOnly);
        right.add(filter);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void fixMenu(MouseEvent e) {
        app.validateNow(); // so the rows are current before reading the one under the mouse
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0) return;
        table.setRowSelectionInterval(r, r);
        app.showFixMenu(List.of(shown.get(table.convertRowIndexToModel(r))), e);
    }

    public void go() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        Issue is = shown.get(table.convertRowIndexToModel(r));
        // Edits check after a short pause, so a row can briefly point at a message just deleted: check now instead.
        boolean gone =
            !app.files.contains(is.file()) || (is.msg() != null && indexOfIdentity(is.file().messages, is.msg()) < 0);
        if (gone) app.validateNow();
        else app.navigate(is);
    }

    public void setIssues(List<Issue> l) {
        all = l;
        refilter();
    }

    public void refilter() {
        int f = filter.getSelectedIndex();
        Map<String, Object> cur = app.editor == null ? null : app.editor.msg;
        shown = new ArrayList<>();
        int shownErr = 0;
        for (Issue is : all) {
            if (f == 0 && is.sev() == Severity.INFO) continue;
            if (f == 1 && is.sev() != Severity.ERROR) continue;
            if (currentOnly.isSelected() && is.msg() != cur) continue;
            shown.add(is);
            if (is.sev() == Severity.ERROR) shownErr++;
        }
        model.fireTableDataChanged();
        title.setText(
            shown.isEmpty()
                ? "No problems found"
                : "Problems (" + shown.size() + (shownErr > 0 ? ", " + shownErr + " errors" : "") + ")"
        );
    }
}
