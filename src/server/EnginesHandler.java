package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import engine.EngineConfig;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP handler for engine registry endpoints.
 *
 * <ul>
 *   <li>{@code GET  /api/engines}           — returns all registered engine configurations</li>
 *   <li>{@code POST /api/engines/custom}    — create or update a custom engine entry</li>
 *   <li>{@code DELETE /api/engines/custom/{id}} — delete a custom engine entry</li>
 * </ul>
 */
final class EnginesHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/api/engines") && "GET".equals(method)) {
                handleGetAll(exchange);
            } else if (path.equals("/api/engines/custom") && "POST".equals(method)) {
                handleSaveCustom(exchange);
            } else if (path.startsWith("/api/engines/custom/") && "DELETE".equals(method)) {
                String id = path.substring("/api/engines/custom/".length());
                handleDeleteCustom(exchange, id);
            } else {
                ApiUtils.sendMethodNotAllowed(exchange, "GET, POST, DELETE");
            }
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleGetAll(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        for (EngineRegistryEntry e : EngineRegistry.getAll()) {
            arr.add(entryToJson(e));
        }
        ApiUtils.sendJson(exchange, 200, arr);
    }

    private void handleSaveCustom(HttpExchange exchange) throws IOException {
        JsonObject body;
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            body = JsonParser.parseReader(reader).getAsJsonObject();
        }

        String id = body.has("id") ? body.get("id").getAsString().trim() : "";
        String engineClass = body.has("engineClass") ? body.get("engineClass").getAsString().trim() : "";
        String description = body.has("description") ? body.get("description").getAsString().trim() : "";
        String tier = body.has("tier") ? body.get("tier").getAsString().trim() : "fast";

        if (id.isEmpty()) {
            ApiUtils.sendError(exchange, 400, "Missing required field: id");
            return;
        }
        if (engineClass.isEmpty()) {
            ApiUtils.sendError(exchange, 400, "Missing required field: engineClass");
            return;
        }

        // Parse config map
        Map<String, String> rawConfig = new HashMap<>();
        if (body.has("config")) {
            JsonObject configObj = body.getAsJsonObject("config");
            for (Map.Entry<String, JsonElement> entry : configObj.entrySet()) {
                rawConfig.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        EngineRegistryEntry entry = new EngineRegistryEntry(
                id, engineClass, description, false, tier,
                EngineRegistryEntry.buildConfig(rawConfig), true);

        try {
            EngineRegistry.saveCustom(entry);
        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 409, e.getMessage());
            return;
        }

        ApiUtils.sendJson(exchange, 200, entryToJson(entry));
    }

    private void handleDeleteCustom(HttpExchange exchange, String id) throws IOException {
        // Check if it's a built-in entry
        var existing = EngineRegistry.findById(id);
        if (existing.isPresent() && !existing.get().custom()) {
            ApiUtils.sendError(exchange, 403, "Cannot delete built-in engine entry: " + id);
            return;
        }

        boolean deleted = EngineRegistry.deleteCustom(id);
        if (!deleted) {
            ApiUtils.sendError(exchange, 404, "Custom engine not found: " + id);
            return;
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("status", "deleted");
        resp.addProperty("id", id);
        ApiUtils.sendJson(exchange, 200, resp);
    }

    private static JsonObject entryToJson(EngineRegistryEntry e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", e.id());
        obj.addProperty("engineClass", e.engineClass());
        obj.addProperty("description", e.description());
        obj.addProperty("tier", e.tier());
        obj.addProperty("isDefault", e.isDefault());
        obj.addProperty("custom", e.custom());

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
        return obj;
    }
}
