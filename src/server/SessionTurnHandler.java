package server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;
import core.Project;
import core.ProjectLoader;
import core.TurnRecord;

import java.io.IOException;

/**
 * POST /api/session/turn — applies a turn to the active session.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "roll":      7,
 *   "boughtId":  "bauernhof",   // null to skip purchase
 *   "isDoubles": false,
 *   "diceCount": 1
 * }
 * </pre>
 *
 * <p>The {@code playerIndex} is derived from {@link GameSession#nextPlayerIndex()},
 * not from the request body.
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class SessionTurnHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionTurnHandler(SessionManager sessionManager) {
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
            int roll = body.get("roll").getAsInt();
            boolean isDoubles = body.has("isDoubles") && body.get("isDoubles").getAsBoolean();
            int diceCount = body.has("diceCount") ? body.get("diceCount").getAsInt() : 1;

            Project bought = null;
            JsonElement boughtEl = body.get("boughtId");
            if (boughtEl != null && !boughtEl.isJsonNull()) {
                String boughtId = boughtEl.getAsString();
                bought = ProjectLoader.getProject(boughtId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown project id: " + boughtId));
            }

            int playerIndex = session.nextPlayerIndex();
            TurnRecord record = new TurnRecord(playerIndex, roll, bought, isDoubles,
                    null, null, null, -1, diceCount);
            session.applyTurn(record);

            ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
