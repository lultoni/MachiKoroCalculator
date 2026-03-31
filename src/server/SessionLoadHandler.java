package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * POST /api/session/load — loads a saved session from a .mkoro file.
 *
 * <h2>Request body</h2>
 * <pre>
 * { "filename": "my-game.mkoro" }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class SessionLoadHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionLoadHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
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
            if (!body.has("filename") || body.get("filename").isJsonNull()) {
                ApiUtils.sendError(exchange, 400, "Missing required field: filename");
                return;
            }
            String filename = body.get("filename").getAsString();

            Path savePath = sessionManager.getSavesDir().resolve(filename);
            if (!Files.exists(savePath)) {
                ApiUtils.sendError(exchange, 404, "Save file not found: " + filename);
                return;
            }

            GameSession session = GameSession.load(savePath);
            sessionManager.setSession(session);

            ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Load failed: " + e.getMessage());
        }
    }
}
