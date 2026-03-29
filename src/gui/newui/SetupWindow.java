package gui.newui;

import logic.probability.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * New-game setup window.
 *
 * <p>Lets the user choose the language (DE/EN), player count (2–4) and player names,
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
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        buildUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void buildUI() {
        setTitle(Strings.setupWindowTitle());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // Title
        JLabel title = new JLabel(Strings.setupHeading());
        title.setFont(TITLE_FONT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        root.add(title, BorderLayout.NORTH);

        // Center: language toggle + player count + name fields
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        // Language toggle row
        JPanel langRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        ButtonGroup langGroup = new ButtonGroup();
        JRadioButton deBtn = new JRadioButton("Deutsch");
        JRadioButton enBtn = new JRadioButton("English");
        deBtn.setFont(LABEL_FONT);
        enBtn.setFont(LABEL_FONT);
        langGroup.add(deBtn);
        langGroup.add(enBtn);
        deBtn.setSelected(Strings.isDE());
        enBtn.setSelected(!Strings.isDE());
        deBtn.addActionListener(e -> { Strings.setLocale(Strings.Locale.DE); rebuildUI(); });
        enBtn.addActionListener(e -> { Strings.setLocale(Strings.Locale.EN); rebuildUI(); });
        langRow.add(deBtn);
        langRow.add(enBtn);
        center.add(langRow);
        center.add(Box.createVerticalStrut(6));

        // Player count row
        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        countRow.add(labelOf(Strings.setupNumPlayers()));
        SpinnerNumberModel snm = new SpinnerNumberModel(
                playerCountSpinner != null ? (int) playerCountSpinner.getValue() : 2, 2, 4, 1);
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
            row.add(labelOf(Strings.setupPlayerName(i + 1)));
            if (nameFields[i] == null) {
                nameFields[i] = new JTextField(18);
                nameFields[i].setText(Strings.setupDefaultName(i + 1));
            }
            row.add(nameFields[i]);
            nameFieldPanel.add(row);
        }
        center.add(nameFieldPanel);

        root.add(center, BorderLayout.CENTER);

        // Start button
        JButton startBtn = new JButton(Strings.setupStartBtn());
        startBtn.setFont(new Font("Arial", Font.BOLD, 14));
        startBtn.addActionListener(this::onStart);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnRow.add(startBtn);
        root.add(btnRow, BorderLayout.SOUTH);

        setContentPane(root);
        refreshNameFields();
    }

    /** Rebuilds the UI after a locale change (keeps spinner value and name field contents). */
    private void rebuildUI() {
        buildUI();
        revalidate();
        pack();
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
            names[i] = name.isEmpty() ? Strings.setupDefaultName(i + 1) : name;
        }
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
