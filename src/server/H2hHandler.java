package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import h2h.*;
import iface.EngineOrchestrator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP handler for head-to-head engine testing endpoints.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/h2h/start}               — start a match in background, return match ID</li>
 *   <li>{@code GET  /api/h2h/status/{matchId}}     — progress (gamesCompleted / gameCount)</li>
 *   <li>{@code GET  /api/h2h/results}              — all completed matches (summary, no game logs)</li>
 *   <li>{@code GET  /api/h2h/results/{matchId}}    — full result with game logs</li>
 *   <li>{@code GET  /api/h2h/results/{matchId}/game/{gameIndex}} — single game log</li>
 * </ul>
 */
final class H2hHandler implements HttpHandler {

    private final EngineOrchestrator orchestrator;
    private final H2hResultStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "h2h-match");
        t.setDaemon(true);
        return t;
    });

    /** In-progress match tracking. */
    private final Map<String, MatchProgress> activeMatches = new ConcurrentHashMap<>();

    H2hHandler(EngineOrchestrator orchestrator, H2hResultStore store) {
        this.orchestrator = orchestrator;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/api/h2h/start") && "POST".equals(method)) {
                handleStart(exchange);
            } else if (path.startsWith("/api/h2h/status/") && "GET".equals(method)) {
                String matchId = path.substring("/api/h2h/status/".length());
                handleStatus(exchange, matchId);
            } else if (path.matches("/api/h2h/results/[^/]+/game/\\d+") && "GET".equals(method)) {
                handleGameLog(exchange, path);
            } else if (path.matches("/api/h2h/results/[^/]+") && "GET".equals(method)) {
                String matchId = path.substring("/api/h2h/results/".length());
                handleMatchResult(exchange, matchId);
            } else if (path.equals("/api/h2h/results") && "GET".equals(method)) {
                handleAllResults(exchange);
            } else {
                ApiUtils.sendError(exchange, 404, "Not found: " + path);
            }
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/h2h/start
    // -------------------------------------------------------------------------

    private void handleStart(HttpExchange exchange) throws IOException {
        JsonObject body = ApiUtils.parseBody(exchange);

        String engineA = body.has("engineA") ? body.get("engineA").getAsString() : "mcts-v1-fast";
        String engineB = body.has("engineB") ? body.get("engineB").getAsString() : "mcts-v1-fast";
        int games = body.has("games") ? body.get("games").getAsInt() : 100;
        int iterations = body.has("iterations") ? body.get("iterations").getAsInt() : 500;
        int maxTurns = body.has("maxTurns") ? body.get("maxTurns").getAsInt() : 200;

        MatchConfig config = new MatchConfig(
                new String[]{engineA, engineB}, games, maxTurns, iterations, true);

        String matchId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MatchProgress progress = new MatchProgress(matchId, config);
        activeMatches.put(matchId, progress);

        executor.submit(() -> {
            try {
                MatchRunner runner = new MatchRunner(orchestrator);
                MatchResult result = runner.runMatch(config, (gameIdx, log) -> {
                    progress.gamesCompleted.incrementAndGet();
                });
                store.save(result);
                progress.resultId = result.id;
                progress.completed = true;
            } catch (Exception e) {
                progress.error = e.getMessage();
                progress.completed = true;
            }
        });

        JsonObject response = new JsonObject();
        response.addProperty("matchId", matchId);
        response.addProperty("status", "started");
        response.addProperty("gameCount", games);
        ApiUtils.sendJson(exchange, 202, response);
    }

    // -------------------------------------------------------------------------
    // GET /api/h2h/status/{matchId}
    // -------------------------------------------------------------------------

    private void handleStatus(HttpExchange exchange, String matchId) throws IOException {
        MatchProgress progress = activeMatches.get(matchId);
        if (progress == null) {
            ApiUtils.sendError(exchange, 404, "No active match: " + matchId);
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("matchId", matchId);
        response.addProperty("gamesCompleted", progress.gamesCompleted.get());
        response.addProperty("gameCount", progress.config.gameCount());
        response.addProperty("completed", progress.completed);
        if (progress.error != null) {
            response.addProperty("error", progress.error);
        }
        if (progress.resultId != null) {
            response.addProperty("resultId", progress.resultId);
        }
        ApiUtils.sendJson(exchange, 200, response);
    }

    // -------------------------------------------------------------------------
    // GET /api/h2h/results
    // -------------------------------------------------------------------------

    private void handleAllResults(HttpExchange exchange) throws IOException {
        List<MatchResult> all = store.loadAll();
        JsonArray arr = new JsonArray();
        for (MatchResult r : all) {
            arr.add(toSummaryJson(r));
        }
        ApiUtils.sendJson(exchange, 200, arr);
    }

    // -------------------------------------------------------------------------
    // GET /api/h2h/results/{matchId}
    // -------------------------------------------------------------------------

    private void handleMatchResult(HttpExchange exchange, String matchId) throws IOException {
        MatchResult result = store.findById(matchId);
        if (result == null) {
            ApiUtils.sendError(exchange, 404, "No match result: " + matchId);
            return;
        }
        ApiUtils.sendJson(exchange, 200, result);
    }

    // -------------------------------------------------------------------------
    // GET /api/h2h/results/{matchId}/game/{gameIndex}
    // -------------------------------------------------------------------------

    private void handleGameLog(HttpExchange exchange, String path) throws IOException {
        // Parse: /api/h2h/results/{matchId}/game/{gameIndex}
        String[] parts = path.split("/");
        // parts: ["", "api", "h2h", "results", matchId, "game", gameIndex]
        if (parts.length < 7) {
            ApiUtils.sendError(exchange, 400, "Invalid path");
            return;
        }
        String matchId = parts[4];
        int gameIndex;
        try {
            gameIndex = Integer.parseInt(parts[6]);
        } catch (NumberFormatException e) {
            ApiUtils.sendError(exchange, 400, "Invalid game index");
            return;
        }

        MatchResult result = store.findById(matchId);
        if (result == null) {
            ApiUtils.sendError(exchange, 404, "No match result: " + matchId);
            return;
        }
        if (gameIndex < 0 || gameIndex >= result.gameLogs.size()) {
            ApiUtils.sendError(exchange, 404, "Game index out of range: " + gameIndex);
            return;
        }
        ApiUtils.sendJson(exchange, 200, result.gameLogs.get(gameIndex));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JsonObject toSummaryJson(MatchResult r) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", r.id);
        obj.addProperty("date", r.date);
        obj.addProperty("totalTimeMs", r.totalTimeMs);
        obj.addProperty("avgGameLength", r.avgGameLength);
        obj.addProperty("avgEvalTimeMs", r.avgEvalTimeMs);
        obj.addProperty("gameCount", r.gameLogs.size());

        JsonArray engines = new JsonArray();
        for (String eid : r.config.engineIds()) engines.add(eid);
        obj.add("engines", engines);

        JsonArray wins = new JsonArray();
        for (int w : r.wins) wins.add(w);
        obj.add("wins", wins);

        JsonArray winRates = new JsonArray();
        for (double wr : r.winRates) winRates.add(Math.round(wr * 1000.0) / 1000.0);
        obj.add("winRates", winRates);

        return obj;
    }

    // -------------------------------------------------------------------------
    // In-progress match tracking
    // -------------------------------------------------------------------------

    private static class MatchProgress {
        final String matchId;
        final MatchConfig config;
        final AtomicInteger gamesCompleted = new AtomicInteger(0);
        volatile boolean completed;
        volatile String error;
        volatile String resultId;

        MatchProgress(String matchId, MatchConfig config) {
            this.matchId = matchId;
            this.config = config;
        }
    }
}
