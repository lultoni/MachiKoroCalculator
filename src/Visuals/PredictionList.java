package Visuals;

import Logic.Game;
import Logic.Player;
import Logic.Project;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Comparator;

public class PredictionList extends JPanel {

    Player player;
    Window window;
    Game game;
    Project[] tempProjects;

    public PredictionList(Player player, Game game, Window window) {
        this.player = player;
        this.game = game;
        this.window = window;
        init();
    }

    private void init() {
        setLayout(new GridBagLayout());
        setBackground(GlobalColors.backgroundGrey);
        int amount = player.getProjects().length;

        Project[] projects = game.getBestProjects(amount, player);
        double[] changeArr = game.getChangeArr(projects, player);

        amount = projects.length;

        changeArr = sortedArr(changeArr, projects);
        projects = tempProjects;

        for (int i = 1; i < amount + 1; i++) {
            add(createRank(i, projects[i - 1], changeArr[i - 1]), createGBC(i - 1));
        }
    }

    private double[] sortedArr(double[] changeArr, Project[] projects) {
        Integer[] indices = new Integer[changeArr.length];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }

        Arrays.sort(indices, Comparator.comparingDouble(index -> changeArr[index]));

        Project[] tempProjects = new Project[projects.length];

        for (int i = 0; i < tempProjects.length; i++) {
            tempProjects[i] = projects[indices[i]];
        }

        Arrays.sort(changeArr);

        this.tempProjects = reverse(tempProjects);

        return reverse(changeArr);
    }

    private double[] reverse(double[] arr) {
        double[] back = new double[arr.length];
        for (int i = 0; i < back.length / 2; i++) {
            double temp = arr[i];
            back[i] = arr[back.length - i - 1];
            back[back.length - i - 1] = temp;
        }
        if ((back.length % 2) != 0) {
            int index = (back.length / 2);
            back[index] = arr[index];
        }
        return back;
    }

    private Project[] reverse(Project[] arr) {
        Project[] back = new Project[arr.length];
        for (int i = 0; i < back.length / 2; i++) {
            Project temp = arr[i];
            back[i] = arr[back.length - i - 1];
            back[back.length - i - 1] = temp;
        }
        if ((back.length % 2) != 0) {
            int index = (back.length / 2);
            back[index] = arr[index];
        }
        return back;
    }

    private JPanel createRank(int i, Project project, double change) {
        if (project == null) System.out.println(i);
        JPanel back = new JPanel();
        back.setLayout(new BorderLayout());

        JLabel rank = new JLabel(i + "." + ((i < 10) ? "   " : " "));
        rank.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        rank.setOpaque(true);

        JLabel name = new JLabel(project.getName());
        name.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        name.setOpaque(true);

        JLabel cLabel = new JLabel(" (" + change + ")");
        cLabel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        cLabel.setOpaque(true);

        back.add(rank, BorderLayout.WEST);
        back.add(name, BorderLayout.CENTER);
        back.add(cLabel, BorderLayout.EAST);

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
        for (Component component : getComponents()) {
            remove(component);
        }
        init();
        revalidate();
        repaint();
    }

}
