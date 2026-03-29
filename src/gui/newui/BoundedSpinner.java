package gui.newui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.*;
import java.beans.PropertyChangeListener;

/**
 * A {@link JSpinner} that automatically disables its increment/decrement arrow buttons
 * when the current value is at the model's minimum or maximum boundary.
 *
 * <p>This provides clear visual feedback to the user that no further adjustment is possible
 * in that direction, preventing repeated clicks on an already-bounded spinner.
 */
public class BoundedSpinner extends JSpinner {

    /**
     * Constructs a BoundedSpinner with the given model.
     *
     * @param model the spinner model (typically a {@link SpinnerNumberModel})
     */
    public BoundedSpinner(SpinnerModel model) {
        super(model);
        addChangeListener(e -> updateButtonStates());
        // Also react when the model's min/max changes (e.g. roll spinner range switch)
        PropertyChangeListener pcl = e -> SwingUtilities.invokeLater(this::updateButtonStates);
        model.addChangeListener(e2 -> updateButtonStates());
        // Defer initial update until the UI is fully installed
        SwingUtilities.invokeLater(this::updateButtonStates);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        SwingUtilities.invokeLater(this::updateButtonStates);
    }

    /**
     * Updates the enabled state of the increment and decrement buttons based on
     * whether the current value equals the model's min or max.
     */
    private void updateButtonStates() {
        if (!(getModel() instanceof SpinnerNumberModel m)) return;
        Object value = m.getValue();
        Object min   = m.getMinimum();
        Object max   = m.getMaximum();

        boolean atMin = compareComparable(value, min) <= 0;
        boolean atMax = compareComparable(value, max) >= 0;

        // The spinner's editor is the center component; find the two arrow buttons
        for (Component c : getComponents()) {
            if (c instanceof JButton btn) {
                // Heuristic: the decrement button's action command is typically "decrement"
                // or it is positioned before the editor. We check the button's preferred
                // location relative to the editor instead by using the component order.
                // A more robust approach: check which button fires next/prev.
                // Swing's BasicSpinnerUI adds "Next" and "Previous" buttons.
                Object action = btn.getActionCommand();
                if ("increment".equals(action)) {
                    btn.setEnabled(!atMax);
                } else if ("decrement".equals(action)) {
                    btn.setEnabled(!atMin);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareComparable(Object a, Object b) {
        if (a instanceof Comparable ca && b != null) {
            return ca.compareTo(b);
        }
        return 0;
    }
}
