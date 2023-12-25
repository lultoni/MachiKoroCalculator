package Logic;

public class Game {

    Player[] players;

    public Game(int playerAmount) {

        players = new Player[playerAmount];
        for (int i = 0; i < playerAmount; i++) {
            players[i] = new Player(i, getProjectList());
        }

    }

    public Player[] getPlayers() {
        return players;
    }

    Project[] getProjectList() {
        Project[] back = new Project[19];
        // GP
        back[0] = new Project(0, "Bahnhof", Category.OFFICE, 4, 1);
        back[1] = new Project(1, "Einkaufzentrum", Category.OFFICE, 10, 1);
        back[2] = new Project(2, "Freizeitpark", Category.OFFICE, 16, 1);
        back[3] = new Project(3, "Funkturm", Category.OFFICE, 22, 1);
        // Starter
        back[4] = new Project(4, "Weizenfeld", Category.FOOD, 1, 7);
        back[5] = new Project(5, "Bäckerei", Category.STORE, 1, 7);
        // Blue
        back[6] = new Project(6, "Apfelplantage", Category.FOOD, 3, 6);
        back[11] = new Project(11, "Bauernhof", Category.ANIMAL, 1, 6);
        back[12] = new Project(12, "Wald", Category.PRODUCTION, 3, 6);
        back[15] = new Project(15, "Bergwerk", Category.CAFE, 6, 6);
        // Green
        back[7] = new Project(7, "Markthalle", Category.MARKET, 2, 6);
        back[8] = new Project(8, "Molkerei", Category.FACTORY, 5, 6);
        back[9] = new Project(9, "Möbelfabrik", Category.FACTORY, 3, 6);
        back[10] = new Project(10, "Mini-Markt", Category.STORE, 2, 6);
        // Red
        back[13] = new Project(13, "Familienrestaurant", Category.CAFE, 3, 6);
        back[14] = new Project(14, "Café", Category.CAFE, 2, 6);
        // Purple
        back[16] = new Project(16, "Bürohaus", Category.OFFICE, 8, 1);
        back[17] = new Project(17, "Stadion", Category.OFFICE, 6, 1);
        back[18] = new Project(18, "Fernsehsender", Category.OFFICE, 7, 1);
        return back;
    }

    public int getPlayerRank(int id) {
        return id;
    }
}
