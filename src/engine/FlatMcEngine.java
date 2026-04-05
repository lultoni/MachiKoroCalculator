package engine;

import calcs.Calcs;
import calcs.RankEntry;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.mcts.MctsRollout;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
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
 * <h2>Purpose</h2>
 * Serves as a <b>lower bound</b> for what tree-based search should beat. Also useful
 * for calibrating H2H: "how much does UCT help vs. just sampling?"
 *
 * <h2>Thread safety</h2>
 * Stateless between calls. Each evaluate() call is self-contained.
 */
public final class FlatMcEngine implements SimulationEngine {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    /** Top-K options to focus on after survey phase. */
    private static final int FOCUS_TOP_K = 5;

    @Override
    public String id() { return "flat-mc"; }

    @Override
    public String description() { return "Flat Monte Carlo — pure sampling, no tree search"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = calcs.Calcs.optimalDiceCount(state, playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        EngineResult.Option top = result.topRecommendation();
        Project purchase = "_wait_".equals(top.project.getId()) ? null : top.project;
        long elapsed = System.currentTimeMillis() - start;
        return TurnPlan.staticPlan(diceCount, purchase != null ? purchase : calcs.RankEntry.WAIT_SENTINEL,
                top.score, result.iterationsUsed, elapsed);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();
        int totalIterations = config.iterations > 0 ? config.iterations : 500;

        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;
        int nextPlayer = (playerIndex + 1) % n;

        SupplyTracker baseSupply = SupplyTracker.fromGameState(state);

        // ---- Enumerate purchase options ----
        List<CandidateOption> candidates = enumerateOptions(state, baseSupply, playerIndex);

        if (candidates.isEmpty()) {
            // Should never happen — save is always present. Defensive fallback.
            candidates.add(new CandidateOption(RankEntry.WAIT_SENTINEL, state, baseSupply, false));
        }

        // ---- Survey phase: 20% of budget, evenly distributed ----
        int surveyBudget = Math.max(candidates.size(), totalIterations / 5);
        int perOptionSurvey = Math.max(1, surveyBudget / candidates.size());

        for (CandidateOption c : candidates) {
            if (!c.unaffordable) runSamples(c, perOptionSurvey, nextPlayer, playerIndex);
        }

        int usedIterations = perOptionSurvey * candidates.size();
        int remaining = totalIterations - usedIterations;

        // ---- Focus phase: 80% of budget on top-K ----
        if (remaining > 0 && candidates.size() > 1) {
            // Sort by win rate descending to find top-K
            candidates.sort(Comparator.comparingDouble(CandidateOption::winRate).reversed());
            int topK = Math.min(FOCUS_TOP_K, candidates.size());
            int perOptionFocus = Math.max(1, remaining / topK);

            for (int i = 0; i < topK; i++) {
                runSamples(candidates.get(i), perOptionFocus, nextPlayer, playerIndex);
            }
            usedIterations += perOptionFocus * topK;
        }

        // ---- Build result ----
        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(state, playerIndex, candidates, coins, n, usedIterations, computeTimeMs);
    }

    // -------------------------------------------------------------------------
    // Option enumeration
    // -------------------------------------------------------------------------

    private List<CandidateOption> enumerateOptions(GameState state, SupplyTracker supply, int playerIndex) {
        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        List<CandidateOption> candidates = new ArrayList<>();

        // Save option
        candidates.add(new CandidateOption(RankEntry.WAIT_SENTINEL, state, supply, false));

        // Non-landmark cards — include all valid cards (affordable or not)
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            boolean canAfford = coins >= p.getCost();
            GameState childState = state.copy();
            Player childActive = childState.getPlayers()[playerIndex];
            if (canAfford) {
                childActive.setCoins(childActive.getCoins() - p.getCost());
            }
            childActive.addProject(p);
            SupplyTracker childSupply = supply.withPurchase(p.getId());
            CandidateOption opt = new CandidateOption(p, childState, childSupply, false);
            opt.unaffordable = !canAfford;
            candidates.add(opt);
        }

        // Landmarks — include all unowned landmarks (affordable or not)
        for (String lmId : LANDMARK_IDS) {
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;
            boolean canAfford = coins >= lm.getCost();
            GameState childState = state.copy();
            Player childActive = childState.getPlayers()[playerIndex];
            if (canAfford) {
                childActive.setCoins(childActive.getCoins() - lm.getCost());
            }
            childActive.addProject(lm);
            // Check instant win (only relevant if affordable)
            if (canAfford && GameState.hasWon(childActive)) {
                CandidateOption winOpt = new CandidateOption(lm, childState, supply, true);
                winOpt.wins = 1;
                winOpt.samples = 1;
                candidates.add(winOpt);
                continue;
            }
            CandidateOption opt = new CandidateOption(lm, childState, supply, false);
            opt.unaffordable = !canAfford;
            candidates.add(opt);
        }

        return candidates;
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    private void runSamples(CandidateOption candidate, int numSamples, int nextPlayer, int perspective) {
        if (candidate.isInstantWin) return; // already scored 1.0
        for (int i = 0; i < numSamples; i++) {
            double result = MctsRollout.simulate(candidate.postState, candidate.postSupply,
                    nextPlayer, perspective);
            candidate.samples++;
            candidate.wins += result;
        }
    }

    // -------------------------------------------------------------------------
    // Result construction
    // -------------------------------------------------------------------------

    private EngineResult buildResult(GameState state, int playerIndex,
                                     List<CandidateOption> candidates, int coins, int n,
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

        // Sort: descending score, save last on ties
        options.sort(Comparator
                .comparingDouble((EngineResult.Option o) -> o.score).reversed()
                .thenComparing(o -> "_wait_".equals(o.project.getId()) ? 1 : 0));

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

    private static final class CandidateOption {
        final Project card;
        final GameState postState;
        final SupplyTracker postSupply;
        final boolean isInstantWin;
        boolean unaffordable = false;
        int samples = 0;
        double wins = 0.0;

        CandidateOption(Project card, GameState postState, SupplyTracker postSupply, boolean isInstantWin) {
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
