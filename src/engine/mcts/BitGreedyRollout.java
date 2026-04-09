package engine.mcts;

import calcs.Calcs;
import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import core.GameState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Greedy rollout policy using {@link BitState} internally.
 *
 * <p>Drop-in replacement for {@link GreedyRollout}: satisfies the {@link RolloutFn} signature
 * and converts to BitState at entry. All turn-level mutation is bitwise.
 *
 * <h2>Greedy decisions</h2>
 * <ul>
 *   <li><b>Dice</b> — 2d6 if Bahnhof AND has high-range card; else 1d6.</li>
 *   <li><b>Funkturm</b> — keep if current-roll income ≥ expected reroll income.</li>
 *   <li><b>Bürohaus</b> — greedy best swap via {@link BitState#executeGreedySwap}.</li>
 *   <li><b>Purchase</b> — landmark priority (cheapest unowned), else best ROI from cache.</li>
 * </ul>
 */
public final class BitGreedyRollout {

    private static final int GREEDY_HORIZON = 5;
    private static final double GREEDY_DISCOUNT = 0.95;
    private static final int EV_CACHE_REFRESH = 40;

    private BitGreedyRollout() {}

    /**
     * Runs a greedy rollout from {@code startState} until the game ends or
     * {@link MctsRollout#MAX_TURNS} is reached.
     */
    public static double simulate(GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
        BitState bs = BitState.fromGameState(startState);
        int[] supply = bs.buildSupplyArray();
        int n = startState.getPlayers().length;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int activePlayer = startingPlayer;
        int turnCount = 0;
        BitRolloutEvCache evCache = new BitRolloutEvCache(bs, startingPlayer, n, EV_CACHE_REFRESH);

        while (turnCount < MctsRollout.MAX_TURNS) {
            evCache.tickTurn();

            // ---- Dice: greedy ----
            boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
            boolean twoDice = hasBahnhof && bs.hasHighRangeCard(activePlayer);

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

            // ---- Funkturm: greedy (keep if income >= expected reroll) ----
            if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FT)) {
                int currentIncome = bs.computeActivePlayerRollIncome(activePlayer, roll);
                double rerollEV = computeExpectedRollIncome(bs, activePlayer, twoDice);
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
            bs.applyRoll(activePlayer, roll);

            // ---- Bürohaus: greedy swap ----
            if (bs.hasPurple(activePlayer, 2) && roll == 6) {
                bs.executeGreedySwap(activePlayer);
            }

            // ---- Purchase: greedy ----
            applyPurchaseGreedy(bs, supply, activePlayer, n, evCache);

            // ---- Win check ----
            if (bs.hasWon(activePlayer)) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FZP) && doubles) {
                playBonusTurn(bs, supply, activePlayer, n, rng, evCache);
                if (bs.hasWon(activePlayer)) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        return WinProbability.computeBaselineWinProb(bs.toGameState(), playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Funkturm helpers
    // -------------------------------------------------------------------------

    private static double computeExpectedRollIncome(BitState bs, int activePlayer, boolean twoDice) {
        double ev = 0.0;
        if (twoDice) {
            for (int r = 2; r <= 12; r++) {
                ev += CardIncome.P2[r] * bs.computeActivePlayerRollIncome(activePlayer, r);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                ev += CardIncome.P1[r] * bs.computeActivePlayerRollIncome(activePlayer, r);
            }
        }
        return ev;
    }

    // -------------------------------------------------------------------------
    // Greedy purchase
    // -------------------------------------------------------------------------

    static void applyPurchaseGreedy(BitState bs, int[] supply, int activePlayer,
                                    int n, BitRolloutEvCache evCache) {
        int coins = bs.getCoins(activePlayer);

        // Instant-win check
        int winLm = bs.findInstantWinLandmark(activePlayer);
        if (winLm >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[winLm]);
            bs.setLandmark(activePlayer, winLm);
            return;
        }

        // 1. Landmark priority: buy cheapest unowned affordable landmark
        int cheapestCost = Integer.MAX_VALUE;
        int landmarkToBuy = -1;
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (!bs.hasLandmark(activePlayer, li)
                    && coins >= BitStateTranslator.LANDMARK_COSTS[li]
                    && BitStateTranslator.LANDMARK_COSTS[li] < cheapestCost) {
                cheapestCost = BitStateTranslator.LANDMARK_COSTS[li];
                landmarkToBuy = li;
            }
        }
        if (landmarkToBuy >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[landmarkToBuy]);
            bs.setLandmark(activePlayer, landmarkToBuy);
            return;
        }

        // 2. Best non-landmark card by net EV
        double geoSum = Calcs.geometricSum(GREEDY_HORIZON, GREEDY_DISCOUNT);
        int bestCandIdx = -1;
        double bestScore = 0.0; // must be positive to be worth buying

        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;

            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                String cardId = BitStateTranslator.PURPLE_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                double net = ev * geoSum - BitStateTranslator.PURPLE_CARD_COSTS[idx];
                if (net > bestScore) { bestScore = net; bestCandIdx = ci; }
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                String cardId = BitStateTranslator.NORMAL_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                double net = ev * geoSum - BitStateTranslator.NORMAL_CARD_COSTS[idx];
                if (net > bestScore) { bestScore = net; bestCandIdx = ci; }
            }
        }

        if (bestCandIdx >= 0) {
            boolean isPurple = bestCandIdx >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? bestCandIdx - BitStateTranslator.NUM_NORMAL_CARDS : bestCandIdx;
            if (isPurple) {
                bs.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[idx]);
                bs.setPurple(activePlayer, idx);
            } else {
                bs.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[idx]);
                bs.addCard(activePlayer, idx);
                supply[idx]--;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bonus turn
    // -------------------------------------------------------------------------

    private static void playBonusTurn(BitState bs, int[] supply, int activePlayer,
                                      int n, ThreadLocalRandom rng, BitRolloutEvCache evCache) {
        boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
        boolean twoDice = hasBahnhof && bs.hasHighRangeCard(activePlayer);

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        // Funkturm: greedy
        if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FT)) {
            int currentIncome = bs.computeActivePlayerRollIncome(activePlayer, roll);
            double rerollEV = computeExpectedRollIncome(bs, activePlayer, twoDice);
            if (currentIncome < rerollEV) {
                roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
            }
        }

        bs.applyRoll(activePlayer, roll);

        if (bs.hasPurple(activePlayer, 2) && roll == 6) {
            bs.executeGreedySwap(activePlayer);
        }

        applyPurchaseGreedy(bs, supply, activePlayer, n, evCache);
    }
}
