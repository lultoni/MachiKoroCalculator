package Visuals;

import Logic.Game;
import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class PlayerPanel extends JPanel {

    Player player;
    Game game;

    public PlayerPanel(Player player, Game game) {
        this.player = player;
        this.game = game;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEtchedBorder());

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new GridLayout(1, 0));
        JLabel nameLabel = new JLabel(player.getName());
        CoinsLabel coinsLabel = new CoinsLabel(player);
        JLabel rankLabel = new JLabel("Rank: " + game.getPlayerRank(player.getID()));
        northPanel.add(nameLabel);
        northPanel.add(coinsLabel);
        northPanel.add(rankLabel);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BorderLayout());

        JPanel uCP = new JPanel();
        uCP.setLayout(new GridLayout(1, 0));
        GPSwitch gps1 = new GPSwitch(player.getProjects()[0]);
        GPSwitch gps2 = new GPSwitch(player.getProjects()[1]);
        GPSwitch gps3 = new GPSwitch(player.getProjects()[2]);
        GPSwitch gps4 = new GPSwitch(player.getProjects()[3]);
        EXDisplay exDisplay = new EXDisplay(player);
        uCP.add(gps1);
        uCP.add(gps2);
        uCP.add(gps3);
        uCP.add(gps4);
        uCP.add(exDisplay);

        JPanel dCP = new JPanel();

        centerPanel.add(uCP, BorderLayout.NORTH);
        centerPanel.add(dCP, BorderLayout.CENTER);

        add(northPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
    }

}
