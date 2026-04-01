package engine.mcts;

import calcs.Calcs;
import core.GameState;
import core.Project;

import java.util.HashMap;

/**
 * Caches per-card EV scores for use in greedy/Boltzmann rollout purchase decisions.
 *
 * <p>Without caching, {@link Calcs#evPerRound} is called for every affordable card on
 * every turn of a rollout (~10 cards × 200 turns = 2000 calls at ~2ms each = ~4s).
 * This cache precomputes EV once per unique card type and refreshes every {@code refreshInterval}
 * turns, reducing the total to ~10 refreshes × 15 cards × 2ms = ~300ms.
 *
 * <p>Between refreshes the scores may be slightly stale (the portfolio changes by 1 card/turn),
 * but relative ordering rarely shifts, preserving the greedy/Boltzmann policy character.
 */
final class RolloutEvCache {

    private final HashMap<String, Double> scores = new HashMap<>();
    private final int refreshInterval;
    private int turnsUntilRefresh;

    /**
     * Creates a cache and populates it with initial scores.
     *
     * @param state            current game state
     * @param activePlayer     player index to evaluate for
     * @param refreshInterval  number of turns between cache rebuilds
     */
    RolloutEvCache(GameState state, int activePlayer, int refreshInterval) {
        this.refreshInterval = refreshInterval;
        this.turnsUntilRefresh = refreshInterval;
        rebuild(state, activePlayer);
    }

    /** Call once per turn. When the counter hits zero, the next {@link #getOrRefresh} will rebuild. */
    void tickTurn() {
        turnsUntilRefresh--;
    }

    /** Returns true if the cache should be rebuilt before use. */
    boolean needsRefresh() {
        return turnsUntilRefresh <= 0;
    }

    /**
     * Refreshes the cache if stale, then returns the cached EV for the given card.
     *
     * @param state        current game state (used only if refresh is needed)
     * @param activePlayer player index (used only if refresh is needed)
     * @param cardId       the card to look up
     * @return cached EV, or 0.0 if the card is unknown
     */
    double getOrRefresh(GameState state, int activePlayer, String cardId) {
        if (needsRefresh()) {
            rebuild(state, activePlayer);
            turnsUntilRefresh = refreshInterval;
        }
        return scores.getOrDefault(cardId, 0.0);
    }

    /**
     * Forces a full cache rebuild from the current game state.
     */
    void refresh(GameState state, int activePlayer) {
        rebuild(state, activePlayer);
        turnsUntilRefresh = refreshInterval;
    }

    private void rebuild(GameState state, int activePlayer) {
        scores.clear();
        for (Project p : state.getUnbuilt_projects()) {
            if (!scores.containsKey(p.getId())) {
                scores.put(p.getId(), Calcs.evPerRound(state, activePlayer, p));
            }
        }
    }
}
