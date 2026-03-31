package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameState;
import core.RollResolver;
import engine.EngineResult;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.io.IOException;
import java.util.Optional;

/**
 * POST /api/evaluate — runs an engine evaluation and returns ranked purchase options.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "state":       { ...GameState JSON... },
 *   "playerIndex": 0,
 *   "engineId":    "mcts-v1-balanced"   // optional; defaults to registry default
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "engineId":       "mcts-v1-balanced",
 *   "iterationsUsed": 5000,
 *   "computeTimeMs":  1234,
 *   "confidence":     0.72,
 *   "rankedOptions":  [ ... ],
 *   "perRollDeltas":  { "1": [3, -1], "2": [0, 2], ... },
 *   "metricRanges":   { "winRate": { "min": "0.38", "max": "0.62" }, ... }
 * }
 * </pre>
 *
 * <p>{@code perRollDeltas} contains per-player coin deltas for every possible dice total
 * (1–6 for 1d6, 2–12 for 2d6), computed analytically from the pre-roll state. The frontend
 * uses this for instant dice-switching without additional API calls.
 *
 * <p>{@code metricRanges} contains the min/max of each numeric metric across all ranked
 * options, so the frontend can color-code values on a gradient.
 */
final class EvaluateHandler implements HttpHandler {

    private final EngineOrchestrator orchestrator;

    EvaluateHandler(EngineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
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
            GameState state = GameStateSerializer.fromJson(body.getAsJsonObject("state"));
            int playerIndex = body.get("playerIndex").getAsInt();

            String engineId = body.has("engineId") ? body.get("engineId").getAsString() : null;
            EngineRegistryEntry entry;
            if (engineId != null) {
                Optional<EngineRegistryEntry> opt = EngineRegistry.findById(engineId);
                if (opt.isEmpty()) {
                    ApiUtils.sendError(exchange, 400, "Unknown engineId: " + engineId);
                    return;
                }
                entry = opt.get();
            } else {
                entry = EngineRegistry.getDefault();
            }

            if (!orchestrator.hasEngine(entry.engineClass())) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "Engine '" + entry.engineClass()
                        + "' is not yet implemented. Phase 2 (MCTS) will add it.");
                err.addProperty("engineId", entry.id());
                ApiUtils.sendJson(exchange, 503, err);
                return;
            }

            if (playerIndex < 0 || playerIndex >= state.getPlayers().length) {
                ApiUtils.sendError(exchange, 400, "playerIndex out of range");
                return;
            }

            // Optional: pre-roll state for computing per-roll coin deltas
            // If "preRollState" is provided, compute coin deltas for all possible rolls
            GameState preRollState = null;
            if (body.has("preRollState") && !body.get("preRollState").isJsonNull()) {
                preRollState = GameStateSerializer.fromJson(body.getAsJsonObject("preRollState"));
            }

            EngineResult result = orchestrator.evaluate(state, playerIndex, entry);

            JsonObject response = serializeResult(entry.id(), result);

            // Add per-roll deltas if pre-roll state was provided
            if (preRollState != null) {
                response.add("perRollDeltas", computePerRollDeltas(preRollState, playerIndex));
            }

            // Add metric ranges across all ranked options
            response.add("metricRanges", computeMetricRanges(result));

            ApiUtils.sendJson(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            ApiUtils.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static JsonObject serializeResult(String engineId, EngineResult result) {
        JsonObject obj = new JsonObject();
        obj.addProperty("engineId", engineId);
        obj.addProperty("iterationsUsed", result.iterationsUsed);
        obj.addProperty("computeTimeMs", result.computeTimeMs);
        obj.addProperty("confidence", result.confidence);
        if (result.debugInfo != null) obj.addProperty("debugInfo", result.debugInfo);

        JsonArray options = new JsonArray();
        for (EngineResult.Option opt : result.rankedOptions) {
            JsonObject o = new JsonObject();
            o.addProperty("projectId", opt.project.getId());
            o.addProperty("score", opt.score);
            o.addProperty("affordable", opt.affordable);

            JsonArray factors = new JsonArray();
            for (String f : opt.explanationFactors) factors.add(f);
            o.add("explanationFactors", factors);

            if (opt.metrics != null) {
                JsonObject metrics = new JsonObject();
                opt.metrics.forEach(metrics::addProperty);
                o.add("metrics", metrics);
            }
            options.add(o);
        }
        obj.add("rankedOptions", options);
        return obj;
    }

    // -------------------------------------------------------------------------
    // Per-roll coin deltas
    // -------------------------------------------------------------------------

    /**
     * Computes coin deltas for every possible roll total (1–6 and 2–12).
     * Returns a JSON object keyed by roll total string, each value is an array of per-player deltas.
     */
    private static JsonObject computePerRollDeltas(GameState preRollState, int playerIndex) {
        JsonObject perRoll = new JsonObject();
        // 1d6 rolls: 1–6
        for (int roll = 1; roll <= 6; roll++) {
            int[] deltas = RollResolver.computeAllDeltasForRoll(preRollState, playerIndex, roll);
            perRoll.add(String.valueOf(roll), intArrayToJson(deltas));
        }
        // 2d6 rolls: 2–12
        for (int roll = 2; roll <= 12; roll++) {
            String key = "2d6_" + roll;
            int[] deltas = RollResolver.computeAllDeltasForRoll(preRollState, playerIndex, roll);
            perRoll.add(key, intArrayToJson(deltas));
        }
        return perRoll;
    }

    private static JsonArray intArrayToJson(int[] arr) {
        JsonArray ja = new JsonArray();
        for (int v : arr) ja.add(v);
        return ja;
    }

    // -------------------------------------------------------------------------
    // Metric ranges
    // -------------------------------------------------------------------------

    /**
     * Computes min/max ranges for each numeric metric key across all ranked options.
     * The frontend uses these to position values on a color gradient.
     */
    private static JsonObject computeMetricRanges(EngineResult result) {
        JsonObject ranges = new JsonObject();

        // Collect all numeric metric keys from the first option that has metrics
        java.util.Map<String, Double> mins = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> maxs = new java.util.LinkedHashMap<>();

        for (EngineResult.Option opt : result.rankedOptions) {
            if (opt.metrics == null) continue;
            for (var entry : opt.metrics.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                // Skip non-numeric values
                try {
                    double d = Double.parseDouble(val);
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    mins.merge(key, d, Math::min);
                    maxs.merge(key, d, Math::max);
                } catch (NumberFormatException ignored) {}
            }
        }

        for (String key : mins.keySet()) {
            JsonObject range = new JsonObject();
            range.addProperty("min", String.format("%.4f", mins.get(key)));
            range.addProperty("max", String.format("%.4f", maxs.get(key)));
            ranges.add(key, range);
        }

        return ranges;
    }
}
