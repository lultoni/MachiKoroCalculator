package visuals.boot;

import logic.Main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BootWindow extends JFrame {

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4;

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

        JLabel subTitleLabel = new JLabel("Add player names to start the game:");
        subTitleLabel.setFont(new Font("Arial", Font.ITALIC, 15));

        add(titleLabel, getGBC(0, 0, 2, 1));
        add(subTitleLabel, getGBC(0, 1, 2, 1));

        // Create numbered labels + fields
        for (int i = 0; i < MAX_PLAYERS; i++) {
            JLabel numLabel = new JLabel((i + 1) + ".");
            numLabel.setFont(new Font("Arial", Font.BOLD, 18));
            numLabel.setVisible(i == 0);
            numberLabels[i] = numLabel;
            add(numLabel, getGBC(0, 2 + i, 1, 1));

            JTextField field = getTextField(i);

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
            // Fordert den Fokus für das erste Feld an.
            // Der FocusListener (in getTextField) kümmert sich um das Löschen des Textes.
            if (playerFields[0] != null) {
                playerFields[0].requestFocusInWindow();
                // Die redundante Logik wurde entfernt.
            }
            updateVisibilityAndButton(); // run initial visibility logic
        });
    }

    // --- replace your getTextField(int i) with this ---
    private JTextField getTextField(int i) {
        JTextField field = new JTextField(20);
        field.setVisible(i == 0);
        String placeholder = "Please type in Player " + (i + 1) + "'s name...";
        field.setToolTipText(placeholder);
        field.setText(placeholder);
        field.setForeground(Color.GRAY);

        int index = i;

        // Focus handling: clear placeholder when focus gained
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // Wir verwenden die Original-Logik: Text löschen bei Fokus.
                if (isPlaceholder(field)) {
                    field.setText("");
                    field.setForeground(Color.BLACK);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().trim().isEmpty()) {
                    field.setText(placeholder);
                    field.setForeground(Color.GRAY);
                }
            }
        });

        // Live updates when the document changes
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateVisibilityAndButton(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateVisibilityAndButton(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateVisibilityAndButton(); }
        });

        // Key handling:
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Die Logik zum Löschen des Platzhalters wurde entfernt,
                // da focusGained() dies bereits erledigt hat.

                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        moveToPreviousField(index);
                    } else {
                        moveToNextField(index);
                    }
                }
            }
        });

        return field;
    }

    private void moveToNextField(int index) {
        JTextField current = playerFields[index];
        if (isPlaceholder(current) || current.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter Player " + (index + 1) + " name first!");
            current.requestFocus();
            return;
        }

        if (index + 1 < MAX_PLAYERS) {
            playerFields[index + 1].setVisible(true);
            numberLabels[index + 1].setVisible(true);
            revalidate();
            repaint();
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

    private void updateVisibilityAndButton() {
        // 1. Sichtbarkeit festlegen. Ein Feld ist nur sichtbar, wenn das *vorherige* gefüllt ist.
        // P1 ist immer sichtbar.
        for (int i = 1; i < MAX_PLAYERS; i++) {
            JTextField prev = playerFields[i-1];

            // Wenn das vorherige Feld gültig ist, zeige dieses Feld an
            if (!isPlaceholder(prev) && !prev.getText().trim().isEmpty()) {
                playerFields[i].setVisible(true);
                numberLabels[i].setVisible(true);
            } else {
                // Wenn das vorherige Feld leer ist, verstecke dieses und alle folgenden Felder
                for (int j = i; j < MAX_PLAYERS; j++) {
                    playerFields[j].setVisible(false);
                    numberLabels[j].setVisible(false);
                }
                break; // Die Schleife für die Sichtbarkeit kann beendet werden
            }
        }

        // 2. Zähle die gefüllten Felder UND prüfe, ob alle *sichtbaren* Felder gefüllt sind.
        int filledCount = 0;
        boolean allVisibleFilled = true;
        for (int i = 0; i < MAX_PLAYERS; i++) {
            JTextField f = playerFields[i];
            if (f.isVisible()) {
                if (!isPlaceholder(f) && !f.getText().trim().isEmpty()) {
                    filledCount++;
                } else {
                    // Dieses Feld ist sichtbar, aber leer (oder Platzhalter)
                    allVisibleFilled = false;
                }
            }
        }

        // 3. Button-Status setzen.
        // Der Button ist aktiv, wenn die Mindestspielerzahl erreicht ist
        // UND alle Felder, die wir sehen, auch ausgefüllt sind.
        startButton.setEnabled(filledCount >= MIN_PLAYERS && allVisibleFilled);
        revalidate();
        repaint();
    }

    private boolean isPlaceholder(JTextField field) {
        return field.getForeground().equals(Color.GRAY);
    }

    private void startGame() {
        Main.boot_finished = true;
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
            // Skip placeholder text or empty fields
            if (!text.isEmpty() && !isPlaceholder(field)) {
                names.add(text);
            }
        }

        return names.toArray(new String[0]);
    }

}
