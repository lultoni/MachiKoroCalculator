package engine.mcts;

import calcs.Calcs;
import calcs.RankEntry;
import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.BürohausLogic;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCTS v1 simulation engine — full UCT tree search with uniform-random full-game rollouts.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Build the root {@link BuyDecisionNode} for the active player's purchase decision.</li>
 *   <li>Run UCT iterations: select → expand → rollout ({@link engine.mcts.MctsRollout}) → backprop.</li>
 *   <li>Collect win rates for each root child; sort descending; populate explanation factors
 *       and metrics using {@link Calcs} methods.</li>
 * </ol>
 *
 * <h2>Configuration ({@link EngineConfig})</h2>
 * <ul>
 *   <li>{@code iterations} — rollout budget (0 = use timeBudgetMs instead)</li>
 *   <li>{@code timeBudgetMs} — wall-clock budget in ms (0 = no limit; at least one iteration
 *       always runs if both are 0)</li>
 *   <li>{@code extra("explorationConstant")} — UCB1 C value (default "1.4142")</li>
 * </ul>
 */
public class MctsV1Engine implements SimulationEngine {

    public static final String ENGINE_ID = "mcts-v1";

    private static final int    DEFAULT_HORIZON       = 10;
    private static final double DEFAULT_DISCOUNT       = 0.95;
    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS v1 — full UCT tree search with uniform-random full-game rollouts";
    }

    // -------------------------------------------------------------------------
    // evaluate
    // -------------------------------------------------------------------------

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startMs = System.currentTimeMillis();
        double explorationConstant = Double.parseDouble(
                config.getExtra("explorationConstant", "1.4142"));
        boolean profile = "true".equals(config.getExtra("profile", "false"));

        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();
        MctsTree tree = buildFullTurnTree(bs, supply, playerIndex, playerIndex, explorationConstant);
        if (profile) tree.enableProfiling();

        // Run iterations or time budget
        int iterationsUsed;
        if (config.iterations > 0) {
            tree.runIterations(config.iterations);
            iterationsUsed = config.iterations;
        } else if (config.timeBudgetMs > 0) {
            long deadline = startMs + config.timeBudgetMs;
            iterationsUsed = tree.runUntilDeadline(deadline);
        } else {
            // Neither set: run a minimal 100 iterations
            tree.runIterations(100);
            iterationsUsed = 100;
        }

        long computeTimeMs = System.currentTimeMillis() - startMs;
        return buildResult(state, playerIndex, tree, iterationsUsed, computeTimeMs, config);
    }

    // -------------------------------------------------------------------------
    // evaluateFullTurn — for H2H match runner
    // -------------------------------------------------------------------------

    /**
     * Evaluates a full turn from the start: dice choice → roll → Funkturm → Bürohaus → purchase.
     *
     * <p>Roots the MCTS tree at DiceChoiceNode (if player has Bahnhof) or ChanceNode (1d6 only).
     * All four decision types come from one tree search. The returned {@link TurnPlan} allows
     * progressive decision extraction as the match runner feeds actual dice outcomes.
     *
     * @param state       current game state (read-only)
     * @param playerIndex index of the active player
     * @param config      engine configuration (iterations, explorationConstant, etc.)
     * @return turn plan; call {@link TurnPlan#navigateRoll} after rolling dice
     */
    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long startMs = System.currentTimeMillis();
        double explorationConstant = Double.parseDouble(
                config.getExtra("explorationConstant", "1.4142"));

        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();
        MctsTree tree = buildFullTurnTree(bs, supply, playerIndex, playerIndex, explorationConstant);

        int iterationsUsed;
        if (config.iterations > 0) {
            tree.runIterations(config.iterations);
            iterationsUsed = config.iterations;
        } else if (config.timeBudgetMs > 0) {
            long deadline = startMs + config.timeBudgetMs;
            iterationsUsed = tree.runUntilDeadline(deadline);
        } else {
            tree.runIterations(100);
            iterationsUsed = 100;
        }

        long computeTimeMs = System.currentTimeMillis() - startMs;

        // Extract dice count decision
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        int diceCount = 1;
        if (hasBahnhof && tree.fullTurnRoot instanceof DiceChoiceNode diceNode) {
            if (diceNode.expanded && diceNode.getChildren().size() == 2) {
                MctsNode best = MctsTree.bestChild(diceNode);
                diceCount = (diceNode.getChildren().indexOf(best) == 1) ? 2 : 1;
            }
        }

        return new TurnPlan(tree, diceCount, iterationsUsed, computeTimeMs);
    }

    /**
     * Builds a full-turn MctsTree rooted at DiceChoiceNode or ChanceNode.
     * Overridable by subclasses to inject custom rollout functions.
     */
    protected MctsTree buildFullTurnTree(BitState bs, int[] supply,
                                          int activePlayer, int playerPerspective,
                                          double explorationConstant) {
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, BitMctsRollout::simulate, false, true);
    }

    /**
     * Builds the {@link EngineResult} from a pre-warmed {@link MctsTree}.
     * Subclasses that control the iteration schedule themselves (e.g. adaptive budget)
     * can call this after running their own iteration logic.
     *
     * @param state          original game state (not mutated)
     * @param playerIndex    the player being advised
     * @param tree           fully-run tree (root must be expanded)
     * @param iterationsUsed total iterations reflected in the tree
     * @param computeTimeMs  elapsed wall-clock time to report
     * @param config         engine config (used for skipEnrichment and profiling flags)
     * @return ranked evaluation result
     */
    protected EngineResult buildResult(GameState state, int playerIndex, MctsTree tree,
                                       int iterationsUsed, long computeTimeMs, EngineConfig config) {
        boolean skipEnrichment = config != null
                && "true".equals(config.getExtra("skipEnrichment", "false"));

        // ---- Pass 1: Collect options from root children ----
        List<EngineResult.Option> rawOptions = skipEnrichment
                ? buildOptionsLite(tree, state, playerIndex)
                : buildOptions(state, playerIndex, tree, iterationsUsed);

        // Sort using standard comparator (score DESC, save last, landmarks first, cost DESC)
        rawOptions.sort(EngineResult.OPTION_COMPARATOR);

        // ---- Pass 2: Enrich with structured factors using cross-option stats ----
        List<EngineResult.Option> options = skipEnrichment
                ? rawOptions
                : enrichWithStructuredFactors(state, playerIndex, rawOptions);

        // Confidence = margin between top-2
        double confidence = 0.0;
        if (options.size() >= 2) {
            confidence = Math.max(0.0, Math.min(1.0,
                    options.get(0).score - options.get(1).score));
        }

        // Debug info (with optional profiling stats)
        String debugInfo = buildDebugInfo(tree, iterationsUsed);
        java.util.Map<String, Long> profStats = tree.getProfilingStats();
        if (!profStats.isEmpty()) {
            debugInfo += " | profiling: " + profStats;
        }

        return new EngineResult(options, confidence, iterationsUsed, computeTimeMs, debugInfo);
    }

    /**
     * Backward-compatible overload for subclasses that don't pass config.
     */
    protected EngineResult buildResult(GameState state, int playerIndex, MctsTree tree,
                                       int iterationsUsed, long computeTimeMs) {
        return buildResult(state, playerIndex, tree, iterationsUsed, computeTimeMs, null);
    }

    // -------------------------------------------------------------------------
    // Tree factory — overridable by subclasses to plug in alternative rollout policies
    // -------------------------------------------------------------------------

    /**
     * Creates the MCTS tree used in {@link #evaluate}. Subclasses override this to inject
     * a different {@link engine.mcts.RolloutFn} (e.g. greedy, Boltzmann).
     */
    protected MctsTree buildTree(BitState bs, int[] supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        return new MctsTree(bs, supply, activePlayer, playerPerspective, explorationConstant);
    }

    // -------------------------------------------------------------------------
    // Option construction
    // -------------------------------------------------------------------------

    private List<EngineResult.Option> buildOptions(
            GameState state, int playerIndex, MctsTree tree, int iterationsUsed) {
        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;

        // When using a full-turn tree, aggregate purchase options across all roll branches
        if (tree.fullTurnRoot != null) {
            return buildOptionsFromFullTurnTree(state, playerIndex, tree, iterationsUsed, coins, n);
        }

        // Legacy path: root is a BuyDecisionNode with pre-roll coins
        List<EngineResult.Option> options = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        if (!tree.root.expanded) {
            tree.root.expand();
        }

        BitState rootBS = tree.root.state;
        for (MctsNode child : tree.root.getChildren()) {
            Project purchased = inferPurchasedCard(rootBS, playerIndex, child.state, playerIndex);
            if (purchased == null) continue;

            double winRate = child.visitCount > 0
                    ? child.totalScore / child.visitCount
                    : 0.0;

            boolean affordable = (purchased == RankEntry.WAIT_SENTINEL)
                    || (coins >= purchased.getCost());

            Map<String, String> metrics = buildMetrics(state, playerIndex, purchased,
                    winRate, child.visitCount, iterationsUsed, coins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, purchased,
                    winRate, child.visitCount, iterationsUsed, metrics, coins, n);

            options.add(new EngineResult.Option(
                    purchased, winRate, factors, metrics, affordable));
            seenIds.add(purchased.getId());
        }

        // Enrich with unaffordable cards not in the tree (B19b fix)
        SupplyTracker supply = SupplyTracker.fromGameState(state);
        for (Project p : state.getUnbuilt_projects()) {
            if (seenIds.contains(p.getId())) continue;
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;

            double score = heuristicScore(state, playerIndex, p);
            Map<String, String> metrics = buildMetrics(state, playerIndex, p,
                    score, 0, iterationsUsed, coins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, p,
                    score, 0, iterationsUsed, metrics, coins, n);
            options.add(new EngineResult.Option(p, score, factors, metrics, false));
        }
        for (String lmId : LANDMARK_IDS) {
            if (seenIds.contains(lmId)) continue;
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;

            double score = heuristicScore(state, playerIndex, lm);
            Map<String, String> metrics = buildMetrics(state, playerIndex, lm,
                    score, 0, iterationsUsed, coins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, lm,
                    score, 0, iterationsUsed, metrics, coins, n);
            options.add(new EngineResult.Option(lm, score, factors, metrics, false));
        }

        boolean hasSave = options.stream().anyMatch(o -> "_wait_".equals(o.project.getId()));
        if (!hasSave) {
            options.add(buildSaveOption(state, playerIndex, coins, n, iterationsUsed));
        }

        return options;
    }

    /**
     * Aggregates purchase options across all BuyDecisionNodes in a full-turn tree.
     * Walks the tree from fullTurnRoot, finds all BuyDecisionNode instances, and merges
     * per-card statistics (visit count, total score) across all roll branches.
     */
    private List<EngineResult.Option> buildOptionsFromFullTurnTree(
            GameState state, int playerIndex, MctsTree tree,
            int iterationsUsed, int preRollCoins, int n) {

        // Collect per-card aggregated stats across all BuyDecisionNodes
        Map<String, AggregatedOption> agg = new HashMap<>();
        collectBuyDecisionStats(tree.fullTurnRoot, playerIndex, agg);

        List<EngineResult.Option> options = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (AggregatedOption ao : agg.values()) {
            double winRate = ao.totalVisits > 0 ? ao.totalScore / ao.totalVisits : 0.0;
            // Affordable if affordable in ANY roll branch (user may roll well enough)
            boolean affordable = ao.card == RankEntry.WAIT_SENTINEL || ao.affordableInAnyBranch;

            Map<String, String> metrics = buildMetrics(state, playerIndex, ao.card,
                    winRate, ao.totalVisits, iterationsUsed, preRollCoins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, ao.card,
                    winRate, ao.totalVisits, iterationsUsed, metrics, preRollCoins, n);

            options.add(new EngineResult.Option(
                    ao.card, winRate, factors, metrics, affordable));
            seenIds.add(ao.card.getId());
        }

        // Enrich with unaffordable cards not explored by the tree (B19b fix).
        // Uses heuristic win-probability on a hypothetical state as the score.
        Player active = state.getPlayers()[playerIndex];
        SupplyTracker supply = SupplyTracker.fromGameState(state);

        // Non-landmark cards
        for (Project p : state.getUnbuilt_projects()) {
            if (seenIds.contains(p.getId())) continue;
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;

            double score = heuristicScore(state, playerIndex, p);
            Map<String, String> metrics = buildMetrics(state, playerIndex, p,
                    score, 0, iterationsUsed, preRollCoins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, p,
                    score, 0, iterationsUsed, metrics, preRollCoins, n);
            options.add(new EngineResult.Option(p, score, factors, metrics, false));
        }

        // Landmarks
        for (String lmId : LANDMARK_IDS) {
            if (seenIds.contains(lmId)) continue;
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;

            double score = heuristicScore(state, playerIndex, lm);
            Map<String, String> metrics = buildMetrics(state, playerIndex, lm,
                    score, 0, iterationsUsed, preRollCoins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, lm,
                    score, 0, iterationsUsed, metrics, preRollCoins, n);
            options.add(new EngineResult.Option(lm, score, factors, metrics, false));
        }

        boolean hasSave = options.stream().anyMatch(o -> "_wait_".equals(o.project.getId()));
        if (!hasSave) {
            options.add(buildSaveOption(state, playerIndex, preRollCoins, n, iterationsUsed));
        }

        return options;
    }

    /**
     * Computes a heuristic score for unaffordable cards not explored by the MCTS tree.
     * Uses baseline win probability on a hypothetical state where the card is added
     * (without paying, since it's unaffordable — models the "what if I had this" question).
     */
    private static double heuristicScore(GameState state, int playerIndex, Project card) {
        GameState hypo = state.copy();
        hypo.getPlayers()[playerIndex].addProject(card);
        return WinProbability.computeBaselineWinProb(hypo, playerIndex);
    }

    /** Mutable accumulator for aggregating stats across BuyDecisionNode branches. */
    private static final class AggregatedOption {
        final Project card;
        int totalVisits;
        double totalScore;
        boolean affordableInAnyBranch;

        AggregatedOption(Project card) {
            this.card = card;
        }
    }

    /**
     * Recursively walks the full-turn tree to find all BuyDecisionNodes and
     * aggregates per-card visit counts and scores.
     */
    private void collectBuyDecisionStats(MctsNode node, int playerIndex,
                                          Map<String, AggregatedOption> agg) {
        if (node == null || !node.expanded) return;

        if (node instanceof BuyDecisionNode buyNode) {
            int coins = buyNode.state.getCoins(playerIndex);

            for (MctsNode child : buyNode.getChildren()) {
                Project purchased = inferPurchasedCard(buyNode.state, playerIndex,
                        child.state, playerIndex);
                if (purchased == null) continue;

                String id = purchased.getId();
                AggregatedOption ao = agg.computeIfAbsent(id, k -> new AggregatedOption(purchased));
                ao.totalVisits += child.visitCount;
                ao.totalScore += child.totalScore;
                if (purchased == RankEntry.WAIT_SENTINEL || coins >= purchased.getCost()) {
                    ao.affordableInAnyBranch = true;
                }
            }
        } else {
            // Not a BuyDecisionNode — recurse into children
            for (MctsNode child : node.getChildren()) {
                collectBuyDecisionStats(child, playerIndex, agg);
            }
        }
    }

    /**
     * Lightweight option extraction — only card ID, win rate, visit count, and affordable flag.
     * Skips all Calcs metric computation and explanation factors. Used by H2H match runner.
     */
    private List<EngineResult.Option> buildOptionsLite(MctsTree tree, GameState state, int playerIndex) {
        List<EngineResult.Option> options = new ArrayList<>();
        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();

        if (!tree.root.expanded) {
            tree.root.expand();
        }

        BitState rootBS = tree.root.state;
        for (MctsNode child : tree.root.getChildren()) {
            Project purchased = inferPurchasedCard(rootBS, playerIndex, child.state, playerIndex);
            if (purchased == null) continue;

            double winRate = child.visitCount > 0
                    ? child.totalScore / child.visitCount
                    : 0.0;

            boolean affordable = (purchased == RankEntry.WAIT_SENTINEL)
                    || (coins >= purchased.getCost());

            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("winRate", String.format("%.4f", winRate));
            metrics.put("visitCount", String.valueOf(child.visitCount));

            options.add(new EngineResult.Option(
                    purchased, winRate, List.of(), metrics, affordable));
        }

        boolean hasSave = options.stream().anyMatch(o -> "_wait_".equals(o.project.getId()));
        if (!hasSave) {
            options.add(new EngineResult.Option(
                    RankEntry.WAIT_SENTINEL, 0.0, List.of(), Map.of("winRate", "0.0000"), true));
        }

        return options;
    }

    /**
     * Infers which card the player at {@code playerIdx} purchased by comparing
     * BitState card counts between before and after states.
     * Returns {@link RankEntry#WAIT_SENTINEL} if no card was added (save action).
     *
     * <p>O(19) integer comparisons (12 normal + 3 purple + 4 landmark), zero allocation.
     * Correctly handles duplicate purchases (e.g. buying a second Weizenfeld).
     */
    private static Project inferPurchasedCard(
            BitState beforeState, int playerIdx,
            BitState afterState, int afterPlayerIdx) {
        // Normal cards (0-11): check count increase
        for (int ci = 0; ci < BitStateTranslator.NUM_NORMAL_CARDS; ci++) {
            if (afterState.getCardCount(afterPlayerIdx, ci) > beforeState.getCardCount(playerIdx, ci)) {
                return BitStateTranslator.NORMAL_CARD_PROJECTS[ci];
            }
        }
        // Purple cards (0-2): check flag set
        for (int pi = 0; pi < BitStateTranslator.NUM_PURPLE_CARDS; pi++) {
            if (afterState.hasPurple(afterPlayerIdx, pi) && !beforeState.hasPurple(playerIdx, pi)) {
                return BitStateTranslator.PURPLE_CARD_PROJECTS[pi];
            }
        }
        // Landmarks (0-3): check flag set
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (afterState.hasLandmark(afterPlayerIdx, li) && !beforeState.hasLandmark(playerIdx, li)) {
                return ProjectLoader.getProject(BitStateTranslator.LANDMARK_IDS[li]).orElse(null);
            }
        }
        return RankEntry.WAIT_SENTINEL;
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    private Map<String, String> buildMetrics(
            GameState state, int playerIndex, Project card,
            double winRate, int visitCount, int totalIterations,
            int coins, int n) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("winRate",    String.format("%.4f", winRate));
        m.put("visitCount", String.valueOf(visitCount));
        m.put("cost",       String.valueOf(card.getCost()));

        // Confidence: computed at EngineResult level; use placeholder here
        m.put("confidence", "N/A"); // filled at EngineResult construction

        if (card == RankEntry.WAIT_SENTINEL) {
            m.put("immediateEV",     "0.00");
            m.put("evPerRound",      "0.00");
            m.put("roiOverHorizon",  "-");
            m.put("winProbDelta",    "0.00");
            m.put("portfolioDeltaEV","0.00");
            m.put("variance",        "0.00");
            m.put("probNoIncomeOwnTurn", "0.00");
            m.put("probNoIncomeRound",   "0.00");
            m.put("turnsToWin",      estimateTurnsToWin(state, playerIndex));
            m.put("tempoAdvantage",  estimateTempoAdvantage(state, playerIndex));
            return m;
        }

        // Simulate the purchase to get metrics
        GameState hypothetical = state.copy();
        hypothetical.getPlayers()[playerIndex].getOwned_projects().add(card);

        try {
            double immEV    = Calcs.immediateEV(state, playerIndex, card, false);
            double evRound  = Calcs.evPerRound(state, playerIndex, card);
            RankEntry roi   = Calcs.roiOverHorizon(state, playerIndex, card,
                    DEFAULT_HORIZON, DEFAULT_DISCOUNT);
            double wpDelta  = Calcs.estimateWinProbDelta(state, playerIndex, card);
            double portDelta = Calcs.portfolioDeltaEV(state, playerIndex, card);

            m.put("immediateEV",      String.format("%.3f", immEV));
            m.put("evPerRound",       String.format("%.3f", evRound));
            m.put("roiOverHorizon",   String.format("%.3f", roi.roiOverHorizon));
            m.put("winProbDelta",     String.format("%.4f", wpDelta));
            m.put("portfolioDeltaEV", String.format("%.3f", portDelta));
            m.put("variance",         String.format("%.3f", roi.variance));
            m.put("probNoIncomeOwnTurn", String.format("%.4f", roi.probNoIncomeOwnTurn));
            m.put("probNoIncomeRound",   String.format("%.4f", roi.probNoIncomeRound));
        } catch (Exception e) {
            // Defensive: some Calcs methods may fail for edge cases; fill with N/A
            for (String key : new String[]{"immediateEV","evPerRound","roiOverHorizon",
                    "winProbDelta","portfolioDeltaEV","variance",
                    "probNoIncomeOwnTurn","probNoIncomeRound"}) {
                m.putIfAbsent(key, "N/A");
            }
        }

        m.put("turnsToWin",     estimateTurnsToWin(hypothetical, playerIndex));
        m.put("tempoAdvantage", estimateTempoAdvantage(hypothetical, playerIndex));
        return m;
    }

    // -------------------------------------------------------------------------
    // Explanation factors
    // -------------------------------------------------------------------------

    private List<String> buildExplanationFactors(
            GameState state, int playerIndex, Project card,
            double winRate, int visitCount, int totalIterations,
            Map<String, String> metrics, int coins, int n) {
        List<String> factors = new ArrayList<>();

        factors.add(String.format("Win rate: %.1f%% (%d rollouts)", winRate * 100, visitCount));

        // Immediate EV
        String immEV = metrics.get("immediateEV");
        if (immEV != null && !immEV.equals("N/A") && !immEV.equals("0.00")) {
            factors.add("Immediate EV: +" + immEV + " coins on own turn");
        }

        // EV per round
        String evR = metrics.get("evPerRound");
        if (evR != null && !evR.equals("N/A")) {
            factors.add("EV per round: +" + evR + " coins/round");
        }

        // ROI
        String roi = metrics.get("roiOverHorizon");
        if (roi != null && !roi.equals("N/A") && !roi.equals("-")) {
            factors.add("ROI over " + DEFAULT_HORIZON + " turns: " + roi + " coins (net of cost)");
        }

        // Win probability delta
        String wpd = metrics.get("winProbDelta");
        if (wpd != null && !wpd.equals("N/A")) {
            double wpdVal = Double.parseDouble(wpd);
            if (Math.abs(wpdVal) > 1e-4) {
                factors.add(String.format("Win probability delta: %+.1f%%", wpdVal * 100));
            }
        }

        // Portfolio delta EV
        String portDelta = metrics.get("portfolioDeltaEV");
        if (portDelta != null && !portDelta.equals("N/A")) {
            factors.add("Portfolio delta EV: +" + portDelta + " coins/round");
        }

        // Variance
        String var = metrics.get("variance");
        if (var != null && !var.equals("N/A")) {
            factors.add("Variance: " + var + " coins² per own turn");
        }

        // Risk
        String pni = metrics.get("probNoIncomeOwnTurn");
        if (pni != null && !pni.equals("N/A")) {
            factors.add(String.format("P(no income, own turn): %.1f%%",
                    Double.parseDouble(pni) * 100));
        }
        String pnir = metrics.get("probNoIncomeRound");
        if (pnir != null && !pnir.equals("N/A")) {
            factors.add(String.format("P(no income, full round): %.1f%%",
                    Double.parseDouble(pnir) * 100));
        }

        // Cost and affordability
        if (card != RankEntry.WAIT_SENTINEL) {
            factors.add("Cost: " + card.getCost() + " coins");
            factors.add("Color: " + card.getColor());
            if (!card.isIs_grossprojekt()) {
                factors.add("Activation rolls: " + formatRolls(card.getDice_activation()));
            }
        }

        // Landmark annotation
        if (card.isIs_grossprojekt()) {
            factors.add("Landmark — " + landmarkEffect(card.getId()));
        }

        // Einkaufszentrum synergy
        boolean hasEinkauf = state.getPlayers()[playerIndex].hasProject("einkaufszentrum");
        if (hasEinkauf && isRedOrGreen(card)) {
            factors.add("+1 coin Einkaufszentrum bonus applies to this card");
        }

        // Bürohaus swap note
        if (state.getPlayers()[playerIndex].hasProject("bürohaus")) {
            String swapNote = BürohausLogic.swapNote(state, playerIndex);
            if (swapNote != null) {
                factors.add("Bürohaus swap on roll 6: " + swapNote);
            }
        }

        // Turns to win / tempo
        String ttw = metrics.get("turnsToWin");
        if (ttw != null && !ttw.equals("N/A")) {
            factors.add("Estimated turns to win: " + ttw);
        }
        String tempo = metrics.get("tempoAdvantage");
        if (tempo != null && !tempo.equals("N/A")) {
            factors.add("Tempo advantage over nearest opponent: " + tempo + " turns");
        }

        return factors;
    }

    // -------------------------------------------------------------------------
    // Pass 2: Structured factor enrichment (cross-option weighting)
    // -------------------------------------------------------------------------

    /**
     * Enriches each option with weighted {@link EngineResult.ExplanationFactor} entries
     * and a summary sentence, using cross-option statistics to compute relative weights.
     *
     * <p>Weight for each category = {@code |optionValue - mean| / range} (0 when range = 0).
     * This makes the most differentiating metrics bubble to the top for each option.
     */
    private List<EngineResult.Option> enrichWithStructuredFactors(
            GameState state, int playerIndex, List<EngineResult.Option> rawOptions) {

        // Extract numeric values per metric across all options
        String[] metricKeys = {"winRate", "evPerRound", "portfolioDeltaEV",
                "variance", "probNoIncomeOwnTurn", "tempoAdvantage",
                "roiOverHorizon", "winProbDelta"};
        Map<String, double[]> metricValues = new LinkedHashMap<>();
        for (String key : metricKeys) {
            double[] vals = new double[rawOptions.size()];
            for (int i = 0; i < rawOptions.size(); i++) {
                vals[i] = parseMetricValue(rawOptions.get(i).metrics, key);
            }
            metricValues.put(key, vals);
        }

        // Compute mean and range per metric
        Map<String, Double> means  = new LinkedHashMap<>();
        Map<String, Double> ranges = new LinkedHashMap<>();
        for (String key : metricKeys) {
            double[] vals = metricValues.get(key);
            double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            int valid = 0;
            for (double v : vals) {
                if (Double.isNaN(v)) continue;
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
                valid++;
            }
            means.put(key, valid > 0 ? sum / valid : 0.0);
            ranges.put(key, valid > 0 ? max - min : 0.0);
        }

        // Build enriched options
        List<EngineResult.Option> enriched = new ArrayList<>();
        for (int i = 0; i < rawOptions.size(); i++) {
            EngineResult.Option raw = rawOptions.get(i);
            List<EngineResult.ExplanationFactor> structured =
                    buildStructuredFactors(state, playerIndex, raw, i, means, ranges, metricValues);

            // Sort by weight descending
            structured.sort(Comparator.comparingDouble(
                    (EngineResult.ExplanationFactor f) -> f.weight).reversed());

            // Derive flat explanation factors from structured
            List<String> flatFactors = new ArrayList<>();
            for (EngineResult.ExplanationFactor f : structured) {
                flatFactors.add(f.summary);
            }

            // Build summary sentence
            String summary = buildSummarySentence(raw.project, structured);

            enriched.add(new EngineResult.Option(
                    raw.project, raw.score, flatFactors,
                    structured, summary, raw.metrics, raw.affordable));
        }
        return enriched;
    }

    /**
     * Builds structured factors for a single option across all categories.
     */
    private List<EngineResult.ExplanationFactor> buildStructuredFactors(
            GameState state, int playerIndex, EngineResult.Option opt, int optIdx,
            Map<String, Double> means, Map<String, Double> ranges,
            Map<String, double[]> metricValues) {

        List<EngineResult.ExplanationFactor> factors = new ArrayList<>();
        Project card = opt.project;
        boolean isSave = "_wait_".equals(card.getId());

        // 1. Win rate factor (always present; includes win probability delta if significant)
        {
            double val = parseMetricValue(opt.metrics, "winRate");
            double w = computeWeight(val, means.get("winRate"), ranges.get("winRate"));
            String visitStr = opt.metrics != null ? opt.metrics.getOrDefault("visitCount", "0") : "0";
            double wpDelta = parseMetricValue(opt.metrics, "winProbDelta");
            String wpDetail = !Double.isNaN(wpDelta) && Math.abs(wpDelta) > 1e-4
                    ? String.format(" Heuristic win probability delta: %+.1f%%.", wpDelta * 100)
                    : "";
            factors.add(new EngineResult.ExplanationFactor("winRate", w,
                    String.format("Win rate: %.1f%% (%s rollouts)", val * 100, visitStr),
                    String.format("MCTS simulation win rate across %s rollouts. "
                            + "Mean across all options: %.1f%%.%s", visitStr, means.get("winRate") * 100, wpDetail)));
        }

        // 2. Income factor (evPerRound)
        {
            double val = parseMetricValue(opt.metrics, "evPerRound");
            double w = computeWeight(val, means.get("evPerRound"), ranges.get("evPerRound"));
            String immEV = opt.metrics != null ? opt.metrics.getOrDefault("immediateEV", "0.00") : "0.00";
            String roiStr = opt.metrics != null ? opt.metrics.getOrDefault("roiOverHorizon", "-") : "-";
            factors.add(new EngineResult.ExplanationFactor("income", w,
                    String.format("EV per round: %+.3f coins", val),
                    String.format("Immediate EV: %s coins. EV/round: %.3f. ROI over %d turns: %s.",
                            immEV, val, DEFAULT_HORIZON, roiStr)));
        }

        // 3. Synergy factor (portfolioDeltaEV)
        {
            double val = parseMetricValue(opt.metrics, "portfolioDeltaEV");
            double w = computeWeight(val, means.get("portfolioDeltaEV"), ranges.get("portfolioDeltaEV"));
            String detail = String.format("Portfolio delta EV: %+.3f coins/round.", val);
            boolean hasEinkauf = state.getPlayers()[playerIndex].hasProject("einkaufszentrum");
            if (hasEinkauf && !isSave && isRedOrGreen(card)) {
                detail += " Einkaufszentrum +1 coin bonus applies.";
            }
            factors.add(new EngineResult.ExplanationFactor("synergy", w,
                    String.format("Synergy: %+.3f EV/round to portfolio", val), detail));
        }

        // 4. Risk factor (variance + probNoIncome)
        {
            double variance = parseMetricValue(opt.metrics, "variance");
            double pni = parseMetricValue(opt.metrics, "probNoIncomeOwnTurn");
            // Weight: average of both deviations
            double wVar = computeWeight(variance, means.get("variance"), ranges.get("variance"));
            double wPni = computeWeight(pni, means.get("probNoIncomeOwnTurn"),
                    ranges.get("probNoIncomeOwnTurn"));
            double w = (wVar + wPni) / 2.0;
            String pniRound = opt.metrics != null
                    ? opt.metrics.getOrDefault("probNoIncomeRound", "0.00") : "0.00";
            factors.add(new EngineResult.ExplanationFactor("risk", w,
                    String.format("Risk: variance %.3f, P(no income) %.1f%%", variance, pni * 100),
                    String.format("Variance: %.3f coins²/turn. P(no income, own turn): %.1f%%. "
                            + "P(no income, full round): %s%%.", variance, pni * 100,
                            formatPercent(pniRound))));
        }

        // 5. Tempo factor (tempoAdvantage)
        {
            double val = parseMetricValue(opt.metrics, "tempoAdvantage");
            double w = computeWeight(val, means.get("tempoAdvantage"), ranges.get("tempoAdvantage"));
            String ttw = opt.metrics != null ? opt.metrics.getOrDefault("turnsToWin", "N/A") : "N/A";
            factors.add(new EngineResult.ExplanationFactor("tempo", w,
                    String.format("Tempo: %+.1f turns advantage", val),
                    String.format("Estimated turns to win: %s. Tempo advantage vs nearest opponent: %+.1f turns.",
                            ttw, val)));
        }

        // 6. Landmark factor (only for Großprojekte)
        if (!isSave && card.isIs_grossprojekt()) {
            factors.add(new EngineResult.ExplanationFactor("landmark", 1.0,
                    "Landmark — " + landmarkEffect(card.getId()),
                    "Landmark cards provide permanent abilities. " + landmarkEffect(card.getId()) + "."));
        }

        // 7. Cost factor
        if (!isSave) {
            int cost = card.getCost();
            int maxCost = 0;
            for (String lmId : LANDMARK_IDS) {
                ProjectLoader.getProject(lmId).ifPresent(lm -> {});
            }
            // Normalize cost weight by player's coin count — low cost = high weight (affordable)
            int coins = state.getPlayers()[playerIndex].getCoins();
            double costFraction = coins > 0 ? (double) cost / coins : 1.0;
            double w = Math.min(1.0, Math.max(0.0, 1.0 - costFraction));
            factors.add(new EngineResult.ExplanationFactor("cost", w,
                    String.format("Cost: %d coins (%d remaining)", cost, Math.max(0, coins - cost)),
                    String.format("Card costs %d of %d available coins. %d coins remaining after purchase.",
                            cost, coins, Math.max(0, coins - cost))));
        }

        // 8. (Win probability delta now folded into factor #1 — B09 fix)

        // 9. Coverage factor (activation rolls + color)
        if (!isSave && !card.isIs_grossprojekt()) {
            int[] rolls = card.getDice_activation();
            double rollCorr = 0.0;
            try {
                rollCorr = Calcs.rollCorrelation(state, playerIndex, card);
            } catch (Exception ignored) {}
            double w = computeWeight(rollCorr,
                    0.5, 1.0); // normalized against [0,1] range
            factors.add(new EngineResult.ExplanationFactor("coverage", Math.min(1.0, Math.abs(w)),
                    String.format("Coverage: rolls %s (%s)", formatRolls(rolls), card.getColor()),
                    String.format("Activates on rolls %s. Color: %s. Roll correlation with portfolio: %.3f.",
                            formatRolls(rolls), card.getColor(), rollCorr)));
        }

        return factors;
    }

    /**
     * Parses a numeric metric value from the metrics map.
     * Returns NaN for missing, "N/A", "-", or "∞" values.
     */
    private static double parseMetricValue(Map<String, String> metrics, String key) {
        if (metrics == null) return Double.NaN;
        String v = metrics.get(key);
        if (v == null || v.equals("N/A") || v.equals("-") || v.equals("∞")) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Computes the weight for a single metric: {@code |value - mean| / range}.
     * Returns 0 when range is 0 or value is NaN.
     */
    private static double computeWeight(double value, double mean, double range) {
        if (Double.isNaN(value) || range <= 0.0) return 0.0;
        return Math.min(1.0, Math.abs(value - mean) / range);
    }

    /**
     * Builds a one-line summary sentence for the option.
     */
    private static String buildSummarySentence(Project card, List<EngineResult.ExplanationFactor> factors) {
        String topFactor = factors.isEmpty() ? "" : factors.get(0).summary;
        if ("_wait_".equals(card.getId())) {
            return "Save coins — " + (topFactor.isEmpty() ? "no strong purchase available" : topFactor);
        }
        return "Buy " + card.getLocalizedName() + " — " + (topFactor.isEmpty() ? "best option" : topFactor);
    }

    private static String formatPercent(String rawValue) {
        try {
            return String.format("%.1f", Double.parseDouble(rawValue) * 100);
        } catch (NumberFormatException e) {
            return rawValue;
        }
    }

    // -------------------------------------------------------------------------
    // Debug info
    // -------------------------------------------------------------------------

    private String buildDebugInfo(MctsTree tree, int iterationsUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("mcts-v1: ").append(iterationsUsed).append(" iterations");
        sb.append(", root-children=").append(tree.root.getChildren().size());

        // Scan from fullTurnRoot (DiceChoice/ChanceNode) when available,
        // otherwise from root (BuyDecisionNode). Scan 3 levels deep to detect
        // special nodes: FunkturmNode, BürohausNode, bonus-turn DiceChoice/ChanceNode.
        MctsNode scanRoot = tree.fullTurnRoot != null ? tree.fullTurnRoot : tree.root;
        boolean seenFunkturm = false;
        boolean seenBürohaus = false;
        boolean seenBonus    = false;
        int maxVisits = 0;

        for (MctsNode child : scanRoot.getChildren()) {
            if (child.visitCount > maxVisits) maxVisits = child.visitCount;
            if (child instanceof FunkturmNode) seenFunkturm = true;
            if (child instanceof BürohausNode) seenBürohaus = true;
            if (child instanceof DiceChoiceNode dc && dc.isBonusTurn) seenBonus = true;
            if (child instanceof ChanceNode cn && cn.isBonusTurn) seenBonus = true;
            for (MctsNode grandchild : child.getChildren()) {
                if (grandchild instanceof FunkturmNode) seenFunkturm = true;
                if (grandchild instanceof BürohausNode) seenBürohaus = true;
                if (grandchild instanceof DiceChoiceNode dc2 && dc2.isBonusTurn) seenBonus = true;
                if (grandchild instanceof ChanceNode cn2 && cn2.isBonusTurn) seenBonus = true;
                for (MctsNode ggchild : grandchild.getChildren()) {
                    if (ggchild instanceof FunkturmNode) seenFunkturm = true;
                    if (ggchild instanceof BürohausNode) seenBürohaus = true;
                    if (ggchild instanceof DiceChoiceNode dc3 && dc3.isBonusTurn) seenBonus = true;
                    if (ggchild instanceof ChanceNode cn3 && cn3.isBonusTurn) seenBonus = true;
                }
            }
        }
        if (seenFunkturm) sb.append(", Funkturm/keep+reroll explored");
        if (seenBürohaus) sb.append(", Bürohaus/swap-options expanded");
        if (seenBonus)    sb.append(", Freizeitpark/bonus-turn doubles explored");
        sb.append(", maxChildVisits=").append(maxVisits);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Save option fallback
    // -------------------------------------------------------------------------

    private EngineResult.Option buildSaveOption(
            GameState state, int playerIndex, int coins, int n, int totalIterations) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("winRate",    "0.0000");
        m.put("visitCount", "0");
        m.put("confidence", "N/A");
        m.put("immediateEV",      "0.00");
        m.put("evPerRound",       "0.00");
        m.put("roiOverHorizon",   "-");
        m.put("winProbDelta",     "0.0000");
        m.put("portfolioDeltaEV", "0.00");
        m.put("variance",         "0.00");
        m.put("probNoIncomeOwnTurn", "0.00");
        m.put("probNoIncomeRound",   "0.00");
        m.put("cost",           "0");
        m.put("turnsToWin",     estimateTurnsToWin(state, playerIndex));
        m.put("tempoAdvantage", estimateTempoAdvantage(state, playerIndex));

        return new EngineResult.Option(
                RankEntry.WAIT_SENTINEL, 0.0,
                List.of("Save coins for a better purchase"), m, true);
    }

    // -------------------------------------------------------------------------
    // Utility: ETW and tempo
    // -------------------------------------------------------------------------

    private static String estimateTurnsToWin(GameState state, int playerIndex) {
        try {
            Player p = state.getPlayers()[playerIndex];
            int landmarkCostRemaining = 0;
            for (String lmId : LANDMARK_IDS) {
                if (!p.hasProject(lmId)) {
                    Project lm = ProjectLoader.getProject(lmId).orElse(null);
                    if (lm != null) landmarkCostRemaining += lm.getCost();
                }
            }
            int coinsNeeded = Math.max(0, landmarkCostRemaining - p.getCoins());
            int[] oppCoins = core.CardIncome.buildOpponentCoins(state.getPlayers(), playerIndex);
            double evRound = core.CardIncome.playerEvPerRound(p, state.getPlayers().length, oppCoins);
            if (evRound <= 0.0) return "∞";
            return String.format("%.1f", coinsNeeded / evRound);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static String estimateTempoAdvantage(GameState state, int playerIndex) {
        try {
            Player me = state.getPlayers()[playerIndex];
            String myEtw = estimateTurnsToWin(state, playerIndex);
            if (myEtw.equals("N/A") || myEtw.equals("∞")) return "N/A";
            double myTurns = Double.parseDouble(myEtw);

            double bestOppTurns = Double.MAX_VALUE;
            for (int i = 0; i < state.getPlayers().length; i++) {
                if (i == playerIndex) continue;
                // Build a surrogate state with player i as "active" for ETW
                String oppEtw = estimateTurnsToWinForPlayer(state, i);
                if (!oppEtw.equals("N/A") && !oppEtw.equals("∞")) {
                    double opp = Double.parseDouble(oppEtw);
                    if (opp < bestOppTurns) bestOppTurns = opp;
                }
            }
            if (bestOppTurns == Double.MAX_VALUE) return "N/A";
            double advantage = bestOppTurns - myTurns;
            return String.format("%+.1f", advantage);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static String estimateTurnsToWinForPlayer(GameState state, int playerIndex) {
        try {
            Player p = state.getPlayers()[playerIndex];
            int landmarkCostRemaining = 0;
            for (String lmId : LANDMARK_IDS) {
                if (!p.hasProject(lmId)) {
                    Project lm = ProjectLoader.getProject(lmId).orElse(null);
                    if (lm != null) landmarkCostRemaining += lm.getCost();
                }
            }
            int coinsNeeded = Math.max(0, landmarkCostRemaining - p.getCoins());
            int[] oppCoins = core.CardIncome.buildOpponentCoins(state.getPlayers(), playerIndex);
            double evRound = core.CardIncome.playerEvPerRound(p, state.getPlayers().length, oppCoins);
            if (evRound <= 0.0) return "∞";
            return String.format("%.1f", coinsNeeded / evRound);
        } catch (Exception e) {
            return "N/A";
        }
    }

    // -------------------------------------------------------------------------
    // Small formatting helpers
    // -------------------------------------------------------------------------

    private static String formatRolls(int[] rolls) {
        if (rolls == null || rolls.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rolls.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(rolls[i]);
        }
        return sb.append("]").toString();
    }

    private static boolean isRedOrGreen(Project card) {
        return "rot".equals(card.getColor()) || "grün".equals(card.getColor());
    }

    private static String landmarkEffect(String id) {
        return switch (id) {
            case "bahnhof"         -> "may roll 2 dice from turn 1";
            case "einkaufszentrum" -> "+1 coin per red/green activation";
            case "freizeitpark"    -> "bonus turn on doubles";
            case "funkturm"        -> "re-roll the dice once per turn";
            default                -> id;
        };
    }
}
