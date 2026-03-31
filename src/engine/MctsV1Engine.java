package engine;

import calcs.Calcs;
import calcs.RankEntry;
import core.BürohausLogic;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import engine.mcts.BuyDecisionNode;
import engine.mcts.MctsNode;
import engine.mcts.MctsTree;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        SupplyTracker supply = SupplyTracker.fromGameState(state);
        MctsTree tree = buildTree(state, supply, playerIndex, playerIndex, explorationConstant);

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

        // ---- Collect options from root children ----
        List<EngineResult.Option> options = buildOptions(state, playerIndex, tree, iterationsUsed);

        // Sort descending by score; on ties, non-save options rank above save
        // (if buying and saving both win with equal probability, prefer the purchase
        // that makes immediate progress toward the win)
        options.sort(Comparator
                .comparingDouble((EngineResult.Option o) -> o.score).reversed()
                .thenComparing(o -> "_wait_".equals(o.project.getId()) ? 1 : 0));

        // Confidence = margin between top-2
        double confidence = 0.0;
        if (options.size() >= 2) {
            confidence = Math.max(0.0, Math.min(1.0,
                    options.get(0).score - options.get(1).score));
        }

        // Debug info
        String debugInfo = buildDebugInfo(tree, iterationsUsed);

        return new EngineResult(options, confidence, iterationsUsed, computeTimeMs, debugInfo);
    }

    // -------------------------------------------------------------------------
    // Tree factory — overridable by subclasses to plug in alternative rollout policies
    // -------------------------------------------------------------------------

    /**
     * Creates the MCTS tree used in {@link #evaluate}. Subclasses override this to inject
     * a different {@link engine.mcts.RolloutFn} (e.g. greedy, Boltzmann).
     */
    protected MctsTree buildTree(GameState state, SupplyTracker supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        return new MctsTree(state, supply, activePlayer, playerPerspective, explorationConstant);
    }

    // -------------------------------------------------------------------------
    // Option construction
    // -------------------------------------------------------------------------

    private List<EngineResult.Option> buildOptions(
            GameState state, int playerIndex, MctsTree tree, int iterationsUsed) {
        List<EngineResult.Option> options = new ArrayList<>();
        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;

        // Root must be expanded to have children
        if (!tree.root.expanded) {
            tree.root.expand();
        }

        for (MctsNode child : tree.root.getChildren()) {
            // Each child of the root BuyDecisionNode is the next player's turn node,
            // with the purchase for activePlayer already applied to child.state.
            // We infer which card was purchased by diffing root vs child owned lists.
            Project purchased = inferPurchasedCard(state, playerIndex, child.state, playerIndex);
            if (purchased == null) continue;

            double winRate = child.visitCount > 0
                    ? child.totalScore / child.visitCount
                    : 0.0;

            boolean affordable = (purchased == RankEntry.WAIT_SENTINEL)
                    || (coins >= purchased.getCost());

            // Build metrics and explanation using Calcs
            Map<String, String> metrics = buildMetrics(state, playerIndex, purchased,
                    winRate, child.visitCount, iterationsUsed, coins, n);
            List<String> factors = buildExplanationFactors(state, playerIndex, purchased,
                    winRate, child.visitCount, iterationsUsed, metrics, coins, n);

            options.add(new EngineResult.Option(
                    purchased, winRate, factors, metrics, affordable));
        }

        // Ensure save sentinel is present even if root had no wait child
        boolean hasSave = options.stream().anyMatch(o -> "_wait_".equals(o.project.getId()));
        if (!hasSave) {
            options.add(buildSaveOption(state, playerIndex, coins, n, iterationsUsed));
        }

        return options;
    }

    /**
     * Infers which card the player at {@code playerIdx} purchased to go from
     * {@code beforeState} to {@code afterState}.
     * Returns {@link RankEntry#WAIT_SENTINEL} if no card was added (save action).
     */
    private static Project inferPurchasedCard(
            GameState beforeState, int playerIdx,
            GameState afterState, int afterPlayerIdx) {
        List<Project> before = beforeState.getPlayers()[playerIdx].getOwned_projects();
        List<Project> after  = afterState.getPlayers()[afterPlayerIdx].getOwned_projects();
        for (Project p : after) {
            if (!before.contains(p)) return p;
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

        m.put("turnsToWin",     estimateTurnsToWin(state, playerIndex));
        m.put("tempoAdvantage", estimateTempoAdvantage(state, playerIndex));
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
    // Debug info
    // -------------------------------------------------------------------------

    private String buildDebugInfo(MctsTree tree, int iterationsUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("mcts-v1: ").append(iterationsUsed).append(" iterations");
        sb.append(", root-children=").append(tree.root.getChildren().size());

        // Count node types in first two levels for transparency
        boolean seenFunkturm = false;
        boolean seenBürohaus = false;
        boolean seenBonus    = false;
        int maxVisits = 0;

        for (MctsNode child : tree.root.getChildren()) {
            if (child.visitCount > maxVisits) maxVisits = child.visitCount;
            // Expand and look for special nodes
            for (MctsNode grandchild : child.getChildren()) {
                if (grandchild instanceof engine.mcts.FunkturmNode) seenFunkturm = true;
                if (grandchild instanceof engine.mcts.BürohausNode) seenBürohaus = true;
                if (grandchild instanceof engine.mcts.DiceChoiceNode dc && dc.isBonusTurn) seenBonus = true;
                if (grandchild instanceof engine.mcts.ChanceNode cn && cn.isBonusTurn) seenBonus = true;
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
