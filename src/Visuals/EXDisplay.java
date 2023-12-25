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
        setLayout(new GridLayout(2, 0));
        JLabel t_s1 = new JLabel("1 D6");
        JLabel t_sn1 = new JLabel("2 D6");
        JLabel t_s2 = new JLabel("1 D6");
        JLabel t_sn2 = new JLabel("2 D6");
        JLabel c_s1 = new JLabel(String.valueOf(player.getEX(true, true)));
        JLabel c_sn1 = new JLabel(String.valueOf(player.getEX(false, true)));
        JLabel c_s2 = new JLabel(String.valueOf(player.getEX(true, false)));
        JLabel c_sn2 = new JLabel(String.valueOf(player.getEX(false, false)));
        add(t_s1);
        add(c_s1);
        add(t_s2);
        add(c_s2);
        add(t_sn1);
        add(c_sn1);
        add(t_sn2);
        add(c_sn2);
    }

}
