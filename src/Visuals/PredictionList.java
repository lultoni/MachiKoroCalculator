package Visuals;

import Logic.Game;
import Logic.Player;
import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class PredictionList extends JPanel {

    Player player;
    Game game;

    public PredictionList(Player player, Game game) {
        this.player = player;
        this.game = game;
        init();
    }

    private void init() {
        setLayout(new GridLayout(0, 1));
        int amount = 5;
        Project[] projects = game.getBestProjects(amount, player);
        for (int i = 1; i < amount + 1; i++) {
            add(createRank(i, projects[i - 1]));
        }
    }

    private JPanel createRank(int i, Project project) {
        JPanel back = new JPanel();
        JLabel rank = new JLabel(i + ".");
        JLabel name = new JLabel(project.getName());
        setLayout(new GridLayout(1, 0));
        back.add(rank);
        back.add(name);
        return back;
    }

}
