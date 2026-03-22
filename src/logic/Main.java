package logic;

import gui.boot.BootWindow;
import gui.game.GameWindow;

import java.util.ArrayList;

public class Main {

    public static boolean boot_finished = false;

    public static void main (String[] args) {

        BootWindow bootWindow = new BootWindow();
        bootWindow.boot();

        while (!boot_finished) Thread.onSpinWait();

        Game game = new Game(bootWindow.getPlayerNames().length);

        // FIXME [Phase 3]: Replace legacy UI with new GameState-driven window.

        GameWindow gameWindow = new GameWindow(game); // FIXME [Phase 3]: Pass player names to new UI.
        gameWindow.boot();

        ArrayList<Project[]> player_projects = new ArrayList<>();
        int[] player_coins = new int[game.getPlayers().length];
        for (int i = 0; i < game.getPlayers().length; i++) {
            Player p = game.getPlayers()[i];
            player_projects.add(p.getProjects());
            player_coins[i] = p.coins;
        }

    }

}
