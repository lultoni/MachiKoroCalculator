package calcs;

import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;

import java.util.function.IntToDoubleFunction;

/**
 * Analytical win-probability estimation using a softmax heuristic.
 *
 * <p>All methods are stateless and side-effect-free.
 * Monte Carlo simulation is intentionally excluded — that responsibility belongs
 * to the pluggable {@code SimulationEngine} implementations.
 */
public final class WinProbability {

    /**
     * Total expected turns per player over a full game.
     * Calibrated from H2H empirical data: average game length ≈ 50–60 effective turns per player.
     * Set conservatively at 50 (strong players/human play tends shorter than engine vs engine).
     * Used as the base for landmark-progress-based remaining-turns estimation.
     */
    private static final double TOTAL_EXPECTED_TURNS = 50.0;

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
        return softmaxEntry(computeScores(gs), playerIndex);
    }

    /**
     * Estimates the change in win probability for {@code playerIndex} from buying {@code candidate}.
     *
     * <p>Uses the analytical softmax heuristic only (no Monte Carlo).
     *
     * @param gs           current game state before the purchase
     * @param playerIndex  the buying player
     * @param candidate    project being purchased (simulated as owned)
     * @return change in win probability in (−1, 1)
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex,
                                               Project candidate) {
        double[] scoresBefore = computeScores(gs);
        double pWinBefore = softmaxEntry(scoresBefore, playerIndex);

        GameState stateAfter = gs.copy();
        stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double[] scoresAfter = computeScores(stateAfter);
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
     *            + Σ dynamicLandmarkWeight(p, landmark)
     *            + coinAdvantage(p)
     *            [× endgameProximityBonus if applicable]
     * </pre>
     *
     * <p>Remaining turns are estimated from average landmark progress across all players:
     * {@code max(3.0, TOTAL_EXPECTED_TURNS × (1 − avgLandmarks / 4))}.
     *
     * <p>Landmark weights are computed dynamically per player based on portfolio synergies.
     *
     * <p>The {@code coinAdvantage} term scales adaptively with average income per round.
     *
     * <p>The {@code endgameProximityBonus} scales continuously with landmark count and
     * coin proximity to the cheapest missing landmark.
     */
    static double[] computeScores(GameState gs) {
        Player[] players = gs.getPlayers();
        int n = players.length;

        // Landmark-based remaining turns estimation
        double totalLandmarks = 0;
        for (Player p : players) {
            for (Project proj : p.getOwned_projects()) {
                if (proj.isIs_grossprojekt()) totalLandmarks++;
            }
        }
        double avgLandmarks = totalLandmarks / n;
        double remainingTurns = Math.max(3.0, TOTAL_EXPECTED_TURNS * (1.0 - avgLandmarks / 4.0));

        double totalCoins = 0;
        for (Player p : players) totalCoins += p.getCoins();
        double avgCoins = totalCoins / n;

        // Adaptive coin-advantage scale: relative to average income per round.
        // Early game (low EV) → coin lead is more significant; late game (high EV) → less so.
        double totalEvPerRound = 0;
        for (int j = 0; j < n; j++) {
            int[] opp = CardIncome.buildOpponentCoins(players, j);
            totalEvPerRound += CardIncome.playerEvPerRound(players[j], n, opp);
        }
        double coinScale = Math.max(1.0, (totalEvPerRound / n) * 2.0);

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            int[] opponentCoins = CardIncome.buildOpponentCoins(players, i);
            double score = CardIncome.playerEvPerRound(players[i], n, opponentCoins)
                    * remainingTurns;

            int landmarkCount = 0;
            for (Project p : players[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) {
                    score += computeLandmarkWeight(players[i], p.getId(), n, remainingTurns);
                    landmarkCount++;
                }
            }

            score += (players[i].getCoins() - avgCoins) / coinScale;

            // Continuous endgame proximity bonus: scales with landmark count and
            // how close the player is to affording the cheapest missing landmark.
            // 0 landmarks → no bonus. 3 landmarks + can afford → 2.5× (backwards-compatible).
            if (landmarkCount > 0) {
                int cheapestLmCost = cheapestMissingLandmarkCost(players[i]);
                if (cheapestLmCost > 0) {
                    double proximity = Math.max(0.0, Math.min(1.0,
                            (double) players[i].getCoins() / cheapestLmCost));
                    score *= 1.0 + landmarkCount * 0.5 * proximity;
                }
            }

            scores[i] = score;
        }
        return scores;
    }

    /**
     * Computes the dynamic weight of a landmark for a specific player based on their
     * actual portfolio synergies. Returns the estimated coin-equivalent value over
     * the remaining game horizon.
     *
     * <p>Each landmark is scored by its marginal EV contribution to the player's portfolio:
     * <ul>
     *   <li><b>Bahnhof:</b> delta between best-of(1d6,2d6) and 1d6-only EV.
     *       Zero if the player has no non-red cards with dice activation ≥ 7.</li>
     *   <li><b>Einkaufszentrum:</b> sum of the +1/+1 store/café bonus across all
     *       applicable cards (Bäckerei, Café, Mini-Markt, Familienrestaurant).</li>
     *   <li><b>Freizeitpark:</b> P(doubles) × expected second-roll EV.
     *       Zero if the player does not own Bahnhof (no 2d6 → no meaningful doubles).</li>
     *   <li><b>Funkturm:</b> expected reroll improvement (EV gain from replacing
     *       below-average rolls with the average).</li>
     * </ul>
     *
     * @param player         the player whose landmark to evaluate
     * @param landmarkId     the landmark ID (bahnhof, einkaufszentrum, freizeitpark, funkturm)
     * @param numPlayers     total number of players
     * @param remainingTurns estimated remaining turns in the game
     * @return coin-equivalent landmark weight (≥ 0)
     */
    private static double computeLandmarkWeight(Player player, String landmarkId,
                                                 int numPlayers, double remainingTurns) {
        CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(player);
        int[] oppCoins = new int[numPlayers - 1]; // zeroed — conservative for scoring
        double deltaPerRound;

        switch (landmarkId) {
            case "bahnhof" -> {
                // Check if the player has any non-red cards with activation >= 7
                boolean hasNonRedHighRange = false;
                for (Project p : player.getOwned_projects()) {
                    if (p.isIs_grossprojekt() || "rot".equals(p.getColor())) continue;
                    for (int act : p.getDice_activation()) {
                        if (act >= 7) { hasNonRedHighRange = true; break; }
                    }
                    if (hasNonRedHighRange) break;
                }
                if (!hasNonRedHighRange) return 0.0;

                // Compute EV with 2d6 option vs 1d6-only
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, oppCoins);
                double ev1d6 = CardIncome.weightedRollEV(false, payout);
                double ev2d6 = CardIncome.weightedRollEV(true, payout);
                deltaPerRound = Math.max(0.0, ev2d6 - ev1d6) * numPlayers;
            }
            case "einkaufszentrum" -> {
                // Compute the EKZ bonus: +1 per activation for Bäckerei, Café, Mini-Markt, Familienrestaurant.
                // Each of these cards gains +1 coin per triggered activation when EKZ is owned.
                double bonusPerRound = 0.0;
                for (Project p : player.getOwned_projects()) {
                    if (p.isIs_grossprojekt()) continue;
                    String pid = p.getId();
                    if ("bäckerei".equals(pid) || "café".equals(pid)
                            || "mini-markt".equals(pid) || "familienrestaurant".equals(pid)) {
                        // Sum P(activation) for each dice value this card triggers on
                        double cardBonusEV = 0.0;
                        for (int act : p.getDice_activation()) {
                            if (stats.hasBahnhof) {
                                // Player can choose 1d6 or 2d6; take max probability for each activation
                                double p1 = (act >= 1 && act <= 6) ? CardIncome.P1[act] : 0;
                                double p2 = (act >= 2 && act <= 12) ? CardIncome.P2[act] : 0;
                                cardBonusEV += Math.max(p1, p2);
                            } else {
                                cardBonusEV += (act >= 1 && act <= 6) ? CardIncome.P1[act] : 0;
                            }
                        }
                        // Scale by turn frequency: café/familienrestaurant are red (opponent turns),
                        // bäckerei/mini-markt are green (own turn only)
                        if ("rot".equals(p.getColor())) {
                            bonusPerRound += cardBonusEV * (numPlayers - 1);
                        } else {
                            bonusPerRound += cardBonusEV; // green: own turn only
                        }
                    }
                }
                deltaPerRound = bonusPerRound;
            }
            case "freizeitpark" -> {
                // Freizeitpark is only valuable with Bahnhof (need 2d6 for meaningful doubles)
                if (!stats.hasBahnhof) return 0.0;

                // P(doubles) with 2d6 = 6/36 = 1/6
                // Value = P(doubles) × expected income from a second roll
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, oppCoins);
                double secondRollEV = CardIncome.bestDiceEV(true, payout);
                deltaPerRound = (1.0 / 6.0) * secondRollEV;
            }
            case "funkturm" -> {
                // Funkturm value = expected improvement from rerolling below-average results
                // funkturmEV = baseline + Σ P(r) × max(0, baseline - gain(r))
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, oppCoins);
                boolean use2d6 = stats.hasBahnhof
                        && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
                double baseline = CardIncome.weightedRollEV(use2d6, payout);
                double rerollGain = 0.0;
                if (use2d6) {
                    for (int r = 2; r <= 12; r++) {
                        double g = payout.applyAsDouble(r);
                        if (g < baseline) rerollGain += CardIncome.P2[r] * (baseline - g);
                    }
                } else {
                    for (int r = 1; r <= 6; r++) {
                        double g = payout.applyAsDouble(r);
                        if (g < baseline) rerollGain += CardIncome.P1[r] * (baseline - g);
                    }
                }
                deltaPerRound = rerollGain;
            }
            default -> { return 0.0; }
        }

        return deltaPerRound * remainingTurns;
    }

    /**
     * Computes the total positive income for a player on a given roll (own turn perspective).
     * Used for landmark weight calculations.
     */
    private static double computePlayerIncomeForRoll(Player player, CardIncome.PlayerStats stats,
                                                      int roll, int[] oppCoins) {
        int net = 0;
        net += CardIncome.sumColorIncome(player, "blau", roll, stats, 99, oppCoins);
        net += CardIncome.sumColorIncome(player, "grün", roll, stats, 99, oppCoins);
        // Purple cards contribute only on own turn and roll == 6
        for (Project p : player.getOwned_projects()) {
            if ("lila".equals(p.getColor())) {
                net += CardIncome.get_I(roll, p.getId(), true, stats.hasEinkaufszentrum,
                        stats.foodCount, stats.animalCount, stats.productionCount,
                        99, oppCoins);
            }
        }
        return net;
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
