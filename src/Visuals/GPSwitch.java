package Visuals;

import Logic.Project;

import javax.swing.*;
import java.awt.*;

public class GPSwitch extends JPanel {

    Project project;
    Window window;
    JButton switchButton;

    public GPSwitch(Project project, Window window) {
        this.project = project;
        this.window = window;
        init();
    }

    private void init() {
        setLayout(new GridLayout(1, 0));
        JLabel nameLabel = new JLabel(project.getName());
        Image projectIcon = new ImageIcon(project.getCategory() + ".png").getImage();
        nameLabel.setIcon(new ImageIcon(projectIcon.getScaledInstance(25, 25, Image.SCALE_SMOOTH)));
        switchButton = new JButton("sO: " + ((project.getOwnCount() == 0) ? "T" : "F"));
        switchButton.addActionListener(e -> {
            if (project.getOwnCount() == 0) {
                project.setOwnCount(1);
            } else {
                project.setOwnCount(0);
            }
            switchButton.setText("sO: " + ((project.getOwnCount() == 0) ? "T" : "F"));
            window.update();
        });
        setBackground(GlobalColors.getCorrectBackgroundColor(project.getID()));
        add(nameLabel);
        add(switchButton);
    }

    public void updateText() {
        switchButton.setText("sO: " + ((project.getOwnCount() == 0) ? "T" : "F"));
    }

}
