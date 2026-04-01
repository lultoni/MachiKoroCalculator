package iface;

import engine.EngineConfig;

import java.util.Map;

/**
 * A single entry in the engine registry JSON ({@code engines.json}).
 *
 * <p>Each entry combines an engine class identifier with a concrete configuration,
 * giving the combination a stable ID that can be referenced in UI dropdowns,
 * head-to-head tests, and autosave metadata.
 *
 * @param id          stable machine-readable identifier (e.g. {@code "mcts-v1-fast"})
 * @param engineClass the engine class identifier that must match {@link engine.SimulationEngine#id()}
 * @param description human-readable description shown in the settings UI
 * @param isDefault   true if this entry is the default for normal play
 * @param tier        performance tier: "fast", "balanced", or "deep"
 * @param config      parsed {@link EngineConfig} built from the JSON {@code config} object
 */
public record EngineRegistryEntry(
        String id,
        String engineClass,
        String description,
        boolean isDefault,
        String tier,
        EngineConfig config
) {
    /**
     * Builds an {@link EngineConfig} from the raw key-value map parsed from JSON.
     *
     * <p>The {@code iterations} and {@code timeBudgetMs} keys are treated as integer fields
     * on {@link EngineConfig}; all other keys are passed through as {@link EngineConfig#extra}.
     */
    static EngineConfig buildConfig(Map<String, String> raw) {
        int iterations   = parseIntOr(raw, "iterations",   0);
        int timeBudgetMs = parseIntOr(raw, "timeBudgetMs", 0);
        double riskWeight = parseDoubleOr(raw, "riskToleranceWeight", 0.0);

        Map<String, String> extra = new java.util.HashMap<>(raw);
        extra.remove("iterations");
        extra.remove("timeBudgetMs");
        extra.remove("riskToleranceWeight");

        return new EngineConfig(iterations, timeBudgetMs, riskWeight,
                extra.isEmpty() ? null : extra);
    }

    private static int parseIntOr(Map<String, String> m, String key, int fallback) {
        String v = m.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDoubleOr(Map<String, String> m, String key, double fallback) {
        String v = m.get(key);
        if (v == null) return fallback;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return fallback; }
    }
}
