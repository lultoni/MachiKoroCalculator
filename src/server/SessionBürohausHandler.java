package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;
import core.Project;
import core.ProjectLoader;

import java.io.IOException;

/**
 * POST /api/session/bürohaus — executes (or declines) a bürohaus card swap.
 *
 * <h2>Request body (swap)</h2>
 * <pre>
 * {
 *   "ownCardId":      "weizenfeld",
 *   "oppPlayerIndex": 1,
 *   "oppCardId":      "bergwerk"
 * }
 * </pre>
 *
 * <h2>Request body (decline)</h2>
 * <pre>
 * { "decline": true }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class SessionBürohausHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionBürohausHandler(SessionManager sessionManager) {
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

        JsonObject body;
        try {
            body = ApiUtils.parseBody(exchange);
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }

        try {
            // If the user declines the swap, return current state unchanged
            if (body.has("decline") && body.get("decline").getAsBoolean()) {
                ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));
                return;
            }

            String ownCardId = body.get("ownCardId").getAsString();
            int oppPlayerIndex = body.get("oppPlayerIndex").getAsInt();
            String oppCardId = body.get("oppCardId").getAsString();

            Project ownCard = ProjectLoader.getProject(ownCardId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown project id: " + ownCardId));
            Project oppCard = ProjectLoader.getProject(oppCardId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown project id: " + oppCardId));

            int playerIndex = session.nextPlayerIndex();
            // After applyTurn, nextPlayerIndex has advanced; the bürohaus swap applies
            // to the player who just took the turn — that is the last entry in history.
            int lastTurnPlayer = session.getHistory().isEmpty()
                    ? 0
                    : session.getHistory().get(session.getHistory().size() - 1).playerIndex;

            session.applyBürohausSwap(lastTurnPlayer, ownCard, oppPlayerIndex, oppCard);

            ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
