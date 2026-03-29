package logic.probability;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Win-probability estimation: analytical softmax and Monte Carlo.
 *
 * <p>All methods are package-visible only — callers outside this package should go
 * through {@link ProbabilityCalc}.
 */
class WinProbabilityCalc {

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
     * These values are in the same unit (coins) as the
     * {@code portfolioEvPerRound × remainingTurns} term, so the softmax scoring
     * gives each landmark meaningful weight in the win-probability estimate.
     * Any landmark not listed here gets the fallback weight 20.0.
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
     * Calibrated from MC statistics: average game length ≈ 25 effective turns across all players
     * (roughly 6–8 turns per player in a 3–4 player game before someone wins).
     */
    private static final double TOTAL_EXPECTED_TURNS = 25.0;

    private WinProbabilityCalc() {}

    // -------------------------------------------------------------------------
    // Baseline win probability (analytical softmax)
    // -------------------------------------------------------------------------

    /**
     * Returns the baseline win probability for {@code playerIndex} using the analytical
     * softmax score approximation.
     *
     * @param gs          current game state
     * @param playerIndex the player whose win probability to estimate
     * @return estimated win probability in [0, 1]
     */
    static double computeBaselineWinProb(GameState gs, int playerIndex) {
        return softmaxEntry(computeScores(gs, 0), playerIndex);
    }

    // -------------------------------------------------------------------------
    // estimateWinProbDelta — analytical or Monte Carlo
    // -------------------------------------------------------------------------

    /**
     * Estimates the change in win probability for playerIndex from buying {@code candidate}.
     *
     * <h3>Analytical mode ({@code mcSimulations == 0})</h3>
     * Softmax score: {@code score(p) = Σ singleCardEvPerRound × remainingTurns + Σ LANDMARK_WEIGHT}.
     *
     * <h3>Monte Carlo mode ({@code mcSimulations > 0})</h3>
     * Runs parallel full-game simulations for both the baseline and post-buy state.
     *
     * @param turnsElapsed effective turns elapsed in the session (0 = use static fallback estimate)
     */
    static double estimateWinProbDelta(GameState gs, int playerIndex,
                                        Project candidate, int mcSimulations) {
        return estimateWinProbDelta(gs, playerIndex, candidate, mcSimulations, 0);
    }

    static double estimateWinProbDelta(GameState gs, int playerIndex,
                                        Project candidate, int mcSimulations, int turnsElapsed) {
        if (mcSimulations > 0) {
            double baseline = mcWinRate(gs, playerIndex, mcSimulations);
            GameState stateAfter = gs.copy();
            stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
            double afterBuy = mcWinRate(stateAfter, playerIndex, mcSimulations);
            return afterBuy - baseline;
        }

        // Analytical path
        double[] scoresBefore = computeScores(gs, turnsElapsed);
        double pWinBefore = softmaxEntry(scoresBefore, playerIndex);

        GameState stateAfter = gs.copy();
        stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double[] scoresAfter = computeScores(stateAfter, turnsElapsed);
        double pWinAfter = softmaxEntry(scoresAfter, playerIndex);

        return pWinAfter - pWinBefore;
    }

    // -------------------------------------------------------------------------
    // mcWinRate — parallel Monte Carlo
    // -------------------------------------------------------------------------

    /**
     * Runs {@code numSims} Monte Carlo simulations in parallel and returns the
     * fraction in which {@code playerIndex} wins. Uses greedy buy policy (temperature=0).
     */
    static double mcWinRate(GameState state, int playerIndex, int numSims) {
        return mcWinRate(state, playerIndex, numSims, 0.0);
    }

    /**
     * Runs {@code numSims} Monte Carlo simulations in parallel and returns the
     * fraction in which {@code playerIndex} wins.
     *
     * @param state       starting state (read-only; a copy is taken per simulation)
     * @param playerIndex player whose win rate is measured
     * @param numSims     number of simulations to run
     * @param temperature Boltzmann temperature for buy policy (0.0 = greedy)
     * @return win rate in [0, 1]
     */
    static double mcWinRate(GameState state, int playerIndex, int numSims, double temperature) {
        int[] outcomes = IntStream.range(0, numSims)
                .parallel()
                .map(i -> GameSimulator.simulate(state.copy(), ThreadLocalRandom.current(), temperature))
                .toArray();

        long wins = 0;
        int timeouts = 0;
        for (int w : outcomes) {
            if (w == playerIndex) wins++;
            else if (w == -1) timeouts++;
        }

        if (timeouts > numSims / 100) {
            System.err.println("[GameSimulator] WARNING: " + timeouts + "/" + numSims
                    + " simulations timed out (>" + GameSimulator.MAX_TURNS
                    + " turns). State may be degenerate.");
            GameSimulator.TIMEOUT_COUNT.addAndGet(timeouts);
        }

        return (double) wins / numSims;
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
     * <p>Unlike the previous isolated {@code singleCardEvPerRound} approach, this accounts
     * for each player's actual card synergies (Einkaufszentrum bonuses, category multipliers
     * for Molkerei/Möbelfabrik/Markthalle, and opponent coin counts for Stadion/Fernsehsender).
     *
     * <h3>coinAdvantage term</h3>
     * {@code (coins_p − avg_opponent_coins) / COIN_ADVANTAGE_SCALE} — adds a coin-level
     * signal that is otherwise only captured indirectly through red-card EV. Contributes
     * roughly 10–20% of the EV component for typical mid-game coin spreads.
     *
     * <h3>endgameProximityBonus</h3>
     * If a player owns exactly 3 landmarks and has enough coins to buy the last one,
     * their score is multiplied by 2.5. This prevents Softmax from underestimating
     * imminent-win states: without this term a player about to win looks similar to one
     * who is 5+ turns away.
     *
     * @param turnsElapsed effective turns elapsed across all players (0 = use static fallback).
     *                     Used to compute a dynamic remaining-turns estimate.
     */
    static double[] computeScores(GameState gs, int turnsElapsed) {
        Player[] players = gs.getPlayers();
        int n = players.length;

        // Dynamic remaining-turns estimate:
        // remainingTurns = max(3, TOTAL_EXPECTED_TURNS − turnsElapsed/n)
        // turnsElapsed/n = average turns per player so far.
        double remainingTurns = (turnsElapsed > 0)
                ? Math.max(3.0, TOTAL_EXPECTED_TURNS - (double) turnsElapsed / n)
                : REMAINING_TURNS_FALLBACK;

        // Precompute average coins per player for the coinAdvantage term
        double totalCoins = 0;
        for (Player p : players) totalCoins += p.getCoins();
        double avgCoins = totalCoins / n;

        double[] scores = new double[n];

        for (int i = 0; i < n; i++) {
            int[] opponentCoins = CardIncome.buildOpponentCoins(players, i);
            double score = CardIncome.playerEvPerRound(players[i], n, opponentCoins)
                    * remainingTurns;

            // Landmark weights
            int landmarkCount = 0;
            for (Project p : players[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) {
                    score += LANDMARK_WEIGHTS.getOrDefault(p.getId(), LANDMARK_WEIGHT_DEFAULT);
                    landmarkCount++;
                }
            }

            // coinAdvantage term: relative coin position contributes a direct score bonus
            double coinAdv = (players[i].getCoins() - avgCoins) / COIN_ADVANTAGE_SCALE;
            score += coinAdv;

            // endgameProximityBonus: player has 3 GPs and can buy the last one immediately
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
