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

    /**
     * Softmax temperature for win-probability conversion.
     *
     * <p>Raw heuristic scores range from ~30 (early game) to ~260 (endgame with landmarks).
     * Without temperature scaling, {@code exp(score)} overflows or collapses to argmax
     * (0.000/1.000) for even small score differences. The temperature T normalizes
     * the scores so that {@code exp(score/T)} produces meaningful probabilities.
     *
     * <p>Calibrated to T=65 against Monte Carlo ground truth (5000 greedy sims) across 10
     * representative game states spanning early/mid/endgame, symmetric/asymmetric.
     * Mean absolute error: ~0.123 (down from ~0.40 without temperature). Some cases
     * (red-heavy, purple-heavy) remain above 10% due to structural model limitations:
     * the static heuristic cannot capture dynamic synergy evolution (e.g., buying
     * Bahnhof to unlock dormant Familienrestaurant income).
     *
     * @see #softmaxEntry(double[], int)
     */
    static double SOFTMAX_TEMPERATURE = 65.0; // package-private for calibration

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
     * BitState overload — converts to GameState internally.
     *
     * @param bs          current bitwise game state
     * @param playerIndex the player whose win probability to estimate
     * @return estimated win probability in [0, 1]
     */
    public static double computeBaselineWinProb(core.BitState bs, int playerIndex) {
        return computeBaselineWinProb(bs.toGameState(), playerIndex);
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
     * <p>The game horizon (remaining turns) is based on the most advanced player's
     * landmark count: {@code max(3.0, TOTAL_EXPECTED_TURNS × (1 − maxLandmarks / 4))}.
     * This shared horizon prevents inflating scores for trailing players — their income
     * beyond the likely game end is worthless.
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

        double totalCoins = 0;
        for (Player p : players) totalCoins += p.getCoins();
        double avgCoins = totalCoins / n;

        // Per-player landmark counts
        int[] landmarkCounts = new int[n];
        for (int i = 0; i < n; i++) {
            for (Project proj : players[i].getOwned_projects()) {
                if (proj.isIs_grossprojekt()) landmarkCounts[i]++;
            }
        }

        // Game horizon: based on the most advanced player's landmark count.
        int maxLandmarks = 0;
        for (int lm : landmarkCounts) if (lm > maxLandmarks) maxLandmarks = lm;
        double gameHorizon = Math.max(3.0,
                TOTAL_EXPECTED_TURNS * (1.0 - (double) maxLandmarks / 4.0));

        // Precompute per-player income EV and red drain
        double[] evPerRound = new double[n];
        double[] redDrainPerRound = new double[n];
        for (int i = 0; i < n; i++) {
            int[] opp = CardIncome.buildOpponentCoins(players, i);
            evPerRound[i] = CardIncome.playerEvPerRound(players[i], n, opp);

            // Compute red drain from opponents' red cards
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                for (Project card : players[j].getOwned_projects()) {
                    if (!"rot".equals(card.getColor())) continue;
                    CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(players[j]);
                    for (int r = 1; r <= 6; r++) {
                        int loss = CardIncome.get_I(r, card.getId(), false, oppStats.hasEinkaufszentrum,
                                oppStats.foodCount, oppStats.animalCount, oppStats.productionCount,
                                Math.max(1, players[i].getCoins()), opp);
                        if (loss < 0) redDrainPerRound[i] += CardIncome.P1[r] * (-loss);
                    }
                }
            }
        }

        double avgEvPerRound = 0;
        for (double e : evPerRound) avgEvPerRound += e;
        avgEvPerRound /= n;
        double coinScale = Math.max(1.0, avgEvPerRound * 2.0);

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            int[] opponentCoins = CardIncome.buildOpponentCoins(players, i);

            // --- Component 1: Landmark progress (dominant signal) ---
            // Each landmark represents ~25% game completion. Score this as a
            // large discrete bonus that dwarfs income differences.
            double landmarkScore = landmarkCounts[i] * 20.0;

            // --- Component 2: Net income × horizon ---
            // Income advantage accrues over the remaining game. Red drain from
            // opponents reduces effective income.
            double netEvPerRound = evPerRound[i] - redDrainPerRound[i] * 0.5;

            // Strategic multiplier for red/purple cards: their disruption effect
            // (draining opponents) is worth more than raw income EV suggests.
            // Compute red/purple proportion of total income and boost it.
            double redPurpleEv = 0;
            {
                CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(players[i]);
                int avgOppCoins = 99;
                if (opponentCoins.length > 0) {
                    int sum = 0;
                    for (int c : opponentCoins) sum += c;
                    avgOppCoins = Math.max(1, sum / opponentCoins.length);
                }
                for (Project card : players[i].getOwned_projects()) {
                    String color = card.getColor();
                    if (!"rot".equals(color) && !"lila".equals(color)) continue;
                    for (int r = 1; r <= 6; r++) {
                        if ("rot".equals(color)) {
                            int rollerLoss = CardIncome.get_I(r, card.getId(), false,
                                    stats.hasEinkaufszentrum, stats.foodCount, stats.animalCount,
                                    stats.productionCount, avgOppCoins, opponentCoins);
                            int ownerGain = -rollerLoss;
                            if (ownerGain > 0) redPurpleEv += CardIncome.P1[r] * ownerGain * (n - 1);
                        } else {
                            int income = CardIncome.get_I(r, card.getId(), true,
                                    stats.hasEinkaufszentrum, stats.foodCount, stats.animalCount,
                                    stats.productionCount, 99, opponentCoins);
                            if (income > 0) redPurpleEv += CardIncome.P1[r] * income;
                        }
                    }
                }
            }
            // Red/purple disruption bonus: 100% extra value on top of raw EV.
            // Red cards drain opponents (slowing their landmark purchases) and purple
            // cards (Stadion/Fernsehsender) have outsized strategic impact beyond raw
            // income. The full EV is doubled to capture this disruption effect.
            double incomeScore = (netEvPerRound + redPurpleEv * 1.0) * gameHorizon;

            // --- Component 3: Landmark synergy weights ---
            for (Project p : players[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) {
                    incomeScore += computeLandmarkWeight(players[i], p.getId(), n, gameHorizon, opponentCoins);
                }
            }

            // --- Component 4: Coin advantage (diminishing returns) ---
            // Coins matter for buying landmarks, but with steep diminishing returns.
            double coinDiff = players[i].getCoins() - avgCoins;
            double coinAdv = Math.signum(coinDiff) * Math.sqrt(Math.abs(coinDiff))
                    / Math.max(0.5, Math.sqrt(coinScale));

            double score = landmarkScore + incomeScore + coinAdv;

            // --- Component 5: Endgame proximity bonus ---
            // Landmark proximity to buying the next landmark. With 3 landmarks,
            // the race to 22 coins dominates everything else.
            int landmarkCount = landmarkCounts[i];
            if (landmarkCount > 0) {
                int cheapestLmCost = cheapestMissingLandmarkCost(players[i]);
                if (cheapestLmCost > 0) {
                    double proximity = Math.max(0.0, Math.min(1.0,
                            (double) players[i].getCoins() / cheapestLmCost));
                    if (landmarkCount == 3 && proximity >= 1.0) {
                        // Can buy winning landmark NOW — overwhelming advantage
                        score += 500.0;
                    } else if (landmarkCount == 3) {
                        // 3 landmarks but can't quite afford: proximity to winning
                        // is the single most important factor. Heavy bonus.
                        score += 90.0 * proximity * proximity;
                    } else {
                        score += landmarkCount * landmarkCount * 10.0 * proximity;
                    }
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
     * @param opponentCoins  coin counts of each opponent (for purple card income clamping)
     * @return coin-equivalent landmark weight (≥ 0)
     */
    private static double computeLandmarkWeight(Player player, String landmarkId,
                                                 int numPlayers, double remainingTurns,
                                                 int[] opponentCoins) {
        CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(player);
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
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, opponentCoins);
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
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, opponentCoins);
                double secondRollEV = CardIncome.bestDiceEV(true, payout);
                deltaPerRound = (1.0 / 6.0) * secondRollEV;
            }
            case "funkturm" -> {
                // Funkturm value = expected improvement from rerolling below-average results
                // funkturmEV = baseline + Σ P(r) × max(0, baseline - gain(r))
                IntToDoubleFunction payout = r -> computePlayerIncomeForRoll(player, stats, r, opponentCoins);
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
     * Numerically stable softmax with temperature scaling: returns the probability
     * for index {@code i}.
     *
     * <p>Formula: {@code exp((score[i] - max) / T) / Σ exp((score[j] - max) / T)}
     *
     * <p>The temperature T ({@link #SOFTMAX_TEMPERATURE}) controls how sharply the
     * softmax responds to score differences. T → 0 produces argmax, T → ∞ produces
     * uniform. The calibrated value yields probabilities that approximate Monte Carlo
     * win rates across a range of game states.
     */
    static double softmaxEntry(double[] scores, int index) {
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) if (s > max) max = s;

        double T = SOFTMAX_TEMPERATURE;
        double sumExp = 0.0;
        for (double s : scores) sumExp += Math.exp((s - max) / T);

        return Math.exp((scores[index] - max) / T) / sumExp;
    }
}
