package iface;

import com.google.gson.Gson;
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
import java.util.List;
import java.util.Optional;

/**
 * Loads and provides access to the engine registry defined in {@code engines.json}.
 *
 * <p>The registry is a flat JSON array of engine+config combinations. Each entry has a
 * stable {@code id} that may be persisted in save files or head-to-head results.
 *
 * <p>The registry is loaded once on first access and cached for the lifetime of the JVM.
 * Call {@link #reload()} to force a re-read (e.g. in tests).
 *
 * <h2>engines.json location</h2>
 * Loaded as a classpath resource: {@code resources/jsons/engines.json}.
 */
public final class EngineRegistry {

    private static final String REGISTRY_PATH = "resources/jsons/engines.json";

    private static List<EngineRegistryEntry> entries = null;

    private EngineRegistry() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns all registered engine entries, in the order they appear in {@code engines.json}.
     */
    public static List<EngineRegistryEntry> getAll() {
        ensureLoaded();
        return entries;
    }

    /**
     * Returns the entry marked {@code "default": true} in the registry, or the first entry
     * if none is marked as default.
     *
     * @throws IllegalStateException if the registry is empty
     */
    public static EngineRegistryEntry getDefault() {
        ensureLoaded();
        if (entries.isEmpty()) throw new IllegalStateException("Engine registry is empty");
        return entries.stream()
                .filter(EngineRegistryEntry::isDefault)
                .findFirst()
                .orElse(entries.get(0));
    }

    /**
     * Returns the entry with the given {@code id}, or empty if not found.
     */
    public static Optional<EngineRegistryEntry> findById(String id) {
        ensureLoaded();
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * Forces a reload of the registry from disk. Useful in tests or when the file changes.
     */
    public static void reload() {
        entries = null;
        ensureLoaded();
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static synchronized void ensureLoaded() {
        if (entries != null) return;
        try {
            entries = Collections.unmodifiableList(load());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load engine registry from " + REGISTRY_PATH, e);
        }
    }

    private static List<EngineRegistryEntry> load() throws IOException {
        InputStream is = EngineRegistry.class.getClassLoader()
                .getResourceAsStream(REGISTRY_PATH);
        if (is == null) {
            throw new IOException("Engine registry not found on classpath: " + REGISTRY_PATH);
        }

        List<EngineRegistryEntry> result = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement el : arr) {
                result.add(parseEntry(el.getAsJsonObject()));
            }
        }
        return result;
    }

    private static EngineRegistryEntry parseEntry(JsonObject obj) {
        String id          = obj.get("id").getAsString();
        String engineClass = obj.get("engineClass").getAsString();
        String description = obj.get("description").getAsString();
        boolean isDefault  = obj.has("default") && obj.get("default").getAsBoolean();

        java.util.Map<String, String> rawConfig = new java.util.HashMap<>();
        if (obj.has("config")) {
            for (java.util.Map.Entry<String, JsonElement> entry
                    : obj.getAsJsonObject("config").entrySet()) {
                rawConfig.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        return new EngineRegistryEntry(id, engineClass, description, isDefault,
                EngineRegistryEntry.buildConfig(rawConfig));
    }
}
