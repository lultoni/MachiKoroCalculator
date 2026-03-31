package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;
import core.GameStateBuilder;
import core.ProjectLoader;

import java.io.IOException;

/**
 * POST /api/session/from-snapshot — creates a new session from an arbitrary game state snapshot.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "state": {
 *     "players": [
 *       { "name": "Alice", "coins": 7, "ownedIds": ["weizenfeld", "bäckerei", "bahnhof"] },
 *       { "name": "Bob",   "coins": 3, "ownedIds": ["weizenfeld", "bäckerei"] }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class SessionFromSnapshotHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionFromSnapshotHandler(SessionManager sessionManager) {
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
            JsonObject stateObj = body.getAsJsonObject("state");
            if (stateObj == null) {
                ApiUtils.sendError(exchange, 400, "Missing required field: state");
                return;
            }

            JsonArray playersArr = stateObj.getAsJsonArray("players");
            if (playersArr == null || playersArr.size() < 2 || playersArr.size() > 4) {
                ApiUtils.sendError(exchange, 400,
                        "\"players\" must be an array of 2–4 player objects");
                return;
            }

            int n = playersArr.size();
            String[] names = new String[n];
            GameStateBuilder builder = new GameStateBuilder(n);

            for (int i = 0; i < n; i++) {
                JsonObject pObj = playersArr.get(i).getAsJsonObject();
                String name = pObj.get("name").getAsString();
                names[i] = name;
                builder.setPlayerName(i, name);
                builder.setCoins(i, pObj.get("coins").getAsInt());
                for (JsonElement idEl : pObj.getAsJsonArray("ownedIds")) {
                    String id = idEl.getAsString();
                    if (ProjectLoader.getProject(id).isEmpty()) {
                        ApiUtils.sendError(exchange, 400, "Unknown project id: " + id);
                        return;
                    }
                    builder.addProject(i, id);
                }
            }

            GameSession session = GameSession.fromSnapshot(builder, names);
            sessionManager.setSession(session);

            ApiUtils.sendJson(exchange, 200, SessionSerializer.toJson(session));

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
