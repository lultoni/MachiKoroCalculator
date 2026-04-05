package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;
import core.GameState;

import java.io.IOException;

/**
 * POST /api/session/create — creates a new game session.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "playerCount": 2,
 *   "playerNames": ["Alice", "Bob"]
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class SessionCreateHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionCreateHandler(SessionManager sessionManager) {
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
            int playerCount = body.get("playerCount").getAsInt();
            if (playerCount < 2 || playerCount > 4) {
                ApiUtils.sendError(exchange, 400, "playerCount must be 2–4");
                return;
            }

            String[] playerNames = new String[playerCount];
            if (body.has("playerNames") && body.get("playerNames").isJsonArray()) {
                JsonArray namesArr = body.getAsJsonArray("playerNames");
                if (namesArr.size() != playerCount) {
                    ApiUtils.sendError(exchange, 400,
                            "playerNames length (" + namesArr.size()
                            + ") must match playerCount (" + playerCount + ")");
                    return;
                }
                for (int i = 0; i < playerCount; i++) {
                    playerNames[i] = namesArr.get(i).getAsString();
                }
            } else {
                for (int i = 0; i < playerCount; i++) {
                    playerNames[i] = "Player " + (i + 1);
                }
            }

            GameState initialState = GameState.initial(playerCount);
            // Apply custom names to the initial state's players
            for (int i = 0; i < playerCount; i++) {
                initialState.getPlayers()[i] = new core.Player(
                        playerNames[i],
                        initialState.getPlayers()[i].getCoins(),
                        initialState.getPlayers()[i].getOwned_projects());
            }

            GameSession session = new GameSession(initialState, playerNames);
            sessionManager.setSession(session);

            JsonObject response = SessionSerializer.toJson(session);
            SessionSerializer.addEngineSnapshots(response, sessionManager.getEngineSnapshots());
            ApiUtils.sendJson(exchange, 200, response);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
