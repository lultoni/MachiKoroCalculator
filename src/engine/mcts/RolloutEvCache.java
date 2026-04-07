package engine.mcts;

import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;

import java.util.HashMap;

/**
 * Caches per-card EV scores for use in greedy/Boltzmann rollout purchase decisions.
 *
 * <p>Uses {@link CardIncome#contextualCardEvPerRound} for fast synergy-aware per-card
 * marginal EV, with {@link CardIncome.PlayerStats} and opponent coins computed once per
 * refresh. This is dramatically cheaper than the full {@code Calcs.evPerRound()} (which
 * includes coin projection, opponent-turn tracking, and repeated roll-gain cache building)
 * while preserving relative ranking accuracy for greedy/Boltzmann purchase decisions.
 *
 * <p>Between refreshes the scores may be slightly stale (the portfolio changes by ~1 card
 * every 2 turns), but relative ordering rarely shifts, preserving the policy character.
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

    /**
     * Rebuilds all card scores using {@link CardIncome#contextualCardEvPerRound}.
     *
     * <p>PlayerStats and opponent coins are computed once, then each card is evaluated
     * with {@code stats.withExtra(candidate)} to capture synergy contributions (e.g.
     * Markthalle's income depends on food count including the candidate itself).
     */
    private void rebuild(GameState state, int activePlayer) {
        scores.clear();
        Player player = state.getPlayers()[activePlayer];
        CardIncome.PlayerStats baseStats = CardIncome.PlayerStats.of(player);
        int numPlayers = state.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), activePlayer);

        for (Project p : state.getUnbuilt_projects()) {
            if (!scores.containsKey(p.getId())) {
                CardIncome.PlayerStats withCandidate = baseStats.withExtra(p);
                scores.put(p.getId(),
                        CardIncome.contextualCardEvPerRound(p, withCandidate, numPlayers, oppCoins));
            }
        }
    }
}
