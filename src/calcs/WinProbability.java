package calcs;

import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;

/**
 * Analytical win-probability estimation using a softmax heuristic.
 *
 * <p>All methods are stateless and side-effect-free.
 * Monte Carlo simulation is intentionally excluded — that responsibility belongs
 * to the pluggable {@code SimulationEngine} implementations.
 */
public final class WinProbability {

    /**
     * Per-landmark score bonus in the softmax win-probability scorer.
     * Calibrated to approximate the coin-equivalent benefit of each landmark over
     * a typical {@link #REMAINING_TURNS_FALLBACK}-turn horizon:
     * <ul>
     *   <li>Bahnhof       (4¢):  ~+2 EV/round × 12 turns  = 24</li>
     *   <li>Einkaufszentrum(10¢): ~+3 EV/round × 12 turns  = 36</li>
     *   <li>Freizeitpark  (16¢): ~+2 EV/round × 12 turns  = 24</li>
     *   <li>Funkturm      (22¢): ~+4 EV/round × 12 turns  = 48</li>
     * </ul>
     */
    private static final java.util.Map<String, Double> LANDMARK_WEIGHTS = java.util.Map.of(
            "bahnhof",          24.0,
            "einkaufszentrum",  36.0,
            "freizeitpark",     24.0,
            "funkturm",         48.0
    );

    /** Fallback landmark weight for any landmark not in {@link #LANDMARK_WEIGHTS}. */
    private static final double LANDMARK_WEIGHT_DEFAULT = 20.0;

    /**
     * Scaling divisor for the coin-advantage term in {@link #computeScores}.
     * With typical mid-game coin spreads of ~10 coins, this yields a contribution
     * of ~±2 per coin advantage, or ~±10 total — roughly 10–20% of the EV component.
     */
    private static final double COIN_ADVANTAGE_SCALE = 5.0;

    /** Remaining-turns estimate used in softmax scoring when no elapsed-turn info is provided. */
    private static final double REMAINING_TURNS_FALLBACK = 12.0;

    /**
     * Total expected turns per player over a full game (used for dynamic remaining-turns estimate).
     * Calibrated from MC statistics: average game length ≈ 25 effective turns across all players.
     */
    private static final double TOTAL_EXPECTED_TURNS = 25.0;

    private WinProbability() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the baseline win probability for {@code playerIndex} using the analytical
     * softmax score approximation.
     *
     * @param gs          current game state
     * @param playerIndex the player whose win probability to estimate
     * @return estimated win probability in [0, 1]
     */
    public static double computeBaselineWinProb(GameState gs, int playerIndex) {
        return softmaxEntry(computeScores(gs, 0), playerIndex);
    }

    /**
     * Estimates the change in win probability for {@code playerIndex} from buying {@code candidate}.
     *
     * <p>Uses the analytical softmax heuristic only (no Monte Carlo).
     *
     * @param gs           current game state before the purchase
     * @param playerIndex  the buying player
     * @param candidate    project being purchased (simulated as owned)
     * @param turnsElapsed effective turns elapsed in the session (0 = use static fallback estimate)
     * @return change in win probability in (−1, 1)
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex,
                                               Project candidate, int turnsElapsed) {
        double[] scoresBefore = computeScores(gs, turnsElapsed);
        double pWinBefore = softmaxEntry(scoresBefore, playerIndex);

        GameState stateAfter = gs.copy();
        stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double[] scoresAfter = computeScores(stateAfter, turnsElapsed);
        double pWinAfter = softmaxEntry(scoresAfter, playerIndex);

        return pWinAfter - pWinBefore;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Computes a heuristic score for each player:
     * <pre>
     *   score(p) = playerEvPerRound(p) × remainingTurns
     *            + Σ LANDMARK_WEIGHT(p)
     *            + coinAdvantage(p)
     *            [× endgameProximityBonus if applicable]
     * </pre>
     *
     * <p>The {@code coinAdvantage} term is {@code (coins_p − avg_opponent_coins) / COIN_ADVANTAGE_SCALE}.
     *
     * <p>The {@code endgameProximityBonus} multiplies by 2.5 when a player owns 3 landmarks
     * and has enough coins to buy the last one immediately.
     *
     * @param turnsElapsed effective turns elapsed across all players (0 = use static fallback)
     */
    static double[] computeScores(GameState gs, int turnsElapsed) {
        Player[] players = gs.getPlayers();
        int n = players.length;

        double remainingTurns = (turnsElapsed > 0)
                ? Math.max(3.0, TOTAL_EXPECTED_TURNS - (double) turnsElapsed / n)
                : REMAINING_TURNS_FALLBACK;

        double totalCoins = 0;
        for (Player p : players) totalCoins += p.getCoins();
        double avgCoins = totalCoins / n;

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            int[] opponentCoins = CardIncome.buildOpponentCoins(players, i);
            double score = CardIncome.playerEvPerRound(players[i], n, opponentCoins)
                    * remainingTurns;

            int landmarkCount = 0;
            for (Project p : players[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) {
                    score += LANDMARK_WEIGHTS.getOrDefault(p.getId(), LANDMARK_WEIGHT_DEFAULT);
                    landmarkCount++;
                }
            }

            score += (players[i].getCoins() - avgCoins) / COIN_ADVANTAGE_SCALE;

            if (landmarkCount == 3) {
                int lastLmCost = cheapestMissingLandmarkCost(players[i]);
                if (lastLmCost > 0 && players[i].getCoins() >= lastLmCost) {
                    score *= 2.5;
                }
            }

            scores[i] = score;
        }
        return scores;
    }

    /**
     * Returns the cost of the cheapest landmark not yet owned by {@code player},
     * or {@code -1} if no unowned landmark exists.
     */
    private static int cheapestMissingLandmarkCost(Player player) {
        int cheapest = Integer.MAX_VALUE;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                if (p.getCost() < cheapest) cheapest = p.getCost();
            }
        }
        return cheapest == Integer.MAX_VALUE ? -1 : cheapest;
    }

    /**
     * Numerically stable softmax: returns the probability for index {@code i}.
     * Uses max-subtraction to prevent overflow.
     */
    static double softmaxEntry(double[] scores, int index) {
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) if (s > max) max = s;

        double sumExp = 0.0;
        for (double s : scores) sumExp += Math.exp(s - max);

        return Math.exp(scores[index] - max) / sumExp;
    }
}
