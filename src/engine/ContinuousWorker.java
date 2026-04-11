package engine;

import core.BitState;
import core.GameState;

/**
 * Per-engine interface for continuous, background evaluation.
 *
 * <p>Each engine family implements this interface to expose its internal iteration
 * loop to {@link ContinuousEvaluator}. The evaluator manages the background thread
 * and mailbox; the worker only needs to know how to perform one unit of work and
 * how to respond to navigation events.
 *
 * <h2>Thread model</h2>
 * All methods are called from the single background worker thread owned by
 * {@link ContinuousEvaluator}. No synchronization is needed inside worker methods.
 * The only exception is {@link #peekResult}, which may be called from the HTTP
 * server thread; implementations must ensure it is safe to read from a different
 * thread (e.g. by reading only volatile/atomic fields or immutable snapshots).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #init} — called once when evaluation begins on a new position</li>
 *   <li>{@link #runOneIteration} — called in a tight loop; must return quickly</li>
 *   <li>{@link #navigate} — called when a lock-in event redirects the search</li>
 *   <li>{@link #peekResult} — called at any time to read the current best result</li>
 * </ol>
 */
public interface ContinuousWorker {

    /**
     * Whether this engine supports continuous background evaluation.
     * Must return {@code true} for all production implementations.
     */
    boolean supportsContinuousMode();

    /**
     * Initialize the worker for the given game position.
     * Builds internal state (tree, candidate list, heuristic result, etc.).
     * Called once before the first {@link #runOneIteration}.
     *
     * @param state       bitwise-encoded game state
     * @param supply      current market supply (12 elements, one per normal card slot)
     * @param playerIndex seat index of the player being evaluated (AI's perspective)
     * @param config      engine configuration (budget, extra params)
     */
    void init(BitState state, int[] supply, int playerIndex, EngineConfig config);

    /**
     * Perform one unit of work (one MCTS iteration, one FlatMC rollout, one
     * Expectimax depth pass, etc.).
     *
     * <p>This method must return quickly; the background loop checks the mailbox
     * after each call. For engines without an iterative loop (HeuristicEv), this
     * is a no-op.
     */
    void runOneIteration();

    /**
     * Extract the current best result without disrupting internal state.
     * May return {@code null} if no valid result is available yet
     * (e.g. Expectimax depth-1 not yet complete).
     *
     * <p>Thread safety: may be called from the HTTP server thread while the worker
     * thread is in {@link #runOneIteration}. Implementations must make this safe.
     *
     * @param state       full game state (for building EngineResult explanations)
     * @param playerIndex AI's seat index
     * @param config      engine configuration
     * @return current best {@link EngineResult}, or {@code null} if not ready
     */
    EngineResult peekResult(GameState state, int playerIndex, EngineConfig config);

    /**
     * Handle a lock-in event that redirects the search to a new game position.
     *
     * <p>If the worker can reuse its existing internal state (e.g. MCTS tree
     * navigation to a subtree), it does so and returns {@code true}.
     * If reuse is impossible (unexplored path, non-MCTS engine, force reset),
     * the worker returns {@code false} and the caller will call {@link #init}
     * with the new state.
     *
     * @param event describes the lock-in and provides the new game state
     * @return {@code true} if internal state was successfully reused;
     *         {@code false} if a fresh {@link #init} is required
     */
    boolean navigate(NavigationEvent event);

    /**
     * Returns the number of iterations performed since the last {@link #init}
     * or successful {@link #navigate} call.
     *
     * <p>For depth-based engines (Expectimax), returns the current completed depth.
     * For heuristic engines, returns 1 after init (single pass), 0 before.
     */
    int iterations();
}
