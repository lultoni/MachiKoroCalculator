package calcs;

import core.GameState;

/**
 * Diagnostic helper that exposes package-private {@link WinProbability}
 * fields for test/diagnostic purposes. Not part of the public API.
 */
public final class WinProbDiag {
    private WinProbDiag() {}

    /** Returns the raw scores for all players (fast logistic path). */
    public static double[] computeScores(GameState gs) {
        return WinProbability.computeScores(gs);
    }

    /** Returns the turns-to-win estimates for all players. */
    public static double[] computeTurnsToWin(GameState gs) {
        return WinProbability.computeTurnsToWin(gs);
    }

    /** Returns the softmax probability for a specific player. */
    public static double softmaxEntry(double[] scores, int index) {
        return WinProbability.softmaxEntry(scores, index);
    }

    /** Sets the softmax temperature (for N-player path). */
    public static void setTemperature(double t) {
        WinProbability.SOFTMAX_TEMPERATURE = t;
    }

    /** Returns the current softmax temperature. */
    public static double getTemperature() {
        return WinProbability.SOFTMAX_TEMPERATURE;
    }

    /** Sets all logistic model weights at once. */
    public static void setWeights(double wBias, double wIncome, double wCoin,
                                   double wInvest, double wLandmark, double wTtw, double wRedDrain) {
        WinProbability.W_BIAS = wBias;
        WinProbability.W_INCOME_ADV = wIncome;
        WinProbability.W_COIN_ADV = wCoin;
        WinProbability.W_INVESTMENT_ADV = wInvest;
        WinProbability.W_LANDMARK_ADV = wLandmark;
        WinProbability.W_TTW_GAP = wTtw;
        WinProbability.W_RED_DRAIN = wRedDrain;
    }

    /** Returns the current weights. */
    public static double[] getWeights() {
        return new double[] {
            WinProbability.W_BIAS, WinProbability.W_INCOME_ADV,
            WinProbability.W_COIN_ADV, WinProbability.W_INVESTMENT_ADV,
            WinProbability.W_LANDMARK_ADV, WinProbability.W_TTW_GAP,
            WinProbability.W_RED_DRAIN
        };
    }

    /** Sets the number of MC rollouts for the default estimator. */
    public static void setMicroMcSims(int sims) {
        WinProbability.MICRO_MC_SIMS = sims;
    }

    /** Returns the current micro MC sim count. */
    public static int getMicroMcSims() {
        return WinProbability.MICRO_MC_SIMS;
    }
}
