package engine;

import core.BitState;
import core.GameState;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a {@link ContinuousWorker} in a background thread that runs engine
 * iterations continuously. External events (lock-in, stop-and-report, shutdown)
 * are delivered via atomic mailbox flags that the worker thread checks between
 * iterations.
 *
 * <h2>Thread model</h2>
 * <pre>
 * HTTP thread             Worker thread
 * ----------              -------------
 * init() ──────────────► starts running iterations
 * navigate() ──────────► delivers NavigationEvent via atomic ref
 *                         checks pendingNavigation; calls worker.navigate()
 * stopAndGetResult() ──► sets stopAndReport flag
 *                         checks flag; writes result; calls notifyAll()
 * ◄──── returns result    waits for resume (next init/navigate/shutdown)
 * peekResult() ──────────► reads volatile latestResult (non-blocking)
 * shutdown() ────────────► sets shutdown flag; notifyAll()
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>{@link #pendingNavigation}: {@link AtomicReference} for lock-free delivery</li>
 *   <li>{@link #stopAndReport}: {@link AtomicBoolean} for lock-free signalling</li>
 *   <li>{@link #shutdown}: {@link AtomicBoolean} for lock-free signalling</li>
 *   <li>{@link #latestResult}: {@code volatile} for lock-free reads from HTTP thread</li>
 *   <li>Worker thread pausing: {@code synchronized(pauseLock)} + {@code wait/notifyAll}</li>
 * </ul>
 */
public final class ContinuousEvaluator {

    // -------------------------------------------------------------------------
    // Mailbox
    // -------------------------------------------------------------------------

    /** Pending navigation event (null if none). Written by HTTP thread; read/cleared by worker. */
    private final AtomicReference<NavigationEvent> pendingNavigation = new AtomicReference<>(null);

    /** If true, worker should pause, write current result, and notify the HTTP thread. */
    private final AtomicBoolean stopAndReport = new AtomicBoolean(false);

    /** If true, worker should terminate. */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // Result buffer
    // -------------------------------------------------------------------------

    /** Current best result (written by worker, read by HTTP thread). */
    private volatile EngineResult latestResult;

    /** Total iterations since last init/navigate. */
    private volatile int accumulatedIterations;

    /** Wall-clock ms when current position thinking started. */
    private volatile long thinkingStartMs;

    // -------------------------------------------------------------------------
    // Worker state
    // -------------------------------------------------------------------------

    private final ContinuousWorker worker;
    private final Thread workerThread;

    /** Lock object for worker pause/resume synchronization. */
    private final Object pauseLock = new Object();

    /** True while the worker is paused (after stop-and-report). */
    private volatile boolean paused = false;

    /** Last known game state (for peekResult calls). */
    private volatile GameState lastGameState;
    private volatile int lastPlayerIndex;
    private volatile EngineConfig lastConfig;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ContinuousEvaluator(ContinuousWorker worker) {
        this.worker = worker;
        this.workerThread = new Thread(this::workerLoop, "continuous-engine");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start thinking about the given game position. Initializes the worker
     * and begins the iteration loop.
     *
     * @param state       current game state
     * @param playerIndex AI's seat index (perspective for evaluation)
     * @param config      engine configuration
     */
    public void init(GameState state, int playerIndex, EngineConfig config) {
        this.lastGameState   = state;
        this.lastPlayerIndex = playerIndex;
        this.lastConfig      = config;

        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();

        synchronized (pauseLock) {
            worker.init(bs, supply, playerIndex, config);
            latestResult        = null;
            accumulatedIterations = 0;
            thinkingStartMs     = System.currentTimeMillis();
            stopAndReport.set(false);

            if (paused) {
                paused = false;
                pauseLock.notifyAll();
            }
        }
    }

    /**
     * Deliver a lock-in event to the worker. The worker will navigate its internal
     * state to the new position on its next mailbox check (between iterations).
     * Non-blocking — returns immediately.
     *
     * @param event lock-in event describing the navigation path
     */
    public void navigate(NavigationEvent event) {
        this.lastGameState   = event.newState();
        this.lastPlayerIndex = event.playerIndex();
        pendingNavigation.set(event);

        // Resume worker if paused (e.g. after a prior stopAndGetResult)
        synchronized (pauseLock) {
            if (paused) {
                paused = false;
                stopAndReport.set(false);
                pauseLock.notifyAll();
            }
        }
    }

    /**
     * Request the worker to stop and return its current best result.
     * Blocks until the worker pauses. Safe to call from the HTTP thread.
     *
     * @return the best result computed so far, or null if no result yet
     */
    public EngineResult stopAndGetResult() {
        stopAndReport.set(true);
        synchronized (pauseLock) {
            while (!paused && !shutdown.get()) {
                try { pauseLock.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return latestResult;
        }
    }

    /**
     * Peek at the current best result without stopping the worker.
     * Non-blocking. Returns null if no result is available yet.
     */
    public EngineResult peekResult() {
        GameState gs = lastGameState;
        if (gs == null || worker == null) return null;
        // Try live peek from worker first (may be more current than latestResult)
        EngineResult live = worker.peekResult(gs, lastPlayerIndex, lastConfig);
        return live != null ? live : latestResult;
    }

    /**
     * Returns how long the engine has been thinking on the current position.
     */
    public long thinkingTimeMs() {
        return System.currentTimeMillis() - thinkingStartMs;
    }

    /**
     * Returns total iterations accumulated on the current position.
     */
    public int iterations() {
        return accumulatedIterations;
    }

    /**
     * Shut down the background worker thread permanently.
     * Should be called when the game session ends.
     */
    public void shutdown() {
        shutdown.set(true);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    // -------------------------------------------------------------------------
    // Worker loop (runs on background thread)
    // -------------------------------------------------------------------------

    private void workerLoop() {
        while (!shutdown.get()) {
            // --- Check for pending navigation event ---
            NavigationEvent navEvent = pendingNavigation.getAndSet(null);
            if (navEvent != null) {
                boolean success = worker.navigate(navEvent);
                if (!success) {
                    // Navigation failed: fresh init from new state
                    BitState bs = BitState.fromGameState(navEvent.newState());
                    int[] supply = bs.buildSupplyArray();
                    worker.init(bs, supply, navEvent.playerIndex(), lastConfig);
                    lastConfig = lastConfig; // unchanged
                }
                accumulatedIterations = 0;
                thinkingStartMs = System.currentTimeMillis();
                latestResult = null;
            }

            // --- Check stop-and-report flag ---
            if (stopAndReport.get()) {
                // Write latest result and pause
                EngineResult r = worker.peekResult(lastGameState, lastPlayerIndex, lastConfig);
                if (r != null) latestResult = r;

                synchronized (pauseLock) {
                    paused = true;
                    pauseLock.notifyAll();
                    while (paused && !shutdown.get()) {
                        try { pauseLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    stopAndReport.set(false);
                }
                continue;
            }

            // --- Check shutdown ---
            if (shutdown.get()) break;

            // --- Run one iteration ---
            worker.runOneIteration();
            accumulatedIterations++;

            // --- Periodically update latestResult (every 100 iterations) ---
            if (accumulatedIterations % 100 == 0 && lastGameState != null) {
                EngineResult r = worker.peekResult(lastGameState, lastPlayerIndex, lastConfig);
                if (r != null) latestResult = r;
            }
        }
    }
}
