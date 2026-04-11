package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import engine.EngineConfig;

import java.io.IOException;

/**
 * POST /api/session/pvai/start — activates Player-vs-AI mode for the current session.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "engineId":       "mcts-v1",   // engine class id (default: "mcts-v1")
 *   "aiPlayerIndex":  1,            // seat index for the AI (0 or 1)
 *   "minThinkTimeMs": 1000,         // minimum think time before AI turn reveal (ms)
 *   "timeBudgetMs":   5000          // engine time budget per position (ms)
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * { "ok": true, "aiPlayerIndex": 1 }
 * </pre>
 */
final class PvAiStartHandler implements HttpHandler {

    private final SessionManager sessionManager;

    PvAiStartHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "POST");
            return;
        }
        if (sessionManager.getSession() == null) {
            ApiUtils.sendError(exchange, 404, "No active session");
            return;
        }

        JsonObject body;
        try {
            body = ApiUtils.parseBody(exchange);
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        try {
            String engineId      = body.has("engineId")       ? body.get("engineId").getAsString() : "mcts-v1";
            int    aiPlayerIndex = body.has("aiPlayerIndex")  ? body.get("aiPlayerIndex").getAsInt()  : 1;
            int    minThinkMs    = body.has("minThinkTimeMs") ? body.get("minThinkTimeMs").getAsInt() : 1000;
            long   timeBudgetMs  = body.has("timeBudgetMs")   ? body.get("timeBudgetMs").getAsLong() : 5000L;

            EngineConfig config = new EngineConfig(500, 0, 0.0, null);

            sessionManager.getPvaiController().start(aiPlayerIndex, engineId, config, minThinkMs);

            JsonObject resp = new JsonObject();
            resp.addProperty("ok", true);
            resp.addProperty("aiPlayerIndex", aiPlayerIndex);
            ApiUtils.sendJson(exchange, 200, resp);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
