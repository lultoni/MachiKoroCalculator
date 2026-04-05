package h2h;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes H2H match results to {@code h2h-results.json}.
 *
 * <p>Append-only: new match results are added to the array.
 * Thread-safe via synchronized methods.
 */
public final class H2hResultStore {

    private static final String DEFAULT_PATH = "data/h2h-results.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<MatchResult>>() {}.getType();

    private final Path filePath;

    public H2hResultStore() {
        this(Path.of(DEFAULT_PATH));
    }

    public H2hResultStore(Path filePath) {
        this.filePath = filePath;
    }

    /**
     * Appends a match result to the store.
     */
    public synchronized void save(MatchResult result) {
        List<MatchResult> all = loadAll();
        all.add(result);
        writeAll(all);
    }

    /**
     * Loads all stored match results.
     */
    public synchronized List<MatchResult> loadAll() {
        if (!Files.exists(filePath)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            List<MatchResult> list = GSON.fromJson(reader, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[H2hResultStore] Failed to read " + filePath + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Finds a match result by ID.
     */
    public synchronized MatchResult findById(String matchId) {
        for (MatchResult r : loadAll()) {
            if (r.id.equals(matchId)) return r;
        }
        return null;
    }

    private void writeAll(List<MatchResult> results) {
        try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(results, LIST_TYPE, writer);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + filePath, e);
        }
    }
}
