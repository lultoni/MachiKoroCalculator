package Visuals;

import Logic.Game;
import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class Window extends JFrame {

    Game game;

    public Window(Game game) {
        this.game = game;
    }

    private void init() {
        setLayout(new GridLayout(2, 0));
        for (Player player: game.getPlayers()) {
            PlayerPanel playerPanel = new PlayerPanel(player, game);
            add(playerPanel);
        }
    }

    public void boot() {
        setTitle("Machi Koro Calculator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setUndecorated(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        init();
        setVisible(true);
    }
}
