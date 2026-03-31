package engine;

import core.Project;

import java.util.List;

/**
 * The output of a {@link SimulationEngine#evaluate} call.
 *
 * <p>Contains a ranked list of all evaluated purchase options, engine metadata,
 * and an explanation of why the top recommendation was chosen.
 * The engine shares everything it computed — the UI decides what to display.
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Iterate {@link #rankedOptions} from index 0 (best) to N (worst).</li>
 *   <li>The first entry is the top recommendation; use its {@link Option#explanationFactors}
 *       for expandable detail in the UI.</li>
 *   <li>Check {@link #iterationsUsed} and {@link #computeTimeMs} for quality indicators.</li>
 * </ol>
 */
public final class EngineResult {

    /**
     * A single evaluated purchase option within a ranked result set.
     */
    public static final class Option {

        /** The candidate project. Never null. */
        public final Project project;

        /**
         * The engine's primary score for this option (higher = better).
         * Units depend on the engine (e.g. estimated win-rate, discounted ROI, EV).
         */
        public final double score;

        /**
         * Human-readable factors that contributed to this score, ordered by magnitude.
         * Each entry is a short phrase like "High EV on own turn (+2.4¢/turn)" or
         * "Win-probability boost +4.2%". May be empty but never null.
         */
        public final List<String> explanationFactors;

        /**
         * Optional per-metric breakdown for power-user display (e.g. immediateEV, variance,
         * portfolioDeltaEV). Keys are metric names; values are formatted strings.
         * May be null if the engine does not provide detailed metrics.
         */
        public final java.util.Map<String, String> metrics;

        /**
         * True if the player can currently afford this card (coins ≥ cost).
         */
        public final boolean affordable;

        public Option(Project project, double score, List<String> explanationFactors,
                      java.util.Map<String, String> metrics, boolean affordable) {
            this.project            = project;
            this.score              = score;
            this.explanationFactors = explanationFactors != null
                    ? List.copyOf(explanationFactors) : List.of();
            this.metrics            = metrics;
            this.affordable         = affordable;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /**
     * All evaluated options, sorted best-to-worst by {@link Option#score}.
     * The list includes both affordable and unaffordable options (see {@link Option#affordable}).
     * Never null or empty.
     */
    public final List<Option> rankedOptions;

    /**
     * The engine's confidence in the top recommendation, in [0, 1].
     * 1.0 = deterministic/certain; lower values indicate the ranking is close or uncertain.
     * May be {@code Double.NaN} if the engine does not provide a confidence measure.
     */
    public final double confidence;

    /** Number of MCTS rollouts / tree nodes / evaluation passes actually performed. */
    public final int iterationsUsed;

    /** Wall-clock time taken by the engine in milliseconds. */
    public final long computeTimeMs;

    /**
     * Optional free-form metadata string for debug/logging (e.g. tree depth reached,
     * cache hit rate). May be null.
     */
    public final String debugInfo;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public EngineResult(List<Option> rankedOptions, double confidence,
                        int iterationsUsed, long computeTimeMs, String debugInfo) {
        if (rankedOptions == null || rankedOptions.isEmpty())
            throw new IllegalArgumentException("rankedOptions must be non-empty");
        this.rankedOptions   = List.copyOf(rankedOptions);
        this.confidence      = confidence;
        this.iterationsUsed  = iterationsUsed;
        this.computeTimeMs   = computeTimeMs;
        this.debugInfo       = debugInfo;
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    /** Returns the top-ranked option (the engine's primary recommendation). */
    public Option topRecommendation() {
        return rankedOptions.get(0);
    }
}
