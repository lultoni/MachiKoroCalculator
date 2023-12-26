package Logic;

import Visuals.Window;

public class Main {

    public static void main (String[] args) {

        Game game = new Game(2);
        Window window = new Window(game);
        window.boot();

    }

}
