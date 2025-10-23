package visuals;

import logic.Player;

import javax.swing.*;
import java.awt.*;

public class CoinsLabel extends JPanel {

    Player player;
    GameWindow gameWindow;
    JLabel text;

    public CoinsLabel(Player player, GameWindow gameWindow) {
        this.player = player;
        this.gameWindow = gameWindow;
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
            gameWindow.update();
        });
        coinsMinus.addActionListener(e -> {
            player.setCoins(player.getCoins() - 1);
            text.setText("Coins: " + player.getCoins());
            coinsMinus.setEnabled(player.getCoins() > 0);
            gameWindow.update();
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
