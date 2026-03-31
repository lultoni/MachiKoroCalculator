package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;

import java.io.IOException;

/**
 * POST /api/session/undo — undoes the last turn in the active session.
 *
 * <h2>Request body</h2>
 * None required.
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 *
 * <h2>Response (400)</h2>
 * {@code {"error": "..."}} if there are no turns to undo.
 */
final class SessionUndoHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionUndoHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "POST");
            return;
        }

        GameSession session = sessionManager.getSession();
        if (session == null) {
            ApiUtils.sendError(exchange, 404, "No active session");
            return;
        }

        try {
            session.undoLastTurn();
            ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));
        } catch (IllegalStateException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
