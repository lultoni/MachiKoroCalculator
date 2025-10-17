package Logic;

import Visuals.Window;

public class Main {

    public static void main (String[] args) {

        // TODO create a boot window that lets you define player names and order

        Game game = new Game(2); // TODO change this to the selected from boot window
        Window window = new Window(game); // TODO add in new params with player names
        window.boot();

    }

}
