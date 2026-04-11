package server;

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
 * Reads and writes saved Player-vs-AI game records to {@code data/pvai-games.json}.
 *
 * <p>Single flat file (one JSON array). Games are expected to be few (tens to hundreds)
 * so split storage is not necessary.
 */
public final class PvAiGameStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<PvAiGameRecord>>() {}.getType();

    private final Path storePath;

    public PvAiGameStore() {
        this(Path.of("data"));
    }

    public PvAiGameStore(Path dataDir) {
        this.storePath = dataDir.resolve("pvai-games.json");
    }

    /** Appends a saved game record to the store. */
    public synchronized void save(PvAiGameRecord record) {
        List<PvAiGameRecord> all = loadAll();
        all.add(record);
        write(all);
    }

    /** Returns a single game record by id, or null if not found. */
    public synchronized PvAiGameRecord loadById(String id) {
        for (PvAiGameRecord r : loadAll()) {
            if (id.equals(r.id)) return r;
        }
        return null;
    }

    /** Returns all saved game records (with full game logs). */
    public synchronized List<PvAiGameRecord> loadAll() {
        if (!Files.exists(storePath)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            List<PvAiGameRecord> list = GSON.fromJson(reader, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[PvAiGameStore] Failed to read " + storePath + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void write(List<PvAiGameRecord> records) {
        try {
            Files.createDirectories(storePath.getParent());
            try (Writer writer = Files.newBufferedWriter(storePath, StandardCharsets.UTF_8)) {
                GSON.toJson(records, LIST_TYPE, writer);
            }
        } catch (IOException e) {
            System.err.println("[PvAiGameStore] Failed to write " + storePath + ": " + e.getMessage());
        }
    }
}
