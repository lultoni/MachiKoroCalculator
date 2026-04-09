package engine.mcts;

import calcs.Calcs;
import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import core.GameState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Boltzmann (softmax) rollout policy using {@link BitState} internally.
 *
 * <p>Drop-in replacement for {@link BoltzmannRollout}: satisfies the {@link RolloutFn} signature
 * and converts to BitState at entry. All turn-level mutation is bitwise.
 *
 * <h2>Policy</h2>
 * Dice, Funkturm, and Bürohaus decisions use the same greedy rules as {@link BitGreedyRollout}.
 * Purchase decisions use Boltzmann sampling: {@code P(card_i) ∝ exp(roi_i / T)}.
 * Landmarks are always bought deterministically (cheapest first).
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} and ThreadLocal pre-allocated buffers.
 */
public final class BitBoltzmannRollout {

    private static final int BOLTZMANN_HORIZON = 5;
    private static final double BOLTZMANN_DISCOUNT = 0.95;
    private static final int EV_CACHE_REFRESH = 40;

    /** Max candidate count for pre-allocated buffers (15 cards + 1 save). */
    private static final int MAX_CANDIDATES = 16;
    private static final ThreadLocal<int[]> CAND_IDX_BUF =
            ThreadLocal.withInitial(() -> new int[MAX_CANDIDATES]);
    private static final ThreadLocal<double[]> SCORES_BUF =
            ThreadLocal.withInitial(() -> new double[MAX_CANDIDATES]);

    private BitBoltzmannRollout() {}

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

            // ---- Funkturm: greedy ----
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

            // ---- Bürohaus: greedy ----
            if (bs.hasPurple(activePlayer, 2) && roll == 6) {
                bs.executeGreedySwap(activePlayer);
            }

            // ---- Purchase: Boltzmann ----
            applyPurchaseBoltzmann(bs, supply, activePlayer, n, temperature, rng, evCache);

            // ---- Win check ----
            if (bs.hasWon(activePlayer)) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FZP) && doubles) {
                playBonusTurn(bs, supply, activePlayer, n, temperature, rng, evCache);
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
    // Boltzmann purchase
    // -------------------------------------------------------------------------

    private static void applyPurchaseBoltzmann(BitState bs, int[] supply, int activePlayer,
                                               int n, double temperature,
                                               ThreadLocalRandom rng, BitRolloutEvCache evCache) {
        int coins = bs.getCoins(activePlayer);

        // Instant-win check
        int winLm = bs.findInstantWinLandmark(activePlayer);
        if (winLm >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[winLm]);
            bs.setLandmark(activePlayer, winLm);
            return;
        }

        // 1. Landmark priority (deterministic, same as greedy)
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

        // 2. Boltzmann sampling over non-landmark cards + save
        double geoSum = Calcs.geometricSum(BOLTZMANN_HORIZON, BOLTZMANN_DISCOUNT);

        // Count candidates first
        int candidateCount = 1; // save option (index 0)
        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
            }
            candidateCount++;
        }

        // Use ThreadLocal buffers when possible
        int[] candIdx;
        double[] scores;
        if (candidateCount <= MAX_CANDIDATES) {
            candIdx = CAND_IDX_BUF.get();
            scores = SCORES_BUF.get();
        } else {
            candIdx = new int[candidateCount];
            scores = new double[candidateCount];
        }

        // Save sentinel: candIdx[0] = -1, score = 0
        candIdx[0] = -1;
        scores[0] = 0.0;

        int pos = 1;
        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                String cardId = BitStateTranslator.PURPLE_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                candIdx[pos] = ci;
                scores[pos] = ev * geoSum - BitStateTranslator.PURPLE_CARD_COSTS[idx];
                pos++;
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                String cardId = BitStateTranslator.NORMAL_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                candIdx[pos] = ci;
                scores[pos] = ev * geoSum - BitStateTranslator.NORMAL_CARD_COSTS[idx];
                pos++;
            }
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

        int ci = candIdx[chosen];
        if (ci < 0) return; // save

        boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
        int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
        if (isPurple) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[idx]);
            bs.setPurple(activePlayer, idx);
        } else {
            bs.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[idx]);
            bs.addCard(activePlayer, idx);
            supply[idx]--;
        }
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
    // Bonus turn
    // -------------------------------------------------------------------------

    private static void playBonusTurn(BitState bs, int[] supply, int activePlayer,
                                      int n, double temperature,
                                      ThreadLocalRandom rng, BitRolloutEvCache evCache) {
        boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
        boolean twoDice = hasBahnhof && bs.hasHighRangeCard(activePlayer);

        int roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);

        if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FT)) {
            int currentIncome = bs.computeActivePlayerRollIncome(activePlayer, roll);
            double rerollEV = computeExpectedRollIncome(bs, activePlayer, twoDice);
            if (currentIncome < rerollEV) {
                roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
            }
        }

        bs.applyRoll(activePlayer, roll);
        if (bs.hasPurple(activePlayer, 2) && roll == 6) bs.executeGreedySwap(activePlayer);
        applyPurchaseBoltzmann(bs, supply, activePlayer, n, temperature, rng, evCache);
    }
}
