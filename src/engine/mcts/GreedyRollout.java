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
 * Greedy rollout policy for Variant A of the MCTS engine.
 *
 * <p>The tree phase is unchanged (full UCT). Only the rollout policy differs from
 * {@link MctsRollout}: purchase decisions use a greedy heuristic rather than uniform random.
 *
 * <h2>Greedy decisions</h2>
 * <ul>
 *   <li><b>Dice count</b> — 2d6 if player owns Bahnhof AND has at least one card activating
 *       on a 7–12 roll; else 1d6.</li>
 *   <li><b>Roll</b> — uniform random (same as v1; greediness is in the purchase step).</li>
 *   <li><b>Funkturm</b> — keep if current-roll income ≥ expected reroll income; else reroll.</li>
 *   <li><b>Bürohaus</b> — execute {@link core.BürohausLogic#bestSwap} (greedy best swap by EV).</li>
 *   <li><b>Purchase</b> — landmark priority (buy the cheapest unowned landmark if affordable);
 *       else card with highest {@code contextualCardEvPerRound × geometricSum − cost};
 *       else save if no card with positive net value is affordable.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} for the roll — safe for concurrent use.
 */
public final class GreedyRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int    GREEDY_HORIZON  = 5;
    private static final double GREEDY_DISCOUNT = 0.95;

    /** Number of turns between cache refreshes in the EV cache. */
    private static final int EV_CACHE_REFRESH = 40;

    private GreedyRollout() {}

    /**
     * Implements the {@link RolloutFn} contract using the greedy policy.
     */
    public static double simulate(GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
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

            // ---- Dice count: 2d6 if Bahnhof and has useful 7-12 card ----
            boolean hasBahnhof  = active.hasProject("bahnhof");
            boolean twoDice     = hasBahnhof && hasHigh7to12Card(active);

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7);
                int d2 = rng.nextInt(1, 7);
                roll    = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: keep if income >= expected reroll income ----
            if (active.hasProject("funkturm")) {
                double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
                double rerollEV      = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
                if (currentIncome < rerollEV) {
                    // Reroll
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7);
                        int d2 = rng.nextInt(1, 7);
                        roll    = d1 + d2;
                        doubles = (d1 == d2);
                    } else {
                        roll    = rng.nextInt(1, 7);
                        doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus: greedy best swap ----
            if (active.hasProject("bürohaus") && roll == 6) {
                core.BürohausLogic.executeSwap(state, activePlayer);
            }

            // ---- Purchase: greedy ----
            applyPurchaseGreedy(state, supply, activePlayer, evCache);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && doubles) {
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

    // -------------------------------------------------------------------------
    // Private helpers
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
            for (int r = 2; r <= 12; r++) {
                ev += core.CardIncome.P2[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                ev += core.CardIncome.P1[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        }
        return ev;
    }

    /**
     * Greedy purchase: landmark priority, then best net EV (from cache), else save.
     */
    private static void applyPurchaseGreedy(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                      int activePlayer, RolloutEvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // Instant-win check: if buying a landmark wins the game, buy it immediately.
        Project winLandmark = GameState.findInstantWinLandmark(active);
        if (winLandmark != null) {
            active.setCoins(coins - winLandmark.getCost());
            active.addProject(winLandmark);
            return;
        }

        // 1. Landmark priority: buy the cheapest unowned affordable landmark
        int cheapestCost = Integer.MAX_VALUE;
        Project landmarkToBuy = null;
        for (String lmId : LANDMARK_IDS) {
            if (!active.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && coins >= lm.getCost() && lm.getCost() < cheapestCost) {
                    cheapestCost    = lm.getCost();
                    landmarkToBuy  = lm;
                }
            }
        }
        if (landmarkToBuy != null) {
            active.setCoins(coins - landmarkToBuy.getCost());
            active.addProject(landmarkToBuy);
            return; // landmarks don't use supply
        }

        // 2. Best non-landmark card by net EV: evPerRound × geometricSum − cost
        Project bestCard  = null;
        double  bestScore = 0.0; // must be positive to be worth buying
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            // Purple cards (lila) are unique — max 1 per player per type
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            double ev  = evCache.getOrRefresh(state, activePlayer, p.getId());
            double net = ev * Calcs.geometricSum(GREEDY_HORIZON, GREEDY_DISCOUNT) - p.getCost();
            if (net > bestScore) {
                bestScore = net;
                bestCard  = p;
            }
        }
        if (bestCard != null) {
            active.setCoins(coins - bestCard.getCost());
            active.addProject(bestCard);
            supply.purchase(bestCard.getId());
        }
        // else: save (no-op)
    }

    private static void playBonusTurn(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                int activePlayer, ThreadLocalRandom rng,
                                                int[] deltas, RolloutEvCache evCache) {
        Player active   = state.getPlayers()[activePlayer];
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean twoDice    = hasBahnhof && hasHigh7to12Card(active);
        int n = state.getPlayers().length;

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        if (active.hasProject("funkturm")) {
            double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
            double rerollEV      = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
            if (currentIncome < rerollEV) {
                roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
            }
        }

        RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }

        if (active.hasProject("bürohaus") && roll == 6) {
            core.BürohausLogic.executeSwap(state, activePlayer);
        }

        applyPurchaseGreedy(state, supply, activePlayer, evCache);
    }
}
