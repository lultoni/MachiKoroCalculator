package Visuals;

import Logic.Player;
import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class ProjectVisual extends JPanel {

    Project project;
    Window window;
    JLabel amountLabel;
    Player player;

    public ProjectVisual(Project project, Window window, Player player) {
        this.project = project;
        this.window = window;
        this.player = player;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayout(1, 0));
        mainPanel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));

        amountLabel = new JLabel(project.getOwnCount() + "/" + project.getMaxOwnCount(player)); // TODO make percentage bar (blocks with border)

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new GridLayout(0, 1));
        infoPanel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        JLabel nameLabel = new JLabel(project.getName());
        Image projectIcon = new ImageIcon(project.getCategory() + ".png").getImage();
        nameLabel.setIcon(new ImageIcon(projectIcon.getScaledInstance(25, 25, Image.SCALE_SMOOTH)));
        JPanel numberPanel = new JPanel();
        numberPanel.setLayout(new GridLayout(1, 0));
        numberPanel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        JLabel coinLabel = getCoinLabel();
        JLabel diceLabel = getDiceLabel();
        numberPanel.add(coinLabel);
        numberPanel.add(diceLabel);

        infoPanel.add(nameLabel);
        infoPanel.add(numberPanel);

        JPanel buttonPanel = getButtonPanel();

        mainPanel.add(infoPanel);
        mainPanel.add(buttonPanel);

        add(mainPanel);
        add(amountLabel, BorderLayout.SOUTH);
    }

    private JLabel getDiceLabel() {
        return new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                g.drawImage(new ImageIcon("DICE.png").getImage(), 0, 5, getWidth() - 2, getHeight() - 10, this);

                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 15));
                String text = String.valueOf(project.getActivationString());
                int textX = getWidth() / 2 - 5;
                int textY = getHeight() / 2 + 5;
                g.drawString(text, textX, textY);
            }
        };
    }

    private JPanel getButtonPanel() {
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        JButton plusButton = new JButton("+");
        plusButton.setFont(GlobalColors.northFont);
        JButton minusButton = new JButton("-");
        minusButton.setFont(GlobalColors.northFont);
        minusButton.setEnabled(project.getOwnCount() > 0);
        plusButton.addActionListener(e -> {
            if (project.getMaxOwnCount(player) > project.getOwnCount() && player.getCoins() >= project.getCost()) {
                project.setOwnCount(project.getOwnCount() + 1);
                player.setCoins(player.getCoins() - project.getCost());
            }
            minusButton.setEnabled(project.getOwnCount() > 0);
            plusButton.setEnabled(project.getOwnCount() < project.getMaxOwnCount(player));
            window.updatePlayerPanels();
        });
        minusButton.addActionListener(e -> {
            if (0 < project.getOwnCount()) {
                project.setOwnCount(project.getOwnCount() - 1);
                player.setCoins(player.getCoins() + project.getCost());
            }
            minusButton.setEnabled(project.getOwnCount() > 0);
            plusButton.setEnabled(project.getOwnCount() < project.getMaxOwnCount(player));
            window.updatePlayerPanels();
        });
        buttonPanel.add(plusButton);
        buttonPanel.add(minusButton);
        return buttonPanel;
    }

    private JLabel getCoinLabel() {
        return new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                g.drawImage(new ImageIcon("COIN.png").getImage(), 5, 12, getWidth() - 10, (int) (getHeight() * 0.75 - 10), this);

                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 20));
                String text = String.valueOf(project.getCost());
                int textX = getWidth() / 2 - 6;
                int textY = getHeight() / 2 + 5;
                g.drawString(text, textX, textY);
            }
        };
    }

    public void updateText() {
        amountLabel.setText(project.getOwnCount() + "/" + project.getMaxOwnCount(player));
    }
}
