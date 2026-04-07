package engine.creator;

import calcs.Calcs;
import calcs.WinProbability;
import core.BürohausLogic;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;
import engine.mcts.MctsRollout;
import engine.mcts.RolloutFn;
import engine.mcts.SupplyTracker;

import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creator's own rollout policy for Monte Carlo simulations.
 *
 * <p>Uses a lightweight heuristic for purchase decisions during rollouts — NOT the
 * full {@link CreatorScorer} (which is too expensive for rollout-speed requirements).
 * The key difference from {@link engine.mcts.GreedyRollout} is that landmarks are
 * evaluated by marginal EV contribution rather than always buying cheapest-first.
 * A landmark that doesn't help the current portfolio (e.g., Bahnhof with no 7-12 cards)
 * is skipped in favor of income-building cards.
 *
 * <h2>Decision policy</h2>
 * <ul>
 *   <li><b>Dice</b>: 2d6 if Bahnhof + useful 7-12 card; else 1d6.</li>
 *   <li><b>Funkturm</b>: keep if income ≥ expected reroll EV.</li>
 *   <li><b>Bürohaus</b>: greedy best swap via {@link BürohausLogic#executeSwap}.</li>
 *   <li><b>Purchase (instant-win)</b>: always buy winning landmark.</li>
 *   <li><b>Purchase (landmark)</b>: buy highest-value landmark where value = marginal EV.
 *       Skip landmarks with marginal EV below a threshold.</li>
 *   <li><b>Purchase (card)</b>: highest {@code contextualCardEvPerRound × geometricSum − cost}.</li>
 *   <li><b>Save</b>: if no card has positive net value.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom}. All state is local to the simulate call.
 */
public final class CreatorRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int    HORIZON  = 5;
    private static final double DISCOUNT = 0.95;
    private static final int    EV_CACHE_REFRESH = 40;

    private CreatorRollout() {}

    /**
     * Returns a {@link RolloutFn} using the Creator policy.
     */
    public static RolloutFn asRolloutFn() {
        return CreatorRollout::simulate;
    }

    /**
     * Simulates one full game from the given leaf state using the Creator policy.
     */
    public static double simulate(GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
        GameState state = startState.copy();
        SupplyTracker.MutableSupplyTracker supply = startSupply.toMutable();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount = 0;
        int[] deltas = new int[n];
        EvCache evCache = new EvCache(state, startingPlayer, EV_CACHE_REFRESH);

        while (turnCount < MctsRollout.MAX_TURNS) {
            Player active = state.getPlayers()[activePlayer];
            evCache.tickTurn();

            // ---- Dice count ----
            boolean hasBahnhof = active.hasProject("bahnhof");
            boolean twoDice = hasBahnhof && hasHigh7to12Card(active);

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7);
                int d2 = rng.nextInt(1, 7);
                roll = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: keep if income >= expected reroll income ----
            if (active.hasProject("funkturm")) {
                double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
                double rerollEV = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
                if (currentIncome < rerollEV) {
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7);
                        int d2 = rng.nextInt(1, 7);
                        roll = d1 + d2;
                        doubles = (d1 == d2);
                    } else {
                        roll = rng.nextInt(1, 7);
                        doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus ----
            if (active.hasProject("bürohaus") && roll == 6) {
                BürohausLogic.executeSwap(state, activePlayer);
            }

            // ---- Purchase ----
            applyPurchase(state, supply, activePlayer, evCache);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (state.getPlayers()[activePlayer].hasProject("freizeitpark") && doubles) {
                playBonusTurn(state, supply, activePlayer, rng, deltas, evCache);
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }

    // =====================================================================
    // Purchase logic — the Creator difference
    // =====================================================================

    /**
     * Creator purchase policy: instant-win → cheapest affordable landmark → best net-EV card → save.
     *
     * <p>Landmarks use cheapest-first ordering (like GreedyRollout) with a coverage check:
     * Bahnhof is skipped if the player has no non-red cards activating on 7-12. This avoids
     * the expensive per-turn marginal-EV analysis while preserving the key Creator insight
     * that Bahnhof without high-range cards is wasteful.
     */
    private static void applyPurchase(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                      int activePlayer, EvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // 1. Instant-win check
        Project winLandmark = GameState.findInstantWinLandmark(active);
        if (winLandmark != null) {
            active.setCoins(coins - winLandmark.getCost());
            active.addProject(winLandmark);
            return;
        }

        // 2. Cheapest affordable landmark (skip Bahnhof without 7-12 coverage)
        Project bestLandmark = null;
        int bestCost = Integer.MAX_VALUE;
        for (String lmId : LANDMARK_IDS) {
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null || coins < lm.getCost()) continue;
            // Skip Bahnhof if no non-red 7-12 cards
            if ("bahnhof".equals(lmId) && !hasHigh7to12Card(active)) continue;
            // Skip Freizeitpark if player doesn't own Bahnhof (doubles only with 2d6)
            if ("freizeitpark".equals(lmId) && !active.hasProject("bahnhof")) continue;
            if (lm.getCost() < bestCost) {
                bestCost = lm.getCost();
                bestLandmark = lm;
            }
        }

        if (bestLandmark != null) {
            active.setCoins(coins - bestLandmark.getCost());
            active.addProject(bestLandmark);
            return;
        }

        // 3. Best non-landmark card by net EV
        Project bestCard = null;
        double bestScore = 0.0;
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            double ev = evCache.getOrRefresh(state, activePlayer, p.getId());
            double net = ev * Calcs.geometricSum(HORIZON, DISCOUNT) - p.getCost();
            if (net > bestScore) {
                bestScore = net;
                bestCard = p;
            }
        }
        if (bestCard != null) {
            active.setCoins(coins - bestCard.getCost());
            active.addProject(bestCard);
            supply.purchase(bestCard.getId());
        }
        // else: save
    }

    // =====================================================================
    // Bonus turn
    // =====================================================================

    private static void playBonusTurn(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                      int activePlayer, ThreadLocalRandom rng,
                                      int[] deltas, EvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean twoDice = hasBahnhof && hasHigh7to12Card(active);
        int n = state.getPlayers().length;

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        if (active.hasProject("funkturm")) {
            double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
            double rerollEV = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
            if (currentIncome < rerollEV) {
                roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
            }
        }

        RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }

        if (active.hasProject("bürohaus") && roll == 6) {
            BürohausLogic.executeSwap(state, activePlayer);
        }

        applyPurchase(state, supply, activePlayer, evCache);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static boolean hasHigh7to12Card(Player p) {
        for (Project card : p.getOwned_projects()) {
            for (int act : card.getDice_activation()) {
                if (act >= 7 && act <= 12) return true;
            }
        }
        return false;
    }

    private static double computeRollIncome(GameState state, int playerIndex, int roll, int[] deltas) {
        RollResolver.computeAllDeltasForRoll(state, playerIndex, roll, deltas);
        return deltas[playerIndex];
    }

    private static double computeExpectedRollIncome(GameState state, int playerIndex,
                                                     boolean twoDice, int[] deltas) {
        double ev = 0.0;
        if (twoDice) {
            for (int r = 2; r <= 12; r++) {
                ev += CardIncome.P2[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                ev += CardIncome.P1[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        }
        return ev;
    }

    // =====================================================================
    // Inline EV cache (same pattern as RolloutEvCache, but in this package)
    // =====================================================================

    /**
     * Lightweight per-card EV cache for rollout purchase decisions.
     * Uses {@link CardIncome#contextualCardEvPerRound} with periodic refresh.
     */
    static final class EvCache {
        private final HashMap<String, Double> scores = new HashMap<>();
        private final int refreshInterval;
        private int turnsUntilRefresh;

        EvCache(GameState state, int activePlayer, int refreshInterval) {
            this.refreshInterval = refreshInterval;
            this.turnsUntilRefresh = refreshInterval;
            rebuild(state, activePlayer);
        }

        void tickTurn() { turnsUntilRefresh--; }

        double getOrRefresh(GameState state, int activePlayer, String cardId) {
            if (turnsUntilRefresh <= 0) {
                rebuild(state, activePlayer);
                turnsUntilRefresh = refreshInterval;
            }
            return scores.getOrDefault(cardId, 0.0);
        }

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
}
