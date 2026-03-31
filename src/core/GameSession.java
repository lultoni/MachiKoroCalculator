package core;

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
 *       whatever the snapshot describes, with an empty history from that point forward.</li>
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

        // Apply coin effects from the roll using the authoritative roll resolver.
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, pi, roll);
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }

        // Apply purchase
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
                // Remove from pool only when all physical copies are now owned
                int totalOwned = 0;
                for (Player p : players) {
                    for (Project owned : p.getOwned_projects()) {
                        if (owned.getId().equals(card.getId())) totalOwned++;
                    }
                }
                if (totalOwned >= GameState.SUPPLY_PER_CARD) {
                    state.getUnbuilt_projects().remove(card);
                }
            }

            buyer.getOwned_projects().add(card);
            buyer.setCoins(buyer.getCoins() - card.getCost());

            // Check win condition: owning all 4 landmarks ends the game immediately
            if (GameState.hasWon(buyer)) {
                finished = true;
                winnerIndex = pi;
            }
        }

        // Store an augmented copy of the record that includes the computed coin deltas.
        TurnRecord stored = (record.coinDeltas != null) ? record
                : new TurnRecord(record.playerIndex, record.roll, record.bought, record.isDoubles, deltas,
                        record.swappedAway, record.swappedIn, record.swapOppPlayerIndex, record.diceCount);
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
        if (!thisWasBonusTurn) {
            effectiveTurnCount++;
        }
    }

    /**
     * Executes the bürohaus card swap for the active player and amends the last TurnRecord
     * to record which cards were exchanged.
     *
     * <p>Must be called immediately after {@link #applyTurn} on a roll=6 turn where the
     * active player owns bürohaus. No-ops if no beneficial swap exists.
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
                        c.worstOwn(), c.bestOpp(), c.bestOppPlayer(), last.diceCount));
    }

    /**
     * Executes a user-chosen bürohaus card swap and amends the last TurnRecord.
     *
     * @param playerIndex    the active player
     * @param ownCard        card the player gives away
     * @param oppPlayerIndex the opponent providing the card
     * @param oppCard        card received from the opponent
     */
    public void applyBürohausSwap(int playerIndex, Project ownCard,
                                   int oppPlayerIndex, Project oppCard) {
        BürohausLogic.executeSwap(state, playerIndex, ownCard, oppPlayerIndex, oppCard);

        TurnRecord last = history.get(history.size() - 1);
        history.set(history.size() - 1,
                new TurnRecord(last.playerIndex, last.roll, last.bought,
                        last.isDoubles, last.coinDeltas,
                        ownCard, oppCard, oppPlayerIndex, last.diceCount));
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
                if (r.swapOppPlayerIndex >= 0) {
                    // User-chosen swap: replay with exact cards and opponent
                    BürohausLogic.executeSwap(state, r.playerIndex,
                            r.swappedAway, r.swapOppPlayerIndex, r.swappedIn);
                } else {
                    // Legacy greedy swap
                    BürohausLogic.executeSwap(state, r.playerIndex);
                }
                TurnRecord last = history.get(history.size() - 1);
                history.set(history.size() - 1,
                        new TurnRecord(last.playerIndex, last.roll, last.bought,
                                last.isDoubles, last.coinDeltas,
                                r.swappedAway, r.swappedIn, r.swapOppPlayerIndex, r.diceCount));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot conversion
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link GameStateBuilder} pre-populated with the current live state.
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
     * roll and the active player owned both Bahnhof and Freizeitpark, the same player
     * gets a bonus second turn. The second turn itself cannot chain further doubles.
     */
    public int nextPlayerIndex() {
        if (bonusTurnPending && !history.isEmpty()) {
            return history.get(history.size() - 1).playerIndex;
        }
        return effectiveTurnCount % state.getPlayers().length;
    }

    /** Returns the number of non-bonus turns completed so far across all players. */
    public int getEffectiveTurnCount() {
        return effectiveTurnCount;
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
    // Persistence
    // -------------------------------------------------------------------------

    /**
     * Saves this session to a JSON file at {@code path}.
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
     * @param path source file path
     * @return restored session
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file is malformed or contains invalid turns
     */
    public static GameSession load(Path path) throws IOException {
        return GameSessionPersistence.load(path);
    }
}
