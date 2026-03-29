package gui.newui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal strip of 6 {@link DiceFacePanel}s (values 1–6) for selecting a die roll value.
 *
 * <p>Exactly one face can be selected at a time (single-selection). If {@code optional=true},
 * clicking the already-selected face deselects it (no selection = die not used, e.g. second die
 * when Bahnhof enables 2d6 but the user wants to enter 1d6 anyway). In non-optional mode the
 * first panel is pre-selected and cannot be deselected.
 *
 * <p>Registered {@link ChangeListener}s are notified whenever the selection changes.
 */
public class DiceSelectorPanel extends JPanel {

    private static final int DIE_SIZE = 36;

    private final DiceFacePanel[] faces = new DiceFacePanel[6];
    private final boolean optional;   // true → can deselect all (second die strip)
    private int selectedValue;        // 1–6, or -1 if deselected (optional mode only)

    private final List<ChangeListener> listeners = new ArrayList<>();

    /**
     * @param optional if {@code true}, clicking the active face deselects the strip entirely
     *                 (returns -1 from {@link #getValue()}). Use for the second-die strip.
     */
    public DiceSelectorPanel(boolean optional) {
        this.optional = optional;
        this.selectedValue = optional ? -1 : 1;  // start deselected if optional, else value 1

        setLayout(new FlowLayout(FlowLayout.LEFT, 3, 2));
        setOpaque(false);

        for (int v = 1; v <= 6; v++) {
            final int face = v;
            DiceFacePanel dfp = new DiceFacePanel(v, DIE_SIZE, true);
            dfp.setSelected(v == selectedValue);
            dfp.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onDieClicked(face);
                }
            });
            faces[v - 1] = dfp;
            add(dfp);
        }
    }

    /** Returns the currently selected die value (1–6), or -1 if deselected (optional strip). */
    public int getValue() {
        return selectedValue;
    }

    /** Programmatically selects the given value (1–6). Pass -1 to deselect (optional strip only). */
    public void setValue(int v) {
        if (v < -1 || v == 0 || v > 6) return;
        if (v == -1 && !optional) return;
        selectedValue = v;
        for (int i = 0; i < 6; i++) {
            faces[i].setSelected((i + 1) == v);
        }
        fireChange();
    }

    public void addChangeListener(ChangeListener l) { listeners.add(l); }
    public void removeChangeListener(ChangeListener l) { listeners.remove(l); }

    private void onDieClicked(int face) {
        if (face == selectedValue && optional) {
            // Deselect (toggle off)
            setValue(-1);
        } else {
            setValue(face);
        }
    }

    private void fireChange() {
        ChangeEvent e = new ChangeEvent(this);
        for (ChangeListener l : listeners) l.stateChanged(e);
        repaint();
    }
}
