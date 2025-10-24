package gui.game;

import logic.Game;
import logic.Player;

import javax.swing.*;
import java.awt.*;

public class GameWindow extends JFrame {

    Game game;
    PlayerPanel[] playerPanels;

    public GameWindow(Game game) {
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
