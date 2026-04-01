package h2h;

import engine.*;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.List;

/**
 * CLI entry point for running H2H matches.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp "out:src:gson-2.11.0.jar" h2h.H2hMain \
 *   --engineA mcts-v1-fast --engineB mcts-v1-depth3 \
 *   --games 100 --iterations 500 --verbose
 * </pre>
 *
 * <p>Results are saved to {@code h2h-results.json}.
 */
public final class H2hMain {

    private H2hMain() {}

    public static void main(String[] args) {
        // Parse args
        String engineA = "mcts-v1-fast";
        String engineB = "mcts-v1-fast";
        int games = 100;
        int iterations = 500;
        int maxTurns = 200;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engineA" -> engineA = args[++i];
                case "--engineB" -> engineB = args[++i];
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                case "--maxTurns" -> maxTurns = Integer.parseInt(args[++i]);
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
        orchestrator.register(new FlatMcEngine());
        orchestrator.register(new HeuristicEvEngine());

        MatchConfig config = new MatchConfig(
                new String[]{engineA, engineB}, games, maxTurns, iterations, true);

        System.out.printf("[H2H] %s vs %s — %d games, %d iter/eval, %d max turns%n",
                engineA, engineB, games, iterations, maxTurns);

        MatchRunner runner = new MatchRunner(orchestrator);
        final boolean verb = verbose;
        final int totalGames = games;
        MatchResult result = runner.runMatch(config, (gameIdx, log) -> {
            if (verb || (gameIdx + 1) % 10 == 0) {
                System.out.printf("  Game %d/%d: winner=P%d, turns=%d%s%n",
                        gameIdx + 1, totalGames,
                        log.winnerIndex + 1, log.totalTurns,
                        log.timeoutWin ? " (timeout)" : "");
            }
        });

        // Print results
        System.out.println();
        System.out.printf("[H2H] Complete in %.1fs%n", result.totalTimeMs / 1000.0);
        for (int i = 0; i < config.playerCount(); i++) {
            System.out.printf("  P%d (%s): %d wins (%.1f%%)%n",
                    i + 1, config.engineIds()[i],
                    result.wins[i], result.winRates[i] * 100);
        }
        System.out.printf("  Avg game length: %.1f turns%n", result.avgGameLength);
        System.out.printf("  Avg eval time: %.1f ms%n", result.avgEvalTimeMs);

        // Save
        H2hResultStore store = new H2hResultStore();
        store.save(result);
        System.out.printf("[H2H] Results saved to h2h-results.json (match id: %s)%n", result.id);
    }

    private static void printUsage() {
        System.out.println("Usage: h2h.H2hMain [options]");
        System.out.println("  --engineA <id>    Engine registry ID for player 1 (default: mcts-v1-fast)");
        System.out.println("  --engineB <id>    Engine registry ID for player 2 (default: mcts-v1-fast)");
        System.out.println("  --games <n>       Number of games (default: 100)");
        System.out.println("  --iterations <n>  MCTS iterations per eval (default: 500)");
        System.out.println("  --maxTurns <n>    Max turns per game (default: 200)");
        System.out.println("  --verbose         Print every game result");
        System.out.println();
        System.out.println("Available engines:");
        for (EngineRegistryEntry e : EngineRegistry.getAll()) {
            System.out.printf("  %-30s %s%n", e.id(), e.description());
        }
    }
}
