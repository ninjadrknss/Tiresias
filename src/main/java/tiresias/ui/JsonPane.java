package tiresias.ui;

import static tiresias.ui.Ui.*;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/**
 * JsonPane: a JSON text editor with syntax colors from the theme: keys blue, strings green, numbers orange, true,
 * false and null purple, punctuation muted. Lines don't wrap. Coloring reruns shortly after typing stops.
 * {@link #history} undoes typing as plain text edits (offset, removed, inserted), never Swing's document edits:
 * those record the styled runs, which recoloring changes underneath them, so undoing them could throw or restore the
 * wrong text.
 */
public final class JsonPane extends JTextPane {

    public final Timer recolor = new Timer(150, e -> highlight());
    public final javax.swing.undo.UndoManager history = new javax.swing.undo.UndoManager();
    /** True while setText, undo or redo change the text, which aren't recorded as typing. */
    public boolean quiet;

    /** One text change, undone and redone by replacing text. */
    public final class TextEdit extends javax.swing.undo.AbstractUndoableEdit {

        public final int at;
        public final String removed, inserted;

        public TextEdit(int at, String removed, String inserted) {
            this.at = at;
            this.removed = removed;
            this.inserted = inserted;
        }

        public void undo() {
            super.undo();
            swap(inserted.length(), removed);
        }

        public void redo() {
            super.redo();
            swap(removed.length(), inserted);
        }

        public void swap(int len, String text) {
            quiet = true;
            try {
                ((AbstractDocument) getDocument()).replace(at, len, text, null);
                setCaretPosition(Math.min(getDocument().getLength(), at + text.length()));
            } catch (BadLocationException e) {
                throw new javax.swing.undo.CannotUndoException();
            } finally {
                quiet = false;
            }
        }
    }

    public JsonPane() {
        recolor.setRepeats(false);
        ((AbstractDocument) getDocument()).setDocumentFilter(
            new DocumentFilter() {
                public void insertString(FilterBypass fb, int at, String s, AttributeSet a)
                    throws BadLocationException {
                    record(at, "", s);
                    fb.insertString(at, s, a);
                }

                public void remove(FilterBypass fb, int at, int len) throws BadLocationException {
                    record(at, fb.getDocument().getText(at, len), "");
                    fb.remove(at, len);
                }

                public void replace(FilterBypass fb, int at, int len, String s, AttributeSet a)
                    throws BadLocationException {
                    record(at, fb.getDocument().getText(at, len), s == null ? "" : s);
                    fb.replace(at, len, s, a);
                }
            }
        );
        getDocument().addDocumentListener(
            new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    recolor.restart();
                }

                public void removeUpdate(DocumentEvent e) {
                    recolor.restart();
                }

                public void changedUpdate(DocumentEvent e) {}
            }
        );
    }

    /** No wrapping: wider than the view means a horizontal scroll bar. */
    public boolean getScrollableTracksViewportWidth() {
        Container p = getParent();
        return p == null || getUI().getPreferredSize(this).width <= p.getWidth();
    }

    public void record(int at, String removed, String inserted) {
        if (!quiet && !(removed.isEmpty() && inserted.isEmpty())) history.addEdit(new TextEdit(at, removed, inserted));
    }

    /** Sets the text and colors it at once (no wait), starting a fresh undo history. */
    public void setText(String t) {
        quiet = true;
        try {
            super.setText(t);
        } finally {
            quiet = false;
        }
        history.discardAllEdits();
        recolor.stop();
        highlight();
    }

    public void highlight() {
        StyledDocument doc = getStyledDocument();
        String s;
        try {
            s = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return;
        }
        setForeground(T.fg());
        setCaretColor(T.bright());
        doc.setCharacterAttributes(0, s.length(), style(T.fg()), true);
        AttributeSet key = style(T.blue()),
            str = style(T.green()),
            num = style(T.orange()),
            lit = style(T.purple()),
            punct = style(T.muted());
        int i = 0,
            n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '"' && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(n, j + 1);
                int k = j;
                while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
                doc.setCharacterAttributes(i, j - i, k < n && s.charAt(k) == ':' ? key : str, true);
                i = j;
            } else if (c == '-' || Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && "0123456789.eE+-".indexOf(s.charAt(j)) >= 0) j++;
                doc.setCharacterAttributes(i, j - i, num, true);
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < n && Character.isLetter(s.charAt(j))) j++;
                String w = s.substring(i, j);
                if (w.equals("true") || w.equals("false") || w.equals("null")) doc.setCharacterAttributes(
                    i,
                    j - i,
                    lit,
                    true
                );
                i = j;
            } else {
                if ("{}[]:,".indexOf(c) >= 0) doc.setCharacterAttributes(i, 1, punct, true);
                i++;
            }
        }
    }

    public AttributeSet style(Color c) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setFontFamily(a, getFont().getFamily());
        StyleConstants.setFontSize(a, getFont().getSize());
        return a;
    }
}
