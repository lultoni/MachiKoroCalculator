package logic.probability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages a live game session: tracks the mutable {@link GameState} and the full turn history.
 *
 * <h2>Turn-by-turn ↔ snapshot duality</h2>
 * <ul>
 *   <li>{@link #toSnapshot()} returns the current state as a {@link GameStateBuilder} so the
 *       caller can inspect or export it.</li>
 *   <li>{@link #fromSnapshot(GameStateBuilder, String[])} creates a new session whose state is
 *       whatever the snapshot describes, with an empty history from that point forward. The caller
 *       can then call {@link #applyTurn} to continue turn-by-turn tracking from that snapshot.</li>
 * </ul>
 */
public class GameSession {

    private GameState state;
    private final ArrayList<TurnRecord> history = new ArrayList<>();
    private final String[] playerNames;

    /**
     * Creates a new session from an initial game state.
     *
     * @param initialState the starting state (not mutated — a copy is stored internally)
     * @param playerNames  display names, must have same length as players in the state
     */
    public GameSession(GameState initialState, String[] playerNames) {
        if (playerNames.length != initialState.getPlayers().length)
            throw new IllegalArgumentException("playerNames length must match player count");
        this.state = initialState.copy();
        this.playerNames = playerNames.clone();
    }

    // -------------------------------------------------------------------------
    // Core turn management
    // -------------------------------------------------------------------------

    /**
     * Applies a completed turn to the game state and records it in the history.
     *
     * <p>Coin income/loss from the roll is applied to all players according to the current
     * game rules. If {@code record.bought} is non-null, the card is moved from the unbuilt
     * pool to the buying player's owned list and the player pays the cost.
     *
     * @param record the turn that just completed
     * @throws IllegalArgumentException if the buying player cannot afford the card, or the
     *                                  card is not in the unbuilt pool
     */
    public void applyTurn(TurnRecord record) {
        int roll = record.roll;
        int pi = record.playerIndex;
        Player[] players = state.getPlayers();

        // --- Apply coin effects from the roll ---
        // Compute each player's delta for this roll and apply simultaneously
        // (so inability-to-pay checks are based on coins before this roll)
        int[] deltas = new int[players.length];

        for (int i = 0; i < players.length; i++) {
            // Net gain for player i when player pi is the active roller
            deltas[i] = (i == pi)
                    ? ProbabilityCalc.computeNetGainForRollPublic(state, pi, roll)
                    : ProbabilityCalc.computeOpponentTurnGainForRollPublic(state, i, pi, roll);
        }
        for (int i = 0; i < players.length; i++) {
            int newCoins = Math.max(0, players[i].getCoins() + deltas[i]);
            players[i].setCoins(newCoins);
        }

        // --- Apply purchase ---
        if (record.bought != null) {
            Project card = record.bought;
            Player buyer = players[pi];
            if (buyer.getCoins() < card.getCost())
                throw new IllegalArgumentException(
                        "Player " + pi + " cannot afford " + card.getId()
                        + " (has " + buyer.getCoins() + ", needs " + card.getCost() + ")");

            boolean inPool = state.getUnbuilt_projects().remove(card);
            // Großprojekte are not in the pool — they are always available for purchase
            if (!inPool && !card.isIs_grossprojekt())
                throw new IllegalArgumentException(
                        "Card " + card.getId() + " is not in the unbuilt pool");

            buyer.getOwned_projects().add(card);
            buyer.setCoins(buyer.getCoins() - card.getCost());
        }

        history.add(record);
    }

    /**
     * Undoes the last applied turn, restoring the game state to what it was before that turn.
     *
     * @throws IllegalStateException if there are no turns to undo
     */
    public void undoLastTurn() {
        if (history.isEmpty()) throw new IllegalStateException("No turns to undo");
        // Rebuild state from scratch by replaying all turns except the last
        GameState fresh = GameState.initial(state.getPlayers().length);
        // Re-apply player names and starting coins from the original (we re-initialize to initial)
        // Since initial() always sets names to "Player N" and coins to 3, we replay from there.
        // Names are injected separately; rebuild players array with correct names.
        Player[] freshPlayers = fresh.getPlayers();
        for (int i = 0; i < freshPlayers.length; i++) {
            // Copy name from our tracked names; initial() coins/projects are already correct
            // We can't set name on existing Player, so rebuild via GameStateBuilder
        }
        // Use GameStateBuilder to rebuild initial state with correct names
        GameStateBuilder builder = new GameStateBuilder(playerNames.length);
        for (int i = 0; i < playerNames.length; i++) {
            builder.setPlayerName(i, playerNames[i]);
            builder.setCoins(i, 3);
            builder.addProject(i, "weizenfeld");
            builder.addProject(i, "bäckerei");
        }
        this.state = builder.build();

        ArrayList<TurnRecord> toReplay = new ArrayList<>(history.subList(0, history.size() - 1));
        history.clear();
        for (TurnRecord r : toReplay) applyTurn(r);
    }

    // -------------------------------------------------------------------------
    // Snapshot conversion
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link GameStateBuilder} pre-populated with the current live state.
     * The caller can {@link GameStateBuilder#build()} it to get a standalone snapshot.
     */
    public GameStateBuilder toSnapshot() {
        Player[] players = state.getPlayers();
        GameStateBuilder b = new GameStateBuilder(players.length);
        for (int i = 0; i < players.length; i++) {
            b.setPlayerName(i, players[i].getName());
            b.setCoins(i, players[i].getCoins());
            for (Project p : players[i].getOwned_projects()) {
                b.addProject(i, p.getId());
            }
        }
        return b;
    }

    /**
     * Creates a new {@link GameSession} starting from the state described by the given builder.
     * Turn history begins empty — subsequent {@link #applyTurn} calls continue from this snapshot.
     *
     * @param snapshot    pre-populated builder describing the current game state
     * @param playerNames display names (length must match numPlayers in builder)
     * @return new session with empty history rooted at the snapshot state
     */
    public static GameSession fromSnapshot(GameStateBuilder snapshot, String[] playerNames) {
        return new GameSession(snapshot.build(), playerNames);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the current live game state (not a copy — do not mutate). */
    public GameState getState() {
        return state;
    }

    /** Returns an unmodifiable view of the turn history. */
    public List<TurnRecord> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /** Returns the player names array (indexed 0-based). */
    public String[] getPlayerNames() {
        return playerNames.clone();
    }

    /** Returns the index of the player whose turn comes next (round-robin). */
    public int nextPlayerIndex() {
        return history.size() % state.getPlayers().length;
    }
}
