package Visuals;

import Logic.Game;
import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class Window extends JFrame {

    Game game;

    public Window(Game game) {
        this.game = game;
        setTitle("Machi Koro Calculator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setUndecorated(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        init();
        setVisible(true);
    }

    private void init() {
        setLayout(new GridLayout());
        for (Player player: Game.getPlayers()) {
            PlayerPanel playerPanel = new PlayerPanel(player);
            add(playerPanel);
        }
    }

}
