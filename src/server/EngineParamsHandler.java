package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import iface.EngineParamRegistry;
import iface.EngineParamRegistry.ParamEntry;

import java.io.IOException;
import java.util.List;

/**
 * HTTP handler for the engine parameter schema endpoint.
 *
 * <p>{@code GET /api/engine-params} returns the full parameter schema for all engine classes,
 * excluding internal params and sweep-specific fields (those are backend-only concerns).
 */
final class EngineParamsHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;

        if (!"GET".equals(exchange.getRequestMethod().toUpperCase())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            JsonObject response = new JsonObject();

            // Standard params (always public, no sweep fields)
            response.add("standard", paramsToJson(EngineParamRegistry.getStandard()));

            // Per-engine params (filter internal, strip sweep fields)
            JsonObject enginesObj = new JsonObject();
            for (String engineClass : EngineParamRegistry.getEngineClassIds()) {
                enginesObj.add(engineClass, paramsToJson(EngineParamRegistry.getForClass(engineClass)));
            }
            response.add("engines", enginesObj);

            ApiUtils.sendJson(exchange, 200, response);
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, e.getMessage());
        }
    }

    private static JsonArray paramsToJson(List<ParamEntry> params) {
        JsonArray arr = new JsonArray();
        for (ParamEntry p : params) {
            if (p.internal()) continue; // exclude internal params from API

            JsonObject obj = new JsonObject();
            obj.addProperty("key", p.key());
            obj.addProperty("description", p.description());
            obj.addProperty("type", p.type());
            if (p.min() != null) obj.addProperty("min", p.min());
            if (p.max() != null) obj.addProperty("max", p.max());
            if (p.step() != null) obj.addProperty("step", p.step());
            if (p.defaultValue() != null) obj.addProperty("default", p.defaultValue());
            if (p.category() != null) obj.addProperty("category", p.category());
            if (p.options() != null) {
                JsonArray opts = new JsonArray();
                p.options().forEach(opts::add);
                obj.add("options", opts);
            }
            // sweep fields intentionally excluded — backend-only
            arr.add(obj);
        }
        return arr;
    }
}
