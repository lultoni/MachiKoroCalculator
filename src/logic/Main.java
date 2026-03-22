package logic;

import gui.newui.SetupWindow;

import javax.swing.*;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SetupWindow::new);
    }

}
