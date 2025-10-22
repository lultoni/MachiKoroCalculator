package Logic;

import Visuals.Window;

import java.util.Arrays;

public class Main {

    public static void main (String[] args) {

        // TODO create a boot window that lets you define player names and order

        Game game = new Game(4); // TODO change this to the selected from boot window
        Window window = new Window(game); // TODO add in new params with player names
        window.boot();

        System.out.println(Arrays.deepToString(ProbabilityCalc.values_per_r_per_p(
                game.players[0].projectList,
                game.players[1].projectList,
                game.players[2].projectList,
                game.players[3].projectList,
                new int[]{
                        game.players[0].coins,
                        game.players[1].coins,
                        game.players[2].coins,
                        game.players[3].coins
                })));

    }

}
