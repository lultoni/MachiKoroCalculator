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

        // TODO add in a new game window, which has better usability and information splitting.
        //      This should have different tabs for each player where you can basically play
        //      the game and also have a screen to see the optimal building for the current player.
        //      You have a screen/mode where you put in the actions of the active player
        //      and the system then on "turn done" does these actions and shows the possible actions
        //      for the next player (so the same dynamic screen, but for a dif player).
        //      From this screen you can also get to the probability calc quickly (and back),
        //      which automatically runs in the background, but is not fully or directly shown.

        GameWindow gameWindow = new GameWindow(game); // TODO add in new params with player names
        gameWindow.boot();

        ArrayList<Project[]> player_projects = new ArrayList<>();
        int[] player_coins = new int[game.getPlayers().length];
        for (int i = 0; i < game.getPlayers().length; i++) {
            Player p = game.getPlayers()[i];
            player_projects.add(p.getProjects());
            player_coins[i] = p.coins;
        }

//        System.out.println(Arrays.deepToString(ProbabilityCalc.values_per_r_per_p(
//                player_projects,
//                player_coins)));

    }

}
