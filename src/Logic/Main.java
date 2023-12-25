package Logic;

import Visuals.Window;

public class Main {

    public static void main (String[] args) {

        Game game = new Game(4);
        Window window = new Window(game);
        window.boot();

    }

}
