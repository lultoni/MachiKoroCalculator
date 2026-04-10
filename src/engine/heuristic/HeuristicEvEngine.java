package engine.heuristic;

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
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heuristic-only engine — zero search, pure formula-based ranking.
 *
 * <p>For each purchase option, computes a composite score from existing
 * {@link Calcs} metrics (EV, ROI, synergy, tempo, landmark urgency). Ranks by
 * composite score. Decisions are instant (&lt;5ms) with no simulation.
 *
 * <h2>Scoring formula</h2>
 * <pre>
 * score(card) = w_ev   × evPerRound(card)
 *             + w_roi  × roiOverHorizon(card, horizon=5)
 *             + w_lm   × landmarkBonus(card)
 *             + w_tempo × tempoAdvantage(card)
 *             + w_delta × portfolioDeltaEV(card)
 *             + w_win   × winProbDelta(card)
 * </pre>
 *
 * <p>Landmark purchases receive a large priority bonus. Save gets score 0.
 *
 * <h2>Purpose</h2>
 * <ul>
 *   <li>Instant decisions for real-time UI / pre-computation.</li>
 *   <li>Tests whether search adds value over pure heuristics.</li>
 *   <li>Fully transparent: every factor in the score is explainable.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Stateless between calls. Each evaluate() call is self-contained.
 */
public final class HeuristicEvEngine implements SimulationEngine {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    // Hand-tuned weights for the composite score
    private static final double W_EV          = 2.0;
    private static final double W_ROI         = 1.0;
    private static final double W_LANDMARK    = 50.0;
    private static final double W_TEMPO       = 3.0;
    private static final double W_DELTA_EV    = 1.5;
    private static final double W_WIN_PROB    = 10.0;

    private static final int    ROI_HORIZON   = 5;
    private static final double ROI_DISCOUNT  = 0.95;

    @Override
    public String id() { return "heuristic-ev"; }

    @Override
    public String description() { return "Heuristic EV — zero-search, formula-based ranking"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = Calcs.optimalDiceCount(state, playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        TurnPlan plan = SimulationEngine.staticPlanWithInstantWinPriority(
                diceCount, result, state, playerIndex, elapsed);
        plan.scoreIsWinRate = false; // composite score, not a [0,1] win probability
        return plan;
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;

        SupplyTracker supply = SupplyTracker.fromGameState(state);
        List<ScoredOption> scored = new ArrayList<>();

        // Save option (score = 0)
        scored.add(new ScoredOption(RankEntry.WAIT_SENTINEL, 0.0, true, Map.of()));

        // Non-landmark cards
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            boolean affordable = coins >= p.getCost();
            ScoredOption opt = scoreCard(state, playerIndex, p, affordable, false);
            scored.add(opt);
        }

        // Landmarks
        for (String lmId : LANDMARK_IDS) {
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;
            boolean affordable = coins >= lm.getCost();

            // Check instant win
            if (affordable) {
                GameState testState = state.copy();
                Player testPlayer = testState.getPlayers()[playerIndex];
                testPlayer.addProject(lm);
                if (GameState.hasWon(testPlayer)) {
                    // Instant win — maximum score
                    Map<String, String> metrics = buildMetrics(lm, Double.MAX_VALUE, 0, 0, 0, 0, 0, true);
                    scored.add(new ScoredOption(lm, Double.MAX_VALUE, affordable, metrics));
                    continue;
                }
            }

            ScoredOption opt = scoreCard(state, playerIndex, lm, affordable, true);
            scored.add(opt);
        }

        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(scored, coins, computeTimeMs);
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    private ScoredOption scoreCard(GameState state, int playerIndex, Project card,
                                   boolean affordable, boolean isLandmark) {
        double evPerRound = Calcs.evPerRound(state, playerIndex, card);
        double portfolioDelta = Calcs.portfolioDeltaEV(state, playerIndex, card);
        double winProbDelta = Calcs.estimateWinProbDelta(state, playerIndex, card);
        double tempo = Calcs.tempoAdvantage(state, playerIndex, card);

        RankEntry roi = Calcs.roiOverHorizon(state, playerIndex, card, ROI_HORIZON, ROI_DISCOUNT);
        double roiVal = roi.roiOverHorizon;

        double landmarkBonus = isLandmark ? W_LANDMARK : 0.0;

        double compositeScore = W_EV * evPerRound
                + W_ROI * roiVal
                + landmarkBonus
                + W_TEMPO * tempo
                + W_DELTA_EV * portfolioDelta
                + W_WIN_PROB * winProbDelta;

        Map<String, String> metrics = buildMetrics(card, compositeScore, evPerRound,
                roiVal, portfolioDelta, winProbDelta, tempo, isLandmark);

        return new ScoredOption(card, compositeScore, affordable, metrics);
    }

    private Map<String, String> buildMetrics(Project card, double score, double evPerRound,
                                              double roi, double portfolioDelta,
                                              double winProbDelta, double tempo,
                                              boolean isLandmark) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("compositeScore", String.format("%.4f", score));
        m.put("evPerRound", String.format("%.4f", evPerRound));
        m.put("roiOverHorizon", String.format("%.4f", roi));
        m.put("portfolioDeltaEV", String.format("%.4f", portfolioDelta));
        m.put("winProbDelta", String.format("%.4f", winProbDelta));
        m.put("tempoAdvantage", String.format("%.4f", tempo));
        m.put("cost", String.valueOf(card.getCost()));
        m.put("isLandmark", String.valueOf(isLandmark));
        return m;
    }

    // -------------------------------------------------------------------------
    // Result construction
    // -------------------------------------------------------------------------

    private EngineResult buildResult(List<ScoredOption> scored, int coins, long computeTimeMs) {
        List<EngineResult.Option> options = new ArrayList<>();

        for (ScoredOption s : scored) {
            boolean isSave = s.card == RankEntry.WAIT_SENTINEL;
            boolean affordable = isSave || s.affordable;

            options.add(new EngineResult.Option(s.card, s.score, List.of(), s.metrics, affordable));
        }

        // Sort using standard comparator (score DESC, save last, landmarks first, cost DESC)
        options.sort(EngineResult.OPTION_COMPARATOR);

        double confidence = 0.0;
        if (options.size() >= 2) {
            double range = options.get(0).score - options.get(options.size() - 1).score;
            if (range > 0) {
                confidence = Math.min(1.0,
                        (options.get(0).score - options.get(1).score) / range);
            }
        }

        return new EngineResult(options, confidence, 0, computeTimeMs,
                "heuristic-ev | " + scored.size() + " options scored");
    }

    // -------------------------------------------------------------------------
    // Internal holder
    // -------------------------------------------------------------------------

    private static final class ScoredOption {
        final Project card;
        final double score;
        final boolean affordable;
        final Map<String, String> metrics;

        ScoredOption(Project card, double score, boolean affordable, Map<String, String> metrics) {
            this.card = card;
            this.score = score;
            this.affordable = affordable;
            this.metrics = metrics;
        }
    }
}
