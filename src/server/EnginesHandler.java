package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.io.IOException;

/**
 * GET /api/engines — returns all registered engine configurations.
 *
 * <p>Response: JSON array of engine entries, each with:
 * {@code id, engineClass, description, isDefault, config}
 */
final class EnginesHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        JsonArray arr = new JsonArray();
        for (EngineRegistryEntry e : EngineRegistry.getAll()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", e.id());
            obj.addProperty("engineClass", e.engineClass());
            obj.addProperty("description", e.description());
            obj.addProperty("tier", e.tier());
            obj.addProperty("isDefault", e.isDefault());

            JsonObject config = new JsonObject();
            config.addProperty("iterations", e.config().iterations);
            config.addProperty("timeBudgetMs", e.config().timeBudgetMs);
            config.addProperty("riskToleranceWeight", e.config().riskToleranceWeight);
            if (e.config().extra != null) {
                JsonObject extra = new JsonObject();
                e.config().extra.forEach(extra::addProperty);
                config.add("extra", extra);
            }
            obj.add("config", config);
            arr.add(obj);
        }

        ApiUtils.sendJson(exchange, 200, arr);
    }
}
