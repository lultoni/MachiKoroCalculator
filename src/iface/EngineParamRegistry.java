package iface;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and provides access to engine parameter schemas from {@code engine-params.json}.
 *
 * <p>This is the single source of truth for all engine parameter definitions (min/max/default/
 * type/description per engine class). Both the frontend (via {@code GET /api/engine-params})
 * and the sweep optimizer ({@link h2h.SweepMain}) consume this data.
 *
 * <p>The registry is loaded once from the classpath and cached for the JVM lifetime.
 */
public final class EngineParamRegistry {

    private static final String RESOURCE_PATH = "resources/jsons/engine-params.json";

    /**
     * A single parameter definition for an engine class.
     *
     * @param key           parameter name matching {@link engine.EngineConfig#extra} keys
     * @param description   human-readable description for UI display
     * @param type          "number", "select", or "boolean"
     * @param min           minimum bound (number type only, may be null)
     * @param max           maximum bound (number type only, may be null)
     * @param step          UI step increment (number type only, may be null)
     * @param defaultValue  string default value as engines parse it
     * @param options       valid choices (select type only, may be null)
     * @param category      UI grouping label (may be null)
     * @param internal      if true, excluded from public API responses
     * @param sweepLow      TPE sweep lower bound (may be null; only on Creator params)
     * @param sweepHigh     TPE sweep upper bound (may be null; only on Creator params)
     * @param sweepDefault  TPE sweep starting point (may be null; only on Creator params)
     */
    public record ParamEntry(
            String key, String description, String type,
            Double min, Double max, Double step,
            String defaultValue, List<String> options, String category,
            boolean internal,
            Double sweepLow, Double sweepHigh, Double sweepDefault
    ) {}

    private static List<ParamEntry> standard;
    private static Map<String, List<ParamEntry>> engines;

    private EngineParamRegistry() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns standard params shared by all engines (iterations, timeBudgetMs, riskToleranceWeight). */
    public static List<ParamEntry> getStandard() {
        ensureLoaded();
        return standard;
    }

    /** Returns engine-specific params for the given engine class (empty list if unknown). */
    public static List<ParamEntry> getForClass(String engineClass) {
        ensureLoaded();
        return engines.getOrDefault(engineClass, List.of());
    }

    /** Returns all known engine class IDs in JSON declaration order. */
    public static List<String> getEngineClassIds() {
        ensureLoaded();
        return List.copyOf(engines.keySet());
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static synchronized void ensureLoaded() {
        if (standard != null) return;
        try {
            load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load engine param schema from " + RESOURCE_PATH, e);
        }
    }

    private static void load() throws IOException {
        InputStream is = EngineParamRegistry.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH);
        if (is == null) {
            throw new IOException("Engine param schema not found on classpath: " + RESOURCE_PATH);
        }

        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            // Parse standard params
            List<ParamEntry> std = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("standard")) {
                std.add(parseParam(el.getAsJsonObject()));
            }
            standard = Collections.unmodifiableList(std);

            // Parse per-engine params (LinkedHashMap preserves insertion order)
            Map<String, List<ParamEntry>> eng = new LinkedHashMap<>();
            JsonObject enginesObj = root.getAsJsonObject("engines");
            for (Map.Entry<String, JsonElement> entry : enginesObj.entrySet()) {
                List<ParamEntry> params = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    params.add(parseParam(el.getAsJsonObject()));
                }
                eng.put(entry.getKey(), Collections.unmodifiableList(params));
            }
            engines = Collections.unmodifiableMap(eng);
        }
    }

    private static ParamEntry parseParam(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String description = obj.has("description") ? obj.get("description").getAsString() : "";
        String type = obj.get("type").getAsString();
        Double min = obj.has("min") ? obj.get("min").getAsDouble() : null;
        Double max = obj.has("max") ? obj.get("max").getAsDouble() : null;
        Double step = obj.has("step") ? obj.get("step").getAsDouble() : null;
        String defaultValue = obj.has("default") ? obj.get("default").getAsString() : null;
        String category = obj.has("category") ? obj.get("category").getAsString() : null;
        boolean internal = obj.has("internal") && obj.get("internal").getAsBoolean();

        List<String> options = null;
        if (obj.has("options")) {
            options = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("options")) {
                options.add(el.getAsString());
            }
            options = Collections.unmodifiableList(options);
        }

        Double sweepLow = obj.has("sweepLow") ? obj.get("sweepLow").getAsDouble() : null;
        Double sweepHigh = obj.has("sweepHigh") ? obj.get("sweepHigh").getAsDouble() : null;
        Double sweepDefault = obj.has("sweepDefault") ? obj.get("sweepDefault").getAsDouble() : null;

        return new ParamEntry(key, description, type, min, max, step,
                defaultValue, options, category, internal,
                sweepLow, sweepHigh, sweepDefault);
    }
}
