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
        JButton switchButton = new JButton("Set Owned: " + ((project.getOwnCount() == 0) ? "TRUE" : "FALSE"));
        add(nameLabel);
        add(switchButton);
    }

}
