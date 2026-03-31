package engine.mcts;

import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of how many market copies remain for each non-landmark card.
 *
 * <p>Semantics: a card is purchasable iff {@link #canPurchase(String)} returns true
 * (count > 0) AND the player has enough coins.
 *
 * <p>Landmarks have no supply limit and are NOT tracked here.
 *
 * <p>Starting cards (weizenfeld, bäckerei) are each owned one copy per player outside
 * the 6-copy shared pool, so their player-owned copies ARE subtracted from the supply
 * (each player's starter copy occupies one of the 6 market slots).
 *
 * <h2>Immutability</h2>
 * Every mutation operation returns a new {@code SupplyTracker}; the original is unchanged.
 */
public final class SupplyTracker {

    /** Card id → remaining supply copies (≥ 0). Landmarks absent from this map. */
    private final Map<String, Integer> counts;

    private SupplyTracker(Map<String, Integer> counts) {
        this.counts = Map.copyOf(counts);
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Builds a SupplyTracker from a live game state by counting owned copies across
     * all players and subtracting from {@link GameState#SUPPLY_PER_CARD}.
     *
     * @param state current game state
     * @return supply tracker reflecting current ownership
     */
    public static SupplyTracker fromGameState(GameState state) {
        // Start with full supply for every non-landmark card
        Map<String, Integer> counts = new HashMap<>();
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) {
                counts.put(p.getId(), GameState.SUPPLY_PER_CARD);
            }
        }
        // Subtract owned copies
        for (Player player : state.getPlayers()) {
            for (Project p : player.getOwned_projects()) {
                if (!p.isIs_grossprojekt()) {
                    counts.merge(p.getId(), -1, Integer::sum);
                }
            }
        }
        // Clamp negatives to 0 (safety)
        counts.replaceAll((id, cnt) -> Math.max(0, cnt));
        return new SupplyTracker(counts);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns the number of remaining market copies of the given card, or 0 if
     * the card is a landmark or unknown.
     */
    public int getCount(String cardId) {
        return counts.getOrDefault(cardId, 0);
    }

    /**
     * Returns true iff at least one copy of the given card remains in the supply.
     * Landmarks always return false here (they have no supply limit; callers should
     * check for landmarks separately using {@link Project#isIs_grossprojekt()}).
     */
    public boolean canPurchase(String cardId) {
        return counts.getOrDefault(cardId, 0) > 0;
    }

    // -------------------------------------------------------------------------
    // Mutation (returns new instance)
    // -------------------------------------------------------------------------

    /**
     * Returns a new SupplyTracker with the given card's count decremented by 1.
     * No-ops silently if the card is unknown or the count is already 0.
     */
    public SupplyTracker withPurchase(String cardId) {
        if (!counts.containsKey(cardId)) return this;
        int current = counts.get(cardId);
        if (current <= 0) return this;
        Map<String, Integer> updated = new HashMap<>(counts);
        updated.put(cardId, current - 1);
        return new SupplyTracker(updated);
    }
}
