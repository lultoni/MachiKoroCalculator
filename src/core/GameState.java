package core;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Complete snapshot of a Machi Koro game: players (coins + owned cards) and available market cards.
 *
 * <h2>Mutability</h2>
 * GameState is mutable — player coins, owned projects, and the unbuilt list can all change.
 * Use {@link #copy()} before mutating in hypothetical scenarios (MCTS tree, engine evaluation).
 * The original GameState passed to an engine must NOT be modified by the engine.
 *
 * <h2>Supply Rule (CRITICAL INVARIANT)</h2>
 * Starting cards (Weizenfeld, Bäckerei) are given to each player OUTSIDE the 6-copy market
 * supply pool. In a 2-player game, all 6 market copies of each card remain purchasable.
 * See {@link #starterCopies(String, int)} for the supply calculation.
 * <b>This was a bug source — do not change supply logic without verifying this invariant.</b>
 */
public class GameState {

    /** Market supply copies per non-landmark card in the base game. */
    public static final int SUPPLY_PER_CARD = 6;

    /** Card IDs that each player receives one copy of at game start (outside the market supply). */
    private static final java.util.Set<String> STARTER_CARD_IDS =
            java.util.Set.of("weizenfeld", "bäckerei");

    /**
     * Returns the number of starter copies of the given card that exist outside the market supply.
     * In Machi Koro, each player starts with 1 Weizenfeld and 1 Bäckerei — these are separate
     * from the 6-copy shared market pool.
     *
     * @param cardId     card ID
     * @param numPlayers number of players (2–4)
     * @return number of starter copies (0 for most cards; numPlayers for weizenfeld/bäckerei)
     */
    public static int starterCopies(String cardId, int numPlayers) {
        return STARTER_CARD_IDS.contains(cardId) ? numPlayers : 0;
    }

    private final Player[] players;
    /**
     * List of card types available for purchase in the current game state.
     *
     * <p><b>Semantics:</b> Each entry represents a card <em>type</em> that is still available
     * in the market (i.e. at least one copy remains). The list does <em>not</em> track how many
     * physical copies of each type remain — that per-copy supply count is reflected by checking
     * total copies owned across all players against {@link #SUPPLY_PER_CARD}.
     *
     * <p>Consequences:
     * <ul>
     *   <li>A card type appears at most once in this list regardless of how many copies are owned.</li>
     *   <li>When a card type is fully exhausted (all {@link #SUPPLY_PER_CARD} copies owned), it
     *       should be removed from this list to stop appearing as a purchase candidate.</li>
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
     * remains). Each card type appears at most once.
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
     * Returns true if the given player owns all 4 landmarks (win condition).
     */
    public static boolean hasWon(Player player) {
        return player.getLandmarkCount() >= 4;
    }

    /**
     * Returns a structural hash of this game state: player coins, owned card IDs (sorted), and landmarks.
     * Used by the pre-computation cache to detect when the state has changed meaningfully.
     */
    public int structuralHash() {
        int hash = 17;
        for (Player p : players) {
            hash = 31 * hash + p.getCoins();
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Project proj : p.getOwned_projects()) {
                ids.add(proj.getId());
            }
            java.util.Collections.sort(ids);
            for (String id : ids) {
                hash = 31 * hash + id.hashCode();
            }
        }
        return hash;
    }

    /**
     * Builds the standard starting state for a new game.
     * <p>
     * Each player starts with 3 coins and owns one Weizenfeld and one Bäckerei.
     * All non-landmark card types go into the unbuilt pool.
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
