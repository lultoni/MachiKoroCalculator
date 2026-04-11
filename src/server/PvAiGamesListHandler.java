package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/**
 * GET /api/session/pvai/games — returns all saved PvAI game records from
 * {@code data/pvai-games.json}.
 *
 * <h2>Query parameters</h2>
 * <ul>
 *   <li>{@code full=true} — include the full {@code gameLog} in each record (default: false,
 *       summaries only for the list view)</li>
 * </ul>
 *
 * <h2>Response (200)</h2>
 * JSON array of {@link PvAiGameRecord} objects (with or without {@code gameLog}).
 */
final class PvAiGamesListHandler implements HttpHandler {

    private final PvAiGameStore store;

    PvAiGamesListHandler(PvAiGameStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        boolean full = query != null && query.contains("full=true");

        // Single-game lookup: ?id=xxx
        String id = null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("id=")) { id = param.substring(3); break; }
            }
        }

        try {
            if (id != null) {
                PvAiGameRecord record = store.loadById(id);
                if (record == null) { ApiUtils.sendError(exchange, 404, "Not found"); return; }
                ApiUtils.sendJson(exchange, 200, record);
                return;
            }

            List<PvAiGameRecord> records = store.loadAll();

            if (full) {
                // Return full records including gameLog
                ApiUtils.sendJson(exchange, 200, records);
            } else {
                // Return summary objects (no gameLog) to keep response small
                JsonArray arr = new JsonArray();
                for (PvAiGameRecord r : records) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", r.id);
                    obj.addProperty("date", r.date);
                    obj.addProperty("engineId", r.engineId);
                    obj.addProperty("humanPlayerIndex", r.humanPlayerIndex);
                    obj.addProperty("winnerIndex", r.winnerIndex);
                    obj.addProperty("totalTurns", r.totalTurns);
                    com.google.gson.JsonArray names = new com.google.gson.JsonArray();
                    for (String n : r.playerNames) names.add(n);
                    obj.add("playerNames", names);
                    com.google.gson.JsonArray coins = new com.google.gson.JsonArray();
                    if (r.finalCoins != null) for (int c : r.finalCoins) coins.add(c);
                    obj.add("finalCoins", coins);
                    com.google.gson.JsonArray landmarks = new com.google.gson.JsonArray();
                    if (r.landmarkCounts != null) for (int l : r.landmarkCounts) landmarks.add(l);
                    obj.add("landmarkCounts", landmarks);
                    com.google.gson.JsonArray luck = new com.google.gson.JsonArray();
                    if (r.totalLuck != null) for (double l : r.totalLuck) luck.add(l);
                    obj.add("totalLuck", luck);
                    arr.add(obj);
                }
                ApiUtils.sendJson(exchange, 200, arr);
            }

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
