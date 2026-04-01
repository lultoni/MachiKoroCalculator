package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameState;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.io.IOException;
import java.util.Optional;

/**
 * POST /api/evaluate/precompute — triggers background engine evaluation.
 *
 * <p>Returns 202 Accepted immediately. The result is retrieved via the normal
 * {@code /api/evaluate} endpoint, which checks the cache first.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "state":       { ...GameState JSON... },
 *   "playerIndex": 0,
 *   "engineId":    "mcts-v1-balanced"   // optional
 * }
 * </pre>
 */
final class PrecomputeHandler implements HttpHandler {

    private final PrecomputeCache cache;

    PrecomputeHandler(PrecomputeCache cache) {
        this.cache = cache;
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

            cache.startPrecompute(state, playerIndex, entry);

            JsonObject response = new JsonObject();
            response.addProperty("status", "accepted");
            ApiUtils.sendJson(exchange, 202, response);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
