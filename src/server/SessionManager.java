package server;

import com.google.gson.JsonObject;
import core.GameSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton-style holder for the active {@link GameSession} and the saves directory path.
 *
 * <p>Thread-safe: all session and snapshot accessors are synchronized.
 * The saves directory path is immutable after construction.
 *
 * <p>Engine snapshots are stored in a parallel list indexed alongside
 * {@link GameSession#getHistory()}. Each entry holds the engine's ranked options
 * at the time a turn was applied, or {@code null} for turns without evaluation
 * (e.g. opponent turns). Snapshots live in the server layer to avoid coupling
 * Core with Engine types.
 */
public final class SessionManager {

    private GameSession activeSession;
    private final Path savesDir;
    private final ArrayList<JsonObject> engineSnapshots = new ArrayList<>();

    /**
     * @param savesDir directory where .mkoro save files are stored
     */
    public SessionManager(Path savesDir) {
        this.savesDir = savesDir;
    }

    /** Returns the currently active session, or {@code null} if none has been created. */
    public synchronized GameSession getSession() { return activeSession; }

    /**
     * Replaces the active session. Pass {@code null} to clear it.
     * Also clears any stored engine snapshots (new game = fresh review data).
     */
    public synchronized void setSession(GameSession s) {
        this.activeSession = s;
        this.engineSnapshots.clear();
    }

    /** Returns the configured saves directory (may not exist yet on disk). */
    public Path getSavesDir() { return savesDir; }

    // ─── Engine Snapshots (parallel to GameSession.history) ────────────

    /** Appends an engine snapshot for the most recently applied turn. May be {@code null}. */
    public synchronized void addEngineSnapshot(JsonObject snapshot) {
        engineSnapshots.add(snapshot);
    }

    /** Returns an unmodifiable view of the engine snapshot list. */
    public synchronized List<JsonObject> getEngineSnapshots() {
        return Collections.unmodifiableList(new ArrayList<>(engineSnapshots));
    }

    /** Removes the last engine snapshot (called on undo). No-op if the list is empty. */
    public synchronized void removeLastSnapshot() {
        if (!engineSnapshots.isEmpty()) {
            engineSnapshots.remove(engineSnapshots.size() - 1);
        }
    }
}
