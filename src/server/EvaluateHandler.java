package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameState;
import engine.EngineResult;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.io.IOException;
import java.util.Optional;

/**
 * POST /api/evaluate — runs an engine evaluation and returns ranked purchase options.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "state":       { ...GameState JSON... },
 *   "playerIndex": 0,
 *   "engineId":    "mcts-v1-balanced"   // optional; defaults to registry default
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "engineId":       "mcts-v1-balanced",
 *   "iterationsUsed": 5000,
 *   "computeTimeMs":  1234,
 *   "confidence":     0.72,
 *   "rankedOptions": [
 *     {
 *       "projectId":          "bergwerk",
 *       "score":              0.34,
 *       "affordable":         true,
 *       "explanationFactors": ["High EV on own turn (+2.4¢/turn)", "..."],
 *       "metrics":            { "immediateEV": "2.4", "variance": "1.2" }
 *     },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <h2>Response (503)</h2>
 * When no engine is registered yet: {@code {"error": "...", "engineId": "..."}}
 */
final class EvaluateHandler implements HttpHandler {

    private final EngineOrchestrator orchestrator;

    EvaluateHandler(EngineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "POST");
            return;
        }

        JsonObject body;
        try {
            body = ApiUtils.parseBody(exchange);
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }

        try {
            GameState state = GameStateSerializer.fromJson(body.getAsJsonObject("state"));
            int playerIndex = body.get("playerIndex").getAsInt();

            String engineId = body.has("engineId") ? body.get("engineId").getAsString() : null;
            EngineRegistryEntry entry;
            if (engineId != null) {
                Optional<EngineRegistryEntry> opt = EngineRegistry.findById(engineId);
                if (opt.isEmpty()) {
                    ApiUtils.sendError(exchange, 400, "Unknown engineId: " + engineId);
                    return;
                }
                entry = opt.get();
            } else {
                entry = EngineRegistry.getDefault();
            }

            if (!orchestrator.hasEngine(entry.engineClass())) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "Engine '" + entry.engineClass()
                        + "' is not yet implemented. Phase 2 (MCTS) will add it.");
                err.addProperty("engineId", entry.id());
                ApiUtils.sendJson(exchange, 503, err);
                return;
            }

            if (playerIndex < 0 || playerIndex >= state.getPlayers().length) {
                ApiUtils.sendError(exchange, 400, "playerIndex out of range");
                return;
            }

            EngineResult result = orchestrator.evaluate(state, playerIndex, entry);
            ApiUtils.sendJson(exchange, 200, serializeResult(entry.id(), result));

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private static JsonObject serializeResult(String engineId, EngineResult result) {
        JsonObject obj = new JsonObject();
        obj.addProperty("engineId", engineId);
        obj.addProperty("iterationsUsed", result.iterationsUsed);
        obj.addProperty("computeTimeMs", result.computeTimeMs);
        obj.addProperty("confidence", result.confidence);
        if (result.debugInfo != null) obj.addProperty("debugInfo", result.debugInfo);

        JsonArray options = new JsonArray();
        for (EngineResult.Option opt : result.rankedOptions) {
            JsonObject o = new JsonObject();
            o.addProperty("projectId", opt.project.getId());
            o.addProperty("score", opt.score);
            o.addProperty("affordable", opt.affordable);

            JsonArray factors = new JsonArray();
            for (String f : opt.explanationFactors) factors.add(f);
            o.add("explanationFactors", factors);

            if (opt.metrics != null) {
                JsonObject metrics = new JsonObject();
                opt.metrics.forEach(metrics::addProperty);
                o.add("metrics", metrics);
            }
            options.add(o);
        }
        obj.add("rankedOptions", options);
        return obj;
    }
}
