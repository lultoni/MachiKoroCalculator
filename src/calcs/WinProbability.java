package calcs;

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
     * Used by MCTS rollouts, Expectimax, and other engine internals
     * where speed is critical.
     */
    public static double computeBaselineWinProb(GameState gs, int playerIndex) {
        Player[] players = gs.getPlayers();
        int n = players.length;

        if (n == 2) {
            double logit = computeLogit(gs, playerIndex);
            return sigmoid(logit);
        }
        double[] scores = computeScores(gs);
        return softmaxEntry(scores, playerIndex);
    }

    public static double computeBaselineWinProb(core.BitState bs, int playerIndex) {
        return computeBaselineWinProb(bs.toGameState(), playerIndex);
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

    public static double computeAccurateWinProb(core.BitState bs, int playerIndex) {
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

    public static double computeHybridWinProb(core.BitState bs, int playerIndex) {
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
    // Fast logistic model
    // -------------------------------------------------------------------------

    private static double computeLogit(GameState gs, int playerIndex) {
        Player[] players = gs.getPlayers();
        int n = players.length;
        int oi = 1 - playerIndex;

        int[] oppCoinsI = CardIncome.buildOpponentCoins(players, playerIndex);
        int[] oppCoinsJ = CardIncome.buildOpponentCoins(players, oi);
        double grossSelf = CardIncome.playerEvPerRound(players[playerIndex], n, oppCoinsI);
        double grossOpp = CardIncome.playerEvPerRound(players[oi], n, oppCoinsJ);

        double investSelf = 0, investOpp = 0;
        for (Project p : players[playerIndex].getOwned_projects())
            if (!p.isIs_grossprojekt()) investSelf += p.getCost();
        for (Project p : players[oi].getOwned_projects())
            if (!p.isIs_grossprojekt()) investOpp += p.getCost();

        double drainSelf = computeRedDrain(players, playerIndex, n, oppCoinsI);
        double drainOpp = computeRedDrain(players, oi, n, oppCoinsJ);

        double netSelf = Math.max(0.5, grossSelf - drainSelf * 0.5);
        double netOpp = Math.max(0.5, grossOpp - drainOpp * 0.5);

        int remSelf = remainingLandmarkCost(players[playerIndex]);
        int remOpp = remainingLandmarkCost(players[oi]);

        double ttwSelf = Math.max(0, remSelf - players[playerIndex].getCoins()) / netSelf;
        double ttwOpp = Math.max(0, remOpp - players[oi].getCoins()) / netOpp;

        int lmSelf = players[playerIndex].getLandmarkCount();
        int lmOpp = players[oi].getLandmarkCount();

        double coinUtilAdv = coinUtility(players[playerIndex]) - coinUtility(players[oi]);

        double avgTtw = (ttwSelf + ttwOpp) / 2.0;
        double incomeTimesHorizon = (grossSelf - grossOpp) * Math.min(avgTtw, 20.0);

        // Endgame urgency: 3-landmark positions are highly non-linear
        double urgency = endgameUrgency(players[playerIndex], remSelf, netSelf)
                       - endgameUrgency(players[oi], remOpp, netOpp);

        return W_BIAS
                + W_INCOME_ADV * (grossSelf - grossOpp)
                + W_COIN_ADV * coinUtilAdv
                + W_INVESTMENT_ADV * (investSelf - investOpp)
                + W_LANDMARK_ADV * (lmSelf - lmOpp)
                + W_TTW_GAP * (ttwOpp - ttwSelf)
                + W_RED_DRAIN * (drainSelf - drainOpp)
                + 0.05 * incomeTimesHorizon
                + W_ENDGAME_URGENCY * urgency;
    }

    private static double coinUtility(Player player) {
        int cheapest = Integer.MAX_VALUE;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                if (p.getCost() < cheapest) cheapest = p.getCost();
            }
        }
        if (cheapest == Integer.MAX_VALUE) return 0;
        double coins = player.getCoins();
        if (coins >= cheapest) return cheapest + Math.sqrt(coins - cheapest);
        return coins * coins / cheapest;
    }

    /**
     * Endgame urgency: non-linear bonus for players with 3 landmarks.
     * With 3 landmarks and enough coins for the 4th, the player wins on
     * their next turn with very high probability. This binary threshold
     * is poorly captured by linear features.
     */
    private static double endgameUrgency(Player player, int remainingCost, double netIncome) {
        int lm = player.getLandmarkCount();
        if (lm < 2) return 0.0;
        int coins = player.getCoins();
        if (lm == 3) {
            // Can afford the last landmark — near-certain win
            if (coins >= remainingCost) return 3.0;
            // Close to affording — scaled by proximity
            double proximity = (double) coins / Math.max(1, remainingCost);
            // Also factor in income: high income means 1-2 turns away
            double turnsAway = Math.max(0, remainingCost - coins) / netIncome;
            if (turnsAway <= 1.0) return 2.5 * proximity;
            if (turnsAway <= 3.0) return 1.5 * proximity;
            return 0.5 * proximity;
        }
        if (lm == 2) {
            // 2 landmarks — mild urgency based on proximity
            double proximity = 1.0 - (double) Math.max(0, remainingCost - coins)
                    / Math.max(1, remainingCost);
            return 0.3 * proximity;
        }
        return 0.0;
    }

    private static double computeRedDrain(Player[] players, int targetIdx, int n, int[] oppCoins) {
        double drain = 0;
        for (int j = 0; j < n; j++) {
            if (j == targetIdx) continue;
            for (Project card : players[j].getOwned_projects()) {
                if (!"rot".equals(card.getColor())) continue;
                CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(players[j]);
                for (int r = 1; r <= 6; r++) {
                    int loss = CardIncome.get_I(r, card.getId(), false, oppStats.hasEinkaufszentrum,
                            oppStats.foodCount, oppStats.animalCount, oppStats.productionCount,
                            Math.max(1, players[targetIdx].getCoins()), oppCoins);
                    if (loss < 0) drain += CardIncome.P1[r] * (-loss);
                }
            }
        }
        return drain;
    }

    private static int remainingLandmarkCost(Player player) {
        int total = 0;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) total += p.getCost();
        }
        return total;
    }

    /**
     * Computes estimated turns-to-win for each player (used by diagnostics).
     */
    static double[] computeTurnsToWin(GameState gs) {
        Player[] players = gs.getPlayers();
        int n = players.length;
        double[] ttw = new double[n];
        for (int i = 0; i < n; i++) {
            int[] oppCoins = CardIncome.buildOpponentCoins(players, i);
            double gross = CardIncome.playerEvPerRound(players[i], n, oppCoins);
            double net = Math.max(0.5, gross);
            int remCost = remainingLandmarkCost(players[i]);
            double deficit = Math.max(0, remCost - players[i].getCoins());
            ttw[i] = deficit / net;
        }
        return ttw;
    }

    // -------------------------------------------------------------------------
    // N-player scoring (fast mode)
    // -------------------------------------------------------------------------

    static double[] computeScores(GameState gs) {
        Player[] players = gs.getPlayers();
        int n = players.length;

        // Pre-compute per-player features
        double[] gross = new double[n];
        double[] netIncome = new double[n];
        double[] invest = new double[n];
        int[] remCost = new int[n];
        int[] lm = new int[n];
        double[] coinUtil = new double[n];
        double[] drain = new double[n];
        double[] urgency = new double[n];

        for (int i = 0; i < n; i++) {
            int[] oppCoins = CardIncome.buildOpponentCoins(players, i);
            gross[i] = CardIncome.playerEvPerRound(players[i], n, oppCoins);
            drain[i] = computeRedDrain(players, i, n, oppCoins);
            netIncome[i] = Math.max(0.5, gross[i] - drain[i] * 0.5);

            for (Project p : players[i].getOwned_projects())
                if (!p.isIs_grossprojekt()) invest[i] += p.getCost();

            remCost[i] = remainingLandmarkCost(players[i]);
            lm[i] = players[i].getLandmarkCount();
            coinUtil[i] = coinUtility(players[i]);
            urgency[i] = endgameUrgency(players[i], remCost[i], netIncome[i]);
        }

        // Compute averages for relative features
        double avgGross = 0, avgCoinUtil = 0, avgInvest = 0, avgLm = 0, avgDrain = 0, avgUrgency = 0;
        for (int i = 0; i < n; i++) {
            avgGross += gross[i];
            avgCoinUtil += coinUtil[i];
            avgInvest += invest[i];
            avgLm += lm[i];
            avgDrain += drain[i];
            avgUrgency += urgency[i];
        }
        avgGross /= n; avgCoinUtil /= n; avgInvest /= n;
        avgLm /= n; avgDrain /= n; avgUrgency /= n;

        // Per-player logit using same features as 2-player model
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            double incomeAdv = gross[i] - avgGross;
            double coinAdv = coinUtil[i] - avgCoinUtil;
            double investAdv = invest[i] - avgInvest;
            double lmAdv = lm[i] - avgLm;
            double drainAdv = drain[i] - avgDrain;
            double urgencyAdv = urgency[i] - avgUrgency;

            scores[i] = W_BIAS
                    + W_INCOME_ADV * incomeAdv
                    + W_COIN_ADV * coinAdv
                    + W_INVESTMENT_ADV * investAdv
                    + W_LANDMARK_ADV * lmAdv
                    + W_RED_DRAIN * drainAdv
                    + W_ENDGAME_URGENCY * urgencyAdv;
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

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
