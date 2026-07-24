package calcs;

import core.*;

/**
 * Per-roll luck computation using a coin-delta model.
 *
 * <p><b>Formula:</b>
 * <pre>
 *   netDelta(roll) = activePlayerGain(roll) - sum(opponentGains(roll))
 *   expectedDelta  = Σ prob(r) * netDelta(r)   (over all possible rolls)
 *   luck(roll)     = netDelta(actualRoll) - expectedDelta
 * </pre>
 *
 * <p>Opponent gains are subtracted because coins in their hands are disadvantageous
 * to the active player. This gives intuitive luck values: rolling your best income
 * number is always the luckiest outcome, and a roll that pays the opponent heavily
 * while giving you nothing is the unluckiest.
 *
 * <p>The formula is fully deterministic — no Monte Carlo, no WinProbability heuristic.
 * Values are in coins (e.g. +3.2 means this roll paid 3.2 coins above the average
 * net outcome across all rolls).
 *
 * <p><b>Properties:</b>
 * <ul>
 *   <li>Luck &gt; 0 means the actual roll was better than average (lucky).</li>
 *   <li>Luck &lt; 0 means the actual roll was worse than average (unlucky).</li>
 *   <li>The expected value of luck over many rolls converges to 0 (unbiased).</li>
 * </ul>
 *
 * <p><b>Doubles handling:</b> When the player uses 2d6, even sums can come from
 * doubles or non-doubles. Luck is computed by sum only (2-12), not split by doubles
 * flag. The Freizeitpark bonus-turn effect averages out across many rolls.
 *
 * <p><b>Legacy parameters:</b> {@code mcSims} and {@code useMc} are accepted but
 * ignored — the formula is always deterministic.
 */
public final class LuckAnalyzer {

    private LuckAnalyzer() {}

    /**
     * Result of a per-roll luck computation.
     *
     * @param luck          actual netDelta minus expected netDelta (positive = lucky), in coins
     * @param wrAfterActual netDelta for the actual roll (coins gained minus opponent coins gained)
     * @param expectedWr    probability-weighted expected netDelta across all possible rolls
     * @param wrPerRoll     netDelta for each possible roll in order (rolls 1-6 for 1d6, rolls 2-12 for 2d6).
     *                      Index 0 = roll 1 (1d6) or roll 2 (2d6), etc.
     *                      Field name kept for JSON/API compatibility.
     */
    public record RollLuck(double luck, double wrAfterActual, double expectedWr, double[] wrPerRoll) {
        /** Convenience constructor without wrPerRoll (backward compatibility). */
        public RollLuck(double luck, double wrAfterActual, double expectedWr) {
            this(luck, wrAfterActual, expectedWr, null);
        }
    }

    /**
     * Computes the per-roll luck for a specific dice outcome (deterministic, coin-delta model).
     *
     * @param stateBeforeRoll game state before the dice roll (not mutated)
     * @param activePlayer    index of the rolling player
     * @param actualRoll      the dice total that was actually rolled
     * @param usedTwoDice     true if the player rolled 2d6
     * @param mcSims          ignored (kept for API compatibility)
     * @return {@link RollLuck} with luck value, actual netDelta, and expected netDelta
     */
    public static RollLuck computeRollLuck(GameState stateBeforeRoll, int activePlayer,
                                            int actualRoll, boolean usedTwoDice, int mcSims) {
        return computeRollLuck(stateBeforeRoll, activePlayer, actualRoll, usedTwoDice, mcSims, true);
    }

    /**
     * Computes the per-roll luck for a specific dice outcome (deterministic, coin-delta model).
     *
     * @param stateBeforeRoll game state before the dice roll (not mutated)
     * @param activePlayer    index of the rolling player
     * @param actualRoll      the dice total that was actually rolled
     * @param usedTwoDice     true if the player rolled 2d6
     * @param mcSims          ignored (kept for API compatibility)
     * @param useMc           ignored (kept for API compatibility)
     * @return {@link RollLuck} with luck value, actual netDelta, and expected netDelta
     */
    public static RollLuck computeRollLuck(GameState stateBeforeRoll, int activePlayer,
                                            int actualRoll, boolean usedTwoDice,
                                            int mcSims, boolean useMc) {
        double expectedDelta = 0.0;
        double actualDelta = 0.0;

        if (usedTwoDice) {
            double[] netPerRoll = new double[11]; // index 0 = roll 2, index 10 = roll 12
            for (int r = 2; r <= 12; r++) {
                double nd = netDelta(stateBeforeRoll, activePlayer, r);
                netPerRoll[r - 2] = nd;
                expectedDelta += CardIncome.P2[r] * nd;
                if (r == actualRoll) actualDelta = nd;
            }
            return new RollLuck(actualDelta - expectedDelta, actualDelta, expectedDelta, netPerRoll);
        } else {
            double[] netPerRoll = new double[6]; // index 0 = roll 1, index 5 = roll 6
            for (int r = 1; r <= 6; r++) {
                double nd = netDelta(stateBeforeRoll, activePlayer, r);
                netPerRoll[r - 1] = nd;
                expectedDelta += CardIncome.P1[r] * nd;
                if (r == actualRoll) actualDelta = nd;
            }
            return new RollLuck(actualDelta - expectedDelta, actualDelta, expectedDelta, netPerRoll);
        }
    }

    /**
     * Computes the net coin delta for the active player on a given roll.
     * netDelta = activePlayerGain - sum(opponentGains).
     * Opponent gains are subtracted: coins in their hands weaken the active player's position.
     */
    private static double netDelta(GameState state, int activePlayer, int roll) {
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        double net = deltas[activePlayer];
        for (int i = 0; i < deltas.length; i++) {
            if (i != activePlayer) net -= Math.max(0, deltas[i]);
        }
        return net;
    }
}
