package iface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
 * <p>Built-in entries are loaded from the classpath resource {@code resources/jsons/engines.json}.
 * Custom entries (created via the Engine Builder UI) are loaded from {@code data/custom-engines.json}
 * and merged after built-in entries.
 *
 * <p>The registry is loaded once on first access and cached for the lifetime of the JVM.
 * Call {@link #reload()} to force a re-read (e.g. after saving a custom engine).
 */
public final class EngineRegistry {

    private static final String REGISTRY_PATH = "resources/jsons/engines.json";
    private static final Path CUSTOM_PATH = Paths.get("data", "custom-engines.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static List<EngineRegistryEntry> entries = null;

    private EngineRegistry() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns all registered engine entries (built-in + custom), in registry order.
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
     * Returns all entries matching the given performance tier ("fast", "balanced", "deep").
     */
    public static List<EngineRegistryEntry> getByTier(String tier) {
        ensureLoaded();
        return entries.stream().filter(e -> tier.equals(e.tier())).toList();
    }

    /**
     * Forces a reload of the registry from disk. Useful in tests or after saving custom engines.
     */
    public static void reload() {
        entries = null;
        ensureLoaded();
    }

    // -------------------------------------------------------------------------
    // Custom engine persistence
    // -------------------------------------------------------------------------

    /**
     * Saves or updates a custom engine entry to {@code data/custom-engines.json}.
     *
     * <p>If an entry with the same ID already exists in the custom file, it is replaced.
     * If the ID collides with a built-in entry, an {@link IllegalArgumentException} is thrown.
     *
     * @param entry the custom engine entry to save (must have {@code custom=true})
     * @throws IllegalArgumentException if the ID collides with a built-in entry
     */
    public static synchronized void saveCustom(EngineRegistryEntry entry) {
        ensureLoaded();
        // Check collision with built-in entries
        for (EngineRegistryEntry e : entries) {
            if (e.id().equals(entry.id()) && !e.custom()) {
                throw new IllegalArgumentException(
                        "ID '" + entry.id() + "' conflicts with a built-in engine entry");
            }
        }

        List<JsonObject> customEntries = loadCustomRaw();
        boolean replaced = false;
        for (int i = 0; i < customEntries.size(); i++) {
            if (customEntries.get(i).get("id").getAsString().equals(entry.id())) {
                customEntries.set(i, entryToJson(entry));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            customEntries.add(entryToJson(entry));
        }
        writeCustom(customEntries);
        reload();
    }

    /**
     * Deletes a custom engine entry by ID.
     *
     * @return true if the entry was found and deleted, false if not found or if it's built-in
     */
    public static synchronized boolean deleteCustom(String id) {
        List<JsonObject> customEntries = loadCustomRaw();
        boolean removed = customEntries.removeIf(obj -> obj.get("id").getAsString().equals(id));
        if (removed) {
            writeCustom(customEntries);
            reload();
        }
        return removed;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static synchronized void ensureLoaded() {
        if (entries != null) return;
        try {
            List<EngineRegistryEntry> all = new ArrayList<>(loadBuiltIn());
            all.addAll(loadCustom());
            entries = Collections.unmodifiableList(all);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load engine registry from " + REGISTRY_PATH, e);
        }
    }

    private static List<EngineRegistryEntry> loadBuiltIn() throws IOException {
        InputStream is = EngineRegistry.class.getClassLoader()
                .getResourceAsStream(REGISTRY_PATH);
        if (is == null) {
            throw new IOException("Engine registry not found on classpath: " + REGISTRY_PATH);
        }

        List<EngineRegistryEntry> result = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement el : arr) {
                result.add(parseEntry(el.getAsJsonObject(), false));
            }
        }
        return result;
    }

    private static List<EngineRegistryEntry> loadCustom() {
        if (!Files.exists(CUSTOM_PATH)) return new ArrayList<>();
        try {
            String json = Files.readString(CUSTOM_PATH);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<EngineRegistryEntry> result = new ArrayList<>();
            for (JsonElement el : arr) {
                result.add(parseEntry(el.getAsJsonObject(), true));
            }
            return result;
        } catch (Exception e) {
            System.err.println("[EngineRegistry] Failed to load custom engines: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Loads the raw JSON objects from custom-engines.json (for save/delete operations). */
    private static List<JsonObject> loadCustomRaw() {
        if (!Files.exists(CUSTOM_PATH)) return new ArrayList<>();
        try {
            String json = Files.readString(CUSTOM_PATH);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<JsonObject> result = new ArrayList<>();
            for (JsonElement el : arr) {
                result.add(el.getAsJsonObject());
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void writeCustom(List<JsonObject> customEntries) {
        try {
            Files.createDirectories(CUSTOM_PATH.getParent());
            JsonArray arr = new JsonArray();
            customEntries.forEach(arr::add);
            Files.writeString(CUSTOM_PATH, GSON.toJson(arr));
        } catch (IOException e) {
            System.err.println("[EngineRegistry] Failed to save custom engines: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Parsing / serialization
    // -------------------------------------------------------------------------

    private static EngineRegistryEntry parseEntry(JsonObject obj, boolean custom) {
        String id          = obj.get("id").getAsString();
        String engineClass = obj.get("engineClass").getAsString();
        String description = obj.get("description").getAsString();
        boolean isDefault  = obj.has("default") && obj.get("default").getAsBoolean();
        String tier        = obj.has("tier") ? obj.get("tier").getAsString() : "fast";

        java.util.Map<String, String> rawConfig = new java.util.HashMap<>();
        if (obj.has("config")) {
            for (java.util.Map.Entry<String, JsonElement> entry
                    : obj.getAsJsonObject("config").entrySet()) {
                rawConfig.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        return new EngineRegistryEntry(id, engineClass, description, isDefault, tier,
                EngineRegistryEntry.buildConfig(rawConfig), custom);
    }

    /** Converts an entry back to JSON for writing to custom-engines.json. */
    private static JsonObject entryToJson(EngineRegistryEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.id());
        obj.addProperty("engineClass", entry.engineClass());
        obj.addProperty("description", entry.description());
        obj.addProperty("tier", entry.tier());
        if (entry.isDefault()) obj.addProperty("default", true);

        JsonObject config = new JsonObject();
        config.addProperty("iterations", String.valueOf(entry.config().iterations));
        if (entry.config().timeBudgetMs > 0) {
            config.addProperty("timeBudgetMs", String.valueOf(entry.config().timeBudgetMs));
        }
        if (entry.config().riskToleranceWeight > 0) {
            config.addProperty("riskToleranceWeight", String.valueOf(entry.config().riskToleranceWeight));
        }
        if (entry.config().extra != null) {
            entry.config().extra.forEach(config::addProperty);
        }
        obj.add("config", config);
        return obj;
    }
}
