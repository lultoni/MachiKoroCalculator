package logic.probability;

import java.util.ArrayList;
import java.util.Objects;

public class GameState {

    private final Player[] players;
    /**
     * List of card types available for purchase in the current game state.
     *
     * <p><b>Semantics:</b> Each entry represents a card <em>type</em> that is still available
     * in the market (i.e. at least one copy remains). The list does <em>not</em> track how many
     * physical copies of each type remain — that per-copy supply count is maintained separately
     * by {@link GameSimulator} (via its {@code Map<String,Integer>} supply map, 6 copies per card).
     *
     * <p>Consequences:
     * <ul>
     *   <li>A card type appears at most once in this list regardless of how many copies are owned.</li>
     *   <li>{@link logic.probability.ProbabilityCalc#rankPurchasableProjects} uses this list to
     *       determine which card types are candidates for purchase; it relies on the supply map
     *       inside {@link GameSimulator} for copy-count enforcement.</li>
     *   <li>When a card type is fully exhausted (all 6 copies owned), it should be removed from
     *       this list to stop appearing as a purchase candidate. Currently the UI's
     *       {@link GameStateBuilder} does not enforce this automatically — it is the caller's
     *       responsibility to keep this list consistent with player ownership.</li>
     * </ul>
     */
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

    /**
     * Returns the player array (not a copy — mutating players' coins or projects is visible
     * to all holders of this state).
     */
    public Player[] getPlayers() {
        return players;
    }

    /**
     * Returns the mutable list of card types still available in the market (at least one copy
     * remains). Each card type appears at most once. See field-level Javadoc for the distinction
     * between this "available types" model and the per-copy supply count in {@link GameSimulator}.
     */
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

        // Unbuilt pool: all non-landmark card types are available from the start.
        // Weizenfeld and Bäckerei are also purchasable from the pool — each player's
        // starter copy is separate from the 6 shared market copies.
        ArrayList<Project> unbuilt = new ArrayList<>();
        for (Project p : allProjects) {
            if (!p.isIs_grossprojekt()) unbuilt.add(p);
        }

        return new GameState(players, unbuilt);
    }
}
