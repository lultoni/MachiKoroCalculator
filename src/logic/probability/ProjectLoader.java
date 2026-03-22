package logic.probability;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and caches all Machi Koro project definitions from
 * {@code resources/jsons/projects.json} on the classpath.
 * <p>
 * The JSON is parsed exactly once at class-load time. All subsequent calls
 * to {@link #getProject} and {@link #getAllProjects} are O(1) / O(n) map lookups
 * with no I/O.
 */
public class ProjectLoader {

    /** Immutable cache: project id → Project, insertion order preserved. */
    private static final Map<String, Project> CACHE;

    static {
        CACHE = Collections.unmodifiableMap(buildCache());
    }

    private static Map<String, Project> buildCache() {
        InputStream stream = ProjectLoader.class
                .getClassLoader()
                .getResourceAsStream("resources/jsons/projects.json");

        if (stream == null) {
            throw new IllegalStateException(
                    "Classpath resource not found: resources/jsons/projects.json");
        }

        Map<String, Project> map = new LinkedHashMap<>();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            JsonObject root = gson.fromJson(reader, JsonObject.class);

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String id = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();

                // Inject the id field (it is the JSON key, not a field inside the object).
                obj.addProperty("id", id);
                Project project = gson.fromJson(obj, Project.class);
                map.put(id, project);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load projects from classpath", e);
        }

        return map;
    }

    /**
     * Returns the project with the given id, or empty if not found.
     *
     * @param id the project id (e.g. {@code "weizenfeld"})
     * @return the project, or {@link Optional#empty()} if the id is unknown
     */
    public static Optional<Project> getProject(String id) {
        return Optional.ofNullable(CACHE.get(id));
    }

    /**
     * Returns all 19 base-game projects in the order they appear in the JSON.
     *
     * @return new mutable list of all projects
     */
    public static ArrayList<Project> getAllProjects() {
        return new ArrayList<>(CACHE.values());
    }
}
