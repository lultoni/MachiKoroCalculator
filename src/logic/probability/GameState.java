package logic.probability;

import java.util.ArrayList;

public class GameState {

    private final Player[] players;
    private final ArrayList<Project> unbuilt_projects;

    public GameState(Player[] players, ArrayList<Project> unbuilt_projects) {
        if (players == null) throw new IllegalArgumentException("'players' must not be null.");
        if (unbuilt_projects == null) throw new IllegalArgumentException("'unbuilt_projects' must not be null.");

        this.players = players;
        this.unbuilt_projects = unbuilt_projects;
    }

    public Player[] getPlayers() {
        return players;
    }

    public ArrayList<Project> getUnbuilt_projects() {
        return unbuilt_projects;
    }

    public GameState copy() {
        Player[] newPlayers = new Player[this.players.length];
        for (int i = 0; i < this.players.length; i++) {
            Player p = this.players[i];
            if (p == null) {
                newPlayers[i] = null;
                continue;
            }

            ArrayList<Project> origOwned = p.getOwned_projects();
            ArrayList<Project> newOwned = null;
            if (origOwned != null) {
                newOwned = new ArrayList<>(origOwned.size());
                for (Project proj : origOwned) {
                    if (proj == null) {
                        newOwned.add(null);
                        continue;
                    }
                    int[] origDice = proj.getDice_activation();
                    int[] newDice = (origDice != null) ? origDice.clone() : null;

                    newOwned.add(new Project(
                            proj.getId(),
                            proj.getCategory(),
                            proj.isIs_grossprojekt(),
                            proj.getCost(),
                            newDice,
                            proj.getColor(),
                            proj.getDescription()
                    ));
                }
            }

            newPlayers[i] = new Player(p.getName(), p.getCoins(), newOwned);
        }

        ArrayList<Project> newUnbuilt = new ArrayList<>(this.unbuilt_projects.size());
        for (Project proj : this.unbuilt_projects) {
            if (proj == null) {
                newUnbuilt.add(null);
                continue;
            }
            int[] origDice = proj.getDice_activation();
            int[] newDice = (origDice != null) ? origDice.clone() : null;

            newUnbuilt.add(new Project(
                    proj.getId(),
                    proj.getCategory(),
                    proj.isIs_grossprojekt(),
                    proj.getCost(),
                    newDice,
                    proj.getColor(),
                    proj.getDescription()
            ));
        }

        return new GameState(newPlayers, newUnbuilt);
    }

}
