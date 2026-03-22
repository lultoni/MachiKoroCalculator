package logic.probability;

import java.util.ArrayList;
import java.util.Objects;

public class GameState {

    private final Player[] players;
    private final ArrayList<Project> unbuilt_projects;

    /**
     * @param players          array of 2–4 non-null players
     * @param unbuilt_projects list of projects not yet owned by any player, must not be null
     */
    public GameState(Player[] players, ArrayList<Project> unbuilt_projects) {
        Objects.requireNonNull(players, "players must not be null");
        Objects.requireNonNull(unbuilt_projects, "unbuilt_projects must not be null");
        if (players.length < 2 || players.length > 4)
            throw new IllegalArgumentException("player count must be 2–4, got: " + players.length);
        for (int i = 0; i < players.length; i++)
            if (players[i] == null)
                throw new IllegalArgumentException("players[" + i + "] must not be null");

        this.players = players;
        this.unbuilt_projects = unbuilt_projects;
    }

    public Player[] getPlayers() {
        return players;
    }

    public ArrayList<Project> getUnbuilt_projects() {
        return unbuilt_projects;
    }

    /**
     * Returns a deep copy of this game state.
     * <p>
     * Each {@link Player} is copied via {@link Player#copy()}, which produces a new Player with
     * a new owned-projects list (safe because {@link Project} is immutable — references are shared).
     * The unbuilt_projects list is also shallow-copied for the same reason.
     */
    public GameState copy() {
        Player[] newPlayers = new Player[players.length];
        for (int i = 0; i < players.length; i++) {
            newPlayers[i] = players[i].copy();
        }
        return new GameState(newPlayers, new ArrayList<>(unbuilt_projects));
    }

    /**
     * Builds the standard starting state for a new game.
     * <p>
     * Each player starts with 3 coins and owns one Weizenfeld and one Bäckerei.
     * All remaining 17 cards go into the unbuilt pool.
     *
     * @param numPlayers number of players (2–4)
     * @return initial game state
     */
    public static GameState initial(int numPlayers) {
        if (numPlayers < 2 || numPlayers > 4)
            throw new IllegalArgumentException("numPlayers must be 2–4, got: " + numPlayers);

        ArrayList<Project> allProjects = ProjectLoader.getAllProjects();

        Project weizenfeld = ProjectLoader.getProject("weizenfeld")
                .orElseThrow(() -> new IllegalStateException("weizenfeld missing from projects.json"));
        Project baeckerei = ProjectLoader.getProject("bäckerei")
                .orElseThrow(() -> new IllegalStateException("bäckerei missing from projects.json"));

        Player[] players = new Player[numPlayers];
        for (int i = 0; i < numPlayers; i++) {
            ArrayList<Project> owned = new ArrayList<>();
            owned.add(weizenfeld);
            owned.add(baeckerei);
            players[i] = new Player("Player " + (i + 1), 3, owned);
        }

        // Unbuilt pool: everything except the two starter cards
        ArrayList<Project> unbuilt = new ArrayList<>();
        for (Project p : allProjects) {
            if (!p.getId().equals("weizenfeld") && !p.getId().equals("bäckerei")) {
                unbuilt.add(p);
            }
        }

        return new GameState(players, unbuilt);
    }
}
