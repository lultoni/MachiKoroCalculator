package Visuals;

import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class GPSwitch extends JPanel {

    Project project;

    public GPSwitch(Project project) {
        this.project = project;
        init();
    }

    private void init() {
        setLayout(new GridLayout(1, 0));
        JLabel nameLabel = new JLabel(project.getName());
        JButton switchButton = new JButton("sO: " + ((project.getOwnCount() == 0) ? "T" : "F"));
        switchButton.addActionListener(e -> {
            if (project.getOwnCount() == 0) {
                project.setOwnCount(1);
            } else {
                project.setOwnCount(0);
            }
            switchButton.setText("sO: " + ((project.getOwnCount() == 0) ? "T" : "F"));
        });
        setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        add(nameLabel);
        add(switchButton);
    }

}
