package h2h;

import engine.*;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CLI entry point for running round-robin tournaments.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier fast --games 50
 * java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --engines mcts-v1-fast,mcts-v1-depth3 --games 20
 * java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30
 * </pre>
 */
public final class TournamentMain {

    private TournamentMain() {}

    // Approximate ms per game at the engine's default registry iterations (2-player).
    // Based on actual measured performance: eval_time × ~140 evals/game (70 turns × 2 players).
    // Key = engine class, Value = {baseline iterations, ms per game at that iteration count}
    private static final Map<String, long[]> MS_PER_GAME_BASELINE = Map.of(
            "mcts-v1",                  new long[]{500, 8000},      // ~58ms/eval × 140
            "mcts-v1-greedy-rollout",   new long[]{500, 500000},    // ~3600ms/eval × 140
            "mcts-v1-boltzmann-rollout", new long[]{500, 500000},   // ~3600ms/eval × 140
            "mcts-v1-greedy-tree",      new long[]{500, 8000},      // similar to v1
            "mcts-v1-depth-limited",    new long[]{2000, 1400},     // ~10ms/eval × 140
            "mcts-v1-adaptive",         new long[]{500, 8000}       // similar to v1
    );

    public static void main(String[] args) {
        // Parse args
        String tier = "fast";
        String enginesArg = null;
        boolean unleashed = false;
        int games = 50;
        int iterations = 0; // 0 = use registry default
        int maxTurns = 200;
        boolean noSwap = false;
        boolean estimateOnly = false;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tier" -> tier = args[++i];
                case "--engines" -> enginesArg = args[++i];
                case "--unleashed" -> unleashed = true;
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                case "--maxTurns" -> maxTurns = Integer.parseInt(args[++i]);
                case "--no-swap" -> noSwap = true;
                case "--estimate" -> estimateOnly = true;
                case "--verbose" -> verbose = true;
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

        // Select engines
        List<String> engineIds;
        if (enginesArg != null) {
            engineIds = Arrays.stream(enginesArg.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } else if (unleashed) {
            engineIds = EngineRegistry.getAll().stream()
                    .map(EngineRegistryEntry::id).toList();
        } else {
            engineIds = EngineRegistry.getByTier(tier).stream()
                    .map(EngineRegistryEntry::id).toList();
        }

        if (engineIds.size() < 2) {
            System.err.println("Need at least 2 engines for a tournament. Got: " + engineIds.size());
            return;
        }

        // Validate engine IDs
        for (String id : engineIds) {
            if (EngineRegistry.findById(id).isEmpty()) {
                System.err.println("Unknown engine ID: " + id);
                return;
            }
        }

        int totalMatchups = engineIds.size() * (engineIds.size() - 1) / 2;
        int totalGames = totalMatchups * games;
        boolean seatSwap = !noSwap;

        // Print plan
        System.out.printf("[TOURNAMENT] %d engines, %d matchups, %d games per matchup%s%n",
                engineIds.size(), totalMatchups, games,
                seatSwap ? " (seat swap at half)" : "");
        System.out.printf("[TOURNAMENT] Total games: %d%n", totalGames);

        // Runtime estimation
        long estimatedMs = estimateRuntime(engineIds, games, iterations);
        System.out.printf("[TOURNAMENT] Estimated runtime: %s%n", formatDuration(estimatedMs));

        if (unleashed && estimatedMs > 3600000) {
            System.out.println("[TOURNAMENT] This will take a while...");
        }

        System.out.println();
        System.out.println("Engines:");
        for (int i = 0; i < engineIds.size(); i++) {
            System.out.printf("  %2d. %s%n", i + 1, engineIds.get(i));
        }
        System.out.println();

        if (estimateOnly) return;

        // Run tournament
        TournamentRunner runner = new TournamentRunner(orchestrator);
        final boolean verb = verbose;
        final int totalM = totalMatchups;
        final int gamesPerMatch = games;

        TournamentResult result = runner.runTournament(engineIds, games, maxTurns, iterations,
                seatSwap, new TournamentRunner.ProgressListener() {
                    @Override
                    public void onMatchStarted(int matchIndex, int totalMatches, String engineA, String engineB) {
                        System.out.printf("[%d/%d] %s vs %s ...%n",
                                matchIndex + 1, totalM, engineA, engineB);
                    }

                    @Override
                    public void onMatchCompleted(int matchIndex, int totalMatches, MatchResult matchResult) {
                        System.out.printf("        %s: %.0f%% | %s: %.0f%%  (%.1fs, avg %.0f turns)%n",
                                matchResult.config.engineIds()[0],
                                matchResult.winRates[0] * 100,
                                matchResult.config.engineIds()[1],
                                matchResult.winRates[1] * 100,
                                matchResult.totalTimeMs / 1000.0,
                                matchResult.avgGameLength);
                    }
                });

        // Print results
        System.out.println();
        System.out.printf("[TOURNAMENT] Complete in %s%n", formatDuration(result.totalTimeMs));
        System.out.println();

        // Leaderboard
        printLeaderboard(result);
        System.out.println();

        // H2H matrix
        printMatrix(result);
    }

    // -------------------------------------------------------------------------
    // Output formatting
    // -------------------------------------------------------------------------

    private static void printLeaderboard(TournamentResult result) {
        System.out.println("=== LEADERBOARD ===");
        System.out.printf("%-4s %-40s %6s %6s %8s%n", "Rank", "Engine", "W", "L", "Win%");
        System.out.println("-".repeat(68));
        int rank = 1;
        for (TournamentResult.LeaderboardEntry e : result.leaderboard) {
            System.out.printf("%-4d %-40s %6d %6d %7.1f%%%n",
                    rank++, e.engineId, e.totalWins, e.totalLosses, e.winRate * 100);
        }
    }

    private static void printMatrix(TournamentResult result) {
        List<String> ids = result.engineIds;
        int n = ids.size();

        // Abbreviate engine IDs for matrix display
        List<String> shortIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shortIds.add(abbreviate(ids.get(i)));
        }

        System.out.println("=== HEAD-TO-HEAD MATRIX (win% of row engine vs column engine) ===");
        System.out.println();

        // Determine column width (max of short IDs + padding)
        int colWidth = shortIds.stream().mapToInt(String::length).max().orElse(6);
        colWidth = Math.max(colWidth + 1, 7); // at least 7 to fit "  100%"
        int labelWidth = colWidth + 2;

        // Header
        System.out.printf("%-" + labelWidth + "s", "");
        for (int j = 0; j < n; j++) {
            System.out.printf("%" + colWidth + "s", shortIds.get(j));
        }
        System.out.println();

        // Rows
        for (int i = 0; i < n; i++) {
            System.out.printf("%-" + labelWidth + "s", shortIds.get(i));
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    System.out.printf("%" + colWidth + "s", "---");
                } else {
                    System.out.printf("%" + (colWidth - 1) + ".0f%%", result.h2hMatrix[i][j] * 100);
                }
            }
            System.out.println();
        }
    }

    /**
     * Abbreviates an engine ID for matrix display:
     * "mcts-v1-boltzmann-t07-fast" → "bolt-t07-f"
     */
    public static String abbreviate(String id) {
        String s = id.replace("mcts-v1-", "");
        s = s.replace("greedy-rollout", "grRoll");
        s = s.replace("boltzmann", "bolt");
        s = s.replace("greedy-tree", "grTree");
        s = s.replace("adaptive", "adapt");
        s = s.replace("depth", "d");
        s = s.replace("-fast", "-f");
        s = s.replace("-balanced", "-b");
        s = s.replace("-deep", "-d");
        // Handle plain "mcts-v1-fast" → after removing prefix: "fast" → "v1-f"
        if (s.equals("fast")) s = "v1-f";
        else if (s.equals("balanced")) s = "v1-b";
        else if (s.equals("deep")) s = "v1-d";
        return s;
    }

    // -------------------------------------------------------------------------
    // Runtime estimation
    // -------------------------------------------------------------------------

    private static long estimateRuntime(List<String> engineIds, int gamesPerMatchup,
                                        int iterationsOverride) {
        long totalMs = 0;
        for (int i = 0; i < engineIds.size(); i++) {
            for (int j = i + 1; j < engineIds.size(); j++) {
                long msPerGameA = estimateMsPerGame(engineIds.get(i), iterationsOverride);
                long msPerGameB = estimateMsPerGame(engineIds.get(j), iterationsOverride);
                // Both engines evaluate every turn, runtime dominated by the slower one
                long msPerGame = Math.max(msPerGameA, msPerGameB);
                totalMs += msPerGame * gamesPerMatchup;
            }
        }
        return totalMs;
    }

    private static long estimateMsPerGame(String engineId, int iterationsOverride) {
        EngineRegistryEntry entry = EngineRegistry.findById(engineId).orElse(null);
        if (entry == null) return 8000L;

        long[] baseline = MS_PER_GAME_BASELINE.getOrDefault(entry.engineClass(),
                new long[]{500, 8000});
        long baselineIter = baseline[0];
        long baseMsPerGame = baseline[1];

        int registryIter = entry.config().iterations;
        int effectiveIter = iterationsOverride > 0 ? iterationsOverride : registryIter;

        // Scale linearly from the baseline iteration count
        return baseMsPerGame * effectiveIter / baselineIter;
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return String.format("%dm %ds", minutes, seconds % 60);
        long hours = minutes / 60;
        return String.format("%dh %dm", hours, minutes % 60);
    }

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    private static void printUsage() {
        System.out.println("Usage: h2h.TournamentMain [options]");
        System.out.println();
        System.out.println("Engine selection (choose one):");
        System.out.println("  --tier <fast|balanced|deep>  Select by performance tier (default: fast)");
        System.out.println("  --engines <id1,id2,...>       Select specific engines by ID");
        System.out.println("  --unleashed                  ALL engines (warning: may take hours)");
        System.out.println();
        System.out.println("Match settings:");
        System.out.println("  --games <n>                  Games per matchup (default: 50)");
        System.out.println("  --iterations <n>             Override MCTS iterations (0 = registry default)");
        System.out.println("  --maxTurns <n>               Max turns per game (default: 200)");
        System.out.println("  --no-swap                    Disable mid-match seat swapping");
        System.out.println();
        System.out.println("Output:");
        System.out.println("  --estimate                   Print runtime estimate and exit");
        System.out.println("  --verbose                    Print every game result");
        System.out.println("  --help                       Show this help");
        System.out.println();

        System.out.println("Engine tiers:");
        for (String t : List.of("fast", "balanced", "deep")) {
            List<EngineRegistryEntry> entries = EngineRegistry.getByTier(t);
            System.out.printf("  %s (%d engines):%n", t, entries.size());
            for (EngineRegistryEntry e : entries) {
                System.out.printf("    %-40s %s%n", e.id(), e.description());
            }
        }
    }
}
