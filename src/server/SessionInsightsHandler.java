package server;

import calcs.Calcs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.CardIncome;
import core.GameSession;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.mcts.SupplyTracker;

import java.io.IOException;

/**
 * GET /api/session/insights?playerIndex=0 — computes position insights for the active session.
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "playerInsights": [
 *     { "name": "Alice", "etw": 4.2, "evPerRound": 3.1, "variance": 2.5, "landmarksOwned": 2 },
 *     { "name": "Bob",   "etw": 6.8, "evPerRound": 2.3, "variance": 1.8, "landmarksOwned": 1 }
 *   ],
 *   "tempoAdvantage": 2.6,
 *   "portfolioEV": 3.1,
 *   "supplyWarnings": [ { "cardId": "bergwerk", "remaining": 1 } ]
 * }
 * </pre>
 */
final class SessionInsightsHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionInsightsHandler(SessionManager sessionManager) {
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

        try {
            // Parse playerIndex from query string
            String query = exchange.getRequestURI().getQuery();
            int playerIndex = 0;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "playerIndex".equals(kv[0])) {
                        playerIndex = Integer.parseInt(kv[1]);
                    }
                }
            }

            GameState state = session.getState();
            Player[] players = state.getPlayers();

            if (playerIndex < 0 || playerIndex >= players.length) {
                ApiUtils.sendError(exchange, 400, "playerIndex out of range");
                return;
            }

            SupplyTracker supply = SupplyTracker.fromGameState(state);

            // Build per-player insights
            JsonArray playerInsights = new JsonArray();
            // We need a dummy candidate for ETW — use weizenfeld as a no-op stand-in.
            // Instead, compute ETW for each player's current portfolio (no candidate).
            for (int i = 0; i < players.length; i++) {
                JsonObject pi = new JsonObject();
                pi.addProperty("name", players[i].getName());

                // ETW for this player's current portfolio
                double etw = estimatedTurnsToWinCurrent(state, i);
                pi.addProperty("etw", round4(etw));

                // EV per round for current portfolio
                int[] oppCoins = CardIncome.buildOpponentCoins(players, i);
                double evPerRound = CardIncome.playerEvPerRound(players[i], players.length, oppCoins);
                pi.addProperty("evPerRound", round4(evPerRound));

                // Variance (compute for current portfolio — use Calcs internal via roiOverHorizon approach)
                double variance = computeCurrentVariance(state, i);
                pi.addProperty("variance", round4(variance));

                // Landmarks owned count
                int landmarksOwned = 0;
                for (Project p : players[i].getOwned_projects()) {
                    if (p.isIs_grossprojekt()) landmarksOwned++;
                }
                pi.addProperty("landmarksOwned", landmarksOwned);

                playerInsights.add(pi);
            }

            // Tempo advantage for the requested player
            double tempoAdvantage = computeTempoAdvantage(state, playerIndex);

            // Portfolio EV for the requested player
            double portfolioEV = Calcs.portfolioEvPerRound(state, playerIndex);

            // Supply warnings: cards with remaining <= 2
            JsonArray supplyWarnings = new JsonArray();
            for (Project p : ProjectLoader.getAllProjects()) {
                if (p.isIs_grossprojekt()) continue;
                int remaining = supply.getCount(p.getId());
                if (remaining <= 2) {
                    JsonObject warning = new JsonObject();
                    warning.addProperty("cardId", p.getId());
                    warning.addProperty("remaining", remaining);
                    supplyWarnings.add(warning);
                }
            }

            JsonObject response = new JsonObject();
            response.add("playerInsights", playerInsights);
            response.addProperty("tempoAdvantage", round4(tempoAdvantage));
            response.addProperty("portfolioEV", round4(portfolioEV));
            response.add("supplyWarnings", supplyWarnings);

            ApiUtils.sendJson(exchange, 200, response);

        } catch (NumberFormatException e) {
            ApiUtils.sendError(exchange, 400, "Invalid playerIndex: " + e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Estimates turns to win for a player's current portfolio (no candidate purchase).
     * Replicates the logic from {@link Calcs#estimatedTurnsToWin} without requiring a candidate.
     */
    private static double estimatedTurnsToWinCurrent(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        int landmarkCostRemaining = 0;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) continue;
            if (player.hasProject(p.getId())) continue;
            landmarkCostRemaining += p.getCost();
        }
        if (landmarkCostRemaining == 0) return 0.0;

        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);
        double ev = CardIncome.playerEvPerRound(player, gs.getPlayers().length, oppCoins);
        if (ev < 1e-9) return Double.MAX_VALUE;

        double deficit = Math.max(0.0, landmarkCostRemaining - player.getCoins());
        return deficit / ev;
    }

    /**
     * Computes variance for a player's current own-turn income distribution.
     * Uses the roll-gain cache approach consistent with Calcs internals.
     */
    private static double computeCurrentVariance(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        boolean hasBahnhof = player.hasProject("bahnhof");

        // Build roll gain cache
        double[] cache = new double[13];
        for (int r = 1; r <= 12; r++) {
            cache[r] = Calcs.computeAllDeltasForRoll(gs, playerIndex, r)[playerIndex];
        }

        if (!hasBahnhof) {
            return variance1d6(cache);
        }
        java.util.function.IntToDoubleFunction payout = r -> cache[r];
        boolean use2d6 = CardIncome.weightedRollEV(true, payout)
                > CardIncome.weightedRollEV(false, payout);
        return use2d6 ? variance2d6(cache) : variance1d6(cache);
    }

    private static double variance1d6(double[] cache) {
        double ev = 0.0, e2 = 0.0;
        for (int d = 1; d <= 6; d++) {
            double gain = cache[d];
            ev += CardIncome.P1[d] * gain;
            e2 += CardIncome.P1[d] * gain * gain;
        }
        return e2 - ev * ev;
    }

    private static double variance2d6(double[] cache) {
        double ev = 0.0, e2 = 0.0;
        for (int d1 = 1; d1 <= 6; d1++) {
            for (int d2 = 1; d2 <= 6; d2++) {
                double p = 1.0 / 36.0;
                double gain = cache[d1 + d2];
                ev += p * gain;
                e2 += p * gain * gain;
            }
        }
        return e2 - ev * ev;
    }

    /**
     * Computes tempo advantage for the requested player: min(opponent ETW) - player ETW.
     * Positive means player is ahead.
     */
    private static double computeTempoAdvantage(GameState gs, int playerIndex) {
        double playerEtw = estimatedTurnsToWinCurrent(gs, playerIndex);
        double opponentMinEtw = Double.MAX_VALUE;
        for (int i = 0; i < gs.getPlayers().length; i++) {
            if (i == playerIndex) continue;
            double oppEtw = estimatedTurnsToWinCurrent(gs, i);
            if (oppEtw < opponentMinEtw) opponentMinEtw = oppEtw;
        }
        if (opponentMinEtw == Double.MAX_VALUE) return 0.0;
        return opponentMinEtw - playerEtw;
    }

    /** Rounds a double to 4 decimal places. */
    private static double round4(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) return value;
        return Math.round(value * 10000.0) / 10000.0;
    }
}
