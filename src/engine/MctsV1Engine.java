package engine;

import core.GameState;

/**
 * MCTS v1 simulation engine — full UCT tree search with uniform-random rollouts.
 *
 * <p>This stub compiles and wires to the {@link SimulationEngine} interface but does not
 * yet implement any logic. All tests that call {@link #evaluate} will fail until the
 * implementation is written (Commit F).
 *
 * @see SimulationEngine
 */
public final class MctsV1Engine implements SimulationEngine {

    public static final String ENGINE_ID = "mcts-v1";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS v1 — full UCT tree search with uniform-random full-game rollouts";
    }

    /**
     * Evaluates the game state using MCTS with UCT.
     *
     * <p><b>Not yet implemented.</b> Will throw {@link UnsupportedOperationException}
     * until the implementation is completed in Commit F.
     */
    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        throw new UnsupportedOperationException("MctsV1Engine.evaluate() not yet implemented");
    }
}
