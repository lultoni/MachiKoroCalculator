package calcs;

import core.*;

/**
 * Per-roll luck computation using the backgammon model.
 *
 * <p><b>Formula:</b>
 * <pre>
 *   Luck(roll) = WR_after(actual_roll) - E[WR_after(all_possible_rolls)]
 * </pre>
 *
 * <p>For each possible roll outcome, we copy the game state, apply income via
 * {@link RollResolver#computeAllDeltasForRoll}, and evaluate win rate via either
 * Monte Carlo simulation ({@link GameSimulator#mcWinRate}) or the
 * {@link WinProbability} softmax heuristic. The {@code useMc} flag selects the mode:
 * MC is more accurate but slower; heuristic is instant but has ~0.25 MAE.
 *
 * <p><b>Properties:</b>
 * <ul>
 *   <li>Luck &gt; 0 means the actual roll was better than average (lucky).</li>
 *   <li>Luck &lt; 0 means the actual roll was worse than average (unlucky).</li>
 *   <li>Over many rolls, the sum of luck values for any player converges to ~0.</li>
 * </ul>
 *
 * <p><b>Doubles handling:</b> When the player uses 2d6, even sums can come from
 * doubles or non-doubles. This implementation does not split doubles vs non-doubles
 * for the hypothetical rolls — it enumerates only by sum (2-12). The Freizeitpark
 * bonus turn effect is therefore averaged out. This is acceptable because the luck
 * value captures the <i>income</i> difference across rolls, and doubles with FZP
 * only add a bonus income roll (which is itself random and averaged by MC).
 *
 * @see <a href="https://www.gnu.org/software/gnubg/">GNU Backgammon luck model</a>
 */
public final class LuckAnalyzer {

    private LuckAnalyzer() {}

    /**
     * Result of a per-roll luck computation.
     *
     * @param luck          actual WR minus expected WR (positive = lucky)
     * @param wrAfterActual win rate after the actual roll was applied
     * @param expectedWr    probability-weighted average WR across all possible rolls
     * @param wrPerRoll     WR for each possible roll in order (rolls 1-6 for 1d6, rolls 2-12 for 2d6).
     *                      Index 0 = roll 1 (1d6) or roll 2 (2d6), etc.
     */
    public record RollLuck(double luck, double wrAfterActual, double expectedWr, double[] wrPerRoll) {
        /** Convenience constructor without wrPerRoll (backward compatibility). */
        public RollLuck(double luck, double wrAfterActual, double expectedWr) {
            this(luck, wrAfterActual, expectedWr, null);
        }
    }

    /**
     * Computes the per-roll luck for a specific dice outcome using Monte Carlo.
     *
     * <p>Equivalent to {@code computeRollLuck(state, activePlayer, actualRoll, usedTwoDice, mcSims, true)}.
     *
     * @param stateBeforeRoll game state before the dice roll (not mutated)
     * @param activePlayer    index of the rolling player
     * @param actualRoll      the dice total that was actually rolled
     * @param usedTwoDice     true if the player rolled 2d6 (Bahnhof + high-range cards)
     * @param mcSims          number of MC simulations per roll outcome (higher = more accurate)
     * @return {@link RollLuck} with luck value, actual WR, and expected WR
     */
    public static RollLuck computeRollLuck(GameState stateBeforeRoll, int activePlayer,
                                            int actualRoll, boolean usedTwoDice, int mcSims) {
        return computeRollLuck(stateBeforeRoll, activePlayer, actualRoll, usedTwoDice, mcSims, true);
    }

    /**
     * Computes the per-roll luck for a specific dice outcome.
     *
     * <p>The {@code stateBeforeRoll} should be the game position immediately before
     * dice are rolled (after the previous buy phase). The method:
     * <ol>
     *   <li>Enumerates all possible roll outcomes (1-6 for 1d6, 2-12 for 2d6)</li>
     *   <li>For each outcome: copies state, applies income, evaluates WR</li>
     *   <li>Computes the probability-weighted expected WR</li>
     *   <li>Returns the difference: actual WR minus expected WR</li>
     * </ol>
     *
     * @param stateBeforeRoll game state before the dice roll (not mutated)
     * @param activePlayer    index of the rolling player
     * @param actualRoll      the dice total that was actually rolled
     * @param usedTwoDice     true if the player rolled 2d6 (Bahnhof + high-range cards)
     * @param mcSims          number of MC simulations per roll outcome (ignored when useMc is false)
     * @param useMc           true = Monte Carlo evaluation (accurate, slow);
     *                        false = WinProbability heuristic (instant, ~0.25 MAE)
     * @return {@link RollLuck} with luck value, actual WR, and expected WR
     */
    public static RollLuck computeRollLuck(GameState stateBeforeRoll, int activePlayer,
                                            int actualRoll, boolean usedTwoDice,
                                            int mcSims, boolean useMc) {
        double expectedWr = 0.0;
        double wrAfterActual = 0.0;

        if (usedTwoDice) {
            // 2d6: enumerate sums 2-12 (11 values)
            double[] wrPerRoll = new double[11]; // index 0 = roll 2, index 10 = roll 12
            for (int r = 2; r <= 12; r++) {
                double wr = wrAfterRoll(stateBeforeRoll, activePlayer, r, mcSims, useMc);
                wrPerRoll[r - 2] = wr;
                expectedWr += CardIncome.P2[r] * wr;
                if (r == actualRoll) wrAfterActual = wr;
            }
            return new RollLuck(wrAfterActual - expectedWr, wrAfterActual, expectedWr, wrPerRoll);
        } else {
            // 1d6: enumerate 1-6 (6 values)
            double[] wrPerRoll = new double[6]; // index 0 = roll 1, index 5 = roll 6
            for (int r = 1; r <= 6; r++) {
                double wr = wrAfterRoll(stateBeforeRoll, activePlayer, r, mcSims, useMc);
                wrPerRoll[r - 1] = wr;
                expectedWr += CardIncome.P1[r] * wr;
                if (r == actualRoll) wrAfterActual = wr;
            }
            return new RollLuck(wrAfterActual - expectedWr, wrAfterActual, expectedWr, wrPerRoll);
        }
    }

    /**
     * Applies a roll to a copy of the state and evaluates the resulting win rate.
     */
    private static double wrAfterRoll(GameState stateBeforeRoll, int activePlayer,
                                       int roll, int mcSims, boolean useMc) {
        GameState copy = stateBeforeRoll.copy();
        Player[] players = copy.getPlayers();

        // Apply income via authoritative RollResolver
        int[] deltas = RollResolver.computeAllDeltasForRoll(copy, activePlayer, roll);
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }

        // Handle Bürohaus swap on roll 6
        if (roll == 6 && players[activePlayer].hasProject("bürohaus")) {
            BürohausLogic.executeSwap(copy, activePlayer);
        }

        return useMc
                ? GameSimulator.mcWinRate(copy, activePlayer, mcSims)
                : WinProbability.computeBaselineWinProb(copy, activePlayer);
    }
}
