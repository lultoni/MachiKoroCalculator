package iface;

import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes analysis requests to the appropriate {@link SimulationEngine} and manages
 * the mapping from engine class identifiers to engine instances.
 *
 * <h2>Usage</h2>
 * <pre>
 *   EngineOrchestrator orch = new EngineOrchestrator();
 *   orch.register(new MctsV1Engine());
 *
 *   EngineRegistryEntry entry = EngineRegistry.getDefault();
 *   EngineResult result = orch.evaluate(state, playerIndex, entry);
 * </pre>
 *
 * <h2>Engine registration</h2>
 * Engines are registered by their {@link SimulationEngine#id()} string, which must match
 * the {@code "engineClass"} field in {@code engines.json}. An engine instance is registered
 * once and reused across calls (engines must be stateless between invocations).
 *
 * <h2>Layer contract</h2>
 * The orchestrator imports from {@code engine.*} and {@code core.*}.
 * It must NOT import from {@code ui.*}.
 */
public final class EngineOrchestrator {

    private final Map<String, SimulationEngine> engines = new HashMap<>();

    /**
     * Registers an engine implementation. The engine's {@link SimulationEngine#id()} is
     * used as the lookup key and must match the {@code "engineClass"} field in the registry.
     *
     * @param engine engine instance (must be stateless between calls)
     * @throws IllegalArgumentException if an engine with the same id is already registered
     */
    public void register(SimulationEngine engine) {
        String id = engine.id();
        if (engines.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Engine already registered with id: " + id);
        }
        engines.put(id, engine);
    }

    /**
     * Evaluates the current game state for the specified player using the engine and config
     * described by {@code entry}.
     *
     * @param state       current game state (read-only — engine must copy before mutating)
     * @param playerIndex 0-based index of the player to advise
     * @param entry       registry entry describing which engine and config to use
     * @return ranked evaluation result
     * @throws IllegalStateException if no engine is registered for the entry's engineClass
     */
    public EngineResult evaluate(GameState state, int playerIndex, EngineRegistryEntry entry) {
        SimulationEngine engine = engines.get(entry.engineClass());
        if (engine == null) {
            throw new IllegalStateException(
                    "No engine registered for engineClass: '" + entry.engineClass()
                    + "'. Register it first via EngineOrchestrator.register().");
        }
        return engine.evaluate(state, playerIndex, entry.config());
    }

    /**
     * Convenience overload: evaluates using the default registry entry.
     *
     * @param state       current game state
     * @param playerIndex 0-based index of the player to advise
     * @return ranked evaluation result from the default engine
     * @throws IllegalStateException if no engine is registered for the default entry's engineClass
     */
    public EngineResult evaluateDefault(GameState state, int playerIndex) {
        return evaluate(state, playerIndex, EngineRegistry.getDefault());
    }

    /**
     * Returns the registered engine for the given engine class id, or {@code null} if not found.
     */
    public SimulationEngine getEngine(String engineClassId) {
        return engines.get(engineClassId);
    }

    /**
     * Returns true if an engine with the given class id has been registered.
     */
    public boolean hasEngine(String engineClassId) {
        return engines.containsKey(engineClassId);
    }
}
