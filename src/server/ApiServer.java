package server;

import com.sun.net.httpserver.HttpServer;
import h2h.H2hResultStore;
import iface.EngineOrchestrator;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Local HTTP API server for the MachiKoro Calculator.
 *
 * <p>Starts a lightweight {@link HttpServer} on {@code localhost:8080} (configurable) and
 * registers all API endpoint handlers. The server is intended for local desktop use only —
 * it binds to loopback and is not designed for network exposure.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/health}              — liveness check</li>
 *   <li>{@code GET  /api/projects}            — all 19 base-game cards</li>
 *   <li>{@code GET  /api/engines}             — all registered engine configurations</li>
 *   <li>{@code POST /api/roll}                — apply a dice roll, return coin deltas + updated state</li>
 *   <li>{@code POST /api/evaluate}            — run engine evaluation, return ranked purchase options</li>
 *   <li>{@code POST /api/session/create}      — create a new game session</li>
 *   <li>{@code GET  /api/session/state}       — get full session state</li>
 *   <li>{@code POST /api/session/turn}        — apply a completed turn</li>
 *   <li>{@code POST /api/session/bürohaus}    — execute or decline Bürohaus swap</li>
 *   <li>{@code POST /api/session/undo}        — undo last turn</li>
 *   <li>{@code POST /api/session/save}        — save to file</li>
 *   <li>{@code POST /api/session/load}        — load from file</li>
 *   <li>{@code GET  /api/session/saves}       — list saved .mkoro files</li>
 *   <li>{@code POST /api/session/from-snapshot} — create session from mid-game state</li>
 *   <li>{@code GET  /api/session/insights}    — position insights for the assistant panel</li>
 *   <li>{@code POST /api/session/pvai/start}       — activate Player-vs-AI continuous thinking</li>
 *   <li>{@code POST /api/session/pvai/human-turn}  — human lock-in event; navigate engine</li>
 *   <li>{@code GET  /api/session/pvai/ai-turn}     — block until think time elapses; return AI decision</li>
 *   <li>{@code POST /api/session/pvai/save}        — save completed PvAI game with luck/WR analysis</li>
 *   <li>{@code GET  /api/session/pvai/games}       — list all saved PvAI game records</li>
 *   <li>{@code POST /api/h2h/start}             — start H2H match in background</li>
 *   <li>{@code GET  /api/h2h/status/{matchId}}  — match progress</li>
 *   <li>{@code GET  /api/h2h/results}           — all completed matches (summary)</li>
 *   <li>{@code GET  /api/h2h/results/{matchId}} — full match result with game logs</li>
 *   <li>{@code GET  /}                        — serve static files from web/dist/</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   EngineOrchestrator orchestrator = new EngineOrchestrator();
 *   ApiServer server = new ApiServer(8080, orchestrator);
 *   server.start();
 * </pre>
 */
public final class ApiServer {

    /** Default port. Can be overridden via constructor. */
    public static final int DEFAULT_PORT = 8080;

    private final int port;
    private final EngineOrchestrator orchestrator;
    private final SessionManager sessionManager;
    private final PrecomputeCache precomputeCache;
    private final H2hResultStore h2hStore;
    private final PvAiGameStore pvAiGameStore;
    private HttpServer httpServer;

    /**
     * Creates an API server on the given port.
     *
     * @param port         TCP port to listen on (1–65535)
     * @param orchestrator the engine orchestrator to use for {@code /api/evaluate}
     */
    public ApiServer(int port, EngineOrchestrator orchestrator) {
        this.port           = port;
        this.orchestrator   = orchestrator;
        this.sessionManager = new SessionManager(Path.of("saves"));
        this.precomputeCache = new PrecomputeCache(orchestrator);
        this.h2hStore = new H2hResultStore();
        this.pvAiGameStore = new PvAiGameStore();
    }

    /** Convenience constructor using {@link #DEFAULT_PORT}. */
    public ApiServer(EngineOrchestrator orchestrator) {
        this(DEFAULT_PORT, orchestrator);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the HTTP server. Returns immediately; the server handles requests on a thread pool.
     *
     * @throws java.io.IOException if the server cannot bind to the port
     */
    public void start() throws java.io.IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // Original endpoints
        httpServer.createContext("/api/health",   new HealthHandler());
        httpServer.createContext("/api/projects", new ProjectsHandler());
        httpServer.createContext("/api/engines",  new EnginesHandler());
        httpServer.createContext("/api/engine-params", new EngineParamsHandler());
        httpServer.createContext("/api/roll",     new RollHandler());
        httpServer.createContext("/api/evaluate", new EvaluateHandler(orchestrator, precomputeCache));
        httpServer.createContext("/api/evaluate/precompute", new PrecomputeHandler(precomputeCache));

        // Session management endpoints
        httpServer.createContext("/api/session/create",        new SessionCreateHandler(sessionManager));
        httpServer.createContext("/api/session/state",         new SessionStateHandler(sessionManager));
        httpServer.createContext("/api/session/turn",          new SessionTurnHandler(sessionManager));
        httpServer.createContext("/api/session/burohaus",      new SessionBürohausHandler(sessionManager));
        httpServer.createContext("/api/session/undo",          new SessionUndoHandler(sessionManager));
        httpServer.createContext("/api/session/save",          new SessionSaveHandler(sessionManager));
        httpServer.createContext("/api/session/load",          new SessionLoadHandler(sessionManager));
        httpServer.createContext("/api/session/saves",         new SessionSavesListHandler(sessionManager));
        httpServer.createContext("/api/session/from-snapshot", new SessionFromSnapshotHandler(sessionManager));
        httpServer.createContext("/api/session/insights",      new SessionInsightsHandler(sessionManager));
        httpServer.createContext("/api/session/roll-luck",     new SessionRollLuckHandler(sessionManager));

        // Player-vs-AI endpoints
        httpServer.createContext("/api/session/pvai/start",      new PvAiStartHandler(sessionManager));
        httpServer.createContext("/api/session/pvai/human-turn", new PvAiHumanTurnHandler(sessionManager));
        httpServer.createContext("/api/session/pvai/ai-turn",    new PvAiAiTurnHandler(sessionManager));
        httpServer.createContext("/api/session/pvai/save",       new PvAiSaveHandler(sessionManager, pvAiGameStore));
        httpServer.createContext("/api/session/pvai/games",      new PvAiGamesListHandler(pvAiGameStore));

        // H2H engine testing endpoints
        H2hHandler h2hHandler = new H2hHandler(orchestrator, h2hStore);
        httpServer.createContext("/api/h2h/start",   h2hHandler);
        httpServer.createContext("/api/h2h/status",  h2hHandler);
        httpServer.createContext("/api/h2h/cancel",  h2hHandler);
        httpServer.createContext("/api/h2h/results", h2hHandler);
        httpServer.createContext("/api/h2h/ratings", h2hHandler);
        httpServer.createContext("/api/h2h/export",  h2hHandler);
        httpServer.createContext("/api/h2h/import",  h2hHandler);
        httpServer.createContext("/api/h2h/auto",    h2hHandler);
        httpServer.createContext("/api/h2h/sweep",   h2hHandler);

        // Static file serving (SPA fallback)
        httpServer.createContext("/", new StaticFileHandler(Path.of("web", "dist")));

        // Thread pool: increased for concurrent session + evaluate requests
        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();

        System.out.println("[ApiServer] Listening on http://localhost:" + port + "/");
    }

    /**
     * Stops the HTTP server gracefully.
     *
     * @param delaySeconds seconds to wait for in-flight requests to complete (0 = immediate)
     */
    public void stop(int delaySeconds) {
        if (httpServer != null) {
            httpServer.stop(delaySeconds);
            System.out.println("[ApiServer] Stopped.");
        }
    }

    /** Returns the port this server is configured to listen on. */
    public int getPort() {
        return port;
    }

    /** Returns the session manager used by this server. */
    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
