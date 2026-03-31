package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /api/health — server liveness check.
 *
 * <p>Response: {@code {"status": "ok"}}
 */
final class HealthHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        ApiUtils.sendJson(exchange, 200, response);
    }
}
