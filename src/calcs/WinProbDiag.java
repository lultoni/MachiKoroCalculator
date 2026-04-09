package calcs;

import core.GameState;

/**
 * Diagnostic helper that exposes package-private {@link WinProbability#computeScores}
 * and {@link WinProbability#SOFTMAX_TEMPERATURE} for test/diagnostic purposes.
 * Not part of the public API.
 */
public final class WinProbDiag {
    private WinProbDiag() {}

    /** Returns the raw softmax heuristic scores for all players. */
    public static double[] computeScores(GameState gs) {
        return WinProbability.computeScores(gs);
    }

    /** Returns the softmax probability for a specific player. */
    public static double softmaxEntry(double[] scores, int index) {
        return WinProbability.softmaxEntry(scores, index);
    }

    /** Sets the softmax temperature (for calibration sweeps). */
    public static void setTemperature(double t) {
        WinProbability.SOFTMAX_TEMPERATURE = t;
    }

    /** Returns the current softmax temperature. */
    public static double getTemperature() {
        return WinProbability.SOFTMAX_TEMPERATURE;
    }
}
