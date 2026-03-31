package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameState;
import core.Player;
import core.RollResolver;

import java.io.IOException;

/**
 * POST /api/roll — applies a dice roll to a game state and returns the resulting coin deltas.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "state":       { ...GameState JSON... },
 *   "playerIndex": 0,
 *   "roll":        7
 * }
 * </pre>
 *
 * <h2>Response</h2>
 * <pre>
 * {
 *   "coinDeltas": [-1, 3, 0, 1],   // indexed by player
 *   "stateAfter": { ...GameState JSON... }
 * }
 * </pre>
 *
 * <p>The state after the roll has coins updated; no purchase is applied.
 */
final class RollHandler implements HttpHandler {

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
            GameState state = GameStateSerializer.fromJson(body.getAsJsonObject("state"));
            int playerIndex = body.get("playerIndex").getAsInt();
            int roll = body.get("roll").getAsInt();

            if (playerIndex < 0 || playerIndex >= state.getPlayers().length) {
                ApiUtils.sendError(exchange, 400, "playerIndex out of range");
                return;
            }
            if (roll < 1 || roll > 12) {
                ApiUtils.sendError(exchange, 400, "roll must be between 1 and 12");
                return;
            }

            int[] deltas = RollResolver.computeAllDeltasForRoll(state, playerIndex, roll);
            Player[] players = state.getPlayers();
            for (int i = 0; i < players.length; i++) {
                players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
            }

            JsonArray deltasArr = new JsonArray();
            for (int d : deltas) deltasArr.add(d);

            JsonObject response = new JsonObject();
            response.add("coinDeltas", deltasArr);
            response.add("stateAfter", GameStateSerializer.toJson(state));
            ApiUtils.sendJson(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
