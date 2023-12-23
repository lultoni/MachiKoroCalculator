package Visuals;

import Logic.Player;

import javax.swing.*;

public class PlayerPanel extends JPanel {

    Player player;

    public PlayerPanel(Player player) {
        this.player = player;
        init();
    }

    private void init() {
        add(new JLabel(player.getName()));
    }

}
