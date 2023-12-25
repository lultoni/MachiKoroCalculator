package Visuals;

import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class ProjectVisual extends JPanel {

    Project project;
    public ProjectVisual(Project project) {
        this.project = project;
        init();
    }

    private void init() {
        setLayout(new GridLayout(1, 0));

        JLabel imageLabel = new JLabel(new ImageIcon("Images." + project.getCategory() + ".png"));
        imageLabel.setBackground(Images.GlobalColors.getCorrectBackgroundColor(project.getID()));
        JLabel nameLabel = new JLabel(project.getName());
        nameLabel.setBackground(Images.GlobalColors.getCorrectBackgroundColor(project.getID()));
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        JButton plusButton = new JButton("+");
        JButton minusButton = new JButton("-");
        buttonPanel.add(plusButton);
        buttonPanel.add(minusButton);

        add(imageLabel);
        add(nameLabel);
        add(buttonPanel);
    }

}
