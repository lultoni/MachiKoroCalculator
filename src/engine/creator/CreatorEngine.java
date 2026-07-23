package engine.creator;

import calcs.Calcs;
import calcs.RankEntry;
import core.BitState;
import core.BitStateTranslator;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;
import engine.mcts.BitBoltzmannRollout;
import engine.mcts.BitGreedyRollout;
import engine.mcts.BitMctsRollout;
import engine.mcts.BitRolloutFn;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Arrays;
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

    /** Samples per reroll outcome for Funkturm evaluation. Small to keep latency <300ms. */
    private static final int FUNKTURM_SAMPLES_PER_OUTCOME = 10;

    @Override
    public String id() { return "creator"; }

    @Override
    public String description() { return "Creator Engine — heuristic-seeded Flat Monte Carlo"; }

    /**
     * Keep if MC win rate of this roll >= expected MC win rate over reroll distribution.
     * Uses the same rollout policy as the main evaluation (configurable via config).
     * Runs {@link #FUNKTURM_SAMPLES_PER_OUTCOME} rollouts per outcome for speed.
     */
    @Override
    public boolean decideFunkturm(engine.TurnPlan plan, GameState state, int playerIndex,
                                   int roll, boolean isDoubles, engine.EngineConfig config) {
        BitState preRoll = BitState.fromGameState(state);
        int[] supply = preRoll.buildSupplyArray();
        boolean twoDice = plan.diceCount == 2;
        int n = preRoll.getNumPlayers();
        int nextPlayer = (playerIndex + 1) % n;
        BitRolloutFn rolloutFn = selectRolloutFn(config);

        // Keep win rate
        BitState afterKeep = preRoll.copy();
        afterKeep.applyRollIncome(playerIndex, roll);
        double keepWr = sampleWinRate(afterKeep, supply, nextPlayer, playerIndex,
                FUNKTURM_SAMPLES_PER_OUTCOME, rolloutFn);

        // Expected win rate over reroll distribution
        double rerollWr = 0.0;
        if (!twoDice) {
            for (int r = 1; r <= 6; r++) {
                BitState s = preRoll.copy();
                s.applyRollIncome(playerIndex, r);
                rerollWr += core.CardIncome.P1[r] * sampleWinRate(s, supply, nextPlayer, playerIndex,
                        FUNKTURM_SAMPLES_PER_OUTCOME, rolloutFn);
            }
        } else {
            for (int r = 2; r <= 12; r++) {
                double prob = core.CardIncome.P2[r];
                if (prob <= 0) continue;
                BitState s = preRoll.copy();
                s.applyRollIncome(playerIndex, r);
                rerollWr += prob * sampleWinRate(s, supply, nextPlayer, playerIndex,
                        FUNKTURM_SAMPLES_PER_OUTCOME, rolloutFn);
            }
        }

        return keepWr >= rerollWr;
    }

    private static double sampleWinRate(BitState bs, int[] supply, int nextPlayer, int perspective,
                                        int n, BitRolloutFn rolloutFn) {
        double wins = 0.0;
        for (int i = 0; i < n; i++) {
            wins += rolloutFn.simulate(bs, supply, nextPlayer, perspective);
        }
        return wins / n;
    }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = Calcs.optimalDiceCount(BitState.fromGameState(state), playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        return SimulationEngine.staticPlanWithInstantWinPriority(diceCount, result, state, playerIndex, elapsed);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        int coins = state.getPlayers()[playerIndex].getCoins();
        int n = state.getPlayers().length;
        int nextPlayer = (playerIndex + 1) % n;

        SupplyTracker baseSupply = SupplyTracker.fromGameState(state);
        BitState rootBS = BitState.fromGameState(state);
        int[] rootSupplyArr = rootBS.buildSupplyArray();

        // ================================================================
        // Phase 1: Heuristic seeding (still uses GameState for CreatorScorer)
        // ================================================================

        List<CreatorScorer.ScoredCandidate> scored = CreatorScorer.scoreAll(state, playerIndex, baseSupply, config);
        long phase1Ms = System.currentTimeMillis() - startTime;

        // Build candidate options with BitState post-purchase states for MC phase
        List<CandidateOption> candidates = new ArrayList<>();
        for (CreatorScorer.ScoredCandidate sc : scored) {
            boolean isSave = sc.card == RankEntry.WAIT_SENTINEL;
            boolean isInstantWin = sc.compositeScore == Double.MAX_VALUE;

            if (isSave) {
                CandidateOption opt = new CandidateOption(sc.card, rootBS, rootSupplyArr, false, true,
                        sc.compositeScore, sc.metrics, sc.factors, true, sc.activationGuard);
                candidates.add(opt);
                continue;
            }

            boolean canAfford = sc.affordable;

            // Build BitState post-purchase state
            BitState childBS = rootBS.copy();
            int[] childSupply = rootSupplyArr;

            int normalIdx = BitStateTranslator.normalCardIndex(sc.card.getId());
            int purpleIdx = BitStateTranslator.purpleCardIndex(sc.card.getId());
            int landmarkIdx = BitStateTranslator.landmarkIndex(sc.card.getId());

            if (normalIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.addCard(playerIndex, normalIdx);
                childSupply = Arrays.copyOf(rootSupplyArr, rootSupplyArr.length);
                childSupply[normalIdx]--;
            } else if (purpleIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.setPurple(playerIndex, purpleIdx);
            } else if (landmarkIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.setLandmark(playerIndex, landmarkIdx);
            }

            if (isInstantWin) {
                CandidateOption opt = new CandidateOption(sc.card, childBS, childSupply, true, false,
                        sc.compositeScore, sc.metrics, sc.factors, canAfford, sc.activationGuard);
                opt.wins = 1;
                opt.samples = 1;
                candidates.add(opt);
                continue;
            }

            CandidateOption opt = new CandidateOption(sc.card, childBS, childSupply, false, false,
                    sc.compositeScore, sc.metrics, sc.factors, canAfford, sc.activationGuard);
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
            BitRolloutFn rolloutFn = selectRolloutFn(config);

            // Separate affordable non-save candidates for MC sampling.
            // Save is excluded: its heuristic score (0.0) competes against MC win rates,
            // so save only wins when MC shows all cards have <0% marginal value.
            // Including save in MC would make it indistinguishable from card purchases
            // because the rollout immediately buys cards on the player's next turn.
            // Cards with activationGuard == 0.0 are also excluded: they can't activate
            // on own turns (7-12 without Bahnhof) and would get artificially inflated
            // MC win rates from the rollout policy eventually buying Bahnhof.
            List<CandidateOption> mcCandidates = new ArrayList<>();
            for (CandidateOption c : candidates) {
                if (!c.unaffordable && !c.isInstantWin && !c.isSave
                        && c.activationGuard > 0.0) mcCandidates.add(c);
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
                               int nextPlayer, int perspective, BitRolloutFn rolloutFn) {
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

    /** Package-private for use by {@link CreatorContinuousWorker}. */
    void runSamples(CandidateOption candidate, int numSamples,
                            int nextPlayer, int perspective, BitRolloutFn rolloutFn) {
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

    /** Package-private for use by {@link CreatorContinuousWorker}. */
    BitRolloutFn selectRolloutFn(EngineConfig config) {
        // Default: creator (BitCreatorRollout). H2H benchmarks (7.46) show CreatorRollout v3
        // matches or beats GreedyRollout across all opponents (+4% vs MCTS v1, +1% vs
        // heuristic-ev, tie vs Flat MC). CreatorRollout adds coverage bonus (portfolio
        // diversification) and save-toward-landmark (prevents wasteful marginal purchases).
        // Greedy and uniform are retained as configurable alternatives.
        String policy = config.extra != null ? config.extra.getOrDefault("rolloutPolicy", "creator") : "creator";
        switch (policy) {
            case "creator":   return BitCreatorRollout::simulate;
            case "boltzmann": {
                double temp = CreatorScorer.readDouble(config, "rolloutTemperature", 0.7);
                return BitBoltzmannRollout.withTemperature(temp);
            }
            case "uniform":   return BitMctsRollout::simulateBit;
            case "greedy":    return BitGreedyRollout::simulateBit;
            default:          return BitCreatorRollout::simulate;
        }
    }

    // =====================================================================
    // Result construction
    // =====================================================================

    /** Package-private for use by {@link CreatorContinuousWorker}. */
    EngineResult buildResult(List<CandidateOption> candidates, int coins,
                                     int iterationsUsed, long computeTimeMs,
                                     boolean usedMC, long phase1Ms) {
        List<EngineResult.Option> options = new ArrayList<>();

        // Score assignment strategy:
        // - Instant-win: 1.0
        // - MC-sampled candidates: MC win rate (already in [0,1])
        // - Save (when MC active): 0.0 (last resort — only wins if no card has positive MC value)
        // - Heuristic-only mode: linear normalization of raw heuristic scores to [0,1]
        //   using max-min range. Save at 0.0 naturally beats negative-scored cards and loses
        //   to positive-scored cards. (Previously used softmax which compressed differences.)
        // - Unaffordable/guarded when MC is active: -1.0
        boolean heuristicOnly = !usedMC;

        // For heuristic-only: linear normalization [min, max] → [0, 1]
        double heuristicMax = Double.NEGATIVE_INFINITY;
        double heuristicMin = Double.POSITIVE_INFINITY;
        if (heuristicOnly) {
            for (CandidateOption c : candidates) {
                if (c.isInstantWin) continue;
                if (c.activationGuard <= 0.0 && !c.isSave) continue; // exclude guarded
                double s = c.heuristicScore;
                if (s > heuristicMax) heuristicMax = s;
                if (s < heuristicMin) heuristicMin = s;
            }
        }
        double heuristicRange = heuristicMax - heuristicMin;

        for (CandidateOption c : candidates) {
            double finalScore;
            if (c.isInstantWin) {
                finalScore = 1.0;
            } else if (usedMC && c.samples > 0) {
                finalScore = c.winRate();
            } else if (heuristicOnly) {
                // Apply activation guard: cards that can't activate score below save
                if (c.activationGuard <= 0.0 && !c.isSave) {
                    finalScore = -1.0;
                } else if (heuristicRange > 1e-12) {
                    // Linear normalization: min → 0, max → 1
                    finalScore = (c.heuristicScore - heuristicMin) / heuristicRange;
                } else {
                    // All scores identical — equal ranking
                    finalScore = 0.5;
                }
            } else if (c.isSave) {
                // Save when MC is active: score 0.0 (last resort)
                finalScore = 0.0;
            } else {
                // Unaffordable or activation-guarded cards when MC is active: score -1.0
                // Negative score ensures they rank below save (0.0)
                finalScore = -1.0;
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

        // Sort using standard comparator (score DESC, save last, landmarks first, cost DESC)
        options.sort(EngineResult.OPTION_COMPARATOR);

        // Confidence = margin between top two
        double confidence = 0.0;
        if (options.size() >= 2) {
            double top = options.get(0).score;
            double second = options.get(1).score;
            confidence = Math.max(0.0, Math.min(1.0, top - second));
        }

        String debugInfo = String.format("creator | %d options | phase1=%dms | mc=%s | total=%dms",
                options.size(), phase1Ms, usedMC ? iterationsUsed + " iter" : "off", computeTimeMs);

        return new EngineResult(options, confidence, iterationsUsed, computeTimeMs, debugInfo);
    }

    // =====================================================================
    // Internal candidate holder
    // =====================================================================

    /** Package-private for use by {@link CreatorContinuousWorker}. */
    static final class CandidateOption {
        final Project card;
        final BitState postState;
        final int[] postSupply;
        final boolean isInstantWin;
        final boolean isSave;
        final double heuristicScore;
        final Map<String, String> metrics;
        final List<EngineResult.ExplanationFactor> structuredFactors;
        final boolean affordable;
        final double activationGuard;
        boolean unaffordable = false;
        int samples = 0;
        double wins = 0.0;

        CandidateOption(Project card, BitState postState, int[] postSupply,
                        boolean isInstantWin, boolean isSave, double heuristicScore,
                        Map<String, String> metrics,
                        List<EngineResult.ExplanationFactor> structuredFactors,
                        boolean affordable, double activationGuard) {
            this.card = card;
            this.postState = postState;
            this.postSupply = postSupply;
            this.isInstantWin = isInstantWin;
            this.isSave = isSave;
            this.heuristicScore = heuristicScore;
            this.metrics = metrics;
            this.structuredFactors = structuredFactors;
            this.affordable = affordable;
            this.activationGuard = activationGuard;
        }

        double winRate() {
            return samples > 0 ? wins / samples : 0.0;
        }
    }
}
