package server;

import calcs.LuckAnalyzer;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;
import core.GameState;

import java.io.IOException;

/**
 * POST /api/session/roll-luck — computes per-roll luck for the active session position.
 *
 * <p>Must be called <em>before</em> applying the turn (pre-income state required by LuckAnalyzer).
 * Always uses heuristic mode (instant, ~0.22 MAE) regardless of the {@code luckUseMc} setting,
 * because this endpoint is called on every die click and must respond in &lt;5 ms.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "roll":        7,   // actual dice total rolled
 *   "diceCount":   2,   // 1 or 2
 *   "playerIndex": 0    // seat index of the active player
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "luck":          0.043,   // WR delta vs expected (positive = lucky)
 *   "wrAfterActual": 0.612,   // WR after this actual roll
 *   "expectedWr":    0.569    // probability-weighted avg WR across all possible rolls
 * }
 * </pre>
 */
final class SessionRollLuckHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionRollLuckHandler(SessionManager sessionManager) {
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
            ApiUtils.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        try {
            int roll        = body.get("roll").getAsInt();
            int diceCount   = body.has("diceCount") ? body.get("diceCount").getAsInt() : 1;
            int playerIndex = body.has("playerIndex") ? body.get("playerIndex").getAsInt() : 0;
            // Always use heuristic (useMc=false) for instant real-time response.
            // MC mode with mcSims=0 returns NaN; heuristic is accurate enough for the live chip.

            GameState state = session.getState();

            LuckAnalyzer.RollLuck rollLuck = LuckAnalyzer.computeRollLuck(
                    state, playerIndex, roll, diceCount == 2, 0, false);

            JsonObject resp = new JsonObject();
            resp.addProperty("luck",          rollLuck.luck());
            resp.addProperty("wrAfterActual", rollLuck.wrAfterActual());
            resp.addProperty("expectedWr",    rollLuck.expectedWr());
            ApiUtils.sendJson(exchange, 200, resp);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
