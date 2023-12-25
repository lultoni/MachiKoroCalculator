package Visuals;

import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class EXDisplay extends JPanel {

    Player player;

    public EXDisplay(Player player) {

        this.player = player;
        init();

    }

    private void init() {
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayout(2, 0));

        JLabel t_s1 = new JLabel("1 D6");
        t_s1.setBackground(GlobalColors.myEX);
        t_s1.setOpaque(true);
        JLabel t_sn1 = new JLabel("2 D6");
        t_sn1.setBackground(GlobalColors.myEX);
        t_sn1.setOpaque(true);
        JLabel t_s2 = new JLabel("1 D6");
        t_s2.setBackground(GlobalColors.otherEX);
        t_s2.setOpaque(true);
        JLabel t_sn2 = new JLabel("2 D6");
        t_sn2.setBackground(GlobalColors.otherEX);
        t_sn2.setOpaque(true);

        JLabel c_s1 = new JLabel(String.valueOf(player.getEX(true, true)));
        JLabel c_sn1 = new JLabel(String.valueOf(player.getEX(false, true)));
        JLabel c_s2 = new JLabel(String.valueOf(player.getEX(true, false)));
        JLabel c_sn2 = new JLabel(String.valueOf(player.getEX(false, false)));

        mainPanel.add(t_s1);
        mainPanel.add(c_s1);
        mainPanel.add(t_s2);
        mainPanel.add(c_s2);
        mainPanel.add(t_sn1);
        mainPanel.add(c_sn1);
        mainPanel.add(t_sn2);
        mainPanel.add(c_sn2);

        add(mainPanel, BorderLayout.CENTER);

        JButton repaintButton = new JButton("Calc");
        repaintButton.addActionListener(e -> {
            c_s1.setText(String.valueOf(player.getEX(true, true)));
            c_sn1.setText(String.valueOf(player.getEX(false, true)));
            c_s2.setText(String.valueOf(player.getEX(true, false)));
            c_sn2.setText(String.valueOf(player.getEX(false, false)));
        });

        add(repaintButton, BorderLayout.EAST);
    }

}
