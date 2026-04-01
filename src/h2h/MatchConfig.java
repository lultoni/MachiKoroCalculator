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
 * @param engineIds         one registry ID per seat (length 2–4)
 * @param gameCount         number of games to play (default 100)
 * @param maxTurnsPerGame   turn limit per game before softmax fallback (default 200)
 * @param iterationsPerEval MCTS iterations per evaluateFullTurn call (0 = use registry default)
 * @param seatSwap          if true, swap P1/P2 seats after half the games for fairness
 */
public record MatchConfig(
        String[] engineIds,
        int gameCount,
        int maxTurnsPerGame,
        int iterationsPerEval,
        boolean seatSwap
) {
    /** Default match: 100 games, 200-turn limit, 500 iterations, seat swap on. */
    public static MatchConfig of(String engineA, String engineB) {
        return new MatchConfig(new String[]{engineA, engineB}, 100, 200, 500, true);
    }

    /** Number of players in this match. */
    public int playerCount() {
        return engineIds.length;
    }

    /**
     * Builds an H2H eval config from a registry entry's config, preserving engine-specific
     * extras (rolloutTemperature, maxRolloutDepth, etc.) and adding skipEnrichment.
     *
     * @param registryConfig     the EngineConfig from the registry entry
     * @param iterationsOverride if &gt; 0, overrides the registry iteration count
     * @return merged EngineConfig suitable for H2H evaluation
     */
    public static EngineConfig buildEvalConfig(EngineConfig registryConfig, int iterationsOverride) {
        Map<String, String> merged = new HashMap<>();
        if (registryConfig.extra != null) {
            merged.putAll(registryConfig.extra);
        }
        merged.put("skipEnrichment", "true");

        int iterations = iterationsOverride > 0 ? iterationsOverride : registryConfig.iterations;

        return new EngineConfig(iterations, registryConfig.timeBudgetMs,
                registryConfig.riskToleranceWeight, merged);
    }
}
