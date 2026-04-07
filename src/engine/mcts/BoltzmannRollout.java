package engine.mcts;

import calcs.Calcs;
import calcs.WinProbability;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Boltzmann (softmax) rollout policy for Variant B of the MCTS engine.
 *
 * <p>The tree phase is unchanged (full UCT). The rollout purchase policy samples
 * from a Boltzmann distribution over ROI scores with temperature T:
 * {@code P(card_i) ∝ exp(roi_i / T)}.
 *
 * <p>Landmark purchases are deterministic (always buy the cheapest affordable landmark first).
 * Dice count, Funkturm, and Bürohaus decisions use the same greedy rules as Variant A.
 *
 * <h2>Temperature semantics</h2>
 * <ul>
 *   <li>T → 0: pure greedy (argmax), equivalent to Variant A in the limit.</li>
 *   <li>T = 0.7: informed but stochastic — the default.</li>
 *   <li>T → ∞: uniform random, equivalent to Variant v1 in the limit.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} — safe for concurrent use.
 */
public final class BoltzmannRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int    BOLTZMANN_HORIZON  = 5;
    private static final double BOLTZMANN_DISCOUNT = 0.95;
    /** Number of turns between cache refreshes in the EV cache. */
    private static final int EV_CACHE_REFRESH = 20;

    private BoltzmannRollout() {}

    /**
     * Creates a {@link RolloutFn} that uses the Boltzmann policy with the given temperature.
     *
     * @param temperature Boltzmann temperature T (> 0)
     * @return a RolloutFn suitable for passing to {@link MctsTree}
     */
    public static RolloutFn withTemperature(double temperature) {
        return (state, supply, startingPlayer, playerPerspective) ->
                simulate(state, supply, startingPlayer, playerPerspective, temperature);
    }

    // -------------------------------------------------------------------------
    // Core simulation
    // -------------------------------------------------------------------------

    static double simulate(GameState startState, SupplyTracker startSupply,
                           int startingPlayer, int playerPerspective, double temperature) {
        GameState state      = startState.copy();
        SupplyTracker.MutableSupplyTracker supply = startSupply.toMutable();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n            = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount    = 0;
        int[] deltas     = new int[n];
        RolloutEvCache evCache = new RolloutEvCache(state, startingPlayer, EV_CACHE_REFRESH);

        while (turnCount < MctsRollout.MAX_TURNS) {
            Player active = state.getPlayers()[activePlayer];
            evCache.tickTurn();

            // ---- Dice count: greedy (same as Variant A) ----
            boolean hasBahnhof = active.hasProject("bahnhof");
            boolean twoDice    = hasBahnhof && hasHigh7to12Card(active);

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7); int d2 = rng.nextInt(1, 7);
                roll = d1 + d2; doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: greedy ----
            if (active.hasProject("funkturm")) {
                double cur    = computeRollIncome(state, activePlayer, roll, deltas);
                double expEV  = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
                if (cur < expEV) {
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7); int d2 = rng.nextInt(1, 7);
                        roll = d1 + d2; doubles = (d1 == d2);
                    } else {
                        roll = rng.nextInt(1, 7); doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus: greedy ----
            if (active.hasProject("bürohaus") && roll == 6) {
                core.BürohausLogic.executeSwap(state, activePlayer);
            }

            // ---- Purchase: Boltzmann ----
            applyPurchaseBoltzmann(state, supply, activePlayer, temperature, rng, evCache);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && doubles) {
                playBonusTurn(state, supply, activePlayer, temperature, rng, deltas, evCache);
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Boltzmann purchase
    // -------------------------------------------------------------------------

    private static void applyPurchaseBoltzmann(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                         int activePlayer, double temperature,
                                                         ThreadLocalRandom rng, RolloutEvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // Instant-win check: if buying a landmark wins the game, buy it immediately.
        Project winLandmark = GameState.findInstantWinLandmark(active);
        if (winLandmark != null) {
            active.setCoins(coins - winLandmark.getCost());
            active.addProject(winLandmark);
            return;
        }

        // 1. Landmark priority (deterministic — same as greedy)
        int cheapestCost = Integer.MAX_VALUE;
        Project landmarkToBuy = null;
        for (String lmId : LANDMARK_IDS) {
            if (!active.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && coins >= lm.getCost() && lm.getCost() < cheapestCost) {
                    cheapestCost   = lm.getCost();
                    landmarkToBuy = lm;
                }
            }
        }
        if (landmarkToBuy != null) {
            active.setCoins(coins - landmarkToBuy.getCost());
            active.addProject(landmarkToBuy);
            return;
        }

        // 2. Boltzmann sampling over non-landmark cards + save
        //    Count eligible cards first to size arrays
        int candidateCount = 1; // save option
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            candidateCount++;
        }

        Project[] candidates = new Project[candidateCount];
        double[] scores      = new double[candidateCount];
        candidates[0] = null; // save sentinel (null = save)
        scores[0]     = 0.0;

        int idx = 1;
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            double ev  = evCache.getOrRefresh(state, activePlayer, p.getId());
            double roi = ev * Calcs.geometricSum(BOLTZMANN_HORIZON, BOLTZMANN_DISCOUNT) - p.getCost();
            candidates[idx] = p;
            scores[idx]     = roi;
            idx++;
        }

        // Compute Boltzmann probabilities: P_i ∝ exp(score_i / T)
        double sum = 0.0;
        for (int i = 0; i < candidateCount; i++) {
            scores[i] = Math.exp(scores[i] / temperature);
            sum += scores[i];
        }
        if (sum < 1e-12) {
            sum = candidateCount;
            for (int i = 0; i < candidateCount; i++) scores[i] = 1.0;
        }

        // Sample
        double r = rng.nextDouble() * sum;
        double cumulative = 0.0;
        int chosen = 0;
        for (int i = 0; i < candidateCount; i++) {
            cumulative += scores[i];
            if (r <= cumulative) { chosen = i; break; }
        }

        Project card = candidates[chosen];
        if (card == null) return; // save

        active.setCoins(coins - card.getCost());
        active.addProject(card);
        supply.purchase(card.getId());
    }

    // -------------------------------------------------------------------------
    // Shared helpers (same as GreedyRollout)
    // -------------------------------------------------------------------------

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

    private static double computeExpectedRollIncome(GameState state, int playerIndex, boolean twoDice, int[] deltas) {
        double ev = 0.0;
        if (twoDice) {
            for (int r = 2; r <= 12; r++) ev += core.CardIncome.P2[r] * computeRollIncome(state, playerIndex, r, deltas);
        } else {
            for (int r = 1; r <= 6; r++) ev += core.CardIncome.P1[r] * computeRollIncome(state, playerIndex, r, deltas);
        }
        return ev;
    }

    private static void playBonusTurn(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                int activePlayer, double temperature,
                                                ThreadLocalRandom rng, int[] deltas, RolloutEvCache evCache) {
        Player active   = state.getPlayers()[activePlayer];
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean twoDice    = hasBahnhof && hasHigh7to12Card(active);
        int n = state.getPlayers().length;

        int roll = twoDice ? rng.nextInt(1,7) + rng.nextInt(1,7) : rng.nextInt(1,7);

        if (active.hasProject("funkturm")) {
            double cur = computeRollIncome(state, activePlayer, roll, deltas);
            double exp = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
            if (cur < exp) roll = twoDice ? rng.nextInt(1,7) + rng.nextInt(1,7) : rng.nextInt(1,7);
        }

        RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }
        if (active.hasProject("bürohaus") && roll == 6) core.BürohausLogic.executeSwap(state, activePlayer);
        applyPurchaseBoltzmann(state, supply, activePlayer, temperature, rng, evCache);
    }
}
