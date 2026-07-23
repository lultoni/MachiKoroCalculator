package engine.flat;

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
import engine.mcts.BitMctsRollout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Flat Monte Carlo engine — the simplest possible search-based engine.
 *
 * <p>For each purchase option, runs N complete random-rollout games and ranks
 * options by observed win rate. No tree structure, no UCT, no
 * selection/expansion/backpropagation — just pure sampling.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Enumerate all valid purchase options (same logic as BuyDecisionNode).</li>
 *   <li><b>Survey phase</b> (20% of budget): allocate iterations evenly across all options.</li>
 *   <li><b>Focus phase</b> (80% of budget): concentrate remaining iterations on top-K
 *       candidates (sorted by win rate after survey), giving more samples to the most
 *       promising options.</li>
 *   <li>Build {@link EngineResult} with win rates as scores.</li>
 * </ol>
 *
 * <h2>Time budget</h2>
 * When {@code timeBudgetMs > 0}, the engine runs in deadline mode: a brief survey phase
 * (20 samples per option), then focus-phase rounds of 50 samples on top-K until the
 * deadline. The anytime property means the result is always valid at any stopping point.
 *
 * <h2>Purpose</h2>
 * Serves as a <b>lower bound</b> for what tree-based search should beat. Also useful
 * for calibrating H2H: "how much does UCT help vs. just sampling?"
 *
 * <h2>Thread safety</h2>
 * Stateless between calls. Each evaluate() call is self-contained.
 */
public final class FlatMcEngine implements SimulationEngine {

    /** Samples per reroll outcome for Funkturm evaluation. Small to keep latency <300ms. */
    private static final int FUNKTURM_SAMPLES_PER_OUTCOME = 10;

    /**
     * Keep if MC win rate of this roll >= expected MC win rate over reroll distribution.
     * Runs {@link #FUNKTURM_SAMPLES_PER_OUTCOME} rollouts per outcome — a speed/accuracy
     * tradeoff (full budget would be 350-600 rollouts and ~2-3s).
     */
    @Override
    public boolean decideFunkturm(engine.TurnPlan plan, GameState state, int playerIndex,
                                   int roll, boolean isDoubles, engine.EngineConfig config) {
        BitState preRoll = BitState.fromGameState(state);
        int[] supply = preRoll.buildSupplyArray();
        boolean twoDice = plan.diceCount == 2;
        int n = preRoll.getNumPlayers();
        int nextPlayer = (playerIndex + 1) % n;

        // Keep win rate
        BitState afterKeep = preRoll.copy();
        afterKeep.applyRollIncome(playerIndex, roll);
        double keepWr = sampleWinRate(afterKeep, supply, nextPlayer, playerIndex, FUNKTURM_SAMPLES_PER_OUTCOME);

        // Expected win rate over reroll distribution
        double rerollWr = 0.0;
        if (!twoDice) {
            for (int r = 1; r <= 6; r++) {
                BitState s = preRoll.copy();
                s.applyRollIncome(playerIndex, r);
                rerollWr += core.CardIncome.P1[r] * sampleWinRate(s, supply, nextPlayer, playerIndex, FUNKTURM_SAMPLES_PER_OUTCOME);
            }
        } else {
            for (int r = 2; r <= 12; r++) {
                double prob = core.CardIncome.P2[r];
                if (prob <= 0) continue;
                BitState s = preRoll.copy();
                s.applyRollIncome(playerIndex, r);
                rerollWr += prob * sampleWinRate(s, supply, nextPlayer, playerIndex, FUNKTURM_SAMPLES_PER_OUTCOME);
            }
        }

        return keepWr >= rerollWr;
    }

    private static double sampleWinRate(BitState bs, int[] supply, int nextPlayer, int perspective, int n) {
        double wins = 0.0;
        for (int i = 0; i < n; i++) {
            wins += BitMctsRollout.simulateBit(bs, supply, nextPlayer, perspective);
        }
        return wins / n;
    }

    /** Top-K options to focus on after survey phase. */
    private static final int FOCUS_TOP_K = 5;

    /** Samples per option per round in time-budget mode. */
    private static final int TIME_ROUND_SIZE = 50;

    @Override
    public String id() { return "flat-mc"; }

    @Override
    public String description() { return "Flat Monte Carlo — pure sampling, no tree search"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = calcs.Calcs.optimalDiceCount(BitState.fromGameState(state), playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        return SimulationEngine.staticPlanWithInstantWinPriority(diceCount, result, state, playerIndex, elapsed);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        BitState bs = BitState.fromGameState(state);
        int coins = bs.getCoins(playerIndex);
        int n = bs.getNumPlayers();
        int nextPlayer = (playerIndex + 1) % n;
        int[] rootSupply = bs.buildSupplyArray();

        // ---- Enumerate purchase options ----
        List<CandidateOption> candidates = enumerateOptions(bs, rootSupply, playerIndex, coins);

        if (candidates.isEmpty()) {
            // Should never happen — save is always present. Defensive fallback.
            candidates.add(new CandidateOption(RankEntry.WAIT_SENTINEL, bs, rootSupply, false));
        }

        int usedIterations;

        if (config.timeBudgetMs > 0) {
            // ---- Time-budget mode: rounds until deadline ----
            usedIterations = evaluateWithTimeBudget(candidates, nextPlayer, playerIndex,
                    startTime + config.timeBudgetMs);
        } else {
            // ---- Iteration mode (original path) ----
            int totalIterations = config.iterations > 0 ? config.iterations : 500;
            usedIterations = evaluateWithIterations(candidates, nextPlayer, playerIndex, totalIterations);
        }

        // ---- Build result ----
        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(candidates, coins, n, usedIterations, computeTimeMs);
    }

    /**
     * Runs fixed-iteration survey + focus phases.
     * @return total iterations used
     */
    private int evaluateWithIterations(List<CandidateOption> candidates, int nextPlayer,
                                        int perspective, int totalIterations) {
        // Survey phase: 20% of budget, evenly distributed
        int surveyBudget = Math.max(candidates.size(), totalIterations / 5);
        int perOptionSurvey = Math.max(1, surveyBudget / candidates.size());

        for (CandidateOption c : candidates) {
            if (!c.unaffordable) runSamples(c, perOptionSurvey, nextPlayer, perspective);
        }

        int usedIterations = perOptionSurvey * candidates.size();
        int remaining = totalIterations - usedIterations;

        // Focus phase: 80% of budget on top-K
        if (remaining > 0 && candidates.size() > 1) {
            candidates.sort(Comparator.comparingDouble(CandidateOption::winRate).reversed());
            int topK = Math.min(FOCUS_TOP_K, candidates.size());
            int perOptionFocus = Math.max(1, remaining / topK);

            for (int i = 0; i < topK; i++) {
                runSamples(candidates.get(i), perOptionFocus, nextPlayer, perspective);
            }
            usedIterations += perOptionFocus * topK;
        }

        return usedIterations;
    }

    /**
     * Runs deadline-based survey + focus phases. Survey: 20 samples per option.
     * Focus: rounds of {@link #TIME_ROUND_SIZE} on top-K until deadline.
     *
     * @return total iterations used
     */
    private int evaluateWithTimeBudget(List<CandidateOption> candidates, int nextPlayer,
                                        int perspective, long deadline) {
        // Survey phase: fixed small sample per option
        int perOptionSurvey = 20;
        for (CandidateOption c : candidates) {
            if (!c.unaffordable) runSamples(c, perOptionSurvey, nextPlayer, perspective);
        }
        int usedIterations = perOptionSurvey * candidates.size();

        // Focus phase: rounds until deadline
        if (candidates.size() > 1) {
            while (System.currentTimeMillis() < deadline) {
                candidates.sort(Comparator.comparingDouble(CandidateOption::winRate).reversed());
                int topK = Math.min(FOCUS_TOP_K, candidates.size());
                for (int i = 0; i < topK && System.currentTimeMillis() < deadline; i++) {
                    runSamples(candidates.get(i), TIME_ROUND_SIZE, nextPlayer, perspective);
                    usedIterations += TIME_ROUND_SIZE;
                }
            }
        }

        return usedIterations;
    }

    // -------------------------------------------------------------------------
    // Option enumeration (BitState-native)
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link FlatMcContinuousWorker}. */
    List<CandidateOption> enumerateOptions(BitState bs, int[] supply, int playerIndex, int coins) {
        List<CandidateOption> candidates = new ArrayList<>();

        // Save option
        candidates.add(new CandidateOption(RankEntry.WAIT_SENTINEL, bs, supply, false));

        // Non-landmark cards via CANDIDATE_ITERATION_ORDER
        for (int entry : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            if (entry < BitStateTranslator.NUM_NORMAL_CARDS) {
                // Normal card
                int ci = entry;
                if (supply[ci] <= 0) continue;
                int cost = BitStateTranslator.NORMAL_CARD_COSTS[ci];
                boolean canAfford = coins >= cost;

                BitState childBS = bs.copy();
                if (canAfford) {
                    childBS.setCoins(playerIndex, coins - cost);
                }
                childBS.addCard(playerIndex, ci);
                int[] childSupply = Arrays.copyOf(supply, supply.length);
                childSupply[ci]--;

                CandidateOption opt = new CandidateOption(
                        BitStateTranslator.NORMAL_CARD_PROJECTS[ci], childBS, childSupply, false);
                opt.unaffordable = !canAfford;
                candidates.add(opt);
            } else {
                // Purple card
                int pi = entry - BitStateTranslator.NUM_NORMAL_CARDS;
                if (bs.hasPurple(playerIndex, pi)) continue; // uniqueness
                int cost = BitStateTranslator.PURPLE_CARD_COSTS[pi];
                boolean canAfford = coins >= cost;

                BitState childBS = bs.copy();
                if (canAfford) {
                    childBS.setCoins(playerIndex, coins - cost);
                }
                childBS.setPurple(playerIndex, pi);

                CandidateOption opt = new CandidateOption(
                        BitStateTranslator.PURPLE_CARD_PROJECTS[pi], childBS, supply, false);
                opt.unaffordable = !canAfford;
                candidates.add(opt);
            }
        }

        // Landmarks
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (bs.hasLandmark(playerIndex, li)) continue;
            int cost = BitStateTranslator.LANDMARK_COSTS[li];
            boolean canAfford = coins >= cost;

            BitState childBS = bs.copy();
            if (canAfford) {
                childBS.setCoins(playerIndex, coins - cost);
            }
            childBS.setLandmark(playerIndex, li);

            // Check instant win (only relevant if affordable)
            if (canAfford && childBS.hasWon(playerIndex)) {
                CandidateOption winOpt = new CandidateOption(
                        ProjectLoader.getProject(BitStateTranslator.LANDMARK_IDS[li]).orElse(null),
                        childBS, supply, true);
                winOpt.wins = 1;
                winOpt.samples = 1;
                candidates.add(winOpt);
                continue;
            }

            CandidateOption opt = new CandidateOption(
                    ProjectLoader.getProject(BitStateTranslator.LANDMARK_IDS[li]).orElse(null),
                    childBS, supply, false);
            opt.unaffordable = !canAfford;
            candidates.add(opt);
        }

        return candidates;
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link FlatMcContinuousWorker}. */
    void runSamples(CandidateOption candidate, int numSamples, int nextPlayer, int perspective) {
        if (candidate.isInstantWin) return; // already scored 1.0
        for (int i = 0; i < numSamples; i++) {
            double result = BitMctsRollout.simulateBit(candidate.postState, candidate.postSupply,
                    nextPlayer, perspective);
            candidate.samples++;
            candidate.wins += result;
        }
    }

    // -------------------------------------------------------------------------
    // Result construction
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link FlatMcContinuousWorker}. */
    EngineResult buildResult(List<CandidateOption> candidates, int coins, int n,
                                     int iterationsUsed, long computeTimeMs) {
        List<EngineResult.Option> options = new ArrayList<>();

        for (CandidateOption c : candidates) {
            double winRate = c.winRate();
            boolean isSave = c.card == RankEntry.WAIT_SENTINEL;
            boolean affordable = isSave || coins >= c.card.getCost();

            Map<String, String> metrics = new java.util.LinkedHashMap<>();
            metrics.put("winRate", String.format("%.4f", winRate));
            metrics.put("visitCount", String.valueOf(c.samples));
            metrics.put("cost", isSave ? "0" : String.valueOf(c.card.getCost()));

            options.add(new EngineResult.Option(c.card, winRate, List.of(), metrics, affordable));
        }

        // Sort using standard comparator (score DESC, save last, landmarks first, cost DESC)
        options.sort(EngineResult.OPTION_COMPARATOR);

        // Confidence = margin between top two
        double confidence = 0.0;
        if (options.size() >= 2) {
            confidence = Math.max(0.0, Math.min(1.0,
                    options.get(0).score - options.get(1).score));
        }

        return new EngineResult(options, confidence, iterationsUsed, computeTimeMs,
                "flat-mc | " + candidates.size() + " options sampled");
    }

    // -------------------------------------------------------------------------
    // Internal candidate holder
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link FlatMcContinuousWorker}. */
    static final class CandidateOption {
        final Project card;
        final BitState postState;
        final int[] postSupply;
        final boolean isInstantWin;
        boolean unaffordable = false;
        int samples = 0;
        double wins = 0.0;

        CandidateOption(Project card, BitState postState, int[] postSupply, boolean isInstantWin) {
            this.card = card;
            this.postState = postState;
            this.postSupply = postSupply;
            this.isInstantWin = isInstantWin;
        }

        double winRate() {
            return samples > 0 ? wins / samples : 0.0;
        }
    }
}
