package Visuals;

import Logic.Game;
import Logic.Player;
import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class PredictionList extends JPanel {

    Player player;
    Window window;
    Game game;

    public PredictionList(Player player, Game game, Window window) {
        this.player = player;
        this.game = game;
        this.window = window;
        init();
    }

    private void init() {
        setLayout(new GridBagLayout());
        int amount = 5;
        Project[] projects = game.getBestProjects(amount, player);
        for (int i = 1; i < amount + 1; i++) {
            add(createRank(i, projects[i - 1]), createGBC(i - 1));
        }
    }

    private JPanel createRank(int i, Project project) {
        JPanel back = new JPanel();
        back.setLayout(new GridLayout(1, 2));
        JLabel rank = new JLabel(i + ".");
        rank.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        JLabel name = new JLabel(project.getName());
        name.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        back.add(rank);
        back.add(name);
        return back;
    }

    private GridBagConstraints createGBC(int gridY) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = gridY;
        gbc.gridheight = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    public void updateText() {
        removeAll();
        init();
    }
}
