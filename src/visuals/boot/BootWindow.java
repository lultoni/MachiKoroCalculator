package visuals.boot;

import logic.Main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BootWindow extends JFrame {

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4;

    // These arrays are still useful for accessing fields later (like in getPlayerNames)
    private JTextField[] playerFields = new JTextField[MAX_PLAYERS];
    private JLabel[] numberLabels = new JLabel[MAX_PLAYERS];
    private JButton startButton;

    public BootWindow() {
        init();
    }

    private void init() {
        setTitle("Machi Koro Calculator - Boot Settings");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(700, 500);
        setLocationRelativeTo(null);
        setLayout(new GridBagLayout());
    }

    public void boot() {
        JLabel titleLabel = new JLabel("Machi Koro Calculator");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 30));

        JLabel subTitleLabel = new JLabel("Add player names (2 to 4 players) to start the game:");
        subTitleLabel.setFont(new Font("Arial", Font.ITALIC, 15));

        add(titleLabel, getGBC(0, 0, 2, 1));
        add(subTitleLabel, getGBC(0, 1, 2, 1));

        // --- Hardcoded creation of 4 numbered labels and text fields ---

        // Player 1
        int i = 0;
        JLabel numLabel1 = new JLabel((i + 1) + ".");
        numLabel1.setFont(new Font("Arial", Font.BOLD, 18));
        numberLabels[i] = numLabel1;
        add(numLabel1, getGBC(0, 2 + i, 1, 1));
        JTextField field1 = new JTextField(20);
        field1.setForeground(Color.BLACK);
        field1.setToolTipText("Player 1's name");
        playerFields[i] = field1;
        add(field1, getGBC(1, 2 + i, 1, 1));

        // Player 2
        i = 1;
        JLabel numLabel2 = new JLabel((i + 1) + ".");
        numLabel2.setFont(new Font("Arial", Font.BOLD, 18));
        numberLabels[i] = numLabel2;
        add(numLabel2, getGBC(0, 2 + i, 1, 1));
        JTextField field2 = new JTextField(20);
        field2.setForeground(Color.BLACK);
        field2.setToolTipText("Player 2's name");
        playerFields[i] = field2;
        add(field2, getGBC(1, 2 + i, 1, 1));

        // Player 3
        i = 2;
        JLabel numLabel3 = new JLabel((i + 1) + ".");
        numLabel3.setFont(new Font("Arial", Font.BOLD, 18));
        numberLabels[i] = numLabel3;
        add(numLabel3, getGBC(0, 2 + i, 1, 1));
        JTextField field3 = new JTextField(20);
        field3.setForeground(Color.BLACK);
        field3.setToolTipText("Player 3's name (Optional)");
        playerFields[i] = field3;
        add(field3, getGBC(1, 2 + i, 1, 1));

        // Player 4
        i = 3;
        JLabel numLabel4 = new JLabel((i + 1) + ".");
        numLabel4.setFont(new Font("Arial", Font.BOLD, 18));
        numberLabels[i] = numLabel4;
        add(numLabel4, getGBC(0, 2 + i, 1, 1));
        JTextField field4 = new JTextField(20);
        field4.setForeground(Color.BLACK);
        field4.setToolTipText("Player 4's name (Optional)");
        playerFields[i] = field4;
        add(field4, getGBC(1, 2 + i, 1, 1));

        // Start button
        startButton = new JButton("Start Game");
        // Start button is always enabled
        startButton.setEnabled(true);
        startButton.addActionListener(e -> startGame());
        add(startButton, getGBC(0, 2 + MAX_PLAYERS, 2, 1));

        setVisible(true);

        SwingUtilities.invokeLater(() -> {
            if (playerFields[0] != null) {
                playerFields[0].requestFocusInWindow();
            }
        });
    }

    // --- getTextField method is removed as its logic is hardcoded in boot() ---

    // --- updateButtonState method is removed as the button is always active ---

    private void startGame() {
        String[] names = getPlayerNames();

        // Validation now happens on click
        if (names.length >= MIN_PLAYERS) {
            Main.boot_finished = true;
        } else {
            // Display message since the button is always clickable
            JOptionPane.showMessageDialog(this,
                    "You need at least " + MIN_PLAYERS + " players to start the game! Please fill in at least two player names.",
                    "Cannot Start Game",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private GridBagConstraints getGBC(int gridx, int gridy, int gridwidth, int gridheight) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = gridx;
        gbc.gridy = gridy;
        gbc.gridwidth = gridwidth;
        gbc.gridheight = gridheight;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(10, 10, 10, 10);
        return gbc;
    }

    public String[] getPlayerNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();

        System.out.println("Names:");
        for (JTextField field : playerFields) {
            System.out.println(" - " + field.getText());
            String text = field.getText().trim();
            // Only add non-empty fields
            if (!text.isEmpty()) {
                names.add(text);
            }
        }

        return names.toArray(new String[0]);
    }

}