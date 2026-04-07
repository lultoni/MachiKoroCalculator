package engine.creator;

import calcs.Calcs;
import calcs.RankEntry;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;
import engine.mcts.BoltzmannRollout;
import engine.mcts.GreedyRollout;
import engine.mcts.MctsRollout;
import engine.mcts.RolloutFn;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Creator Engine — heuristic-seeded Flat Monte Carlo.
 *
 * <p>A two-phase engine that combines the Creator's multi-dimensional heuristic scoring
 * ({@link CreatorScorer}) with Monte Carlo validation using the Creator's own rollout
 * policy ({@link CreatorRollout}).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Phase 1 — Heuristic seeding</b> (~2-5ms): Score all candidates with
 *       CreatorScorer. This produces a pre-ranking and allocation weights.</li>
 *   <li><b>Phase 2 — MC validation</b> (budget-dependent): Run rollouts biased toward
 *       heuristic favorites. Top-3 get 50% of budget, next-5 get 30%, rest get 20%.
 *       Uses CreatorRollout by default (configurable).</li>
 * </ol>
 *
 * <h2>Anytime property</h2>
 * <ul>
 *   <li>If {@code timeBudgetMs > 0}: runs MC in 100-iteration rounds until deadline.</li>
 *   <li>If {@code iterations > 0}: runs exactly that many MC iterations.</li>
 *   <li>If both are 0: returns heuristic-only result (&lt;5ms).</li>
 *   <li>Result is always valid — the heuristic provides a baseline even with 0 MC iterations.</li>
 * </ul>
 *
 * <h2>Configurable parameters</h2>
 * All 31 scoring parameters are tunable via {@link EngineConfig#extra}. See
 * {@link CreatorScorer} for the full parameter list. Additionally:
 * <ul>
 *   <li>{@code rolloutPolicy}: "creator" (default), "greedy", "uniform", "boltzmann"</li>
 *   <li>{@code rolloutTemperature}: Boltzmann temperature (default "0.7")</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Stateless between calls. Each evaluate() call is self-contained.
 */
public final class CreatorEngine implements SimulationEngine {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    @Override
    public String id() { return "creator"; }

    @Override
    public String description() { return "Creator Engine — heuristic-seeded Flat Monte Carlo"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = Calcs.optimalDiceCount(state, playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        EngineResult.Option top = result.topRecommendation();
        Project purchase = "_wait_".equals(top.project.getId()) ? null : top.project;
        long elapsed = System.currentTimeMillis() - start;
        return TurnPlan.staticPlan(diceCount,
                purchase != null ? purchase : RankEntry.WAIT_SENTINEL,
                top.score, result.iterationsUsed, elapsed);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;
        int nextPlayer = (playerIndex + 1) % n;

        SupplyTracker baseSupply = SupplyTracker.fromGameState(state);

        // ================================================================
        // Phase 1: Heuristic seeding
        // ================================================================

        List<CreatorScorer.ScoredCandidate> scored = CreatorScorer.scoreAll(state, playerIndex, baseSupply, config);
        long phase1Ms = System.currentTimeMillis() - startTime;

        // Build candidate options for MC phase
        List<CandidateOption> candidates = new ArrayList<>();
        for (CreatorScorer.ScoredCandidate sc : scored) {
            boolean isSave = sc.card == RankEntry.WAIT_SENTINEL;
            boolean isInstantWin = sc.compositeScore == Double.MAX_VALUE;

            if (isSave) {
                // Save option: no post-state needed for MC (uses base state)
                CandidateOption opt = new CandidateOption(sc.card, state, baseSupply, false,
                        sc.compositeScore, sc.metrics, sc.factors, true);
                candidates.add(opt);
                continue;
            }

            boolean canAfford = sc.affordable;

            // Build post-purchase state for MC sampling
            GameState childState = state.copy();
            Player childActive = childState.getPlayers()[playerIndex];
            if (canAfford) {
                childActive.setCoins(childActive.getCoins() - sc.card.getCost());
            }
            childActive.addProject(sc.card);
            SupplyTracker childSupply = sc.card.isIs_grossprojekt()
                    ? baseSupply : baseSupply.withPurchase(sc.card.getId());

            if (isInstantWin) {
                CandidateOption opt = new CandidateOption(sc.card, childState, childSupply, true,
                        sc.compositeScore, sc.metrics, sc.factors, canAfford);
                opt.wins = 1;
                opt.samples = 1;
                candidates.add(opt);
                continue;
            }

            CandidateOption opt = new CandidateOption(sc.card, childState, childSupply, false,
                    sc.compositeScore, sc.metrics, sc.factors, canAfford);
            opt.unaffordable = !canAfford;
            candidates.add(opt);
        }

        // ================================================================
        // Phase 2: Monte Carlo validation
        // ================================================================

        int iterationsUsed = 0;
        boolean usedMC = false;

        // Determine budget
        int iterationBudget = config.iterations;
        int timeBudgetMs = config.timeBudgetMs;

        if (iterationBudget > 0 || timeBudgetMs > 0) {
            usedMC = true;
            RolloutFn rolloutFn = selectRolloutFn(config);

            // Separate affordable candidates for MC sampling
            List<CandidateOption> mcCandidates = new ArrayList<>();
            for (CandidateOption c : candidates) {
                if (!c.unaffordable && !c.isInstantWin) mcCandidates.add(c);
            }

            if (!mcCandidates.isEmpty()) {
                // Sort by heuristic score descending for allocation
                mcCandidates.sort(Comparator.comparingDouble((CandidateOption c) -> c.heuristicScore).reversed());

                if (timeBudgetMs > 0) {
                    // Anytime mode: run in rounds until deadline
                    long deadline = startTime + timeBudgetMs;
                    int roundSize = 100;
                    while (System.currentTimeMillis() < deadline) {
                        int allocated = allocateAndRun(mcCandidates, roundSize, nextPlayer, playerIndex, rolloutFn);
                        iterationsUsed += allocated;
                        // Re-sort by MC win rate for next round's allocation
                        mcCandidates.sort(Comparator.comparingDouble((CandidateOption c) -> c.winRate()).reversed());
                    }
                } else {
                    // Fixed iteration budget
                    iterationsUsed = allocateAndRun(mcCandidates, iterationBudget, nextPlayer, playerIndex, rolloutFn);
                }
            }
        }

        // ================================================================
        // Build result
        // ================================================================

        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(candidates, coins, iterationsUsed, computeTimeMs, usedMC, phase1Ms);
    }

    // =====================================================================
    // MC allocation
    // =====================================================================

    /**
     * Allocates iterations across candidates using biased 50/30/20 strategy.
     * Assumes candidates are sorted by priority (heuristic or MC win rate).
     *
     * @return total iterations actually run
     */
    private int allocateAndRun(List<CandidateOption> candidates, int totalIterations,
                               int nextPlayer, int perspective, RolloutFn rolloutFn) {
        int size = candidates.size();
        if (size == 0) return 0;

        int top3Count = Math.min(3, size);
        int next5Count = Math.min(5, size - top3Count);
        int restCount = size - top3Count - next5Count;

        // Budget split: 50% top-3, 30% next-5, 20% rest
        int top3Budget = totalIterations / 2;
        int next5Budget = (int) (totalIterations * 0.3);
        int restBudget = totalIterations - top3Budget - next5Budget;

        int totalRun = 0;

        // Top 3
        if (top3Count > 0) {
            int perOption = Math.max(1, top3Budget / top3Count);
            for (int i = 0; i < top3Count; i++) {
                runSamples(candidates.get(i), perOption, nextPlayer, perspective, rolloutFn);
                totalRun += perOption;
            }
        }

        // Next 5
        if (next5Count > 0) {
            int perOption = Math.max(1, next5Budget / next5Count);
            for (int i = top3Count; i < top3Count + next5Count; i++) {
                runSamples(candidates.get(i), perOption, nextPlayer, perspective, rolloutFn);
                totalRun += perOption;
            }
        }

        // Rest
        if (restCount > 0) {
            int perOption = Math.max(1, restBudget / restCount);
            for (int i = top3Count + next5Count; i < size; i++) {
                runSamples(candidates.get(i), perOption, nextPlayer, perspective, rolloutFn);
                totalRun += perOption;
            }
        }

        return totalRun;
    }

    private void runSamples(CandidateOption candidate, int numSamples,
                            int nextPlayer, int perspective, RolloutFn rolloutFn) {
        if (candidate.isInstantWin) return;
        for (int i = 0; i < numSamples; i++) {
            double result = rolloutFn.simulate(candidate.postState, candidate.postSupply,
                    nextPlayer, perspective);
            candidate.samples++;
            candidate.wins += result;
        }
    }

    // =====================================================================
    // Rollout policy selection
    // =====================================================================

    private RolloutFn selectRolloutFn(EngineConfig config) {
        String policy = config.extra != null ? config.extra.getOrDefault("rolloutPolicy", "creator") : "creator";
        switch (policy) {
            case "greedy":    return GreedyRollout::simulate;
            case "uniform":   return MctsRollout::simulate;
            case "boltzmann": {
                double temp = CreatorScorer.readDouble(config, "rolloutTemperature", 0.7);
                return BoltzmannRollout.withTemperature(temp);
            }
            default:          return CreatorRollout::simulate;
        }
    }

    // =====================================================================
    // Result construction
    // =====================================================================

    private EngineResult buildResult(List<CandidateOption> candidates, int coins,
                                     int iterationsUsed, long computeTimeMs,
                                     boolean usedMC, long phase1Ms) {
        List<EngineResult.Option> options = new ArrayList<>();

        for (CandidateOption c : candidates) {
            double finalScore;
            if (c.isInstantWin) {
                finalScore = Double.MAX_VALUE;
            } else if (usedMC && c.samples > 0) {
                finalScore = c.winRate();
            } else {
                finalScore = c.heuristicScore;
            }

            boolean isSave = c.card == RankEntry.WAIT_SENTINEL;
            boolean affordable = isSave || c.affordable;

            // Enrich metrics with MC data if available
            java.util.LinkedHashMap<String, String> metrics = new java.util.LinkedHashMap<>(c.metrics);
            if (usedMC && c.samples > 0) {
                metrics.put("mcWinRate", String.format("%.4f", c.winRate()));
                metrics.put("mcSamples", String.valueOf(c.samples));
            }
            metrics.put("heuristicScore", String.format("%.4f", c.heuristicScore));

            options.add(new EngineResult.Option(c.card, finalScore, List.of(),
                    c.structuredFactors, null, metrics, affordable));
        }

        // Sort: descending score, save last on ties
        options.sort(Comparator
                .comparingDouble((EngineResult.Option o) -> o.score).reversed()
                .thenComparing(o -> "_wait_".equals(o.project.getId()) ? 1 : 0));

        // Confidence = margin between top two
        double confidence = 0.0;
        if (options.size() >= 2) {
            double top = options.get(0).score;
            double second = options.get(1).score;
            if (top != Double.MAX_VALUE) {
                confidence = Math.max(0.0, Math.min(1.0, top - second));
            } else {
                confidence = 1.0;
            }
        }

        String debugInfo = String.format("creator | %d options | phase1=%dms | mc=%s | total=%dms",
                options.size(), phase1Ms, usedMC ? iterationsUsed + " iter" : "off", computeTimeMs);

        return new EngineResult(options, confidence, iterationsUsed, computeTimeMs, debugInfo);
    }

    // =====================================================================
    // Internal candidate holder
    // =====================================================================

    private static final class CandidateOption {
        final Project card;
        final GameState postState;
        final SupplyTracker postSupply;
        final boolean isInstantWin;
        final double heuristicScore;
        final Map<String, String> metrics;
        final List<EngineResult.ExplanationFactor> structuredFactors;
        final boolean affordable;
        boolean unaffordable = false;
        int samples = 0;
        double wins = 0.0;

        CandidateOption(Project card, GameState postState, SupplyTracker postSupply,
                        boolean isInstantWin, double heuristicScore,
                        Map<String, String> metrics,
                        List<EngineResult.ExplanationFactor> structuredFactors,
                        boolean affordable) {
            this.card = card;
            this.postState = postState;
            this.postSupply = postSupply;
            this.isInstantWin = isInstantWin;
            this.heuristicScore = heuristicScore;
            this.metrics = metrics;
            this.structuredFactors = structuredFactors;
            this.affordable = affordable;
        }

        double winRate() {
            return samples > 0 ? wins / samples : 0.0;
        }
    }
}
