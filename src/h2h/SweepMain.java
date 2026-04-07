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

import java.util.*;

/**
 * CLI tool for automated Creator Engine parameter optimization via TPE.
 *
 * <p>Runs head-to-head matches with different Creator parameter vectors against
 * a fixed opponent, using a Tree-structured Parzen Estimator (TPE) to guide the
 * search toward high-win-rate parameter regions.
 *
 * <p>Initial trials use Latin Hypercube Sampling for uniform space coverage.
 * Once enough data is collected, TPE models the good/bad observation split
 * and suggests candidates that maximize the expected improvement.
 *
 * <p>Results are saved to {@code data/sweep-results.json} after <em>every</em>
 * completed trial, so Ctrl+C never loses work. Use {@code --resume} on the
 * next run to continue from where the sweep left off.
 *
 * <h2>Usage</h2>
 * <pre>
 * # Quick smoke test (5 trials, 20 games each)
 * java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 5 --games 20
 *
 * # Run indefinitely until Ctrl+C (recommended for overnight runs)
 * java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --infinite --games 50
 *
 * # Full sweep (100 trials, 50 games, ~30-60 min)
 * java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 100 --games 50
 *
 * # Custom opponent and seed
 * java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain \
 *   --opponent mcts-v1-fast --trials 50 --games 100 --seed 42
 *
 * # Resume all prior trials and keep going for 200 total
 * java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 200 --resume
 * </pre>
 *
 * @see TpeSampler
 * @see SweepResult
 */
public final class SweepMain {

    private SweepMain() {}

    // =====================================================================
    // Parameter space — all CreatorScorer configurable knobs
    // =====================================================================

    static final TpeSampler.ParamDef[] PARAMS = {
        // Base dimension weights (8)
        new TpeSampler.ParamDef("wIncome",   0.5, 5.0, 2.5),
        new TpeSampler.ParamDef("wRisk",     0.3, 4.0, 2.0),
        new TpeSampler.ParamDef("wCoverage", 0.3, 3.0, 1.5),
        new TpeSampler.ParamDef("wTempo",    0.3, 4.0, 2.0),
        new TpeSampler.ParamDef("wWinProb",  0.5, 6.0, 3.0),
        new TpeSampler.ParamDef("wLandmark", 0.5, 4.0, 2.0),
        new TpeSampler.ParamDef("wUrgency",  0.2, 3.0, 1.0),
        new TpeSampler.ParamDef("wRoi",      0.3, 3.0, 1.5),
        // Situation assessment (6)
        new TpeSampler.ParamDef("sitLandmark",      0.05, 0.60, 0.30),
        new TpeSampler.ParamDef("sitIncome",         0.05, 0.60, 0.30),
        new TpeSampler.ParamDef("sitCoins",          0.05, 0.40, 0.15),
        new TpeSampler.ParamDef("sitTempo",          0.05, 0.50, 0.25),
        new TpeSampler.ParamDef("targetEvPerRound",  2.0,  8.0,  4.0),
        new TpeSampler.ParamDef("maxETW",           20.0, 80.0, 50.0),
        // Sigmoid + gravity wells (5)
        new TpeSampler.ParamDef("sigmoidK",          2.0, 12.0, 6.0),
        new TpeSampler.ParamDef("sprintHorizon",     3.0, 12.0, 6.0),
        new TpeSampler.ParamDef("sprintSharpness",   0.3,  3.0, 1.0),
        new TpeSampler.ParamDef("threatHorizon",     4.0, 16.0, 8.0),
        new TpeSampler.ParamDef("threatSharpness",   0.3,  3.0, 1.0),
        // Bürohaus swap (1)
        new TpeSampler.ParamDef("wBurohausSwap",     0.5,  4.0, 1.5),
    };

    // =====================================================================
    // Main
    // =====================================================================

    public static void main(String[] args) {
        // Parse CLI args
        int trials = 100;
        boolean infinite = false;
        int games = 50;
        String creatorId = "creator-fast";
        String opponent = "heuristic-ev-default";
        int iterations = 0;  // 0 = use registry defaults
        int startup = 20;
        double gamma = 0.25;
        long seed = System.nanoTime();
        boolean resume = false;
        boolean includeDefault = true;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--trials"    -> trials = Integer.parseInt(args[++i]);
                case "--infinite"  -> infinite = true;
                case "--games"     -> games = Integer.parseInt(args[++i]);
                case "--creator"   -> creatorId = args[++i];
                case "--opponent"  -> opponent = args[++i];
                case "--iterations"-> iterations = Integer.parseInt(args[++i]);
                case "--startup"   -> startup = Integer.parseInt(args[++i]);
                case "--gamma"     -> gamma = Double.parseDouble(args[++i]);
                case "--seed"      -> seed = Long.parseLong(args[++i]);
                case "--resume"    -> resume = true;
                case "--no-default"-> includeDefault = false;
                case "--verbose"   -> verbose = true;
                case "--help"      -> { printUsage(); return; }
                default            -> System.err.println("Unknown arg: " + args[i]);
            }
        }

        // --infinite overrides --trials; treat trials=0 as infinite as well
        if (infinite || trials == 0) {
            infinite = true;
            trials = Integer.MAX_VALUE;
        }

        // Validate engines exist
        if (EngineRegistry.findById(opponent).isEmpty()) {
            System.err.println("Unknown opponent engine: " + opponent);
            return;
        }
        if (EngineRegistry.findById(creatorId).isEmpty()) {
            System.err.println("Unknown creator engine: " + creatorId);
            return;
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

        Random rng = new Random(seed);
        TpeSampler sampler = new TpeSampler(rng, gamma, 24);
        MatchRunner runner = new MatchRunner(orchestrator);

        // Load existing trials if resuming
        List<SweepResult.Trial> priorTrials = resume ? SweepResult.loadAllTrials() : new ArrayList<>();
        int priorCount = priorTrials.size();

        System.out.printf("[SWEEP] %s vs %s — %s trials (%d startup), %d games/trial, seed=%d%n",
                creatorId, opponent,
                infinite ? "∞" : String.valueOf(trials),
                startup, games, seed);
        if (priorCount > 0) {
            System.out.printf("[SWEEP] Resuming from %d existing trials%n", priorCount);
        }
        if (infinite) {
            System.out.println("[SWEEP] Running indefinitely — press Ctrl+C to stop and save");
        }
        System.out.printf("[SWEEP] Parameter space: %d dimensions%n", PARAMS.length);
        System.out.println();

        // Generate LHS points for startup
        int effectiveStartup = Math.max(0, startup - priorCount);
        double[][] lhsPoints = effectiveStartup > 0
                ? sampler.latinHypercube(PARAMS, effectiveStartup) : new double[0][];

        // Stable run ID for this session — used by saveOrUpdate so Ctrl+C preserves all trials
        String runId = UUID.randomUUID().toString().substring(0, 8);

        long sweepStartMs = System.currentTimeMillis();
        List<SweepResult.Trial> newTrials = new ArrayList<>();
        double bestWinRate = priorTrials.stream().mapToDouble(t -> t.winRate).max().orElse(0.0);
        int bestTrialIdx = priorTrials.stream()
                .max(Comparator.comparingDouble(t -> t.winRate))
                .map(t -> t.index).orElse(-1);

        // Install shutdown hook so Ctrl+C always saves completed work.
        // normalExit[0] is set to true before the main loop ends; the hook is a no-op in that case
        // (all data is already persisted by saveOrUpdate inside the loop).
        final boolean[] normalExit = {false};
        final int[] trialsOnExit = {0};
        final long[] startMsRef = {sweepStartMs};
        final String finalCreatorId = creatorId;
        final String finalOpponent = opponent;
        final int finalGames = games;
        final int finalStartup = startup;
        final double finalGamma = gamma;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (normalExit[0] || trialsOnExit[0] == 0) return;
            long elapsed = System.currentTimeMillis() - startMsRef[0];
            System.out.printf("%n[SWEEP] Interrupted — saving %d completed trial(s)...%n",
                    trialsOnExit[0]);
            SweepResult.SweepRun run = new SweepResult.SweepRun(
                    runId, finalCreatorId, finalOpponent, finalGames,
                    trialsOnExit[0], finalStartup, finalGamma,
                    new ArrayList<>(newTrials), elapsed);
            SweepResult.saveOrUpdate(run);
            System.out.printf("[SWEEP] Saved to data/sweep-results.json%n");
            printTopResults(newTrials, priorTrials, 10);
        }, "sweep-shutdown"));

        // ---- Main trial loop ----
        int startIdx = priorCount;

        for (int t = 0; t < trials; t++) {
            int trialIdx = startIdx + t;

            // Choose parameter vector
            double[] paramValues;
            String source;

            if (includeDefault && trialIdx == 0) {
                // First trial: evaluate the current default weights
                paramValues = new double[PARAMS.length];
                for (int d = 0; d < PARAMS.length; d++) paramValues[d] = PARAMS[d].defaultVal();
                source = "default";
            } else if (trialIdx < startup) {
                // Startup phase: Latin Hypercube
                int lhsIdx = trialIdx - (includeDefault ? 1 : 0);
                paramValues = (lhsIdx >= 0 && lhsIdx < lhsPoints.length)
                        ? lhsPoints[lhsIdx] : randomPoint(rng, PARAMS);
                source = "LHS";
            } else {
                // TPE phase: use observed data to suggest next point
                int totalObs = priorTrials.size() + newTrials.size();
                double[][] obsParams = new double[totalObs][PARAMS.length];
                double[] obsObjectives = new double[totalObs];
                int idx = 0;
                for (SweepResult.Trial tr : priorTrials) {
                    obsParams[idx] = trialToArray(tr);
                    obsObjectives[idx++] = tr.winRate;
                }
                for (SweepResult.Trial tr : newTrials) {
                    obsParams[idx] = trialToArray(tr);
                    obsObjectives[idx++] = tr.winRate;
                }
                paramValues = sampler.suggest(PARAMS, obsParams, obsObjectives);
                source = "TPE";
            }

            // Build config overrides for this trial
            Map<String, String> creatorOverrides = new LinkedHashMap<>();
            Map<String, Double> paramMap = new LinkedHashMap<>();
            for (int d = 0; d < PARAMS.length; d++) {
                double v = PARAMS[d].clamp(paramValues[d]);
                creatorOverrides.put(PARAMS[d].key(), String.valueOf(v));
                paramMap.put(PARAMS[d].key(), v);
            }

            if (verbose) {
                System.out.printf("  [verbose] Trial %d params (%s):%n", trialIdx, source);
                for (int d = 0; d < PARAMS.length; d++) {
                    double v = paramMap.get(PARAMS[d].key());
                    double delta = v - PARAMS[d].defaultVal();
                    if (Math.abs(delta) > 0.001) {
                        System.out.printf("    %-22s %6.3f  (%s%.3f from default)%n",
                                PARAMS[d].key(), v, delta >= 0 ? "+" : "", delta);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, String>[] overrides = new Map[2];
            overrides[0] = creatorOverrides;
            overrides[1] = Map.of();

            MatchConfig matchConfig = new MatchConfig(
                    new String[]{creatorId, opponent}, games, 200, iterations, true, overrides);

            // Run the match
            long matchStart = System.currentTimeMillis();
            final boolean verb = verbose;
            final int totalGames = games;
            MatchResult result = runner.runMatch(matchConfig, verbose ? (gameIdx, log) -> {
                if (verb && ((gameIdx + 1) % 10 == 0 || totalGames <= 20)) {
                    System.out.printf("    Game %d/%d: winner=P%d, turns=%d%s%n",
                            gameIdx + 1, totalGames,
                            log.winnerIndex + 1, log.totalTurns,
                            log.timeoutWin ? " (timeout)" : "");
                }
            } : null);
            long matchTime = System.currentTimeMillis() - matchStart;

            double winRate = result.winRates[0];
            SweepResult.Trial trial = new SweepResult.Trial(trialIdx, paramMap, winRate, games, matchTime);
            newTrials.add(trial);
            trialsOnExit[0] = newTrials.size();

            if (winRate > bestWinRate) {
                bestWinRate = winRate;
                bestTrialIdx = trialIdx;
            }

            String trialLabel = infinite
                    ? String.format("Trial %d", t + 1)
                    : String.format("Trial %d/%d", t + 1, trials);
            System.out.printf("  %s (%s): WR=%.1f%%  [best: %.1f%% @ #%d]  (%.1fs)%n",
                    trialLabel, source, winRate * 100,
                    bestWinRate * 100, bestTrialIdx, matchTime / 1000.0);

            if (verbose) {
                System.out.printf("    Match details: %d-%d (%.0f%%-%.0f%%), avg %.0f turns, avg eval %.1fms%n",
                        result.wins[0], result.wins[1],
                        result.winRates[0] * 100, result.winRates[1] * 100,
                        result.avgGameLength, result.avgEvalTimeMs);
            }

            // Save after every trial so Ctrl+C never loses completed work.
            // saveOrUpdate replaces the in-progress entry by runId (no duplicates).
            long elapsed = System.currentTimeMillis() - sweepStartMs;
            SweepResult.SweepRun inProgress = new SweepResult.SweepRun(
                    runId, creatorId, opponent, games,
                    newTrials.size(), startup, gamma,
                    new ArrayList<>(newTrials), elapsed);
            SweepResult.saveOrUpdate(inProgress);
        }

        long totalTime = System.currentTimeMillis() - sweepStartMs;
        normalExit[0] = true;  // suppress shutdown hook output on clean exit

        // Normal completion: the loop already saved via saveOrUpdate on the last trial.
        System.out.println();
        System.out.printf("[SWEEP] Complete in %s — %d trials evaluated%n",
                TournamentMain.formatDuration(totalTime), newTrials.size());
        System.out.println();
        printTopResults(newTrials, priorTrials, 10);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static double[] randomPoint(Random rng, TpeSampler.ParamDef[] params) {
        double[] result = new double[params.length];
        for (int d = 0; d < params.length; d++) {
            result[d] = params[d].low() + rng.nextDouble() * params[d].range();
        }
        return result;
    }

    private static double[] trialToArray(SweepResult.Trial trial) {
        double[] result = new double[PARAMS.length];
        for (int d = 0; d < PARAMS.length; d++) {
            Double v = trial.params.get(PARAMS[d].key());
            result[d] = v != null ? v : PARAMS[d].defaultVal();
        }
        return result;
    }

    private static void printTopResults(List<SweepResult.Trial> newTrials,
                                         List<SweepResult.Trial> priorTrials, int topN) {
        List<SweepResult.Trial> all = new ArrayList<>(priorTrials);
        all.addAll(newTrials);
        all.sort(Comparator.comparingDouble((SweepResult.Trial t) -> t.winRate).reversed());

        System.out.printf("=== TOP %d PARAMETER VECTORS ===%n", Math.min(topN, all.size()));
        System.out.println();

        int rank = 1;
        for (SweepResult.Trial t : all.subList(0, Math.min(topN, all.size()))) {
            System.out.printf("#%d — Trial %d: WR=%.1f%% (%d games, %.1fs)%n",
                    rank++, t.index, t.winRate * 100, t.games, t.timeMs / 1000.0);

            TreeMap<String, Double> sorted = new TreeMap<>(t.params);
            for (Map.Entry<String, Double> e : sorted.entrySet()) {
                double defaultVal = 0;
                for (TpeSampler.ParamDef p : PARAMS) {
                    if (p.key().equals(e.getKey())) { defaultVal = p.defaultVal(); break; }
                }
                double delta = e.getValue() - defaultVal;
                System.out.printf("    %-22s %6.3f  (default: %.3f, %s%.3f)%n",
                        e.getKey(), e.getValue(), defaultVal,
                        delta >= 0 ? "+" : "", delta);
            }
            System.out.println();
        }

        // Print as engines.json config snippet for the best vector
        if (!all.isEmpty()) {
            SweepResult.Trial best = all.get(0);
            System.out.println("=== engines.json CONFIG FOR BEST VECTOR ===");
            System.out.println("{");
            System.out.printf("  \"id\": \"creator-tuned\",%n");
            System.out.printf("  \"engineClass\": \"creator\",%n");
            System.out.printf("  \"description\": \"Creator Engine — TPE-tuned (WR=%.1f%% vs opponent)\",%n",
                    best.winRate * 100);
            System.out.printf("  \"default\": false,%n");
            System.out.printf("  \"tier\": \"fast\",%n");
            System.out.printf("  \"config\": {%n");
            System.out.printf("    \"iterations\": \"500\"");
            TreeMap<String, Double> sorted = new TreeMap<>(best.params);
            for (Map.Entry<String, Double> e : sorted.entrySet()) {
                System.out.printf(",%n    \"%s\": \"%.4f\"", e.getKey(), e.getValue());
            }
            System.out.printf("%n  }%n");
            System.out.println("}");
        }
    }

    // =====================================================================
    // Usage
    // =====================================================================

    private static void printUsage() {
        System.out.println("Usage: h2h.SweepMain [options]");
        System.out.println();
        System.out.println("Runs automated parameter optimization for the Creator Engine");
        System.out.println("using TPE (Tree-structured Parzen Estimator) guided search.");
        System.out.println();
        System.out.println("Results are saved to data/sweep-results.json after every trial.");
        System.out.println("Ctrl+C stops the sweep and prints the top results found so far.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --trials N        Total evaluation trials (default: 100)");
        System.out.println("  --infinite        Run indefinitely until Ctrl+C (overrides --trials)");
        System.out.println("  --games N         Games per trial match (default: 50)");
        System.out.println("  --creator <id>    Creator engine ID (default: creator-fast)");
        System.out.println("  --opponent <id>   Opponent engine ID (default: heuristic-ev-default)");
        System.out.println("  --iterations N    Override iterations (0 = registry default)");
        System.out.println("  --startup N       Random trials before TPE (default: 20)");
        System.out.println("  --gamma F         TPE good/bad split (default: 0.25)");
        System.out.println("  --seed N          Random seed for reproducibility");
        System.out.println("  --resume          Continue from all trials in sweep-results.json");
        System.out.println("  --no-default      Skip evaluating default params as trial 0");
        System.out.println("  --verbose         Per-game results, param deltas, match details");
        System.out.println("  --help            Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Quick smoke test (~1 min)");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain --trials 5 --games 20");
        System.out.println();
        System.out.println("  # Run indefinitely (overnight), stop with Ctrl+C");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain --infinite --games 50");
        System.out.println();
        System.out.println("  # Resume all prior work and continue to 200 trials total");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain --trials 200 --resume");
        System.out.println();
        System.out.println("  # Reproducible sweep against MCTS");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain \\");
        System.out.println("    --opponent mcts-v1-fast --trials 50 --games 100 --seed 42");
        System.out.println();
        System.out.println("Parameter space (" + PARAMS.length + " dimensions):");
        for (TpeSampler.ParamDef p : PARAMS) {
            System.out.printf("  %-22s [%.2f, %.2f]  default=%.2f%n",
                    p.key(), p.low(), p.high(), p.defaultVal());
        }
    }
}
