package tiresias.editor;

import static tiresias.spec.Spec.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import tiresias.spec.*;
import tiresias.ui.*;

/**
 * TopicsDialog: Go > Find topic usages and Edit > Rename topic level. Lists every field at or under a topic level
 * across the spec (double-click opens it), and renames that level everywhere, with a preview and a warning when the
 * new topics would clash with existing ones. A rename is one undo step per file.
 */
public final class TopicsDialog extends JDialog {

    public final App app;
    public final HintField from = new HintField("Topic level, such as VCU/Status", 30);
    public final HintField to = new HintField("Rename to (leave empty to only list usages)", 30);
    public final DefaultListModel<FieldRef> model = new DefaultListModel<>();
    public final JList<FieldRef> list = new JList<>(model);
    public final JLabel summary = new JLabel(" ");
    public final JButton rename = new JButton("Rename");

    public TopicsDialog(App app) {
        super(app, "Topic usages", false);
        this.app = app;
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 0, 2, 8);
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("Topic level"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(from, c);
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Rename to"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(to, c);
        root.add(form, BorderLayout.NORTH);
        for (JTextField tf : new JTextField[] { from, to }) {
            tf.setFont(mono(Font.PLAIN, tf.getFont().getSize()));
            Suggest.attach(tf, app::topicSuggestions);
            tf.getDocument().addDocumentListener(GoToDialog.docListener(this::refresh));
        }

        list.setCellRenderer(
            new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                    super.getListCellRendererComponent(l, "", i, sel, false);
                    FieldRef r = (FieldRef) v;
                    String tp = Topics.topic(r),
                        muted = hx(T.muted());
                    String shown = escHtml(tp);
                    if (!Topics.level(to.getText()).isEmpty()) shown =
                        "<font color='" +
                        muted +
                        "'>" +
                        escHtml(tp) +
                        " →</font> " +
                        escHtml(Topics.renamed(tp, from.getText(), to.getText()));
                    setText(
                        "<html><tt>" +
                            shown +
                            "</tt>&nbsp;&nbsp;<font color='" +
                            muted +
                            "'>" +
                            escHtml(strOr(r.msg(), "id", "?")) +
                            " " +
                            escHtml(strOr(r.msg(), "desc", "")) +
                            " · " +
                            escHtml(app.relName(r.file())) +
                            "</font></html>"
                    );
                    setBorder(new EmptyBorder(2, 8, 2, 8));
                    return this;
                }
            }
        );
        list.addMouseListener(
            new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) app.openField(
                        list.getSelectedValue()
                    );
                }
            }
        );
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(760, 380));
        root.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(summary, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        btns.add(close);
        rename.addActionListener(e -> doRename());
        btns.add(rename);
        bottom.add(btns, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(
            e -> setVisible(false),
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        setContentPane(root);
        pack();
    }

    /** Opens the dialog on a level (null keeps the last one), with the cursor in the rename box if asked. */
    public void open(String level, boolean renaming) {
        if (level != null) {
            from.setText(level);
            to.setText(renaming ? level : "");
        }
        refresh();
        if (!isVisible()) setLocationRelativeTo(app);
        setVisible(true);
        toFront();
        JTextField focus = renaming ? to : from;
        focus.requestFocusInWindow();
        focus.selectAll();
    }

    public void refresh() {
        model.clear();
        String lv = Topics.level(from.getText()),
            nw = Topics.level(to.getText());
        rename.setEnabled(false);
        if (lv.isEmpty()) {
            summary.setText("Type a topic level to list the fields that publish under it.");
            return;
        }
        List<FieldRef> found = Topics.usages(app.files, lv);
        for (FieldRef r : found) model.addElement(r);
        Set<SpecFile> inFiles = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FieldRef r : found) inFiles.add(r.file());
        String where = found.size() + (found.size() == 1 ? " topic" : " topics") + " in " + inFiles.size() + " file(s)";
        if (nw.isEmpty() || nw.equals(lv)) {
            summary.setText(found.isEmpty() ? "No topics are at or under " + lv : where + " at or under " + lv);
            return;
        }
        if (nw.contains("//") || nw.matches(".*\\s.*")) {
            summary.setText(
                "<html><font color='" + hx(T.red()) + "'>The new level can't have spaces or empty levels</font></html>"
            );
            return;
        }
        Set<String> clash = Topics.collisions(app.files, lv, nw);
        rename.setEnabled(!found.isEmpty());
        summary.setText(
            "<html>Renames " +
                where +
                (clash.isEmpty()
                    ? ""
                    : "<br><font color='" +
                          hx(T.yellow()) +
                          "'>⚠ " +
                          clash.size() +
                          " would match topics other fields already publish, such as " +
                          escHtml(clash.iterator().next()) +
                          "</font>") +
                "</html>"
        );
    }

    public void doRename() {
        String lv = Topics.level(from.getText()),
            nw = Topics.level(to.getText());
        if (!app.renameTopicLevel(lv, nw)) return;
        from.setText(nw);
        to.setText("");
        refresh();
    }
}
