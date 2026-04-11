package engine.expectimax;

import core.BitState;
import core.GameState;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.NavigationEvent;

import java.util.List;

/**
 * {@link ContinuousWorker} implementation for {@link ExpectimaxEngine}.
 *
 * <p>Uses iterative deepening: each "iteration" is a complete evaluation at depth N.
 * The best completed depth result is stored and returned by {@link #peekResult}.
 *
 * <h2>Depth progression</h2>
 * Depth increases monotonically: depth 1, 2, 3, ... Each depth is a full tree search.
 * Since depth grows exponentially in time, this worker may spend many seconds on
 * a single "iteration" at depth 3+. This is intentional — Expectimax is not a
 * sampling algorithm.
 *
 * <h2>State change handling</h2>
 * On a lock-in event, the current game state and depth counter reset. The worker
 * restarts deepening from depth 1 on the new position. No transposition table is
 * maintained (deferred to a future enhancement).
 *
 * <h2>peekResult</h2>
 * Returns the result from the last completed depth. Returns {@code null} if depth 1
 * has not yet completed.
 */
public final class ExpectimaxContinuousWorker implements ContinuousWorker {

    private final ExpectimaxEngine engine = new ExpectimaxEngine();

    /** The last fully completed depth evaluation. Null until depth 1 finishes. */
    private volatile EngineResult bestResult;

    /** Completed depth level (0 = not started). */
    private int currentDepth;

    /** State stored for iterative deepening reruns on navigate. */
    private BitState lastState;
    private int[] lastSupply;
    private int lastPlayerIndex;
    private EngineConfig lastConfig;

    @Override
    public boolean supportsContinuousMode() { return true; }

    @Override
    public void init(BitState state, int[] supply, int playerIndex, EngineConfig config) {
        this.lastState       = state;
        this.lastSupply      = supply;
        this.lastPlayerIndex = playerIndex;
        this.lastConfig      = config;
        this.bestResult      = null;
        this.currentDepth    = 0;
    }

    @Override
    public void runOneIteration() {
        if (lastState == null) return;

        int targetDepth = currentDepth + 1;
        GameState gs = lastState.toGameState();
        int coins    = lastState.getCoins(lastPlayerIndex);
        int n        = lastState.getNumPlayers();
        int next     = (lastPlayerIndex + 1) % n;
        String leafEval = lastConfig.getExtra("leafEval", "winprob");

        List<ExpectimaxEngine.ScoredOption> scored = engine.evaluateAtDepth(
                lastState, lastSupply, lastPlayerIndex, next, coins, n, targetDepth, leafEval);

        if (scored != null && !scored.isEmpty()) {
            bestResult   = engine.buildResult(gs, lastPlayerIndex, scored, coins, 0L, leafEval, targetDepth);
            currentDepth = targetDepth;
        }
    }

    @Override
    public EngineResult peekResult(GameState state, int playerIdx, EngineConfig cfg) {
        return bestResult; // volatile field, safe to read from HTTP thread
    }

    @Override
    public boolean navigate(NavigationEvent event) {
        // Restart deepening from new state (no transposition table reuse yet)
        this.lastState       = core.BitState.fromGameState(event.newState());
        this.lastSupply      = this.lastState.buildSupplyArray();
        this.lastPlayerIndex = event.playerIndex();
        this.bestResult      = null;
        this.currentDepth    = 0;
        return true; // "navigation succeeded" — we reset ourselves
    }

    @Override
    public int iterations() {
        return currentDepth; // reports completed depth rather than iteration count
    }
}
