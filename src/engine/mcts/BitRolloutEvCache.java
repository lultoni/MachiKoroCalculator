package engine.mcts;

import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;

import java.util.HashMap;

/**
 * Caches per-card EV scores for use in BitState-based greedy/Boltzmann rollout purchase decisions.
 *
 * <p>Same concept as {@link RolloutEvCache} but operates entirely on {@link BitState}:
 * uses {@link BitState#buildPlayerStats} and {@link BitState#buildOpponentCoins} for
 * fast rebuilds, and iterates {@link BitStateTranslator#CANDIDATE_ITERATION_ORDER}.
 *
 * <p>Between refreshes the scores may be slightly stale (the portfolio changes by ~1 card
 * every 2 turns), but relative ordering rarely shifts, preserving the policy character.
 */
final class BitRolloutEvCache {

    private final HashMap<String, Double> scores = new HashMap<>();
    private final int refreshInterval;
    private int turnsUntilRefresh;

    /**
     * Creates a cache and populates it with initial scores.
     *
     * @param bs               current BitState
     * @param activePlayer     player index to evaluate for
     * @param numPlayers       total players
     * @param refreshInterval  number of turns between cache rebuilds
     */
    BitRolloutEvCache(BitState bs, int activePlayer, int numPlayers, int refreshInterval) {
        this.refreshInterval = refreshInterval;
        this.turnsUntilRefresh = refreshInterval;
        rebuild(bs, activePlayer, numPlayers);
    }

    /** Call once per turn. When the counter hits zero, the next {@link #getOrRefresh} will rebuild. */
    void tickTurn() {
        turnsUntilRefresh--;
    }

    /**
     * Refreshes the cache if stale, then returns the cached EV for the given card ID.
     *
     * @param bs           current BitState (used only if refresh is needed)
     * @param activePlayer player index (used only if refresh is needed)
     * @param numPlayers   total players (used only if refresh is needed)
     * @param cardId       the card to look up
     * @return cached EV, or 0.0 if the card is unknown
     */
    double getOrRefresh(BitState bs, int activePlayer, int numPlayers, String cardId) {
        if (turnsUntilRefresh <= 0) {
            rebuild(bs, activePlayer, numPlayers);
            turnsUntilRefresh = refreshInterval;
        }
        return scores.getOrDefault(cardId, 0.0);
    }

    /**
     * Rebuilds all card scores using {@link CardIncome#contextualCardEvPerRound}.
     *
     * <p>PlayerStats and opponent coins are computed once from BitState, then each card
     * is evaluated with {@code stats.withExtra(candidate)} to capture synergy contributions.
     */
    private void rebuild(BitState bs, int activePlayer, int numPlayers) {
        scores.clear();
        CardIncome.PlayerStats baseStats = bs.buildPlayerStats(activePlayer);
        int[] oppCoins = bs.buildOpponentCoins(activePlayer);

        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;

            core.Project p = isPurple
                    ? BitStateTranslator.PURPLE_CARD_PROJECTS[idx]
                    : BitStateTranslator.NORMAL_CARD_PROJECTS[idx];

            if (!scores.containsKey(p.getId())) {
                CardIncome.PlayerStats withCandidate = baseStats.withExtra(p);
                scores.put(p.getId(),
                        CardIncome.contextualCardEvPerRound(p, withCandidate, numPlayers, oppCoins));
            }
        }
    }
}
