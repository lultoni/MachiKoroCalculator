package logic.probability;

/**
 * Options controlling how {@link ProbabilityCalc#rankPurchasableProjects} computes rankings.
 *
 * <p>Create an instance and set fields as needed before passing to ranking methods.
 * All fields have sensible defaults for a standard 10-turn analytical analysis.
 */
public class RankingOptions {

    /** Default horizon (turns) used when no instance is available, e.g. in UI string formatting. */
    public static final int DEFAULT_HORIZON = 10;

    /**
     * Number of future turns to look ahead when computing discounted ROI.
     * Default: {@code 10}.
     */
    public int horizonTurns = DEFAULT_HORIZON;

    /**
     * Per-turn discount factor for the geometric ROI series (0 &lt; γ ≤ 1).
     * A value of 1.0 means no discounting; lower values penalise later returns more heavily.
     * Default: {@code 0.95}.
     */
    public double discountFactor = 0.95;

    /**
     * Number of Monte Carlo game simulations to run when computing win-probability deltas.
     * {@code 0} uses the analytical softmax heuristic instead (fast, approximate).
     * Values ≥ 1000 give a ±3% confidence interval at 95% confidence.
     * Default: {@code 0} (analytical only).
     */
    public int mcSimulations = 0;

    /**
     * Whether to compute and populate {@link RankEntry#winProbDelta} for each candidate.
     * Expensive when combined with MC simulations; off by default.
     * Default: {@code false}.
     */
    public boolean includeWinProbDelta = false;

    /**
     * Number of effective turns that have elapsed so far in the session (non-bonus turns,
     * across all players). Used to compute a dynamic remaining-turns estimate for the
     * softmax win-probability scorer. {@code 0} (default) uses the static fallback estimate.
     *
     * <p>Pass {@code session.getEffectiveTurnCount()} here from the UI for improved accuracy.
     */
    public int turnsElapsed = 0;

    /**
     * Boltzmann temperature for the Monte Carlo buy policy (T ≥ 0).
     *
     * <p>Controls how randomly simulated players choose which card to buy:
     * <ul>
     *   <li>{@code T = 0.0} — greedy (always buy the highest-scoring card; current default)</li>
     *   <li>{@code T = 0.7} — recommended: soft exploration around the greedy optimum</li>
     *   <li>{@code T → ∞} — uniform random selection among affordable cards</li>
     * </ul>
     *
     * <p>Higher temperature means more variety in opponent play-styles, which reduces
     * systematic bias in win-rate estimates when the real player deviates from greedy.
     * Only takes effect when {@link #mcSimulations} &gt; 0.
     *
     * <p>Default: {@code 0.0} (greedy, backward-compatible).
     */
    public double mcExplorationTemp = 0.0;
}
