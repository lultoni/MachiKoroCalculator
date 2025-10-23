package Logic;

import Visuals.Window;

import java.util.ArrayList;
import java.util.Arrays;

public class Main {

    public static void main (String[] args) {

        // TODO create a boot window that lets you define player names and order

        Game game = new Game(2); // TODO change this to the selected from boot window
        Window window = new Window(game); // TODO add in new params with player names
        window.boot();

        ArrayList<Project[]> player_projects = new ArrayList<>();
        int[] player_coins = new int[game.getPlayers().length];
        for (int i = 0; i < game.getPlayers().length; i++) {
            Player p = game.getPlayers()[i];
            player_projects.add(p.getProjects());
            player_coins[i] = p.coins;
        }

        System.out.println(Arrays.deepToString(ProbabilityCalc.values_per_r_per_p(
                player_projects,
                player_coins)));

    }

}
