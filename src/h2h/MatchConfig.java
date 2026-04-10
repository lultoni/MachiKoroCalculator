package h2h;

import engine.EngineConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a head-to-head match between engines.
 *
 * <p>Each seat in the game is assigned an engine registry ID. The match runner resolves
 * IDs to engine instances and configs via the registry.
 *
 * <p>Per-engine config overrides can be specified via {@link #configOverrides}. When present,
 * the override map for each seat is merged with the registry entry's config (override keys
 * win). When absent, global overrides ({@link #timeBudgetMsPerEval}, {@link #iterationsPerEval})
 * are used as fallbacks (backward compat with CLI tools and tournaments).
 *
 * <h2>Config resolution order in {@link #buildSeatConfig}</h2>
 * <ol>
 *   <li>Per-seat configOverrides (if present) — merged with registry config</li>
 *   <li>Global timeBudgetMsPerEval &gt; 0: always wins — sets timeBudgetMs, clears iterations to 0</li>
 *   <li>Global iterationsPerEval &gt; 0: overrides iterations (legacy, only when no per-seat overrides)</li>
 *   <li>Registry config as-is</li>
 * </ol>
 */
public record MatchConfig(
        String[] engineIds,
        int gameCount,
        int maxTurnsPerGame,
        int iterationsPerEval,
        int timeBudgetMsPerEval,
        boolean seatSwap,
        Map<String, String>[] configOverrides,
        boolean computeLuck,
        int luckMcSims,
        boolean luckUseMc,
        boolean computeCardIncome
) {
    /**
     * Constructor without computeCardIncome: defaults to false.
     */
    public MatchConfig(String[] engineIds, int gameCount, int maxTurnsPerGame,
                       int iterationsPerEval, int timeBudgetMsPerEval, boolean seatSwap,
                       Map<String, String>[] configOverrides,
                       boolean computeLuck, int luckMcSims, boolean luckUseMc) {
        this(engineIds, gameCount, maxTurnsPerGame, iterationsPerEval, timeBudgetMsPerEval,
                seatSwap, configOverrides, computeLuck, luckMcSims, luckUseMc, false);
    }

    /**
     * Constructor without luckUseMc: defaults to true.
     */
    public MatchConfig(String[] engineIds, int gameCount, int maxTurnsPerGame,
                       int iterationsPerEval, int timeBudgetMsPerEval, boolean seatSwap,
                       Map<String, String>[] configOverrides,
                       boolean computeLuck, int luckMcSims) {
        this(engineIds, gameCount, maxTurnsPerGame, iterationsPerEval, timeBudgetMsPerEval,
                seatSwap, configOverrides, computeLuck, luckMcSims, true);
    }

    /**
     * Constructor without luck fields: defaults to computeLuck=false.
     */
    public MatchConfig(String[] engineIds, int gameCount, int maxTurnsPerGame,
                       int iterationsPerEval, int timeBudgetMsPerEval, boolean seatSwap,
                       Map<String, String>[] configOverrides) {
        this(engineIds, gameCount, maxTurnsPerGame, iterationsPerEval, timeBudgetMsPerEval,
                seatSwap, configOverrides, false, 200);
    }

    /**
     * Legacy constructor: single iterations override, no time budget.
     * Used by CLI (H2hMain), TournamentRunner, and tests.
     */
    public MatchConfig(String[] engineIds, int gameCount, int maxTurnsPerGame,
                       int iterationsPerEval, boolean seatSwap) {
        this(engineIds, gameCount, maxTurnsPerGame, iterationsPerEval, 0, seatSwap, null);
    }

    /** Default match: 100 games, 200-turn limit, 500 iterations, seat swap on. */
    public static MatchConfig of(String engineA, String engineB) {
        return new MatchConfig(new String[]{engineA, engineB}, 100, 200, 500, true);
    }

    /** Number of players in this match. */
    public int playerCount() {
        return engineIds.length;
    }

    /**
     * Builds an H2H eval config for a specific seat by merging overrides with the
     * registry entry's config.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code configOverrides[seatIndex]} exists, merge its keys onto registry config</li>
     *   <li>If {@code timeBudgetMsPerEval > 0}, always override timeBudgetMs and clear iterations to 0</li>
     *   <li>Else if {@code iterationsPerEval > 0} and no per-seat overrides, override iterations</li>
     *   <li>Else use registry config as-is</li>
     * </ol>
     *
     * <p>{@code skipEnrichment=true} is always added to the extra map.
     *
     * @param registryConfig the EngineConfig from the registry entry
     * @param seatIndex      the seat index (0 or 1)
     * @return merged EngineConfig suitable for H2H evaluation
     */
    public EngineConfig buildSeatConfig(EngineConfig registryConfig, int seatIndex) {
        Map<String, String> merged = new HashMap<>();
        if (registryConfig.extra != null) {
            merged.putAll(registryConfig.extra);
        }
        merged.put("skipEnrichment", "true");

        int iterations = registryConfig.iterations;
        int timeBudget = registryConfig.timeBudgetMs;
        double riskWeight = registryConfig.riskToleranceWeight;

        if (configOverrides != null && seatIndex < configOverrides.length
                && configOverrides[seatIndex] != null) {
            Map<String, String> override = configOverrides[seatIndex];
            if (override.containsKey("iterations")) {
                try { iterations = Integer.parseInt(override.get("iterations")); }
                catch (NumberFormatException ignored) {}
            }
            if (override.containsKey("timeBudgetMs")) {
                try { timeBudget = Integer.parseInt(override.get("timeBudgetMs")); }
                catch (NumberFormatException ignored) {}
            }
            if (override.containsKey("riskToleranceWeight")) {
                try { riskWeight = Double.parseDouble(override.get("riskToleranceWeight")); }
                catch (NumberFormatException ignored) {}
            }
            for (Map.Entry<String, String> e : override.entrySet()) {
                String key = e.getKey();
                if (!key.equals("iterations") && !key.equals("timeBudgetMs")
                        && !key.equals("riskToleranceWeight")) {
                    merged.put(key, e.getValue());
                }
            }
        }

        // Global overrides apply on top of per-seat overrides when set.
        // Time budget takes precedence: clears iterations so engines use deadline mode.
        if (timeBudgetMsPerEval > 0) {
            timeBudget = timeBudgetMsPerEval;
            iterations = 0;
        } else if (iterationsPerEval > 0 && configOverrides == null) {
            // Legacy: global iterations only when no per-seat overrides exist
            iterations = iterationsPerEval;
        }

        return new EngineConfig(iterations, timeBudget, riskWeight, merged);
    }
}
