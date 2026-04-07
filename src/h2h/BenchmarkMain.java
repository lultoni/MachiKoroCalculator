package h2h;

import engine.creator.CreatorEngine;
import engine.expectimax.ExpectimaxEngine;
import engine.flat.FlatMcEngine;
import engine.heuristic.HeuristicEvEngine;
import engine.mcts.MctsAdaptiveEngine;
import engine.mcts.MctsBoltzmannRolloutEngine;
import engine.mcts.MctsDepthLimitedEngine;
import engine.mcts.MctsGreedyRolloutEngine;
import engine.mcts.MctsGreedyTreeEngine;
import engine.mcts.MctsV1Engine;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CLI tool for benchmarking unrated engines against the current champion.
 *
 * <p>Auto-discovers engines without H2H match history, identifies the highest-rated
 * engine (the "champion"), and runs each unrated engine against it. Results are saved
 * after each match via {@link H2hResultStore}, integrating with the Glicko-2 rating system.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp "out:src:gson-2.11.0.jar" h2h.BenchmarkMain [options]
 *   --games &lt;n&gt;       Games per match (default: 50)
 *   --tier &lt;name&gt;     Only benchmark unrated engines in this tier (optional)
 *   --champion &lt;id&gt;   Override the champion engine (default: auto-detect)
 *   --estimate        Print plan and exit
 *   --help            Show usage
 * </pre>
 */
public final class BenchmarkMain {

    private BenchmarkMain() {}

    public static void main(String[] args) {
        int games = 50;
        int maxTurns = 200;
        String tierFilter = null;
        String championOverride = null;
        boolean estimateOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--maxTurns" -> maxTurns = Integer.parseInt(args[++i]);
                case "--tier" -> tierFilter = args[++i];
                case "--champion" -> championOverride = args[++i];
                case "--estimate" -> estimateOnly = true;
                case "--help" -> { printUsage(); return; }
                default -> System.err.println("Unknown arg: " + args[i]);
            }
        }

        // Register all engines
        EngineOrchestrator orchestrator = new EngineOrchestrator();
        orchestrator.register(new MctsV1Engine());
        orchestrator.register(new MctsGreedyRolloutEngine());
        orchestrator.register(new MctsBoltzmannRolloutEngine());
        orchestrator.register(new MctsGreedyTreeEngine());
        orchestrator.register(new MctsDepthLimitedEngine());
        orchestrator.register(new MctsAdaptiveEngine());
        orchestrator.register(new FlatMcEngine());
        orchestrator.register(new HeuristicEvEngine());
        orchestrator.register(new ExpectimaxEngine());
        orchestrator.register(new CreatorEngine());

        // Load existing H2H results and compute ratings
        H2hResultStore store = new H2hResultStore();
        List<MatchResult> existing = store.loadAll();
        Map<String, Glicko2Rating> ratings = RatingCalculator.computeRatings(existing);

        // Find the champion (highest rated engine)
        String champion;
        if (championOverride != null) {
            if (EngineRegistry.findById(championOverride).isEmpty()) {
                System.err.println("Unknown champion engine ID: " + championOverride);
                return;
            }
            champion = championOverride;
        } else {
            champion = findChampion(ratings);
            if (champion == null) {
                System.err.println("No rated engines found. Run some H2H matches first.");
                return;
            }
        }

        Glicko2Rating champRating = ratings.getOrDefault(champion, Glicko2Rating.initial());
        System.out.printf("[BENCHMARK] Champion: %s (rating %.0f ± %.0f, %d matches)%n",
                champion, champRating.rating, champRating.rd * 2, champRating.matchCount);

        // Find engines with zero match history
        Set<String> ratedEngines = collectRatedEngines(existing);
        final String tierFilterFinal = tierFilter;
        List<EngineRegistryEntry> unrated = EngineRegistry.getAll().stream()
                .filter(e -> !ratedEngines.contains(e.id()))
                .filter(e -> tierFilterFinal == null || tierFilterFinal.equals(e.tier()))
                .toList();

        if (unrated.isEmpty()) {
            System.out.println("[BENCHMARK] All engines already have match history!");
            if (tierFilter != null) {
                System.out.println("  (filtered by tier: " + tierFilter + ")");
            }
            return;
        }

        // Print plan
        System.out.printf("[BENCHMARK] %d unrated engine(s) to benchmark against %s%n",
                unrated.size(), champion);
        System.out.println();

        int cores = Runtime.getRuntime().availableProcessors();
        long totalEstimateMs = 0;

        for (int i = 0; i < unrated.size(); i++) {
            EngineRegistryEntry e = unrated.get(i);
            long estimateMs = estimateMsPerMatch(e, champion, games, cores);
            totalEstimateMs += estimateMs;
            System.out.printf("  %2d. %-40s [%s] ~%s%n",
                    i + 1, e.id(), e.tier(), formatDuration(estimateMs));
        }
        System.out.println();
        System.out.printf("[BENCHMARK] Total estimated time: %s (%d games × %d matches)%n",
                formatDuration(totalEstimateMs), games, unrated.size());
        System.out.println();

        if (estimateOnly) return;

        // Run benchmarks
        MatchRunner runner = new MatchRunner(orchestrator);
        int completed = 0;

        for (EngineRegistryEntry entry : unrated) {
            completed++;
            System.out.printf("[%d/%d] %s vs %s (%d games)...%n",
                    completed, unrated.size(), entry.id(), champion, games);

            // Use iterationsPerEval=0 so each engine uses its registry-default config
            MatchConfig config = new MatchConfig(
                    new String[]{entry.id(), champion},
                    games, maxTurns, 0, true);

            final int totalGames = games;
            MatchResult result = runner.runMatch(config, (gameIdx, log) -> {
                if ((gameIdx + 1) % 10 == 0 || gameIdx + 1 == totalGames) {
                    System.out.printf("    Game %d/%d done%n", gameIdx + 1, totalGames);
                }
            });

            // Print result
            System.out.printf("  Result: %s %.0f%% - %.0f%% %s (%.1fs, avg %.0f turns, avg eval %.1fms)%n",
                    result.config.engineIds()[0], result.winRates[0] * 100,
                    result.winRates[1] * 100, result.config.engineIds()[1],
                    result.totalTimeMs / 1000.0,
                    result.avgGameLength, result.avgEvalTimeMs);

            // Save immediately after each match
            store.save(result);
            System.out.printf("  Saved (match id: %s)%n", result.id);
            System.out.println();
        }

        // Print final summary with updated ratings
        List<MatchResult> updatedResults = store.loadAll();
        Map<String, Glicko2Rating> updatedRatings = RatingCalculator.computeRatings(updatedResults);

        System.out.println("=== BENCHMARK COMPLETE ===");
        System.out.println();
        System.out.printf("%-40s %8s %8s %8s%n", "Engine", "Rating", "±2σ", "Matches");
        System.out.println("-".repeat(70));

        // Show all newly benchmarked engines + champion, sorted by rating
        Set<String> relevantIds = new HashSet<>();
        relevantIds.add(champion);
        unrated.forEach(e -> relevantIds.add(e.id()));

        relevantIds.stream()
                .sorted((a, b) -> {
                    double ra = updatedRatings.getOrDefault(a, Glicko2Rating.initial()).rating;
                    double rb = updatedRatings.getOrDefault(b, Glicko2Rating.initial()).rating;
                    return Double.compare(rb, ra);
                })
                .forEach(id -> {
                    Glicko2Rating r = updatedRatings.getOrDefault(id, Glicko2Rating.initial());
                    String marker = id.equals(champion) ? " ★" : "";
                    System.out.printf("%-40s %8.0f %8.0f %8d%s%n",
                            id, r.rating, r.rd * 2, r.matchCount, marker);
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the engine ID with the highest Glicko-2 rating, or null if no ratings exist.
     */
    private static String findChampion(Map<String, Glicko2Rating> ratings) {
        return ratings.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().rating))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Collects all engine IDs that have participated in at least one match.
     */
    private static Set<String> collectRatedEngines(List<MatchResult> results) {
        Set<String> ids = new HashSet<>();
        for (MatchResult r : results) {
            for (String id : r.config.engineIds()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Rough runtime estimate for a single match.
     */
    private static long estimateMsPerMatch(EngineRegistryEntry entry, String championId,
                                            int games, int cores) {
        long challengerMs = estimateMsPerGame(entry);
        EngineRegistryEntry champEntry = EngineRegistry.findById(championId).orElse(null);
        long champMs = champEntry != null ? estimateMsPerGame(champEntry) : 8000L;
        long msPerGame = Math.max(challengerMs, champMs);
        return msPerGame * games / cores;
    }

    /**
     * Estimates ms per game for an engine at its registry-default iterations.
     * Based on ~140 evaluations per game (70 turns × 2 players).
     */
    private static long estimateMsPerGame(EngineRegistryEntry entry) {
        int iterations = entry.config().iterations;
        return switch (entry.engineClass()) {
            case "heuristic-ev" -> 100L; // <1ms per eval × 140
            case "expectimax" -> {
                int depth = Integer.parseInt(
                        entry.config().getExtra("maxDepth", "1"));
                yield depth <= 1 ? 8000L : 180_000L; // d1=~60ms, d2=~1300ms per eval
            }
            case "mcts-v1-depth-limited" -> iterations * 2L; // ~1.4ms per iteration
            case "mcts-v1", "mcts-v1-greedy-tree", "mcts-v1-adaptive" ->
                    iterations * 16L; // ~0.11ms per iteration
            case "mcts-v1-greedy-rollout", "mcts-v1-boltzmann-rollout" ->
                    iterations * 24L; // ~0.17ms per iteration (post-optimization)
            case "flat-mc" -> iterations * 16L;
            default -> 8000L;
        };
    }

    private static String formatDuration(long ms) {
        return TournamentMain.formatDuration(ms);
    }

    private static void printUsage() {
        System.out.println("Usage: h2h.BenchmarkMain [options]");
        System.out.println();
        System.out.println("Benchmarks all unrated engines against the current champion.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --games <n>       Games per match (default: 50)");
        System.out.println("  --maxTurns <n>    Max turns per game (default: 200)");
        System.out.println("  --tier <name>     Only benchmark unrated engines in this tier");
        System.out.println("  --champion <id>   Override the champion engine (default: auto-detect)");
        System.out.println("  --estimate        Print plan and exit");
        System.out.println("  --help            Show this help");
        System.out.println();

        // Show current engine status
        H2hResultStore store = new H2hResultStore();
        List<MatchResult> existing = store.loadAll();
        Set<String> rated = new HashSet<>();
        for (MatchResult r : existing) {
            for (String id : r.config.engineIds()) rated.add(id);
        }

        System.out.println("Engine status:");
        for (String tier : List.of("fast", "balanced", "deep")) {
            List<EngineRegistryEntry> entries = EngineRegistry.getByTier(tier);
            long unratedCount = entries.stream().filter(e -> !rated.contains(e.id())).count();
            System.out.printf("  %s: %d engines (%d unrated)%n",
                    tier, entries.size(), unratedCount);
            for (EngineRegistryEntry e : entries) {
                String status = rated.contains(e.id()) ? "✓" : "○";
                System.out.printf("    %s %-40s%n", status, e.id());
            }
        }
    }
}
