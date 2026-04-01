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
 *   <li>The first entry is the top recommendation; use its {@link Option#structuredFactors}
 *       for expandable detail in the UI, or {@link Option#explanationFactors} for flat strings.</li>
 *   <li>Check {@link #iterationsUsed} and {@link #computeTimeMs} for quality indicators.</li>
 * </ol>
 */
public final class EngineResult {

    // -------------------------------------------------------------------------
    // ExplanationFactor — structured, weighted explanation entry
    // -------------------------------------------------------------------------

    /**
     * A single weighted explanation factor for a purchase recommendation.
     *
     * <p>Factors are generated per-option and sorted by {@link #weight} descending.
     * The UI renders them as expandable bullet points: the {@link #summary} is always
     * visible; clicking expands to show {@link #detail}.
     *
     * <h2>Categories</h2>
     * Categories are free-form strings so engines can define their own. Known categories:
     * {@code "winRate"}, {@code "income"}, {@code "synergy"}, {@code "risk"},
     * {@code "tempo"}, {@code "landmark"}, {@code "scarcity"}, {@code "coverage"},
     * {@code "cost"}.
     */
    public static final class ExplanationFactor {

        /** Category label (e.g. "synergy", "risk", "tempo"). */
        public final String category;

        /**
         * Relative importance of this factor for this recommendation, in [0, 1].
         * Higher = more important. Factors are sorted by weight descending.
         * Weight represents how much this metric differentiates this option from the average.
         */
        public final double weight;

        /** One-line summary (e.g. "Synergy: +1.3 EV/round with 2 Bauernhöfe"). */
        public final String summary;

        /** Multi-sentence breakdown for the expandable detail view. */
        public final String detail;

        public ExplanationFactor(String category, double weight, String summary, String detail) {
            this.category = category;
            this.weight   = weight;
            this.summary  = summary;
            this.detail   = detail != null ? detail : "";
        }

        @Override
        public String toString() {
            return String.format("[%.2f] %s: %s", weight, category, summary);
        }
    }

    // -------------------------------------------------------------------------
    // Option — a single evaluated purchase candidate
    // -------------------------------------------------------------------------

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
         * Each entry is a short phrase derived from {@link #structuredFactors} summaries.
         * May be empty but never null. Kept for backward compatibility.
         */
        public final List<String> explanationFactors;

        /**
         * Structured explanation factors with categories, weights, and expandable detail.
         * Sorted by weight descending (highest-impact factor first).
         * May be empty but never null.
         */
        public final List<ExplanationFactor> structuredFactors;

        /**
         * One-line recommendation summary (e.g. "Buy Käsefabrik — strongest synergy
         * with your 2 Bauernhöfe"). May be null if the engine does not generate summaries.
         */
        public final String summarySentence;

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

        /**
         * Full constructor with structured factors and summary sentence.
         */
        public Option(Project project, double score, List<String> explanationFactors,
                      List<ExplanationFactor> structuredFactors, String summarySentence,
                      java.util.Map<String, String> metrics, boolean affordable) {
            this.project            = project;
            this.score              = score;
            this.explanationFactors = explanationFactors != null
                    ? List.copyOf(explanationFactors) : List.of();
            this.structuredFactors  = structuredFactors != null
                    ? List.copyOf(structuredFactors) : List.of();
            this.summarySentence    = summarySentence;
            this.metrics            = metrics;
            this.affordable         = affordable;
        }

        /**
         * Backward-compatible constructor without structured factors.
         */
        public Option(Project project, double score, List<String> explanationFactors,
                      java.util.Map<String, String> metrics, boolean affordable) {
            this(project, score, explanationFactors, null, null, metrics, affordable);
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
