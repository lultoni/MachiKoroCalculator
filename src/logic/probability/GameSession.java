package logic.probability;

import java.io.IOException;
import java.nio.file.Path;
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
 *
 * <h2>Persistence</h2>
 * JSON serialization and deserialization are handled by {@link GameSessionPersistence}.
 * {@link #save} and {@link #load} are thin public wrappers that delegate there.
 */
public class GameSession {

    private GameState state;
    private final GameState initialState;  // immutable copy of the starting state
    private final ArrayList<TurnRecord> history = new ArrayList<>();
    private final String[] playerNames;
    private boolean finished = false;
    private int winnerIndex = -1;
    /** True when the next turn is a Freizeitpark bonus turn for the same player. */
    private boolean bonusTurnPending = false;
    /**
     * Counts the number of "effective" player advances (i.e. turns that advance to the
     * next player). Bonus Freizeitpark turns do NOT increment this counter, so
     * {@link #nextPlayerIndex()} stays correct even when bonus turns are interspersed.
     */
    private int effectiveTurnCount = 0;

    /**
     * Creates a new session from an initial game state.
     *
     * @param initialState the starting state (not mutated — a copy is stored internally)
     * @param playerNames  display names, must have same length as players in the state
     */
    public GameSession(GameState initialState, String[] playerNames) {
        if (playerNames.length != initialState.getPlayers().length)
            throw new IllegalArgumentException("playerNames length must match player count");
        this.initialState = initialState.copy();
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
        // computeAllDeltasForRoll resolves all players' deltas in the correct order:
        // red card payments counter-clockwise first, then blue/green/purple income.
        // This ensures the roller's coins are consumed in the right order when
        // multiple red card owners trigger on the same roll.
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }

        // --- Apply purchase ---
        if (record.bought != null) {
            Project card = record.bought;
            Player buyer = players[pi];
            if (buyer.getCoins() < card.getCost())
                throw new IllegalArgumentException(
                        "Player " + pi + " cannot afford " + card.getId()
                        + " (has " + buyer.getCoins() + ", needs " + card.getCost() + ")");

            // Großprojekte are never in the pool — they are always available for purchase
            if (!card.isIs_grossprojekt()) {
                if (!state.getUnbuilt_projects().contains(card))
                    throw new IllegalArgumentException(
                            "Card " + card.getId() + " is not in the unbuilt pool");
                // Remove from pool only when all 6 physical copies are now owned
                int totalOwned = 0;
                for (Player p : players) {
                    for (Project owned : p.getOwned_projects()) {
                        if (owned.getId().equals(card.getId())) totalOwned++;
                    }
                }
                if (totalOwned >= GameSimulator.SUPPLY_PER_CARD) {
                    state.getUnbuilt_projects().remove(card);
                }
            }

            buyer.getOwned_projects().add(card);
            buyer.setCoins(buyer.getCoins() - card.getCost());

            // Check win condition: owning all 4 landmarks ends the game immediately
            if (GameSimulator.hasWon(buyer)) {
                finished = true;
                winnerIndex = pi;
            }
        }

        // Store an augmented copy of the record that includes the computed coin deltas.
        // If the caller already supplied deltas (e.g. replay path), keep them; otherwise attach.
        TurnRecord stored = (record.coinDeltas != null) ? record
                : new TurnRecord(record.playerIndex, record.roll, record.bought, record.isDoubles, deltas);
        history.add(stored);

        // Determine if a Freizeitpark bonus turn is pending:
        // The player gets a bonus turn if:
        //   1. They just rolled doubles (record.isDoubles = true)
        //   2. This was NOT itself a bonus turn (no chaining — Freizeitpark rule)
        //   3. The player owns both Bahnhof (required for 2-dice) and Freizeitpark
        boolean thisWasBonusTurn = bonusTurnPending;
        boolean qualifiesForBonus = record.isDoubles && !thisWasBonusTurn
                && players[pi].hasProject("bahnhof")
                && players[pi].hasProject("freizeitpark");
        bonusTurnPending = qualifiesForBonus;

        // Advance effectiveTurnCount only for non-bonus turns.
        // Bonus turns count as the same player's turn continuation.
        if (!thisWasBonusTurn) {
            effectiveTurnCount++;
        }
    }

    /**
     * Executes the bürohaus card swap for the active player and amends the last TurnRecord
     * to record which cards were exchanged.
     *
     * <p>Must be called immediately after {@link #applyTurn} on a roll=6 turn where the
     * active player owns bürohaus. No-ops if no beneficial swap exists (the last TurnRecord
     * is left unchanged in that case).
     *
     * @param playerIndex the active player (must match the last recorded turn)
     */
    public void applyBürohausSwap(int playerIndex) {
        BürohausLogic.SwapCandidates c = BürohausLogic.findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return;

        BürohausLogic.executeSwap(state, playerIndex);

        // Amend the last TurnRecord to include the swap info
        TurnRecord last = history.get(history.size() - 1);
        history.set(history.size() - 1,
                new TurnRecord(last.playerIndex, last.roll, last.bought,
                        last.isDoubles, last.coinDeltas,
                        c.worstOwn(), c.bestOpp()));
    }

    /**
     * Undoes the last applied turn, restoring the game state to what it was before that turn.
     *
     * @throws IllegalStateException if there are no turns to undo
     */
    public void undoLastTurn() {
        if (history.isEmpty()) throw new IllegalStateException("No turns to undo");
        // Rebuild state from the stored initial snapshot, then replay all turns except the last.
        this.state = initialState.copy();

        // Reset win state and bonus turn state — will be re-set during replay
        finished = false;
        winnerIndex = -1;
        bonusTurnPending = false;
        effectiveTurnCount = 0;

        ArrayList<TurnRecord> toReplay = new ArrayList<>(history.subList(0, history.size() - 1));
        history.clear();
        for (TurnRecord r : toReplay) {
            applyTurn(r);
            // Re-apply any bürohaus swap that was recorded with this turn
            if (r.swappedAway != null) {
                BürohausLogic.executeSwap(state, r.playerIndex);
                // Amend the stored record to preserve the swap fields (applyTurn stores
                // a fresh record without swap info; we need to restore it)
                TurnRecord last = history.get(history.size() - 1);
                history.set(history.size() - 1,
                        new TurnRecord(last.playerIndex, last.roll, last.bought,
                                last.isDoubles, last.coinDeltas,
                                r.swappedAway, r.swappedIn));
            }
        }
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

    /**
     * Returns the index of the player whose turn comes next.
     *
     * <p>Normally advances round-robin. However, if the last recorded turn was a doubles
     * roll ({@link TurnRecord#isDoubles} = true) and the active player owned both
     * Bahnhof and Freizeitpark at that point, the same player gets a bonus second turn.
     * The second turn itself cannot chain further doubles (Freizeitpark rule).
     */
    public int nextPlayerIndex() {
        if (bonusTurnPending && !history.isEmpty()) {
            return history.get(history.size() - 1).playerIndex;
        }
        return effectiveTurnCount % state.getPlayers().length;
    }

    /** Returns true when the next turn is a Freizeitpark bonus turn for the same player. */
    public boolean isBonusTurnPending() {
        return bonusTurnPending;
    }

    /** Returns true if a player has won the game (all 4 landmarks purchased). */
    public boolean isFinished() {
        return finished;
    }

    /** Returns the index of the winning player, or -1 if the game is not yet finished. */
    public int getWinnerIndex() {
        return winnerIndex;
    }

    // -------------------------------------------------------------------------
    // Persistence — save / load (JSON delegated to GameSessionPersistence)
    // -------------------------------------------------------------------------

    /**
     * Saves this session to a JSON file at {@code path}.
     *
     * <p>The file stores the initial game state snapshot plus the full turn history.
     * On load, the initial state is reconstructed from the snapshot and turns are replayed
     * to restore the live game state. This correctly handles sessions started from a
     * mid-game snapshot (via {@link #fromSnapshot}) as well as fresh games.
     *
     * <p>For the file format see {@link GameSessionPersistence}.
     *
     * @param path destination file path (created or overwritten)
     * @throws IOException if the file cannot be written
     */
    public void save(Path path) throws IOException {
        GameSessionPersistence.save(initialState, history, playerNames, path);
    }

    /**
     * Loads a previously saved session from a JSON file.
     *
     * <p>Reconstructs the initial game state from the stored snapshot, then replays
     * all turns to restore the live game state. Correctly handles both fresh games
     * and sessions started from a mid-game snapshot.
     *
     * @param path source file path
     * @return restored session
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file is malformed or contains invalid turns
     */
    public static GameSession load(Path path) throws IOException {
        return GameSessionPersistence.load(path);
    }
}


