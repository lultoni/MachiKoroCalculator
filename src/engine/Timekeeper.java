package engine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Timing contract for AI turn reveal in Player-vs-AI mode.
 *
 * <p>Manages the minimum think time before the AI reveals its decision.
 * The effective minimum is {@code max(minThinkTimeMs, engineTimeBudgetMs)}.
 * If the engine has already thought long enough, {@link #requestResult()} returns
 * immediately; otherwise it schedules a delayed stop-and-report.
 *
 * <p>Usage in PlayerVsAiController:
 * <ol>
 *   <li>Call {@link #start(long)} when AI's think phase begins (after human's lock-in)</li>
 *   <li>Call {@link #requestResult()} when AI turn should be revealed</li>
 *   <li>Await the returned future; it resolves to the best EngineResult</li>
 * </ol>
 */
public class Timekeeper {

    private final ContinuousEvaluator evaluator;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "timekeeper-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** Minimum think time from user Settings (milliseconds). */
    private volatile int minThinkTimeMs;

    /** Minimum think time from EngineConfig.timeBudgetMs (milliseconds). */
    private volatile int engineTimeBudgetMs;

    /** Wall-clock time when the AI's current think phase started. */
    private volatile long thinkStartMs = -1;

    public Timekeeper(ContinuousEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void setMinThinkTimeMs(int ms) {
        this.minThinkTimeMs = ms;
    }

    public void setEngineTimeBudgetMs(int ms) {
        this.engineTimeBudgetMs = ms;
    }

    /** Returns {@code max(minThinkTimeMs, engineTimeBudgetMs)}. */
    public int effectiveMinMs() {
        return Math.max(minThinkTimeMs, engineTimeBudgetMs);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Record that AI thinking started at the given wall-clock time.
     * Typically called after the human's lock-in event is delivered to the evaluator.
     *
     * @param startMs {@code System.currentTimeMillis()} when thinking began
     */
    public void start(long startMs) {
        this.thinkStartMs = startMs;
    }

    // -------------------------------------------------------------------------
    // Result request
    // -------------------------------------------------------------------------

    /**
     * Request the AI's current best result, respecting the minimum think time.
     *
     * <p>If the engine has already thought for at least {@link #effectiveMinMs()},
     * calls {@link ContinuousEvaluator#stopAndGetResult()} immediately and returns
     * a completed future.
     *
     * <p>Otherwise, schedules the stop after the remaining wait time and returns
     * a future that completes when the result is available.
     *
     * @return future resolving to the best EngineResult (may be null if engine has
     *         not completed any iteration yet)
     */
    public CompletableFuture<EngineResult> requestResult() {
        int effectiveMs = effectiveMinMs();
        long elapsed = thinkStartMs >= 0
                ? System.currentTimeMillis() - thinkStartMs
                : effectiveMs; // if not started, treat as "enough time elapsed"

        long remainingMs = effectiveMs - elapsed;

        if (remainingMs <= 0) {
            // Already thought long enough — stop immediately
            EngineResult result = evaluator.stopAndGetResult();
            return CompletableFuture.completedFuture(result);
        } else {
            // Schedule stop after remaining wait
            CompletableFuture<EngineResult> future = new CompletableFuture<>();
            scheduler.schedule(() -> {
                try {
                    EngineResult result = evaluator.stopAndGetResult();
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, remainingMs, TimeUnit.MILLISECONDS);
            return future;
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /** Shuts down the scheduler. Call when the game session ends. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
