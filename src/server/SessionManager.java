package server;

import core.GameSession;
import java.nio.file.Path;

/**
 * Singleton-style holder for the active {@link GameSession} and the saves directory path.
 *
 * <p>Thread-safe: {@link #getSession()} and {@link #setSession(GameSession)} are synchronized.
 * The saves directory path is immutable after construction.
 */
public final class SessionManager {

    private GameSession activeSession;
    private final Path savesDir;

    /**
     * @param savesDir directory where .mkoro save files are stored
     */
    public SessionManager(Path savesDir) {
        this.savesDir = savesDir;
    }

    /** Returns the currently active session, or {@code null} if none has been created. */
    public synchronized GameSession getSession() { return activeSession; }

    /** Replaces the active session. Pass {@code null} to clear it. */
    public synchronized void setSession(GameSession s) { this.activeSession = s; }

    /** Returns the configured saves directory (may not exist yet on disk). */
    public Path getSavesDir() { return savesDir; }
}
