package Visuals;

import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class CoinsLabel extends JPanel {

    Player player;
    Window window;
    JLabel text;

    public CoinsLabel(Player player, Window window) {
        this.player = player;
        this.window = window;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());

        text = new JLabel();
        text.setText("Coins: " + player.getCoins());
        text.setFont(GlobalColors.northFont);

        JButton coinsPlus = new JButton("+");
        JButton coinsMinus = new JButton("-");

        coinsMinus.setEnabled(player.getCoins() > 0);

        coinsPlus.addActionListener(e -> {
            player.setCoins(player.getCoins() + 1);
            text.setText("Coins: " + player.getCoins());
            coinsMinus.setEnabled(player.getCoins() > 0);
            window.updatePlayerPanels();
        });
        coinsMinus.addActionListener(e -> {
            player.setCoins(player.getCoins() - 1);
            text.setText("Coins: " + player.getCoins());
            coinsMinus.setEnabled(player.getCoins() > 0);
            window.updatePlayerPanels();
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(0, 1));
        buttonPanel.add(coinsPlus);
        buttonPanel.add(coinsMinus);

        add(text, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.EAST);
    }

    public void updateText() {
        text.setText("Coins: " + player.getCoins());
    }
}
