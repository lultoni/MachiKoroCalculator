package h2h;

import engine.*;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.*;

/**
 * CLI entry point for running round-robin tournaments.
 *
 * <p>On Ctrl+C or normal completion, prints a detailed summary: leaderboard,
 * head-to-head matrix, per-matchup results, and notable stats.
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

    /** Shared reference for the shutdown hook. */
    private static volatile TournamentRunner activeRunner = null;
    private static volatile long tournamentStartMs = 0;

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

        // Set up runner with shutdown hook for Ctrl+C
        TournamentRunner runner = new TournamentRunner(orchestrator);
        activeRunner = runner;
        tournamentStartMs = System.currentTimeMillis();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TournamentRunner r = activeRunner;
            if (r == null) return; // already printed results normally
            List<MatchResult> partial = r.getCompletedResults();
            List<String> ids = r.getCurrentEngineIds();
            if (partial.isEmpty() || ids.isEmpty()) return;

            long elapsed = System.currentTimeMillis() - tournamentStartMs;
            TournamentResult partialResult = new TournamentResult(ids, partial, elapsed);

            System.out.println();
            System.out.println("[TOURNAMENT] Interrupted! Printing results from completed matchups...");
            System.out.println();
            printResults(partialResult, partial, true);
        }, "tournament-shutdown-hook"));

        // Run tournament
        final int totalM = totalMatchups;

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

        // Clear runner reference so shutdown hook knows we printed normally
        activeRunner = null;

        // Print results
        System.out.println();
        System.out.printf("[TOURNAMENT] Complete in %s%n", formatDuration(result.totalTimeMs));
        System.out.println();
        printResults(result, runner.getCompletedResults(), false);
    }

    // -------------------------------------------------------------------------
    // Result display (used for both normal completion and Ctrl+C)
    // -------------------------------------------------------------------------

    /**
     * Prints the full tournament summary: leaderboard, H2H matrix, per-matchup
     * results, and notable stats.
     */
    private static void printResults(TournamentResult result, List<MatchResult> matchResults,
                                     boolean partial) {
        int completedMatchups = matchResults.size();
        int totalPossible = result.engineIds.size() * (result.engineIds.size() - 1) / 2;

        if (partial) {
            System.out.printf("[TOURNAMENT] %d / %d matchups completed (%.0f%%)%n",
                    completedMatchups, totalPossible,
                    (double) completedMatchups / totalPossible * 100);
            System.out.printf("[TOURNAMENT] Elapsed: %s%n", formatDuration(result.totalTimeMs));
            System.out.println();
        }

        // 1. Leaderboard
        printLeaderboard(result);
        System.out.println();

        // 2. H2H matrix
        printMatrix(result);
        System.out.println();

        // 3. Per-matchup detail
        printMatchupDetails(matchResults);
        System.out.println();

        // 4. Notable stats
        printNotableStats(result, matchResults);
    }

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
                } else if (result.h2hMatrix[i][j] == 0.0 && result.h2hMatrix[j][i] == 0.0) {
                    // Not yet played
                    System.out.printf("%" + colWidth + "s", "  -");
                } else {
                    System.out.printf("%" + (colWidth - 1) + ".0f%%", result.h2hMatrix[i][j] * 100);
                }
            }
            System.out.println();
        }
    }

    private static void printMatchupDetails(List<MatchResult> matchResults) {
        System.out.println("=== MATCHUP DETAILS ===");
        System.out.printf("%-30s %-30s %6s %6s %8s %8s%n",
                "Engine A", "Engine B", "A wins", "B wins", "Avg len", "Time");
        System.out.println("-".repeat(120));
        for (MatchResult r : matchResults) {
            System.out.printf("%-30s %-30s %6d %6d %8.0f %7.1fs%n",
                    r.config.engineIds()[0],
                    r.config.engineIds()[1],
                    r.wins[0], r.wins[1],
                    r.avgGameLength,
                    r.totalTimeMs / 1000.0);
        }
    }

    private static void printNotableStats(TournamentResult result, List<MatchResult> matchResults) {
        if (matchResults.isEmpty()) return;

        System.out.println("=== NOTABLE STATS ===");

        // Most dominant matchup (highest win rate)
        MatchResult mostDominant = null;
        double highestRate = 0;
        for (MatchResult r : matchResults) {
            double rate = Math.max(r.winRates[0], r.winRates[1]);
            if (rate > highestRate) {
                highestRate = rate;
                mostDominant = r;
            }
        }
        if (mostDominant != null) {
            int winnerIdx = mostDominant.winRates[0] >= mostDominant.winRates[1] ? 0 : 1;
            int loserIdx = 1 - winnerIdx;
            System.out.printf("  Most dominant: %s beat %s %.0f%%-%.0f%%%n",
                    mostDominant.config.engineIds()[winnerIdx],
                    mostDominant.config.engineIds()[loserIdx],
                    mostDominant.winRates[winnerIdx] * 100,
                    mostDominant.winRates[loserIdx] * 100);
        }

        // Closest matchup (nearest to 50-50)
        MatchResult closest = null;
        double closestDiff = 1.0;
        for (MatchResult r : matchResults) {
            double diff = Math.abs(r.winRates[0] - r.winRates[1]);
            if (diff < closestDiff) {
                closestDiff = diff;
                closest = r;
            }
        }
        if (closest != null) {
            System.out.printf("  Closest match: %s vs %s (%.0f%%-%.0f%%)%n",
                    closest.config.engineIds()[0],
                    closest.config.engineIds()[1],
                    closest.winRates[0] * 100,
                    closest.winRates[1] * 100);
        }

        // Shortest avg game length
        MatchResult shortest = matchResults.stream()
                .min(Comparator.comparingDouble(r -> r.avgGameLength)).orElse(null);
        if (shortest != null) {
            System.out.printf("  Shortest games: %s vs %s (avg %.0f turns)%n",
                    shortest.config.engineIds()[0],
                    shortest.config.engineIds()[1],
                    shortest.avgGameLength);
        }

        // Longest avg game length
        MatchResult longest = matchResults.stream()
                .max(Comparator.comparingDouble(r -> r.avgGameLength)).orElse(null);
        if (longest != null) {
            System.out.printf("  Longest games:  %s vs %s (avg %.0f turns)%n",
                    longest.config.engineIds()[0],
                    longest.config.engineIds()[1],
                    longest.avgGameLength);
        }

        // Total games, total eval time
        int totalGamesPlayed = matchResults.stream().mapToInt(r -> r.gameLogs.size()).sum();
        double totalEvalMs = matchResults.stream().mapToDouble(r -> r.totalTimeMs).sum();
        System.out.printf("  Total: %d games played across %d matchups in %s%n",
                totalGamesPlayed, matchResults.size(), formatDuration((long) totalEvalMs));
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
        int cores = Runtime.getRuntime().availableProcessors();
        long totalMs = 0;
        for (int i = 0; i < engineIds.size(); i++) {
            for (int j = i + 1; j < engineIds.size(); j++) {
                long msPerGameA = estimateMsPerGame(engineIds.get(i), iterationsOverride);
                long msPerGameB = estimateMsPerGame(engineIds.get(j), iterationsOverride);
                // Both engines evaluate every turn, runtime dominated by the slower one
                long msPerGame = Math.max(msPerGameA, msPerGameB);
                // Games within a match run in parallel via ForkJoinPool
                long matchMs = msPerGame * gamesPerMatchup / cores;
                totalMs += matchMs;
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

    static String formatDuration(long ms) {
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

        System.out.println("Presets:");
        System.out.println("  Quick test (3 engines, ~30s):");
        System.out.println("    --engines mcts-v1-fast,mcts-v1-depth3,mcts-v1-greedy-tree-fast --games 10");
        System.out.println();
        System.out.println("  Fast tier (10 engines, ~5 min without A/B, hours with):");
        System.out.println("    --tier fast --games 20");
        System.out.println();
        System.out.println("  Speed demons only (6 engines, ~30 min):");
        System.out.println("    --engines mcts-v1-fast,mcts-v1-greedy-tree-fast,mcts-v1-depth3,mcts-v1-depth7,mcts-v1-depth10,mcts-v1-adaptive-fast --games 50");
        System.out.println();
        System.out.println("  Full fast tier (10 engines, est. hours — A/B variants are slow):");
        System.out.println("    --tier fast --games 50");
        System.out.println();
        System.out.println("  All engines (24 engines, est. days):");
        System.out.println("    --unleashed --games 30");
        System.out.println();
        System.out.println("  Tip: Ctrl+C at any time prints results from completed matchups.");
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
