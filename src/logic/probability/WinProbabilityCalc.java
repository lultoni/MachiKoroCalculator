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

    /** Landmark weight in the softmax score function. */
    private static final double LANDMARK_WEIGHT = 2.0;

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
     * fraction in which {@code playerIndex} wins.
     *
     * @param state       starting state (read-only; a copy is taken per simulation)
     * @param playerIndex player whose win rate is measured
     * @param numSims     number of simulations to run
     * @return win rate in [0, 1]
     */
    static double mcWinRate(GameState state, int playerIndex, int numSims) {
        int[] outcomes = IntStream.range(0, numSims)
                .parallel()
                .map(i -> GameSimulator.simulate(state.copy(), ThreadLocalRandom.current()))
                .toArray();

        long wins = 0;
        int timeouts = 0;
        for (int w : outcomes) {
            if (w == playerIndex) wins++;
            else if (w == -1) timeouts++;
        }

        if (timeouts > numSims / 100) { // more than 1% timeouts
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
     * Computes a heuristic score for each player using synergy-aware per-round EV:
     * {@code score(p) = playerEvPerRound(p) × remainingTurns + Σ LANDMARK_WEIGHT}.
     *
     * <p>Unlike the previous isolated {@code singleCardEvPerRound} approach, this accounts
     * for each player's actual card synergies (Einkaufszentrum bonuses, category multipliers
     * for Molkerei/Möbelfabrik/Markthalle, and opponent coin counts for Stadion/Fernsehsender).
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

        double[] scores = new double[n];

        for (int i = 0; i < n; i++) {
            int[] opponentCoins = CardIncome.buildOpponentCoins(players, i);
            double score = CardIncome.playerEvPerRound(players[i], n, opponentCoins)
                    * remainingTurns;
            for (Project p : players[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) score += LANDMARK_WEIGHT;
            }
            scores[i] = score;
        }
        return scores;
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
