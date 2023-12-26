package Visuals;

import Logic.Game;
import Logic.Player;
import Logic.Project;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

public class PlayerPanel extends JPanel {

    Player player;
    Game game;
    Window window;
    JLabel rankLabel;
    GPSwitch gps1;
    GPSwitch gps2;
    GPSwitch gps3;
    GPSwitch gps4;
    EXDisplay exDisplay;
    PredictionList predictionList;
    ArrayList<ProjectVisual> pvs;

    public PlayerPanel(Player player, Game game, Window window) {
        this.player = player;
        this.game = game;
        this.window = window;
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
        CoinsLabel coinsLabel = new CoinsLabel(player, window);
        rankLabel = new JLabel("Rank: " + game.getPlayerRank(player.getID()));
        northPanel.add(nameLabel);
        northPanel.add(coinsLabel);
        northPanel.add(rankLabel);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BorderLayout());

        JPanel uCP = new JPanel();
        uCP.setLayout(new GridLayout(1, 0));
        gps1 = new GPSwitch(player.getProjects()[0], window);
        gps2 = new GPSwitch(player.getProjects()[1], window);
        gps3 = new GPSwitch(player.getProjects()[2], window);
        gps4 = new GPSwitch(player.getProjects()[3], window);
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
            ProjectVisual pv = new ProjectVisual(projects[i], window);
            pvs.add(pv);
            dCP.add(pv);
        }

        predictionList = new PredictionList(player, game, window);

        centerPanel.add(uCP, BorderLayout.NORTH);
        centerPanel.add(dCP, BorderLayout.CENTER);

        add(northPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(predictionList, BorderLayout.EAST);
    }

    public void updateText() {
        rankLabel.setText("Rank: " + game.getPlayerRank(player.getID()));
        gps1.updateText();
        gps2.updateText();
        gps3.updateText();
        gps4.updateText();
        exDisplay.updateText();
        for (ProjectVisual pv: pvs) {
            pv.updateText();
        }
        predictionList.updateText();
    }

}
