package visuals;

import logic.Game;
import logic.Player;
import logic.Project;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

public class PlayerPanel extends JPanel {

    Player player;
    Game game;
    GameWindow gameWindow;
    JLabel rankLabel;
    GPSwitch gps1;
    GPSwitch gps2;
    GPSwitch gps3;
    GPSwitch gps4;
    EXDisplay exDisplay;
    PredictionList predictionList;
    ArrayList<ProjectVisual> pvs;
    CoinsLabel coinsLabel;

    public PlayerPanel(Player player, Game game, GameWindow gameWindow) {
        this.player = player;
        this.game = game;
        this.gameWindow = gameWindow;
        pvs = new ArrayList<>();
        init();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEtchedBorder());

        JPanel northPanel = new JPanel();
        northPanel.setBorder(BorderFactory.createEtchedBorder());
        northPanel.setLayout(new GridLayout(1, 0));
        JLabel nameLabel = new JLabel(player.getName());
        nameLabel.setFont(GlobalColors.northFont);
        coinsLabel = new CoinsLabel(player, gameWindow);
        rankLabel = new JLabel("Rank: " + game.getPlayerRank(player));
        rankLabel.setFont(GlobalColors.northFont);

        JPanel dicePanel = new JPanel();
        Integer[] diceNumbers = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        JComboBox<Integer> diceComboBox = new JComboBox<>(diceNumbers);
        JButton rollButton = new JButton("Roll!");
        rollButton.addActionListener(_ -> {
            int dn = (int) diceComboBox.getSelectedItem();
            for (Player p1: game.getPlayers()) {
                p1.setCoins(p1.getCoins() + p1.getDiceThrow(dn, p1.getID() == player.getID()));
            }
            gameWindow.update();
        });

        dicePanel.add(diceComboBox);
        dicePanel.add(rollButton);

        northPanel.add(nameLabel);
        northPanel.add(coinsLabel);
        northPanel.add(rankLabel);
        northPanel.add(dicePanel);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BorderLayout());

        JPanel uCP = new JPanel();
        uCP.setLayout(new GridLayout(1, 0));
        gps1 = new GPSwitch(player.getProjects()[0], gameWindow);
        gps2 = new GPSwitch(player.getProjects()[1], gameWindow);
        gps3 = new GPSwitch(player.getProjects()[2], gameWindow);
        gps4 = new GPSwitch(player.getProjects()[3], gameWindow);
        exDisplay = new EXDisplay(player);
        uCP.add(gps1);
        uCP.add(gps2);
        uCP.add(gps3);
        uCP.add(gps4);
        uCP.add(exDisplay);

        JPanel dCP = new JPanel();
        dCP.setLayout(new GridLayout(3, 0));
        Project[] projects = player.getProjects();
        for (int i = 4; i < projects.length; i++) {
            ProjectVisual pv = new ProjectVisual(projects[i], gameWindow, player);
            pvs.add(pv);
            dCP.add(pv);
        }

        predictionList = new PredictionList(player, game, gameWindow);

        centerPanel.add(uCP, BorderLayout.NORTH);
        centerPanel.add(dCP, BorderLayout.CENTER);

        add(northPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(predictionList, BorderLayout.EAST);
    }

    public void updateText() {
        rankLabel.setText("Rank: " + game.getPlayerRank(player));
        coinsLabel.updateText();
        gps1.updateText();
        gps2.updateText();
        gps3.updateText();
        gps4.updateText();
        exDisplay.updateText();
        for (ProjectVisual pv: pvs) {
            pv.update();
        }
        predictionList.updateText();
    }

}
