package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;

import java.io.IOException;

/**
 * GET /api/session/state — returns the current session state.
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 *
 * <h2>Response (404)</h2>
 * {@code {"error": "No active session"}} if no session has been created.
 */
final class SessionStateHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionStateHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        GameSession session = sessionManager.getSession();
        if (session == null) {
            ApiUtils.sendError(exchange, 404, "No active session");
            return;
        }

        ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));
    }
}
