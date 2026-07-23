package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import engine.EngineConfig;
import engine.SimulationEngine;
import iface.EngineOrchestrator;

import java.io.IOException;

final class PvAiStartHandler implements HttpHandler {

    private final SessionManager sessionManager;
    private final EngineOrchestrator orchestrator;

    PvAiStartHandler(SessionManager sessionManager, EngineOrchestrator orchestrator) {
        this.sessionManager = sessionManager;
        this.orchestrator   = orchestrator;
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
            SimulationEngine engine = orchestrator.getEngine(engineId);
            if (engine == null) {
                ApiUtils.sendError(exchange, 400, "Unknown engine: " + engineId);
                return;
            }

            sessionManager.getPvaiController().start(aiPlayerIndex, engineId, engine, config, minThinkMs);

            JsonObject resp = new JsonObject();
            resp.addProperty("ok", true);
            resp.addProperty("aiPlayerIndex", aiPlayerIndex);
            ApiUtils.sendJson(exchange, 200, resp);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
