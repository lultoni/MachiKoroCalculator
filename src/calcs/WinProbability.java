package calcs;

import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;

/**
 * Win-probability estimation with dual modes:
 *
 * <ul>
 *   <li>{@link #computeBaselineWinProb} — Fast heuristic (~0.25 MAE, <1ms).
 *       Used inside MCTS rollouts, Expectimax, and other hot paths.</li>
 *   <li>{@link #computeAccurateWinProb} — Micro MC with 50 greedy rollouts
 *       (~0.03 MAE, ~5-20ms). Used for UI display, luck analysis, and ranking.</li>
 * </ul>
 *
 * <p>All methods are stateless and side-effect-free.
 *
 * <p><b>BitState-native hot path:</b> {@link #computeBaselineWinProb(BitState, int)} is the
 * real implementation — no {@code toGameState()} call. The {@link #computeBaselineWinProb(GameState, int)}
 * overload is a bridge that converts via {@code BitState.fromGameState()}.
 */
public final class WinProbability {

    /** Number of MC rollouts for the accurate win probability estimate. */
    static int MICRO_MC_SIMS = 50;

    /** Number of MC rollouts for the hybrid (mid-accuracy) estimate. */
    static int HYBRID_MC_SIMS = 5;

    /** Softmax temperature — for N>2 player games in fast mode. */
    static double SOFTMAX_TEMPERATURE = 5.0;

    /** Feature weights for fast logistic model. */
    static double W_BIAS = 0.0;
    static double W_INCOME_ADV = 0.5;
    static double W_COIN_ADV = 0.10;
    static double W_INVESTMENT_ADV = 0.05;
    static double W_LANDMARK_ADV = 4.0;
    static double W_TTW_GAP = 0.0;
    static double W_RED_DRAIN = -0.5;
    static double W_ENDGAME_URGENCY = 1.5;

    private WinProbability() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fast heuristic win probability estimate (~0.25 MAE, <1ms).
     * Bridge overload — converts to BitState and delegates to the native implementation.
     */
    public static double computeBaselineWinProb(GameState gs, int playerIndex) {
        return computeBaselineWinProb(BitState.fromGameState(gs), playerIndex);
    }

    /**
     * Fast heuristic win probability estimate (~0.25 MAE, <1ms).
     * Real implementation — fully BitState-native, no {@code toGameState()} call.
     * Used by MCTS rollouts, Expectimax, and other engine internals where speed is critical.
     */
    public static double computeBaselineWinProb(BitState bs, int playerIndex) {
        int n = bs.getNumPlayers();
        if (n == 2) {
            return sigmoid(computeLogitBit(bs, playerIndex));
        }
        double[] scores = computeScoresBit(bs);
        return softmaxEntry(scores, playerIndex);
    }

    /**
     * Accurate win probability using micro Monte Carlo (~0.03 MAE, ~5-20ms).
     * Runs a small number of greedy rollouts to capture game dynamics that
     * static features miss (future purchases, dice variance, interactions).
     *
     * <p>Use this for UI display, luck analysis, ranking, and anywhere
     * accuracy matters more than sub-millisecond speed.
     */
    public static double computeAccurateWinProb(GameState gs, int playerIndex) {
        return GameSimulator.mcWinRate(gs, playerIndex, MICRO_MC_SIMS);
    }

    public static double computeAccurateWinProb(BitState bs, int playerIndex) {
        return computeAccurateWinProb(bs.toGameState(), playerIndex);
    }

    /**
     * Hybrid win probability using a small number of fast greedy rollouts.
     * Bridges the gap between the pure heuristic (~0.22 MAE) and full micro MC
     * (~0.03 MAE). Runs {@link #HYBRID_MC_SIMS} rollouts (~1-3ms).
     *
     * <p>Use this for MCTS depth-limited rollout terminals and other hot paths
     * where the heuristic is too inaccurate but 50 MC sims is too slow.
     */
    public static double computeHybridWinProb(GameState gs, int playerIndex) {
        return GameSimulator.mcWinRate(gs, playerIndex, HYBRID_MC_SIMS);
    }

    public static double computeHybridWinProb(BitState bs, int playerIndex) {
        return computeHybridWinProb(bs.toGameState(), playerIndex);
    }

    /**
     * Estimates how much buying a card changes the win probability.
     * Uses micro MC for accuracy.
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex, Project candidate) {
        double pBefore = computeAccurateWinProb(gs, playerIndex);
        GameState after = gs.copy();
        after.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double pAfter = computeAccurateWinProb(after, playerIndex);
        return pAfter - pBefore;
    }

    // -------------------------------------------------------------------------
    // Fast logistic model — BitState-native
    // -------------------------------------------------------------------------

    private static double computeLogitBit(BitState bs, int playerIndex) {
        int n = bs.getNumPlayers();
        int oi = 1 - playerIndex;

        double grossSelf = CardIncome.playerEvPerRound(bs, playerIndex);
        double grossOpp  = CardIncome.playerEvPerRound(bs, oi);

        double investSelf = investmentCostBit(bs, playerIndex);
        double investOpp  = investmentCostBit(bs, oi);

        double drainSelf = computeRedDrainBit(bs, playerIndex, n);
        double drainOpp  = computeRedDrainBit(bs, oi, n);

        double netSelf = Math.max(0.5, grossSelf - drainSelf * 0.5);
        double netOpp  = Math.max(0.5, grossOpp  - drainOpp  * 0.5);

        int remSelf = remainingLandmarkCostBit(bs, playerIndex);
        int remOpp  = remainingLandmarkCostBit(bs, oi);

        double ttwSelf = Math.max(0, remSelf - bs.getCoins(playerIndex)) / netSelf;
        double ttwOpp  = Math.max(0, remOpp  - bs.getCoins(oi))         / netOpp;

        int lmSelf = bs.getLandmarkCount(playerIndex);
        int lmOpp  = bs.getLandmarkCount(oi);

        double coinUtilAdv = coinUtilityBit(bs, playerIndex) - coinUtilityBit(bs, oi);

        double avgTtw = (ttwSelf + ttwOpp) / 2.0;
        double incomeTimesHorizon = (grossSelf - grossOpp) * Math.min(avgTtw, 20.0);

        double urgency = endgameUrgencyBit(bs, playerIndex, remSelf, netSelf)
                       - endgameUrgencyBit(bs, oi, remOpp, netOpp);

        return W_BIAS
                + W_INCOME_ADV      * (grossSelf - grossOpp)
                + W_COIN_ADV        * coinUtilAdv
                + W_INVESTMENT_ADV  * (investSelf - investOpp)
                + W_LANDMARK_ADV    * (lmSelf - lmOpp)
                + W_TTW_GAP         * (ttwOpp - ttwSelf)
                + W_RED_DRAIN       * (drainSelf - drainOpp)
                + 0.05              * incomeTimesHorizon
                + W_ENDGAME_URGENCY * urgency;
    }

    /** Sum of non-landmark card costs owned by a player — reads directly from BitState. */
    private static double investmentCostBit(BitState bs, int player) {
        double total = 0;
        for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
            int cnt = bs.getCardCount(player, i);
            if (cnt > 0) total += cnt * BitStateTranslator.NORMAL_CARD_COSTS[i];
        }
        for (int i = 0; i < BitStateTranslator.NUM_PURPLE_CARDS; i++) {
            if (bs.hasPurple(player, i)) total += BitStateTranslator.PURPLE_CARD_COSTS[i];
        }
        return total;
    }

    /** Remaining total cost of unbuilt landmarks for a player. */
    private static int remainingLandmarkCostBit(BitState bs, int player) {
        int total = 0;
        for (int i = 0; i < BitStateTranslator.NUM_LANDMARKS; i++) {
            if (!bs.hasLandmark(player, i)) total += BitStateTranslator.LANDMARK_COSTS[i];
        }
        return total;
    }

    /** Coin utility: non-linear value of current coins relative to the cheapest unbuilt landmark. */
    private static double coinUtilityBit(BitState bs, int player) {
        int cheapest = Integer.MAX_VALUE;
        for (int i = 0; i < BitStateTranslator.NUM_LANDMARKS; i++) {
            if (!bs.hasLandmark(player, i)) {
                int cost = BitStateTranslator.LANDMARK_COSTS[i];
                if (cost < cheapest) cheapest = cost;
            }
        }
        if (cheapest == Integer.MAX_VALUE) return 0;
        double coins = bs.getCoins(player);
        if (coins >= cheapest) return cheapest + Math.sqrt(coins - cheapest);
        return coins * coins / cheapest;
    }

    /**
     * Endgame urgency: non-linear bonus for players with 3 landmarks.
     * With 3 landmarks and enough coins for the 4th, the player wins on
     * their next turn with very high probability. This binary threshold
     * is poorly captured by linear features.
     */
    private static double endgameUrgencyBit(BitState bs, int player, int remainingCost, double netIncome) {
        int lm = bs.getLandmarkCount(player);
        if (lm < 2) return 0.0;
        int coins = bs.getCoins(player);
        if (lm == 3) {
            if (coins >= remainingCost) return 3.0;
            double proximity = (double) coins / Math.max(1, remainingCost);
            double turnsAway = Math.max(0, remainingCost - coins) / netIncome;
            if (turnsAway <= 1.0) return 2.5 * proximity;
            if (turnsAway <= 3.0) return 1.5 * proximity;
            return 0.5 * proximity;
        }
        // lm == 2
        double proximity = 1.0 - (double) Math.max(0, remainingCost - coins)
                / Math.max(1, remainingCost);
        return 0.3 * proximity;
    }

    /**
     * Expected coin drain per round inflicted on {@code targetIdx} by all opponents' red cards.
     * Reads card counts and EKZ status directly from BitState.
     */
    private static double computeRedDrainBit(BitState bs, int targetIdx, int n) {
        int targetCoins = Math.max(1, bs.getCoins(targetIdx));
        double drain = 0;
        for (int j = 0; j < n; j++) {
            if (j == targetIdx) continue;
            boolean oppHasEKZ = bs.hasLandmark(j, BitStateTranslator.LM_EKZ);
            // café (idx 5): r=3
            int cafeCount = bs.getCardCount(j, 5);
            if (cafeCount > 0) {
                int demand = cafeCount * (oppHasEKZ ? 2 : 1);
                double loss = Math.min(demand, targetCoins);
                drain += CardIncome.P1[3] * loss;
            }
            // familienrestaurant (idx 10): r=9, r=10
            int restCount = bs.getCardCount(j, 10);
            if (restCount > 0) {
                int demand = restCount * (oppHasEKZ ? 3 : 2);
                double loss = Math.min(demand, targetCoins);
                drain += (CardIncome.P1[9] + CardIncome.P1[10]) * loss;
            }
        }
        return drain;
    }

    /**
     * Computes estimated turns-to-win for each player (used by diagnostics).
     */
    static double[] computeTurnsToWin(GameState gs) {
        BitState bs = BitState.fromGameState(gs);
        return computeTurnsToWinBit(bs);
    }

    static double[] computeTurnsToWinBit(BitState bs) {
        int n = bs.getNumPlayers();
        double[] ttw = new double[n];
        for (int i = 0; i < n; i++) {
            double gross = CardIncome.playerEvPerRound(bs, i);
            double net = Math.max(0.5, gross);
            int remCost = remainingLandmarkCostBit(bs, i);
            double deficit = Math.max(0, remCost - bs.getCoins(i));
            ttw[i] = deficit / net;
        }
        return ttw;
    }

    // -------------------------------------------------------------------------
    // N-player scoring (fast mode) — BitState-native
    // -------------------------------------------------------------------------

    static double[] computeScores(GameState gs) {
        return computeScoresBit(BitState.fromGameState(gs));
    }

    static double[] computeScoresBit(BitState bs) {
        int n = bs.getNumPlayers();

        double[] gross     = new double[n];
        double[] netIncome = new double[n];
        double[] invest    = new double[n];
        int[]    remCost   = new int[n];
        int[]    lm        = new int[n];
        double[] coinUtil  = new double[n];
        double[] drain     = new double[n];
        double[] urgency   = new double[n];

        for (int i = 0; i < n; i++) {
            gross[i]     = CardIncome.playerEvPerRound(bs, i);
            drain[i]     = computeRedDrainBit(bs, i, n);
            netIncome[i] = Math.max(0.5, gross[i] - drain[i] * 0.5);
            invest[i]    = investmentCostBit(bs, i);
            remCost[i]   = remainingLandmarkCostBit(bs, i);
            lm[i]        = bs.getLandmarkCount(i);
            coinUtil[i]  = coinUtilityBit(bs, i);
            urgency[i]   = endgameUrgencyBit(bs, i, remCost[i], netIncome[i]);
        }

        double avgGross = 0, avgCoinUtil = 0, avgInvest = 0, avgLm = 0, avgDrain = 0, avgUrgency = 0;
        for (int i = 0; i < n; i++) {
            avgGross    += gross[i];
            avgCoinUtil += coinUtil[i];
            avgInvest   += invest[i];
            avgLm       += lm[i];
            avgDrain    += drain[i];
            avgUrgency  += urgency[i];
        }
        avgGross /= n; avgCoinUtil /= n; avgInvest /= n;
        avgLm    /= n; avgDrain    /= n; avgUrgency /= n;

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            scores[i] = W_BIAS
                    + W_INCOME_ADV     * (gross[i]    - avgGross)
                    + W_COIN_ADV       * (coinUtil[i] - avgCoinUtil)
                    + W_INVESTMENT_ADV * (invest[i]   - avgInvest)
                    + W_LANDMARK_ADV   * (lm[i]       - avgLm)
                    + W_RED_DRAIN      * (drain[i]    - avgDrain)
                    + W_ENDGAME_URGENCY* (urgency[i]  - avgUrgency);
        }
        return scores;
    }

    // -------------------------------------------------------------------------
    // Probability conversion
    // -------------------------------------------------------------------------

    static double softmaxEntry(double[] scores, int index) {
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) if (s > max) max = s;

        double T = SOFTMAX_TEMPERATURE;
        double sumExp = 0.0;
        for (double s : scores) sumExp += Math.exp((s - max) / T);

        return Math.exp((scores[index] - max) / T) / sumExp;
    }

    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
