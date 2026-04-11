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
 * POST /api/session/pvai/human-turn — applies the human player's completed turn and
 * delivers a NavigationEvent to the continuous engine.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "roll":      7,
 *   "boughtId":  "bauernhof",   // null to skip purchase
 *   "isDoubles": false,
 *   "diceCount": 1,
 *   "bürohausOwnCardId": null,  // optional: card swapped away by active player
 *   "bürohausOppCardId": null,  // optional: card received
 *   "bürohausOppPlayer": null   // optional: opponent index
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * Full session JSON via {@link SessionSerializer#toJson}.
 */
final class PvAiHumanTurnHandler implements HttpHandler {

    private final SessionManager sessionManager;

    PvAiHumanTurnHandler(SessionManager sessionManager) {
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
            int roll       = body.get("roll").getAsInt();
            boolean isDoubles = body.has("isDoubles") && body.get("isDoubles").getAsBoolean();
            int diceCount  = body.has("diceCount") ? body.get("diceCount").getAsInt() : 1;

            Project bought = null;
            JsonElement boughtEl = body.get("boughtId");
            if (boughtEl != null && !boughtEl.isJsonNull()) {
                String boughtId = boughtEl.getAsString();
                bought = ProjectLoader.getProject(boughtId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown project id: " + boughtId));
            }

            Project bürohausOwn = null;
            Project bürohausOpp = null;
            int bürohausOppPlayer = -1;
            if (body.has("bürohausOwnCardId") && !body.get("bürohausOwnCardId").isJsonNull()) {
                String ownId = body.get("bürohausOwnCardId").getAsString();
                bürohausOwn = ProjectLoader.getProject(ownId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown card: " + ownId));
            }
            if (body.has("bürohausOppCardId") && !body.get("bürohausOppCardId").isJsonNull()) {
                String oppId = body.get("bürohausOppCardId").getAsString();
                bürohausOpp = ProjectLoader.getProject(oppId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown card: " + oppId));
            }
            if (body.has("bürohausOppPlayer") && !body.get("bürohausOppPlayer").isJsonNull()) {
                bürohausOppPlayer = body.get("bürohausOppPlayer").getAsInt();
            }

            int playerIndex = session.nextPlayerIndex();
            TurnRecord record = new TurnRecord(playerIndex, roll, bought, isDoubles,
                    null, bürohausOwn, bürohausOpp, bürohausOppPlayer, diceCount);
            session.applyTurn(record);

            // Store engine snapshot if provided
            JsonObject snapshot = null;
            if (body.has("engineSnapshot") && !body.get("engineSnapshot").isJsonNull()) {
                snapshot = body.getAsJsonObject("engineSnapshot");
            }
            sessionManager.addEngineSnapshot(snapshot);

            // Notify PvAI controller (if active) to navigate engine to AI's upcoming position
            PlayerVsAiController pvai = sessionManager.getPvaiController();
            if (pvai.isActive()) {
                pvai.onHumanTurnComplete(record);
            }

            JsonObject response = SessionSerializer.toJson(session);
            SessionSerializer.addEngineSnapshots(response, sessionManager.getEngineSnapshots());
            ApiUtils.sendJson(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
