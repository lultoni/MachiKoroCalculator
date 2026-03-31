package engine;

/**
 * Generic configuration container for a {@link SimulationEngine} invocation.
 *
 * <p>Each engine type interprets these fields according to its own needs.
 * Configs are stored in the engine registry JSON alongside the engine class identifier.
 *
 * <h2>Common fields</h2>
 * <ul>
 *   <li>{@link #iterations} — number of rollout iterations (MCTS) or evaluation passes</li>
 *   <li>{@link #timeBudgetMs} — wall-clock time budget in milliseconds (0 = unlimited)</li>
 *   <li>{@link #riskToleranceWeight} — scalar weight for risk vs. EV trade-off (0.0 = EV-only,
 *       1.0 = max risk-averse)</li>
 * </ul>
 *
 * <p>Engine-specific settings (e.g. rollout policy, max depth) should be stored in
 * {@link #extra} using well-known key names documented by the engine class.
 */
public final class EngineConfig {

    /** Maximum number of iterations (MCTS rollouts, tree-search nodes, etc.). 0 = unlimited. */
    public final int iterations;

    /** Wall-clock time budget in milliseconds. 0 = no time limit. */
    public final int timeBudgetMs;

    /**
     * Risk-tolerance weight in [0, 1].
     * 0.0 = pure EV maximiser; 1.0 = fully risk-averse (minimise variance regardless of EV).
     */
    public final double riskToleranceWeight;

    /**
     * Engine-specific configuration key-value pairs (e.g. rolloutPolicy, maxDepth, evaluator).
     * May be {@code null} if the engine requires no extra config.
     */
    public final java.util.Map<String, String> extra;

    /**
     * Creates a fully specified config.
     *
     * @param iterations          rollout / iteration budget (0 = unlimited)
     * @param timeBudgetMs        wall-clock time limit in ms (0 = unlimited)
     * @param riskToleranceWeight risk weight in [0, 1]
     * @param extra               engine-specific key-value settings (may be null)
     */
    public EngineConfig(int iterations, int timeBudgetMs, double riskToleranceWeight,
                        java.util.Map<String, String> extra) {
        this.iterations          = iterations;
        this.timeBudgetMs        = timeBudgetMs;
        this.riskToleranceWeight = riskToleranceWeight;
        this.extra               = extra != null ? java.util.Collections.unmodifiableMap(extra) : null;
    }

    /** Convenience factory: iteration-only config with no time limit and default risk weight (0). */
    public static EngineConfig ofIterations(int iterations) {
        return new EngineConfig(iterations, 0, 0.0, null);
    }

    /** Returns the value of an extra config key, or {@code defaultValue} if absent. */
    public String getExtra(String key, String defaultValue) {
        if (extra == null) return defaultValue;
        return extra.getOrDefault(key, defaultValue);
    }
}
