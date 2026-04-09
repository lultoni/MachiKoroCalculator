package engine.creator;

import calcs.Calcs;
import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import engine.mcts.BitMctsRollout;
import engine.mcts.BitRolloutEvCache;
import engine.mcts.BitRolloutFn;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Creator's own rollout policy for Monte Carlo simulations, using {@link BitState} internally.
 *
 * <p>Uses a lightweight heuristic for purchase decisions during rollouts — NOT the
 * full {@link CreatorScorer} (which is too expensive for rollout-speed requirements).
 *
 * <p>Port of {@code CreatorRollout} v3 to BitState. Coverage bonus rewards cards that
 * activate on roll values the player doesn't currently cover (portfolio diversification).
 * Save-toward-landmark prevents buying marginal cards when close to affording the next landmark.
 *
 * <h2>Decision policy</h2>
 * <ul>
 *   <li><b>Dice</b>: 2d6 if Bahnhof + useful 7-12 card; else 1d6.</li>
 *   <li><b>Funkturm</b>: keep if income ≥ expected reroll EV.</li>
 *   <li><b>Bürohaus</b>: greedy best swap via {@link BitState#executeGreedySwap}.</li>
 *   <li><b>Purchase (instant-win)</b>: always buy winning landmark.</li>
 *   <li><b>Purchase (landmark)</b>: buy cheapest affordable landmark.</li>
 *   <li><b>Purchase (card)</b>: highest {@code contextualCardEvPerRound × geometricSum − cost + coverageBonus}.</li>
 *   <li><b>Save-toward-landmark</b>: save if within 4 coins of next landmark and best card is marginal.</li>
 *   <li><b>Save</b>: if no card has positive net value.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom}. All state is local to the simulate call.
 */
public final class BitCreatorRollout {

    private static final int    HORIZON  = 5;
    private static final double DISCOUNT = 0.95;
    private static final int    EV_CACHE_REFRESH = 40;
    /** Bonus multiplier per new roll coverage: net += newCoverage * COVERAGE_BONUS * ev. */
    private static final double COVERAGE_BONUS = 0.15;
    /** If card net EV is below this fraction of next-landmark cost, prefer saving. */
    private static final double SAVE_THRESHOLD_RATIO = 0.3;

    private BitCreatorRollout() {}

    /**
     * Returns a {@link BitRolloutFn} using the Creator policy.
     */
    public static BitRolloutFn asBitRolloutFn() {
        return BitCreatorRollout::simulate;
    }

    /**
     * BitState-native rollout entry point matching {@link BitRolloutFn}.
     * Copies the state internally — callers do not need to pre-copy.
     */
    public static double simulate(BitState bs, int[] supply,
                                  int startingPlayer, int playerPerspective) {
        int n = bs.getNumPlayers();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int activePlayer = startingPlayer;
        int turnCount = 0;
        BitRolloutEvCache evCache = new BitRolloutEvCache(bs, startingPlayer, n, EV_CACHE_REFRESH);

        while (turnCount < BitMctsRollout.MAX_TURNS) {
            evCache.tickTurn();

            // ---- Dice count ----
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

            // ---- Funkturm: keep if income >= expected reroll income ----
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

            // ---- Purchase ----
            applyPurchase(bs, supply, activePlayer, n, evCache);

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

        return WinProbability.computeBaselineWinProb(bs, playerPerspective);
    }

    // =====================================================================
    // Purchase logic — the Creator difference
    // =====================================================================

    /**
     * Creator purchase policy: instant-win → cheapest affordable landmark →
     * best net-EV card (with coverage bonus + save-toward-landmark check) → save.
     */
    private static void applyPurchase(BitState bs, int[] supply, int activePlayer,
                                      int n, BitRolloutEvCache evCache) {
        int coins = bs.getCoins(activePlayer);

        // 1. Instant-win check
        int winLm = bs.findInstantWinLandmark(activePlayer);
        if (winLm >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[winLm]);
            bs.setLandmark(activePlayer, winLm);
            return;
        }

        // 2. Cheapest affordable landmark
        int bestLmCost = Integer.MAX_VALUE;
        int landmarkToBuy = -1;
        int nextLandmarkCost = Integer.MAX_VALUE; // cheapest unowned (even unaffordable)
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (bs.hasLandmark(activePlayer, li)) continue;
            int cost = BitStateTranslator.LANDMARK_COSTS[li];
            if (cost < nextLandmarkCost) nextLandmarkCost = cost;
            if (coins >= cost && cost < bestLmCost) {
                bestLmCost = cost;
                landmarkToBuy = li;
            }
        }
        if (landmarkToBuy >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[landmarkToBuy]);
            bs.setLandmark(activePlayer, landmarkToBuy);
            return;
        }

        // 3. Best non-landmark card by net EV + coverage bonus
        double geoSum = Calcs.geometricSum(HORIZON, DISCOUNT);
        boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
        long coveredRolls = computeCoveredRolls(bs, activePlayer);

        int bestCandIdx = -1;
        double bestScore = 0.0; // must be positive to be worth buying

        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;

            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                int cost = BitStateTranslator.PURPLE_CARD_COSTS[idx];
                if (coins < cost) continue;
                String cardId = BitStateTranslator.PURPLE_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                double net = ev * geoSum - cost;

                // Coverage bonus for purple cards
                int[] activations = BitStateTranslator.PURPLE_CARD_PROJECTS[idx].getDice_activation();
                int newCoverage = countNewCoverage(activations, coveredRolls, hasBahnhof);
                if (newCoverage > 0) net += newCoverage * COVERAGE_BONUS * ev;

                if (net > bestScore) { bestScore = net; bestCandIdx = ci; }
            } else {
                if (supply[idx] <= 0) continue;
                int cost = BitStateTranslator.NORMAL_CARD_COSTS[idx];
                if (coins < cost) continue;
                String cardId = BitStateTranslator.NORMAL_CARD_IDS[idx];
                double ev = evCache.getOrRefresh(bs, activePlayer, n, cardId);
                double net = ev * geoSum - cost;

                // Coverage bonus for normal cards
                int[] activations = BitStateTranslator.NORMAL_CARD_PROJECTS[idx].getDice_activation();
                // Exclude red cards from coverage (they only activate on opponent turns)
                String color = BitStateTranslator.NORMAL_CARD_PROJECTS[idx].getColor();
                if (!"rot".equals(color)) {
                    int newCoverage = countNewCoverage(activations, coveredRolls, hasBahnhof);
                    if (newCoverage > 0) net += newCoverage * COVERAGE_BONUS * ev;
                }

                if (net > bestScore) { bestScore = net; bestCandIdx = ci; }
            }
        }

        // 4. Save-toward-landmark check
        if (bestCandIdx >= 0 && nextLandmarkCost < Integer.MAX_VALUE) {
            int gap = nextLandmarkCost - coins;
            if (gap > 0 && gap <= 4 && bestScore < nextLandmarkCost * SAVE_THRESHOLD_RATIO) {
                return; // save
            }
        }

        // Execute purchase
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
        // else: save
    }

    // =====================================================================
    // Bonus turn
    // =====================================================================

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

        applyPurchase(bs, supply, activePlayer, n, evCache);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Returns a bitmask of roll values (1-12) that produce income for this player.
     * Bit i is set if the player has at least one non-landmark, non-red card activating on roll i.
     */
    private static long computeCoveredRolls(BitState bs, int activePlayer) {
        long mask = 0;
        for (int ci = 0; ci < BitStateTranslator.NUM_NORMAL_CARDS; ci++) {
            if (bs.getCardCount(activePlayer, ci) == 0) continue;
            String color = BitStateTranslator.NORMAL_CARD_PROJECTS[ci].getColor();
            if ("rot".equals(color)) continue; // red = opponent turns only
            for (int act : BitStateTranslator.NORMAL_CARD_PROJECTS[ci].getDice_activation()) {
                mask |= (1L << act);
            }
        }
        // Purple cards (non-red, non-landmark)
        for (int pi = 0; pi < BitStateTranslator.NUM_PURPLE_CARDS; pi++) {
            if (!bs.hasPurple(activePlayer, pi)) continue;
            for (int act : BitStateTranslator.PURPLE_CARD_PROJECTS[pi].getDice_activation()) {
                mask |= (1L << act);
            }
        }
        return mask;
    }

    private static int countNewCoverage(int[] activations, long coveredRolls, boolean hasBahnhof) {
        int count = 0;
        for (int act : activations) {
            if (!hasBahnhof && act > 6) continue;
            if ((coveredRolls & (1L << act)) == 0) count++;
        }
        return count;
    }

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
}
