package calcs;

/**
 * Options controlling how ranking methods compute purchase recommendations.
 *
 * <p>Create an instance and set fields as needed before passing to ranking methods.
 * All fields have sensible defaults for a standard 10-turn analytical analysis.
 */
public class RankingOptions {

    /** Default horizon (turns) used when no instance is available. */
    public static final int DEFAULT_HORIZON = 10;

    /** Number of future turns to look ahead when computing discounted ROI. Default: 10. */
    public int horizonTurns = DEFAULT_HORIZON;

    /** Per-turn discount factor for the geometric ROI series (0 < γ ≤ 1). Default: 0.95. */
    public double discountFactor = 0.95;

    /**
     * Number of Monte Carlo game simulations for win-probability deltas.
     * 0 uses the analytical softmax heuristic. Default: 0.
     */
    public int mcSimulations = 0;

    /** Whether to compute {@link RankEntry#winProbDelta} for each candidate. Default: false. */
    public boolean includeWinProbDelta = false;

    /**
     * Effective turns elapsed so far (across all players). Used for dynamic remaining-turns
     * estimate in the softmax scorer. 0 uses static fallback. Default: 0.
     */
    public int turnsElapsed = 0;

    /**
     * Boltzmann temperature for the Monte Carlo buy policy (T ≥ 0).
     * 0.0 = greedy, 0.7 = recommended exploration, ∞ = uniform random.
     * Only takes effect when {@link #mcSimulations} > 0. Default: 0.0.
     */
    public double mcExplorationTemp = 0.0;
}
