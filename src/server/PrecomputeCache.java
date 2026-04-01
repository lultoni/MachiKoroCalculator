package server;

import core.GameState;
import engine.EngineResult;
import iface.EngineOrchestrator;
import iface.EngineRegistryEntry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Single-entry thread-safe cache for background engine pre-computation.
 *
 * <p>A new {@link #startPrecompute} request cancels any in-flight computation.
 * The cache key is {@code (structuralHash, playerIndex, engineId)}.
 */
final class PrecomputeCache {

    private final EngineOrchestrator orchestrator;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "precompute");
        t.setDaemon(true);
        return t;
    });

    /** Cache key. */
    private record CacheKey(int structuralHash, int playerIndex, String engineId) {}

    private volatile CacheKey currentKey;
    private volatile EngineResult currentResult;
    private volatile Future<?> inFlight;

    PrecomputeCache(EngineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Starts a background evaluation. Cancels any in-flight one.
     */
    void startPrecompute(GameState state, int playerIndex, EngineRegistryEntry entry) {
        int hash = state.structuralHash();
        CacheKey key = new CacheKey(hash, playerIndex, entry.id());

        // Already cached?
        if (key.equals(currentKey) && currentResult != null) return;

        // Cancel in-flight
        if (inFlight != null) {
            inFlight.cancel(true);
        }

        currentKey = null;
        currentResult = null;

        // Copy state for thread safety
        GameState copy = state.copy();
        inFlight = executor.submit(() -> {
            try {
                EngineResult result = orchestrator.evaluate(copy, playerIndex, entry);
                // Only store if not cancelled
                if (!Thread.currentThread().isInterrupted()) {
                    currentKey = key;
                    currentResult = result;
                }
            } catch (Exception e) {
                // Silently discard — precompute is best-effort
            }
        });
    }

    /**
     * Returns a cached result if it matches the current state, or null.
     */
    EngineResult getIfReady(GameState state, int playerIndex, String engineId) {
        int hash = state.structuralHash();
        CacheKey key = new CacheKey(hash, playerIndex, engineId);
        if (key.equals(currentKey) && currentResult != null) {
            return currentResult;
        }
        return null;
    }

    /** Invalidates the cache. */
    void invalidate() {
        if (inFlight != null) {
            inFlight.cancel(true);
        }
        currentKey = null;
        currentResult = null;
    }
}
