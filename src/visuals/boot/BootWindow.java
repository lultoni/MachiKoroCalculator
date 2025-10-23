package visuals.boot;

import logic.Main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BootWindow extends JFrame {

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4; // All 4 fields will be shown

    private JTextField[] playerFields = new JTextField[MAX_PLAYERS];
    private JLabel[] numberLabels = new JLabel[MAX_PLAYERS];
    private JButton startButton;

    public BootWindow() {
        init();
        boot();
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

        // Reworked subtitle to reflect that up to 4 players can be added
        JLabel subTitleLabel = new JLabel("Add player names (2 to 4 players) to start the game:");
        subTitleLabel.setFont(new Font("Arial", Font.ITALIC, 15));

        add(titleLabel, getGBC(0, 0, 2, 1));
        add(subTitleLabel, getGBC(0, 1, 2, 1));

        // Create numbered labels + fields
        for (int i = 0; i < MAX_PLAYERS; i++) {
            JLabel numLabel = new JLabel((i + 1) + ".");
            numLabel.setFont(new Font("Arial", Font.BOLD, 18));
            // All labels are visible now
            numberLabels[i] = numLabel;
            add(numLabel, getGBC(0, 2 + i, 1, 1));

            JTextField field = getTextField(i);
            // All fields are visible now
            playerFields[i] = field;
            add(field, getGBC(1, 2 + i, 1, 1));
        }

        // Start button
        startButton = new JButton("Start Game");
        startButton.setEnabled(false);
        startButton.addActionListener(e -> startGame());
        add(startButton, getGBC(0, 2 + MAX_PLAYERS, 2, 1));

        setVisible(true);

        SwingUtilities.invokeLater(() -> {
            if (playerFields[0] != null) {
                playerFields[0].requestFocusInWindow();
            }
            updateButtonState(); // run initial button logic
        });
    }

    // --- Reworked getTextField(int i) ---
    private JTextField getTextField(int i) {
        JTextField field = new JTextField(20);
        // Field is always visible

        // Tooltip is kept but placeholder text and gray color are removed
        field.setToolTipText("Player " + (i + 1) + "'s name (Optional, requires at least 2 players in total)");
        field.setForeground(Color.BLACK); // Set text color to black by default

        // Live updates when the document changes to manage button state
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateButtonState(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateButtonState(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateButtonState(); }
        });

        // Key handling: move focus on ENTER
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        moveToPreviousField(i);
                    } else {
                        moveToNextField(i);
                    }
                }
            }
        });

        return field;
    }

    // Simplified movement, no visibility changes or mandatory field checks needed
    private void moveToNextField(int index) {
        if (index + 1 < MAX_PLAYERS) {
            playerFields[index + 1].requestFocus();
            playerFields[index + 1].selectAll();
        } else {
            startButton.requestFocus();
        }
    }

    private void moveToPreviousField(int index) {
        if (index > 0) {
            playerFields[index - 1].requestFocus();
            playerFields[index - 1].selectAll();
        }
    }

    /**
     * Updates the Start Game button state based on the number of non-empty player fields.
     */
    private void updateButtonState() {
        int filledCount = 0;
        System.out.println("\nubs call");
        for (JTextField f : playerFields) {
            // Count non-empty fields
            System.out.println(" - " + f.getText());
            if (!f.getText().trim().isEmpty()) {
                filledCount++;
            }
        }

        // The button is enabled when the minimum number of players (2) is reached.
        startButton.setEnabled(filledCount >= MIN_PLAYERS);
        System.out.println("fc >= min_p | " + filledCount + " >= " + MIN_PLAYERS + " | res:" + (filledCount >= MIN_PLAYERS));
        revalidate();
        repaint();
    }

    // Removed isPlaceholder method as there are no placeholders

    private void startGame() {
        // Here you might want to add a check for the player names validity (e.g., uniqueness)
        // before setting boot_finished to true.
        if (getPlayerNames().length >= MIN_PLAYERS) {
            Main.boot_finished = true;
        } else {
            JOptionPane.showMessageDialog(this, "You need at least " + MIN_PLAYERS + " players to start the game!");
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

        for (JTextField field : playerFields) {
            String text = field.getText().trim();
            // Only add non-empty fields
            if (!text.isEmpty()) {
                names.add(text);
            }
        }

        return names.toArray(new String[0]);
    }

}