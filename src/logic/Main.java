package logic;

import gui.newui.SetupWindow;

import javax.swing.*;

// LEGACY — to be removed in Phase 4 once the probability layer and new UI are complete.
public class Main {

    /** @deprecated LEGACY — referenced only by legacy BootWindow; will be removed in Phase 4. */
    @Deprecated
    public static boolean boot_finished = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SetupWindow::new);
    }

}
