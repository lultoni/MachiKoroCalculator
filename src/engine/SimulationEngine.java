package engine;

import core.GameState;

/**
 * Contract for all Machi Koro strategy engines.
 *
 * <p>An engine is a self-contained strategy implementation that, given the current
 * game state and the index of the player to advise, returns a ranked list of purchase
 * options with scores and explanation data.
 *
 * <p>Implementations must be stateless between calls — all mutable state is passed in
 * via {@link GameState} and {@link EngineConfig}. The engine registry instantiates each
 * engine class once and reuses the instance across calls.
 *
 * <h2>Layer contract</h2>
 * Engines may import from {@code calcs.*} and {@code core.*}.
 * Engines must NOT import from {@code ui.*} or {@code iface.*}.
 */
public interface SimulationEngine {

    /**
     * Returns the stable machine-readable identifier for this engine class
     * (e.g. {@code "mcts-v1"}). Must match the {@code "engine"} field in the registry JSON.
     */
    String id();

    /**
     * Returns a human-readable description of this engine for display in settings UI.
     */
    String description();

    /**
     * Evaluates the current game state and returns ranked purchase options for the
     * specified player.
     *
     * @param state       current game state (read-only; engine must copy before mutating)
     * @param playerIndex index of the player to advise (0-based)
     * @param config      engine-specific configuration (iterations, time budget, etc.)
     * @return ranked evaluation result; never null
     */
    EngineResult evaluate(GameState state, int playerIndex, EngineConfig config);
}
