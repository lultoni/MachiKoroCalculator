package gui.newui;

import logic.probability.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;

/**
 * New-game setup window.
 *
 * <p>Lets the user choose player count (2–4) and enter player names,
 * then launches {@link MainWindow}.
 */
public class SetupWindow extends JFrame {

    private static final Font TITLE_FONT = new Font("Arial", Font.BOLD, 26);
    private static final Font LABEL_FONT = new Font("Arial", Font.PLAIN, 14);

    private JSpinner playerCountSpinner;
    private JPanel nameFieldPanel;
    private final JTextField[] nameFields = new JTextField[4];

    /** Constructs and displays the setup window on the EDT. */
    public SetupWindow() {
        setTitle("Machi Koro Calculator — New Game");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        buildUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // Title
        JLabel title = new JLabel("Machi Koro Calculator");
        title.setFont(TITLE_FONT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        root.add(title, BorderLayout.NORTH);

        // Center: player count + name fields
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        // Player count row
        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        countRow.add(labelOf("Number of players:"));
        SpinnerNumberModel snm = new SpinnerNumberModel(2, 2, 4, 1);
        playerCountSpinner = new JSpinner(snm);
        playerCountSpinner.setPreferredSize(new Dimension(55, 28));
        playerCountSpinner.addChangeListener(e -> refreshNameFields());
        countRow.add(playerCountSpinner);
        center.add(countRow);
        center.add(Box.createVerticalStrut(8));

        // Name fields (dynamically shown based on count)
        nameFieldPanel = new JPanel();
        nameFieldPanel.setLayout(new BoxLayout(nameFieldPanel, BoxLayout.Y_AXIS));
        for (int i = 0; i < 4; i++) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            row.add(labelOf("Player " + (i + 1) + " name:"));
            nameFields[i] = new JTextField(18);
            nameFields[i].setText("Player " + (i + 1));
            row.add(nameFields[i]);
            nameFieldPanel.add(row);
        }
        center.add(nameFieldPanel);

        root.add(center, BorderLayout.CENTER);

        // Start button
        JButton startBtn = new JButton("Start Game");
        startBtn.setFont(new Font("Arial", Font.BOLD, 14));
        startBtn.addActionListener(this::onStart);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnRow.add(startBtn);
        root.add(btnRow, BorderLayout.SOUTH);

        setContentPane(root);
        refreshNameFields();
    }

    private void refreshNameFields() {
        int count = (int) playerCountSpinner.getValue();
        for (int i = 0; i < 4; i++) {
            nameFieldPanel.getComponent(i).setVisible(i < count);
        }
        pack();
    }

    private void onStart(ActionEvent e) {
        int count = (int) playerCountSpinner.getValue();
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            String name = nameFields[i].getText().trim();
            names[i] = name.isEmpty() ? "Player " + (i + 1) : name;
        }
        // Build initial GameSession
        GameStateBuilder builder = new GameStateBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.setPlayerName(i, names[i])
                   .setCoins(i, 3)
                   .addProject(i, "weizenfeld")
                   .addProject(i, "bäckerei");
        }
        GameSession session = new GameSession(builder.build(), names);

        dispose();
        new MainWindow(session).setVisible(true);
    }

    private static JLabel labelOf(String text) {
        JLabel l = new JLabel(text);
        l.setFont(LABEL_FONT);
        return l;
    }
}
