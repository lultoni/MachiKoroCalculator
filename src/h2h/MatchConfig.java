package h2h;

import engine.EngineConfig;

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
 * @param iterationsPerEval MCTS iterations per evaluateFullTurn call (overrides registry config)
 */
public record MatchConfig(
        String[] engineIds,
        int gameCount,
        int maxTurnsPerGame,
        int iterationsPerEval
) {
    /** Default match: 100 games, 200-turn limit, 500 iterations. */
    public static MatchConfig of(String engineA, String engineB) {
        return new MatchConfig(new String[]{engineA, engineB}, 100, 200, 500);
    }

    /** Number of players in this match. */
    public int playerCount() {
        return engineIds.length;
    }

    /**
     * Builds the EngineConfig for H2H evaluation: skipEnrichment enabled,
     * iteration count from this config.
     */
    public EngineConfig toEngineConfig() {
        return new EngineConfig(iterationsPerEval, 0, 0.0,
                Map.of("skipEnrichment", "true"));
    }
}
