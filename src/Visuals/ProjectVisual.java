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
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayout(1, 0));
        mainPanel.setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));

        JLabel amountLabel = new JLabel(project.getOwnCount() + "/" + project.getMaxOwnCount());

        JLabel nameLabel = new JLabel(project.getName());
        Image projectIcon = new ImageIcon(project.getCategory() + ".png").getImage();
        nameLabel.setIcon(new ImageIcon(projectIcon.getScaledInstance(25, 25, Image.SCALE_SMOOTH)));
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        JButton plusButton = new JButton("+");
        plusButton.addActionListener(e -> {
             if (project.getMaxOwnCount() > project.getOwnCount()) project.setOwnCount(project.getOwnCount() + 1);
            amountLabel.setText(project.getOwnCount() + "/" + project.getMaxOwnCount());
        });
        JButton minusButton = new JButton("-");
        minusButton.addActionListener(e -> {
            if (0 < project.getOwnCount()) project.setOwnCount(project.getOwnCount() - 1);
            amountLabel.setText(project.getOwnCount() + "/" + project.getMaxOwnCount());
        });
        buttonPanel.add(plusButton);
        buttonPanel.add(minusButton);

        mainPanel.add(nameLabel);
        mainPanel.add(buttonPanel);

        add(mainPanel);
        add(amountLabel, BorderLayout.SOUTH);
    }

}
