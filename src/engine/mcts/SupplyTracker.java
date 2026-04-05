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
 * <p>Starting cards (weizenfeld, bäckerei) are given to each player outside the 6-copy
 * market pool — they do NOT count against the supply. Only purchased copies reduce
 * the market count.
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
     * all players, subtracting starter copies (which are outside the market pool),
     * and computing remaining market supply.
     *
     * <p><b>Supply logic (DO NOT CHANGE without understanding):</b>
     * <ol>
     *   <li>Start at 6 (SUPPLY_PER_CARD) for each non-landmark card.</li>
     *   <li>Subtract ALL owned copies (including starters, since they're in owned_projects).</li>
     *   <li>Add back starter copies (numPlayers for weizenfeld/bäckerei, 0 for others).</li>
     * </ol>
     * Net effect: only purchased (non-starter) copies reduce the market supply.
     * Example (2 players, each owns 1 starter Weizenfeld + 1 purchased Weizenfeld):
     * 6 − 4 (owned) + 2 (starters) = 4 remaining. Correct.
     *
     * @param state current game state
     * @return supply tracker reflecting current market availability
     */
    public static SupplyTracker fromGameState(GameState state) {
        int numPlayers = state.getPlayers().length;
        // Start with full supply for every non-landmark card
        Map<String, Integer> counts = new HashMap<>();
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) {
                counts.put(p.getId(), GameState.SUPPLY_PER_CARD);
            }
        }
        // Subtract owned copies, but NOT starter copies (they are outside the market pool)
        for (Player player : state.getPlayers()) {
            for (Project p : player.getOwned_projects()) {
                if (!p.isIs_grossprojekt()) {
                    counts.merge(p.getId(), -1, Integer::sum);
                }
            }
        }
        // Add back starter copies (they don't come from the market)
        for (String cardId : counts.keySet()) {
            int starters = GameState.starterCopies(cardId, numPlayers);
            if (starters > 0) {
                counts.merge(cardId, starters, Integer::sum);
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

    // -------------------------------------------------------------------------
    // Mutable variant for rollout hot paths
    // -------------------------------------------------------------------------

    /**
     * Creates a mutable copy of this tracker for use in rollout simulations.
     * The mutable tracker avoids HashMap allocation on every purchase by
     * using an in-place {@code int[]} array.
     */
    public MutableSupplyTracker toMutable() {
        return new MutableSupplyTracker(counts);
    }

    /**
     * Mutable, array-backed supply tracker for rollout hot paths.
     *
     * <p>Backed by a flat {@code int[]} indexed by a card-ID-to-index map built
     * once per instance. {@link #purchase} and {@link #undoPurchase} are O(1)
     * with no allocation. Use {@link SupplyTracker#toMutable()} to create.
     *
     * <p>Not thread-safe. Intended for single-threaded rollout loops.
     */
    public static final class MutableSupplyTracker {

        private final String[] ids;
        private final Map<String, Integer> idToIndex;
        private final int[] supply;

        private MutableSupplyTracker(Map<String, Integer> counts) {
            int n = counts.size();
            ids = new String[n];
            idToIndex = new HashMap<>(n * 2);
            supply = new int[n];
            int i = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                ids[i] = e.getKey();
                idToIndex.put(e.getKey(), i);
                supply[i] = e.getValue();
                i++;
            }
        }

        /** Returns true iff at least one copy remains. Landmarks always return false. */
        public boolean canPurchase(String cardId) {
            Integer idx = idToIndex.get(cardId);
            return idx != null && supply[idx] > 0;
        }

        /** Returns remaining supply count, or 0 if unknown/landmark. */
        public int getCount(String cardId) {
            Integer idx = idToIndex.get(cardId);
            return idx != null ? supply[idx] : 0;
        }

        /** Decrements supply for a purchase. No-op if unknown or already 0. */
        public void purchase(String cardId) {
            Integer idx = idToIndex.get(cardId);
            if (idx != null && supply[idx] > 0) {
                supply[idx]--;
            }
        }

        /** Increments supply (undo a purchase). No-op if unknown. */
        public void undoPurchase(String cardId) {
            Integer idx = idToIndex.get(cardId);
            if (idx != null) {
                supply[idx]++;
            }
        }
    }
}
