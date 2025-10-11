package Visuals;

import Logic.Game;
import Logic.Player;

import javax.swing.*;
import java.awt.*;

public class Window extends JFrame {

    Game game;
    PlayerPanel[] playerPanels;

    public Window(Game game) {
        this.game = game;
    }

    private void init() {
        setLayout(new GridLayout(2, 0));
        Player[] players = game.getPlayers();
        playerPanels = new PlayerPanel[players.length];
        for (int i = 0; i < players.length; i++) {
            PlayerPanel playerPanel = new PlayerPanel(players[i], game, this);
            playerPanels[i] = playerPanel;
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

    public void update() {
        for (PlayerPanel panel: playerPanels) {
            panel.updateText();
        }
    }
}
