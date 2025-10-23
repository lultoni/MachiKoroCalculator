package visuals.game;

import logic.Player;
import logic.Project;

import javax.swing.*;
import java.awt.*;

public class ProjectVisual extends JPanel {

    Project project;
    GameWindow gameWindow;
    AmountBar amountBar;
    Player player;
    JButton plusButton, minusButton;

    public ProjectVisual(Project project, GameWindow gameWindow, Player player) {
        this.project = project;
        this.gameWindow = gameWindow;
        this.player = player;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        mainPanel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));

        amountBar = new AmountBar(project.getOwnCount(), project.getMaxOwnCount(player));

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
        mainPanel.add(buttonPanel, BorderLayout.EAST);

        add(mainPanel);
        add(amountBar, BorderLayout.SOUTH);
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

        plusButton = new JButton("+");
        plusButton.setFont(GlobalColors.northFont);
        minusButton = new JButton("-");
        minusButton.setFont(GlobalColors.northFont);

        plusButton.addActionListener(_ -> {
            if (get_plus_action_allowed()) {
                project.setOwnCount(project.getOwnCount() + 1);
                player.setCoins(player.getCoins() - project.getCost());
            }
            minusButton.setEnabled(get_minus_action_allowed());
            plusButton.setEnabled(get_plus_action_allowed());
            gameWindow.update();
        });
        minusButton.addActionListener(_ -> {
            if (get_minus_action_allowed()) {
                project.setOwnCount(project.getOwnCount() - 1);
                player.setCoins(player.getCoins() + project.getCost());
            }
            minusButton.setEnabled(get_minus_action_allowed());
            plusButton.setEnabled(get_plus_action_allowed());
            gameWindow.update();
        });

        minusButton.setEnabled(get_minus_action_allowed());
        plusButton.setEnabled(get_plus_action_allowed());

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

    public void update() {
        amountBar.change_max(project.getMaxOwnCount(player));
        amountBar.change_fill(project.getOwnCount());
        minusButton.setEnabled(get_minus_action_allowed());
        plusButton.setEnabled(get_plus_action_allowed());
    }

    private boolean get_plus_action_allowed() {
        return project.getMaxOwnCount(player) > project.getOwnCount()
                && player.getCoins() >= project.getCost();
    }

    private boolean get_minus_action_allowed() {
        return 0 < project.getOwnCount()
                && (project.getID() == 4 && project.getOwnCount() > 1 || project.getID() == 5 && project.getOwnCount() > 1
                   || (project.getID() != 4 && project.getID() != 5));
    }
}
