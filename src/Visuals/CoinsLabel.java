package Visuals;

import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class CoinsLabel extends JPanel {

    Player player;

    public CoinsLabel(Player player) {
        this.player = player;
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
        });

        JButton coinsMinus = new JButton("-");
        coinsMinus.addActionListener(e -> {
            player.setCoins(player.getCoins() - 1);
            text.setText("Coins: " + player.getCoins());
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        buttonPanel.add(coinsPlus);
        buttonPanel.add(coinsMinus);

        add(text, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.EAST);
    }

}
