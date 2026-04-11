package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;

import java.io.IOException;

/**
 * GET /api/session/pvai/ai-turn — requests the AI's pre-computed decision.
 *
 * <p>Blocks until the engine's minimum think time has elapsed, then extracts
 * the best TurnPlan, rolls dice, applies Funkturm/Bürohaus decisions, applies
 * the turn to the session, and returns the result.
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "diceCount":          2,
 *   "rollTotal":          8,
 *   "isDoubles":          false,
 *   "coinDeltas":         [3, -2],
 *   "funkturmKeep":       null,
 *   "rerollTotal":        null,
 *   "rerollIsDoubles":    null,
 *   "bürohausOwnCardId":  null,
 *   "bürohausOppCardId":  null,
 *   "bürohausOppPlayer":  null,
 *   "purchasedCardId":    "bauernhof",
 *   "iterationsUsed":     15000,
 *   "thinkTimeMs":        2341,
 *   "session":            { ... full session state ... }
 * }
 * </pre>
 */
final class PvAiAiTurnHandler implements HttpHandler {

    private final SessionManager sessionManager;

    PvAiAiTurnHandler(SessionManager sessionManager) {
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

        PlayerVsAiController pvai = sessionManager.getPvaiController();
        if (!pvai.isActive()) {
            ApiUtils.sendError(exchange, 400, "PvAI mode not active. Call /api/session/pvai/start first.");
            return;
        }

        try {
            AiTurnResult aiTurn = pvai.executeAiTurn();
            if (aiTurn == null) {
                ApiUtils.sendError(exchange, 500, "AI turn execution failed");
                return;
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("diceCount",   aiTurn.diceCount);
            resp.addProperty("rollTotal",   aiTurn.rollTotal);
            resp.addProperty("isDoubles",   aiTurn.isDoubles);

            JsonArray deltas = new JsonArray();
            if (aiTurn.coinDeltas != null) {
                for (int d : aiTurn.coinDeltas) deltas.add(d);
            }
            resp.add("coinDeltas", deltas);

            if (aiTurn.funkturmKeep != null)    resp.addProperty("funkturmKeep",    aiTurn.funkturmKeep);
            else                                resp.add("funkturmKeep",    com.google.gson.JsonNull.INSTANCE);
            if (aiTurn.rerollTotal != null)     resp.addProperty("rerollTotal",     aiTurn.rerollTotal);
            else                                resp.add("rerollTotal",     com.google.gson.JsonNull.INSTANCE);
            if (aiTurn.rerollIsDoubles != null) resp.addProperty("rerollIsDoubles", aiTurn.rerollIsDoubles);
            else                                resp.add("rerollIsDoubles", com.google.gson.JsonNull.INSTANCE);

            if (aiTurn.bürohausOwnCardId != null) resp.addProperty("bürohausOwnCardId", aiTurn.bürohausOwnCardId);
            else                                   resp.add("bürohausOwnCardId", com.google.gson.JsonNull.INSTANCE);
            if (aiTurn.bürohausOppCardId != null) resp.addProperty("bürohausOppCardId", aiTurn.bürohausOppCardId);
            else                                   resp.add("bürohausOppCardId", com.google.gson.JsonNull.INSTANCE);
            if (aiTurn.bürohausOppPlayer != null)  resp.addProperty("bürohausOppPlayer", aiTurn.bürohausOppPlayer);
            else                                   resp.add("bürohausOppPlayer", com.google.gson.JsonNull.INSTANCE);

            if (aiTurn.purchasedCardId != null) resp.addProperty("purchasedCardId", aiTurn.purchasedCardId);
            else                                resp.add("purchasedCardId", com.google.gson.JsonNull.INSTANCE);

            resp.addProperty("iterationsUsed", aiTurn.iterationsUsed);
            resp.addProperty("thinkTimeMs",    aiTurn.thinkTimeMs);

            // Include updated session state
            GameSession updatedSession = sessionManager.getSession();
            if (updatedSession != null) {
                JsonObject sessionJson = SessionSerializer.toJson(updatedSession);
                SessionSerializer.addEngineSnapshots(sessionJson, sessionManager.getEngineSnapshots());
                resp.add("session", sessionJson);
            }

            ApiUtils.sendJson(exchange, 200, resp);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
