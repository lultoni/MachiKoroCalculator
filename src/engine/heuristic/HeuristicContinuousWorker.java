package engine.heuristic;

import core.BitState;
import core.GameState;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.NavigationEvent;

/**
 * {@link ContinuousWorker} implementation for {@link HeuristicEvEngine}.
 *
 * <p>The heuristic is a single-pass formula evaluation (~2ms). There is no iteration
 * loop. On init, the engine evaluates once and stores the result. On navigate,
 * it re-evaluates on the new state. Between events, {@link #runOneIteration} is a no-op.
 *
 * <p>This implementation is trivial but needed so that {@link engine.ContinuousEvaluator}
 * can treat all engines uniformly.
 */
public final class HeuristicContinuousWorker implements ContinuousWorker {

    private final HeuristicEvEngine engine = new HeuristicEvEngine();

    /** Cached result from last init or navigate. */
    private volatile EngineResult result;

    /** Whether init has been called at least once. */
    private boolean initialized = false;

    @Override
    public boolean supportsContinuousMode() { return true; }

    @Override
    public void init(BitState state, int[] supply, int playerIndex, EngineConfig config) {
        GameState gs = state.toGameState();
        this.result      = engine.evaluate(gs, playerIndex, config);
        this.initialized = true;
    }

    @Override
    public void runOneIteration() {
        // No-op: heuristic is a single pass; nothing to iterate.
    }

    @Override
    public EngineResult peekResult(GameState state, int playerIdx, EngineConfig cfg) {
        return result;
    }

    @Override
    public boolean navigate(NavigationEvent event) {
        // Re-evaluate synchronously on the new state (~2ms, negligible).
        this.result = engine.evaluate(event.newState(), event.playerIndex(), lastConfig(event));
        return true;
    }

    @Override
    public int iterations() {
        return initialized ? 1 : 0;
    }

    // Heuristic evaluate doesn't need config for correctness (all params are constants),
    // but the interface passes config through navigate for completeness.
    private EngineConfig lastConfig(NavigationEvent event) {
        // Default config: no budget (heuristic ignores it anyway)
        return new engine.EngineConfig(0, 0, 0.0, null);
    }
}
