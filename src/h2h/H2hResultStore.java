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
 * Reads and writes H2H match results with split storage:
 * <ul>
 *   <li>{@code data/h2h-summaries.json} — compact array of MatchResult without gameLogs (~14 KB)</li>
 *   <li>{@code data/h2h-gamelogs/{matchId}.json} — full game logs per match (on-demand)</li>
 * </ul>
 *
 * <p>On first startup, automatically migrates from the legacy monolithic
 * {@code data/h2h-results.json} (can be 45+ MB) into the split format.
 *
 * <p>Thread-safe via synchronized methods.
 */
public final class H2hResultStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SUMMARY_LIST_TYPE = new TypeToken<List<MatchResult>>() {}.getType();
    private static final Type GAMELOG_LIST_TYPE = new TypeToken<List<GameLog>>() {}.getType();

    private final Path summaryPath;
    private final Path gamelogDir;
    /** Monotonically increasing counter; bumped on each save. */
    private volatile int version = 0;

    public H2hResultStore() {
        this(Path.of("data"));
    }

    public H2hResultStore(Path dataDir) {
        this.summaryPath = dataDir.resolve("h2h-summaries.json");
        this.gamelogDir = dataDir.resolve("h2h-gamelogs");
        migrateIfNeeded(dataDir);
    }

    /**
     * Appends a match result to the store. Saves summary (without gameLogs)
     * to the summary file and gameLogs to a separate file.
     */
    public synchronized void save(MatchResult result) {
        // Save game logs to separate file
        saveGameLogs(result.id, result.gameLogs);

        // Save summary (gameLogs will be null in JSON because we use transient-like approach)
        List<MatchResult> all = loadAll();
        all.add(result);
        writeSummaries(all);
        version++;
    }

    /** Returns current version counter (bumped on each save). */
    public int version() { return version; }

    /**
     * Loads all stored match results (summaries only, no gameLogs).
     * This is fast — reads only the compact summary file.
     */
    public synchronized List<MatchResult> loadAll() {
        if (!Files.exists(summaryPath)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(summaryPath, StandardCharsets.UTF_8)) {
            List<MatchResult> list = GSON.fromJson(reader, SUMMARY_LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[H2hResultStore] Failed to read " + summaryPath + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Finds a match result by ID (summary + gameLogs loaded from separate file).
     */
    public synchronized MatchResult findById(String matchId) {
        for (MatchResult r : loadAll()) {
            if (r.id.equals(matchId)) {
                // Reattach game logs from separate file
                List<GameLog> logs = loadGameLogs(matchId);
                if (logs != null) r.gameLogs = logs;
                return r;
            }
        }
        return null;
    }

    /**
     * Loads a single game log by match ID and game index.
     */
    public synchronized GameLog findGameLog(String matchId, int gameIndex) {
        List<GameLog> logs = loadGameLogs(matchId);
        if (logs != null && gameIndex >= 0 && gameIndex < logs.size()) {
            return logs.get(gameIndex);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void writeSummaries(List<MatchResult> results) {
        try {
            Files.createDirectories(summaryPath.getParent());
            // Write without gameLogs by temporarily nulling them
            List<List<GameLog>> saved = new ArrayList<>();
            for (MatchResult r : results) {
                saved.add(r.gameLogs);
                r.gameLogs = null;
            }
            try (Writer writer = Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8)) {
                GSON.toJson(results, SUMMARY_LIST_TYPE, writer);
            }
            // Restore
            for (int i = 0; i < results.size(); i++) {
                results.get(i).gameLogs = saved.get(i);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + summaryPath, e);
        }
    }

    private void saveGameLogs(String matchId, List<GameLog> logs) {
        if (logs == null || logs.isEmpty()) return;
        Path logFile = gamelogDir.resolve(matchId + ".json");
        try {
            Files.createDirectories(gamelogDir);
            try (Writer writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                GSON.toJson(logs, GAMELOG_LIST_TYPE, writer);
            }
        } catch (IOException e) {
            System.err.println("[H2hResultStore] Failed to write gamelogs for " + matchId + ": " + e.getMessage());
        }
    }

    private List<GameLog> loadGameLogs(String matchId) {
        Path logFile = gamelogDir.resolve(matchId + ".json");
        if (!Files.exists(logFile)) return null;
        try (Reader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, GAMELOG_LIST_TYPE);
        } catch (IOException e) {
            System.err.println("[H2hResultStore] Failed to read gamelogs for " + matchId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * One-time migration from legacy monolithic {@code h2h-results.json}
     * to the split summary + gamelog format.
     */
    private void migrateIfNeeded(Path dataDir) {
        Path legacyPath = dataDir.resolve("h2h-results.json");
        if (!Files.exists(legacyPath) || Files.exists(summaryPath)) return;

        System.out.println("[H2hResultStore] Migrating legacy h2h-results.json to split format...");
        try (Reader reader = Files.newBufferedReader(legacyPath, StandardCharsets.UTF_8)) {
            List<MatchResult> all = GSON.fromJson(reader, SUMMARY_LIST_TYPE);
            if (all == null || all.isEmpty()) return;

            // Save each match's gamelogs to separate files
            for (MatchResult r : all) {
                if (r.gameLogs != null && !r.gameLogs.isEmpty()) {
                    saveGameLogs(r.id, r.gameLogs);
                }
            }

            // Write summaries (without gameLogs)
            writeSummaries(all);

            // Rename legacy file to mark migration complete
            Files.move(legacyPath, dataDir.resolve("h2h-results.json.migrated"));
            System.out.println("[H2hResultStore] Migration complete. " + all.size() + " matches split. "
                    + "Legacy file renamed to h2h-results.json.migrated");
        } catch (IOException e) {
            System.err.println("[H2hResultStore] Migration failed: " + e.getMessage());
        }
    }
}
