package Visuals;

import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class CoinsLabel extends JPanel {

    Player player;
    Window window;

    public CoinsLabel(Player player, Window window) {
        this.player = player;
        this.window = window;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());

        JLabel text = new JLabel();
        text.setText("Coins: " + player.getCoins());

        JButton coinsPlus = new JButton("+");
        coinsPlus.addActionListener(e -> {
            player.setCoins(player.getCoins() + 1);
            text.setText("Coins: " + player.getCoins());
            window.updatePlayerPanels();
        });

        JButton coinsMinus = new JButton("-");
        coinsMinus.addActionListener(e -> {
            player.setCoins(player.getCoins() - 1);
            text.setText("Coins: " + player.getCoins());
            window.updatePlayerPanels();
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        buttonPanel.add(coinsPlus);
        buttonPanel.add(coinsMinus);

        add(text, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.EAST);
    }

}
