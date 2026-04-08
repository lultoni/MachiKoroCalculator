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
import iface.EngineParamRegistry;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

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

    /*
    --- List of best params from old run against only heuristic-ev as starting point (creator-balanced) --- 
    "params": {
      "wIncome": 0.5,
      "wRisk": 1.034159692421214,
      "wCoverage": 0.3418816081193578,
      "wTempo": 1.2975187412307696,
      "wWinProb": 4.601562040526129,
      "wLandmark": 2.9785906332662893,
      "wUrgency": 2.475601860309849,
      "wRoi": 1.1465292262045277,
      "sitLandmark": 0.3974390936978679,
      "sitIncome": 0.052984962276980865,
      "sitCoins": 0.3792443332612002,
      "sitTempo": 0.3229663161861425,
      "targetEvPerRound": 2.4074532126136887,
      "maxETW": 45.594286541222274,
      "sigmoidK": 10.43656136642734,
      "sprintHorizon": 9.129449138031692,
      "sprintSharpness": 1.0128301603669694,
      "threatHorizon": 4.327211831669695,
      "threatSharpness": 1.2187182469370976,
      "wBurohausSwap": 2.7319287177692937
    },

    --- List of best params from best trial against 4 engines (creator balanced) --- 
    "params": {
      "wIncome": 4.762482484660718,
      "wRisk": 2.823735005567347,
      "wCoverage": 5.731904027594611,
      "wTempo": 6.0,
      "wWinProb": 4.061873486638532,
      "wLandmark": 2.508174101853229,
      "wUrgency": 4.831647513788628,
      "wRoi": 0.4554705437172182,
      "sitLandmark": 0.9030080702976719,
      "sitIncome": 0.0,
      "sitCoins": 0.14951468785641275,
      "sitTempo": 0.560019980411893,
      "targetEvPerRound": 3.6282739670732607,
      "maxETW": 100.0,
      "sigmoidK": 15.074706493392714,
      "sprintHorizon": 15.48556792767432,
      "sprintSharpness": 2.743122679603714,
      "threatHorizon": 10.084568459486619,
      "threatSharpness": 1.356505476878349,
      "wBurohausSwap": 1.454001790944752
    },
    */

    /**
     * Sweep parameter space — built from {@code engine-params.json} via {@link EngineParamRegistry}.
     * Only includes Creator engine params that have sweepLow/sweepHigh defined.
     */
    static final TpeSampler.ParamDef[] PARAMS = buildSweepParams();

    private static TpeSampler.ParamDef[] buildSweepParams() {
        return EngineParamRegistry.getForClass("creator").stream()
                .filter(e -> e.sweepLow() != null && e.sweepHigh() != null && e.sweepDefault() != null)
                .map(e -> new TpeSampler.ParamDef(e.key(), e.sweepLow(), e.sweepHigh(), e.sweepDefault()))
                .toArray(TpeSampler.ParamDef[]::new);
    }

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
        List<String> opponentList = null;  // set when --opponents is used
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
                case "--opponents" -> opponentList = Arrays.asList(args[++i].split(","));
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

        // Validate creator engine and read its registry entry for tier/iterations metadata
        Optional<EngineRegistryEntry> creatorEntryOpt = EngineRegistry.findById(creatorId);
        if (creatorEntryOpt.isEmpty()) {
            System.err.println("Unknown creator engine: " + creatorId);
            return;
        }
        EngineRegistryEntry creatorEntry = creatorEntryOpt.get();

        // Resolve opponent list — single --opponent or multi --opponents
        if (opponentList == null) {
            opponentList = List.of(opponent);
        }
        for (String opp : opponentList) {
            if (EngineRegistry.findById(opp).isEmpty()) {
                System.err.println("Unknown opponent engine: " + opp);
                return;
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

        Random rng = new Random(seed);
        TpeSampler sampler = new TpeSampler(rng, gamma, 24);
        MatchRunner runner = new MatchRunner(orchestrator);

        // Load existing trials if resuming
        List<SweepResult.Trial> priorTrials = resume ? SweepResult.loadAllTrials() : new ArrayList<>();
        int priorCount = priorTrials.size();

        String oppDisplay = opponentList.size() == 1
                ? opponentList.get(0)
                : "[" + String.join(", ", opponentList) + "]";
        String gamesDesc = opponentList.size() == 1
                ? String.format("%d games/trial", games)
                : String.format("%d games/opponent × %d opponents", games, opponentList.size());
        System.out.printf("[SWEEP] %s vs %s — %s trials (%d startup), %s, seed=%d%n",
                creatorId, oppDisplay,
                infinite ? "∞" : String.valueOf(trials),
                startup, gamesDesc, seed);
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
        final String finalOppDisplay = oppDisplay;
        final int finalGames = games;
        final int finalStartup = startup;
        final double finalGamma = gamma;
        final List<String> finalOpponentList = opponentList;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (normalExit[0] || trialsOnExit[0] == 0) return;
            long elapsed = System.currentTimeMillis() - startMsRef[0];
            System.out.printf("%n[SWEEP] Interrupted — saving %d completed trial(s)...%n",
                    trialsOnExit[0]);
            SweepResult.SweepRun run = new SweepResult.SweepRun(
                    runId, finalCreatorId, finalOppDisplay, finalGames,
                    trialsOnExit[0], finalStartup, finalGamma,
                    new ArrayList<>(newTrials), elapsed);
            SweepResult.saveOrUpdate(run);
            System.out.printf("[SWEEP] Saved to data/sweep-results.json%n");
            printTopResults(newTrials, priorTrials, 10, creatorEntry, finalOpponentList);
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

            // Play against ALL opponents and average the win rate.
            // This makes the objective unbiased across opponent strengths.
            long matchStart = System.currentTimeMillis();
            double winRateSum = 0.0;
            StringBuilder perOpp = new StringBuilder();

            for (int oi = 0; oi < opponentList.size(); oi++) {
                String currentOpponent = opponentList.get(oi);
                MatchConfig matchConfig = new MatchConfig(
                        new String[]{creatorId, currentOpponent}, games, 200, iterations, true, overrides);

                final boolean verb = verbose;
                final int totalGames = games;
                final int oppIdx = oi;
                MatchResult result = runner.runMatch(matchConfig, verbose ? (gameIdx, log) -> {
                    if (verb && ((gameIdx + 1) % 10 == 0 || totalGames <= 20)) {
                        System.out.printf("    [opp %d] Game %d/%d: winner=P%d, turns=%d%s%n",
                                oppIdx + 1, gameIdx + 1, totalGames,
                                log.winnerIndex + 1, log.totalTurns,
                                log.timeoutWin ? " (timeout)" : "");
                    }
                } : null);

                winRateSum += result.winRates[0];
                if (oi > 0) perOpp.append(", ");
                perOpp.append(result.wins[0]).append("-").append(result.wins[1]);

                if (verbose && opponentList.size() > 1) {
                    System.out.printf("    vs %-30s: %d-%d (%.1f%%)%n",
                            currentOpponent, result.wins[0], result.wins[1],
                            result.winRates[0] * 100);
                }
            }

            long matchTime = System.currentTimeMillis() - matchStart;
            double winRate = winRateSum / opponentList.size();
            int totalGamesPlayed = games * opponentList.size();
            SweepResult.Trial trial = new SweepResult.Trial(trialIdx, paramMap, winRate, totalGamesPlayed, matchTime);
            newTrials.add(trial);
            trialsOnExit[0] = newTrials.size();

            if (winRate > bestWinRate) {
                bestWinRate = winRate;
                bestTrialIdx = trialIdx;
            }

            String trialLabel = infinite
                    ? String.format("Trial %d", trialIdx + 1)
                    : String.format("Trial %d/%d", trialIdx + 1, trials);
            if (opponentList.size() > 1) {
                System.out.printf("  %s (%s): avgWR=%.1f%% (%s)  [best: %.1f%% @ #%d]  (%.1fs)%n",
                        trialLabel, source, winRate * 100,
                        perOpp,
                        bestWinRate * 100, bestTrialIdx, matchTime / 1000.0);
            } else {
                System.out.printf("  %s (%s): WR=%.1f%%  [best: %.1f%% @ #%d]  (%.1fs)%n",
                        trialLabel, source, winRate * 100,
                        bestWinRate * 100, bestTrialIdx, matchTime / 1000.0);
            }

            // Save after every trial so Ctrl+C never loses completed work.
            // saveOrUpdate replaces the in-progress entry by runId (no duplicates).
            long elapsed = System.currentTimeMillis() - sweepStartMs;
            SweepResult.SweepRun inProgress = new SweepResult.SweepRun(
                    runId, creatorId, oppDisplay, games,
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
        printTopResults(newTrials, priorTrials, 10, creatorEntry, opponentList);
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
                                         List<SweepResult.Trial> priorTrials, int topN,
                                         EngineRegistryEntry creatorEntry,
                                         List<String> opponentList) {
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

        // Print as engines.json config snippet for the best vector.
        // Tier and iterations are derived from the registry entry of the creator engine used,
        // so creator-balanced-tuned correctly gets tier=balanced and iterations=5000.
        if (!all.isEmpty()) {
            SweepResult.Trial best = all.get(0);
            String tier = creatorEntry.tier();
            int registryIterations = creatorEntry.config().iterations;

            // Derive tuned ID: replace the base creator ID suffix, e.g.
            // "creator-fast" → "creator-fast-tuned", "creator-balanced" → "creator-balanced-tuned"
            String tunedId = creatorEntry.id() + "-tuned";

            String oppDesc = opponentList.size() == 1
                    ? opponentList.get(0)
                    : String.join("+", opponentList);

            System.out.println("=== engines.json CONFIG FOR BEST VECTOR ===");
            System.out.println("{");
            System.out.printf("  \"id\": \"%s\",%n", tunedId);
            System.out.printf("  \"engineClass\": \"creator\",%n");
            System.out.printf("  \"description\": \"Creator Engine — TPE-tuned %s (WR=%.1f%% vs %s)\",%n",
                    tier, best.winRate * 100, oppDesc);
            System.out.printf("  \"default\": false,%n");
            System.out.printf("  \"tier\": \"%s\",%n", tier);
            System.out.printf("  \"config\": {%n");
            System.out.printf("    \"iterations\": \"%d\"", registryIterations);
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
        System.out.println("  --trials N           Total evaluation trials (default: 100)");
        System.out.println("  --infinite           Run indefinitely until Ctrl+C (overrides --trials)");
        System.out.println("  --games N            Games per trial match (default: 50)");
        System.out.println("  --creator <id>       Creator engine ID (default: creator-fast)");
        System.out.println("  --opponent <id>      Single opponent engine ID (default: heuristic-ev-default)");
        System.out.println("  --opponents <a,b,c>  Comma-separated opponent list; each trial plays ALL, WR averaged");
        System.out.println("  --iterations N       Override iterations (0 = registry default)");
        System.out.println("  --startup N          Random trials before TPE (default: 20)");
        System.out.println("  --gamma F            TPE good/bad split (default: 0.25)");
        System.out.println("  --seed N             Random seed for reproducibility");
        System.out.println("  --resume             Continue from all trials in sweep-results.json");
        System.out.println("  --no-default         Skip evaluating default params as trial 0");
        System.out.println("  --verbose            Per-game results, param deltas, match details");
        System.out.println("  --help               Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Quick smoke test (~1 min)");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain --trials 5 --games 20");
        System.out.println();
        System.out.println("  # Run indefinitely (overnight), stop with Ctrl+C");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain --infinite --games 50");
        System.out.println();
        System.out.println("  # Overnight balanced run against multiple opponents");
        System.out.println("  java -cp \"out:src:gson-2.11.0.jar\" h2h.SweepMain \\");
        System.out.println("    --creator creator-balanced --infinite --games 50 \\");
        System.out.println("    --opponents heuristic-ev-default,flat-mc-fast,mcts-v1-depth3,mcts-v1-fast");
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
