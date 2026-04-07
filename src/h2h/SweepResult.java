package h2h;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Stores and loads sweep trial results.
 *
 * <p>Each sweep run produces a list of trials. Results are saved to
 * {@code data/sweep-results.json} as an array of {@link SweepRun} objects.
 * New runs are appended to the file, enabling comparison across sweep sessions.
 *
 * @see SweepMain
 */
public final class SweepResult {

    private static final Path RESULTS_PATH = Paths.get("data", "sweep-results.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SweepResult() {}

    /**
     * A single evaluation trial within a sweep run.
     */
    public static final class Trial {
        public final int index;
        public final Map<String, Double> params;
        public final double winRate;
        public final int games;
        public final long timeMs;

        public Trial(int index, Map<String, Double> params, double winRate, int games, long timeMs) {
            this.index = index;
            this.params = params;
            this.winRate = winRate;
            this.games = games;
            this.timeMs = timeMs;
        }
    }

    /**
     * A complete sweep run with metadata and all trials.
     */
    public static final class SweepRun {
        public final String id;
        public final String date;
        public final String creatorEngine;
        public final String opponent;
        public final int gamesPerTrial;
        public final int totalTrials;
        public final int startupTrials;
        public final double gamma;
        public final List<Trial> trials;
        public final long totalTimeMs;

        public SweepRun(String creatorEngine, String opponent, int gamesPerTrial,
                        int totalTrials, int startupTrials, double gamma,
                        List<Trial> trials, long totalTimeMs) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.date = java.time.Instant.now().toString();
            this.creatorEngine = creatorEngine;
            this.opponent = opponent;
            this.gamesPerTrial = gamesPerTrial;
            this.totalTrials = totalTrials;
            this.startupTrials = startupTrials;
            this.gamma = gamma;
            this.trials = trials;
            this.totalTimeMs = totalTimeMs;
        }

        /** Returns the trial with the highest win rate. */
        public Trial best() {
            return trials.stream()
                    .max(Comparator.comparingDouble(t -> t.winRate))
                    .orElse(null);
        }
    }

    // =====================================================================
    // File I/O
    // =====================================================================

    /**
     * Saves a sweep run, appending to existing results file.
     */
    public static void save(SweepRun run) {
        List<SweepRun> existing = loadAll();
        existing.add(run);
        try {
            Files.createDirectories(RESULTS_PATH.getParent());
            Files.writeString(RESULTS_PATH, GSON.toJson(existing));
        } catch (IOException e) {
            System.err.println("[SWEEP] Failed to save results: " + e.getMessage());
        }
    }

    /**
     * Loads all sweep runs from the results file.
     * Returns an empty mutable list if the file doesn't exist.
     */
    public static List<SweepRun> loadAll() {
        if (!Files.exists(RESULTS_PATH)) return new ArrayList<>();
        try {
            String json = Files.readString(RESULTS_PATH);
            List<SweepRun> runs = GSON.fromJson(json,
                    new TypeToken<List<SweepRun>>() {}.getType());
            return runs != null ? new ArrayList<>(runs) : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[SWEEP] Failed to load results: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Loads all trials from all runs in the results file (for --resume).
     * Returns an empty list if no prior runs exist.
     */
    public static List<Trial> loadAllTrials() {
        List<Trial> all = new ArrayList<>();
        for (SweepRun run : loadAll()) {
            all.addAll(run.trials);
        }
        return all;
    }
}
