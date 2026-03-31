package server;

import com.sun.net.httpserver.HttpServer;
import iface.EngineOrchestrator;

import java.net.InetSocketAddress;
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
 *   <li>{@code GET  /api/health}   — liveness check</li>
 *   <li>{@code GET  /api/projects} — all 19 base-game cards</li>
 *   <li>{@code GET  /api/engines}  — all registered engine configurations</li>
 *   <li>{@code POST /api/roll}     — apply a dice roll, return coin deltas + updated state</li>
 *   <li>{@code POST /api/evaluate} — run engine evaluation, return ranked purchase options</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   EngineOrchestrator orchestrator = new EngineOrchestrator();
 *   // orchestrator.register(new MctsV1Engine()); // added in Phase 2
 *   ApiServer server = new ApiServer(8080, orchestrator);
 *   server.start();
 * </pre>
 */
public final class ApiServer {

    /** Default port. Can be overridden via constructor. */
    public static final int DEFAULT_PORT = 8080;

    private final int port;
    private final EngineOrchestrator orchestrator;
    private HttpServer httpServer;

    /**
     * Creates an API server on the given port.
     *
     * @param port         TCP port to listen on (1–65535)
     * @param orchestrator the engine orchestrator to use for {@code /api/evaluate}
     */
    public ApiServer(int port, EngineOrchestrator orchestrator) {
        this.port         = port;
        this.orchestrator = orchestrator;
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

        httpServer.createContext("/api/health",   new HealthHandler());
        httpServer.createContext("/api/projects", new ProjectsHandler());
        httpServer.createContext("/api/engines",  new EnginesHandler());
        httpServer.createContext("/api/roll",     new RollHandler());
        httpServer.createContext("/api/evaluate", new EvaluateHandler(orchestrator));

        // Use a small thread pool for concurrent requests
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();

        System.out.println("[ApiServer] Listening on http://localhost:" + port + "/api/");
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
}
