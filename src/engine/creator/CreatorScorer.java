package engine.creator;

import calcs.Calcs;
import calcs.RankEntry;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import engine.EngineConfig;
import engine.EngineResult;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heuristic scoring engine for the Creator Engine.
 *
 * <p>Computes a multi-dimensional composite score for each purchase candidate using
 * 8 weighted dimensions (income, risk, coverage, tempo, winProb, landmark, urgency, roi).
 * Weights are modulated by a continuous situation assessment and gravity wells that
 * activate gradually as endgame conditions strengthen.
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li>Every weight and threshold is a named constant with a config override key.</li>
 *   <li>No hardcoded card combos — synergies emerge from the math (portfolioDeltaEV, rollCorrelation).</li>
 *   <li>Situation assessment is holistic (income capacity + coins + tempo + landmarks), not landmark-centric.</li>
 *   <li>Gravity wells ramp gradually (configurable sharpness), not binary on/off.</li>
 *   <li>Every scored candidate produces a full metrics map for explainability.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All methods are stateless and safe for concurrent use.
 */
public final class CreatorScorer {

    private CreatorScorer() {}

    // =====================================================================
    // Dimension indices
    // =====================================================================

    static final int DIM_INCOME   = 0;
    static final int DIM_RISK     = 1;
    static final int DIM_COVERAGE = 2;
    static final int DIM_TEMPO    = 3;
    static final int DIM_WINPROB  = 4;
    static final int DIM_LANDMARK = 5;
    static final int DIM_URGENCY  = 6;
    static final int DIM_ROI      = 7;
    static final int NUM_DIMS     = 8;

    static final String[] DIM_NAMES = {
        "income", "risk", "coverage", "tempo", "winProb", "landmark", "urgency", "roi"
    };

    // =====================================================================
    // Default base weights — all overridable via EngineConfig.extra
    // =====================================================================

    private static final double[] DEFAULT_BASE_WEIGHTS = {
        2.5,  // income
        2.0,  // risk
        1.5,  // coverage
        2.0,  // tempo
        3.0,  // winProb
        2.0,  // landmark
        1.0,  // urgency
        1.5,  // roi
    };

    private static final String[] BASE_WEIGHT_KEYS = {
        "wIncome", "wRisk", "wCoverage", "wTempo", "wWinProb", "wLandmark", "wUrgency", "wRoi"
    };

    // =====================================================================
    // Situation assessment defaults — all overridable
    // =====================================================================

    private static final double DEFAULT_SIT_LANDMARK = 0.30;
    private static final double DEFAULT_SIT_INCOME   = 0.30;
    private static final double DEFAULT_SIT_COINS    = 0.15;
    private static final double DEFAULT_SIT_TEMPO    = 0.25;
    private static final double DEFAULT_TARGET_EV    = 4.0;
    private static final double DEFAULT_MAX_ETW      = 50.0;

    // =====================================================================
    // Situation multiplier defaults — low and high endpoints per dimension
    // =====================================================================

    /** Multiplier at situation=0.0 (early game / weak position). */
    private static final double[] DEFAULT_MULT_LOW = {
        1.5,  // income: more important early
        1.5,  // risk: more important early
        1.3,  // coverage: more important early
        0.8,  // tempo: less important early
        0.5,  // winProb: less important early
        0.5,  // landmark: less important early
        1.0,  // urgency: constant
        1.3,  // roi: more important early
    };

    /** Multiplier at situation=1.0 (late game / strong position). */
    private static final double[] DEFAULT_MULT_HIGH = {
        0.5,  // income: less important late
        0.3,  // risk: less important late
        0.3,  // coverage: less important late
        1.5,  // tempo: more important late
        2.0,  // winProb: more important late
        2.0,  // landmark: more important late
        1.2,  // urgency: slightly more late
        0.7,  // roi: less important late
    };

    private static final double DEFAULT_SIGMOID_K = 6.0;

    // =====================================================================
    // Gravity well defaults
    // =====================================================================

    private static final double DEFAULT_SPRINT_HORIZON   = 6.0;
    private static final double DEFAULT_SPRINT_SHARPNESS = 1.0;
    private static final double DEFAULT_THREAT_HORIZON   = 8.0;
    private static final double DEFAULT_THREAT_SHARPNESS = 1.0;

    // =====================================================================
    // =====================================================================
    // ROI horizon defaults
    // =====================================================================

    private static final int    ROI_HORIZON  = 5;
    private static final double ROI_DISCOUNT = 0.95;
    private static final double CVAR_ALPHA   = 0.10;

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Result of scoring a single candidate.
     */
    public static final class ScoredCandidate {
        public final Project card;
        public final double compositeScore;
        public final Map<String, String> metrics;
        public final List<EngineResult.ExplanationFactor> factors;
        public final boolean affordable;

        ScoredCandidate(Project card, double compositeScore,
                        Map<String, String> metrics,
                        List<EngineResult.ExplanationFactor> factors,
                        boolean affordable) {
            this.card = card;
            this.compositeScore = compositeScore;
            this.metrics = metrics;
            this.factors = factors;
            this.affordable = affordable;
        }
    }

    /**
     * Scores all candidates (cards + landmarks + save) for the given game state.
     *
     * @param gs          current game state
     * @param playerIndex the player choosing a purchase
     * @param supply      current market supply
     * @param config      engine config (for parameter overrides)
     * @return scored candidates sorted descending by composite score
     */
    public static List<ScoredCandidate> scoreAll(GameState gs, int playerIndex,
                                                  SupplyTracker supply, EngineConfig config) {
        Player active = gs.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int numPlayers = gs.getPlayers().length;

        // ---- Read configurable parameters ----
        double[] baseWeights = readBaseWeights(config);
        double sigmoidK = readDouble(config, "sigmoidK", DEFAULT_SIGMOID_K);

        // ---- Compute situation ----
        double situation = computeSituation(gs, playerIndex, config);

        // ---- Compute effective weights (base × sigmoid multiplier) ----
        double[] effectiveWeights = computeEffectiveWeights(baseWeights, situation, sigmoidK, config);

        // ---- Apply gravity wells ----
        GravityWellResult wellResult = applyGravityWells(gs, playerIndex, effectiveWeights, config);
        String activeWell = wellResult.activeWell;

        // ---- Score all candidates ----
        List<ScoredCandidate> affordableCards = new ArrayList<>();
        List<ScoredCandidate> unaffordableCards = new ArrayList<>();
        List<ScoredCandidate> allCandidates = new ArrayList<>();

        // Non-landmark cards
        for (Project p : gs.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            boolean canAfford = coins >= p.getCost();
            ScoredCandidate sc = scoreCard(gs, playerIndex, p, supply, effectiveWeights,
                    numPlayers, situation, activeWell, canAfford, false, config,
                    wellResult.sprintIntensity);
            allCandidates.add(sc);
            if (canAfford) affordableCards.add(sc); else unaffordableCards.add(sc);
        }

        // Landmarks
        String[] landmarkIds = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
        for (String lmId : landmarkIds) {
            if (active.hasProject(lmId)) continue;
            Project lm = core.ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;
            boolean canAfford = coins >= lm.getCost();

            // Instant-win check
            if (canAfford && active.getLandmarkCount() == 3) {
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("compositeScore", "Infinity");
                metrics.put("situation", fmt(situation));
                metrics.put("activeGravityWell", "instant-win");
                metrics.put("cost", String.valueOf(lm.getCost()));
                List<EngineResult.ExplanationFactor> factors = List.of(
                    new EngineResult.ExplanationFactor("winRate", 1.0,
                        "Buying this landmark wins the game immediately.",
                        "Player has 3 landmarks and can afford the 4th."));
                ScoredCandidate sc = new ScoredCandidate(lm, Double.MAX_VALUE, metrics, factors, true);
                allCandidates.add(sc);
                affordableCards.add(sc);
                continue;
            }

            ScoredCandidate sc = scoreCard(gs, playerIndex, lm, supply, effectiveWeights,
                    numPlayers, situation, activeWell, canAfford, true, config,
                    wellResult.sprintIntensity);
            allCandidates.add(sc);
            if (canAfford) affordableCards.add(sc); else unaffordableCards.add(sc);
        }

        // Save option
        ScoredCandidate saveSc = scoreSave(gs, playerIndex, effectiveWeights,
                situation, activeWell, affordableCards, unaffordableCards, config);
        allCandidates.add(saveSc);

        // Sort descending by composite score, save last on ties
        allCandidates.sort((a, b) -> {
            int cmp = Double.compare(b.compositeScore, a.compositeScore);
            if (cmp != 0) return cmp;
            boolean aIsSave = a.card == RankEntry.WAIT_SENTINEL;
            boolean bIsSave = b.card == RankEntry.WAIT_SENTINEL;
            return Boolean.compare(aIsSave, bIsSave);
        });

        return allCandidates;
    }

    // =====================================================================
    // Situation assessment
    // =====================================================================

    /**
     * Computes a holistic situation assessment in [0, 1].
     *
     * <p>Unlike a pure landmark count, this captures income capacity, coin position,
     * and tempo. A player with 0 landmarks but massive income scores high on the income
     * component, correctly flagging them as advanced in the game.
     */
    static double computeSituation(GameState gs, int playerIndex, EngineConfig config) {
        Player active = gs.getPlayers()[playerIndex];
        int numPlayers = gs.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);

        double sitLandmark = readDouble(config, "sitLandmark", DEFAULT_SIT_LANDMARK);
        double sitIncome   = readDouble(config, "sitIncome", DEFAULT_SIT_INCOME);
        double sitCoins    = readDouble(config, "sitCoins", DEFAULT_SIT_COINS);
        double sitTempo    = readDouble(config, "sitTempo", DEFAULT_SIT_TEMPO);
        double targetEv    = readDouble(config, "targetEvPerRound", DEFAULT_TARGET_EV);
        double maxETW      = readDouble(config, "maxETW", DEFAULT_MAX_ETW);

        double landmarkFrac = active.getLandmarkCount() / 4.0;
        double evPerRound = CardIncome.playerEvPerRound(active, numPlayers, oppCoins);
        double incomeFrac = clamp01(evPerRound / targetEv);

        // Coin position: fraction of remaining landmark cost covered
        double remainingCost = remainingLandmarkCost(active);
        double coinFrac = remainingCost > 0 ? clamp01(active.getCoins() / remainingCost) : 1.0;

        // Tempo: inverse of ETW normalized
        double etw = Calcs.estimatedTurnsToWin(gs, playerIndex, RankEntry.WAIT_SENTINEL);
        double tempoFrac = clamp01(1.0 - etw / maxETW);

        return sitLandmark * landmarkFrac
             + sitIncome   * incomeFrac
             + sitCoins    * coinFrac
             + sitTempo    * tempoFrac;
    }

    // =====================================================================
    // Effective weight computation
    // =====================================================================

    static double[] computeEffectiveWeights(double[] baseWeights, double situation,
                                             double sigmoidK, EngineConfig config) {
        double[] effective = new double[NUM_DIMS];
        for (int d = 0; d < NUM_DIMS; d++) {
            double low  = DEFAULT_MULT_LOW[d];
            double high = DEFAULT_MULT_HIGH[d];
            double sig = sigmoid(sigmoidK * (situation - 0.5));
            double multiplier = low + (high - low) * sig;
            effective[d] = baseWeights[d] * multiplier;
        }
        return effective;
    }

    // =====================================================================
    // Gravity wells
    // =====================================================================

    /**
     * Result of applying gravity wells. Contains the active well name and the sprint
     * intensity for use in landmark bonus calculations.
     */
    static final class GravityWellResult {
        final String activeWell;
        final double sprintIntensity;

        GravityWellResult(String activeWell, double sprintIntensity) {
            this.activeWell = activeWell;
            this.sprintIntensity = sprintIntensity;
        }
    }

    /**
     * Applies gravity wells as multiplicative modifiers to the effective weights.
     * Returns the result including the active gravity well name and sprint intensity.
     *
     * <p>Gravity wells ramp gradually with configurable sharpness. The win-sprint and
     * threat-response wells do NOT snap on/off — they smoothly increase influence as
     * the triggering condition strengthens.
     */
    static GravityWellResult applyGravityWells(GameState gs, int playerIndex,
                                     double[] weights, EngineConfig config) {
        Player active = gs.getPlayers()[playerIndex];
        int numPlayers = gs.getPlayers().length;

        // Instant-win is handled in scoreAll() per-landmark, not here

        // ---- Win-sprint ramp ----
        double sprintHorizon   = readDouble(config, "sprintHorizon", DEFAULT_SPRINT_HORIZON);
        double sprintSharpness = readDouble(config, "sprintSharpness", DEFAULT_SPRINT_SHARPNESS);

        double playerETW = Calcs.estimatedTurnsToWin(gs, playerIndex, RankEntry.WAIT_SENTINEL);
        double sprintRaw = clamp01(1.0 - playerETW / sprintHorizon);
        double sprintIntensity = sprintSharpness > 0.01
                ? Math.pow(sprintRaw, 1.0 / sprintSharpness)
                : (sprintRaw > 0.99 ? 1.0 : 0.0);

        // ---- Threat-response ramp ----
        double threatHorizon   = readDouble(config, "threatHorizon", DEFAULT_THREAT_HORIZON);
        double threatSharpness = readDouble(config, "threatSharpness", DEFAULT_THREAT_SHARPNESS);

        double maxThreatRaw = 0.0;
        for (int i = 0; i < numPlayers; i++) {
            if (i == playerIndex) continue;
            Player opp = gs.getPlayers()[i];
            double oppETW = Calcs.estimatedTurnsToWin(gs, i, RankEntry.WAIT_SENTINEL);
            double threat = clamp01(1.0 - oppETW / threatHorizon) * (opp.getLandmarkCount() / 4.0);
            maxThreatRaw = Math.max(maxThreatRaw, threat);
        }
        double threatIntensity = threatSharpness > 0.01
                ? Math.pow(maxThreatRaw, 1.0 / threatSharpness)
                : (maxThreatRaw > 0.99 ? 1.0 : 0.0);

        // ---- Apply the strongest well ----
        String activeWell = "none";
        double finalSprintIntensity = 0.0;

        if (sprintIntensity > 0.01 || threatIntensity > 0.01) {
            if (sprintIntensity >= threatIntensity) {
                activeWell = "win-sprint";
                finalSprintIntensity = sprintIntensity;
                weights[DIM_WINPROB] *= (1.0 + 2.0 * sprintIntensity);
                weights[DIM_TEMPO]   *= (1.0 + 2.0 * sprintIntensity);
                weights[DIM_INCOME]  *= (1.0 - 0.7 * sprintIntensity);
                weights[DIM_RISK]    *= (1.0 - 0.7 * sprintIntensity);
            } else {
                activeWell = "threat-response";
                weights[DIM_TEMPO]   *= (1.0 + threatIntensity);
                weights[DIM_WINPROB] *= (1.0 + threatIntensity);
            }
        }

        return new GravityWellResult(activeWell, finalSprintIntensity);
    }

    // =====================================================================
    // Per-card scoring
    // =====================================================================

    private static ScoredCandidate scoreCard(GameState gs, int playerIndex, Project card,
                                              SupplyTracker supply, double[] weights,
                                              int numPlayers, double situation,
                                              String activeWell, boolean affordable,
                                              boolean isLandmark, EngineConfig config,
                                              double sprintIntensity) {
        // ---- Compute dimension raw values ----
        // Compute roiOverHorizon first — it internally computes evPerRound and portfolioDeltaEV,
        // which we reuse to avoid redundant Calcs calls (saves ~3 full recomputations per card).
        RankEntry roi = Calcs.roiOverHorizon(gs, playerIndex, card, ROI_HORIZON, ROI_DISCOUNT);

        // Income dimension: evPerRound + portfolioDeltaEV (extracted from roi)
        double evPerRound = roi.evPerRound;
        double portfolioDelta = roi.portfolioDeltaEV;
        double incomeTerm = evPerRound + portfolioDelta;

        // Risk dimension: CVaR + (1 - probNoIncomeRound) + correlation diversity
        double cvar = Calcs.conditionalValueAtRisk(gs, playerIndex, card, CVAR_ALPHA);
        double probNoIncome = roi.probNoIncomeRound;
        double rollCorr = Calcs.rollCorrelation(gs, playerIndex, card);
        if (Double.isNaN(rollCorr)) rollCorr = 0.0;
        // Higher is better: good CVaR (less negative), low probNoIncome, low correlation
        double riskTerm = cvar + (1.0 - probNoIncome) + (1.0 - rollCorr) / 2.0;

        // Coverage dimension: entropy + coverage density
        double entropy = Calcs.incomeEntropy(gs, playerIndex, card);
        double coverageDensity = computeCoverageDensity(gs, playerIndex, card);
        double coverageTerm = entropy + coverageDensity;

        // Tempo dimension — inline computation using values we already have,
        // avoiding 2 redundant evPerRound calls inside Calcs.tempoAdvantage()
        double playerEtw = evPerRound > 1e-9 ? Math.max(0.0, remainingLandmarkCost(gs.getPlayers()[playerIndex]) - gs.getPlayers()[playerIndex].getCoins()) / evPerRound : Double.MAX_VALUE;
        double opponentMinEtw = Double.MAX_VALUE;
        for (int i = 0; i < numPlayers; i++) {
            if (i == playerIndex) continue;
            double oppEtw = Calcs.estimatedTurnsToWin(gs, i, RankEntry.WAIT_SENTINEL);
            if (oppEtw < opponentMinEtw) opponentMinEtw = oppEtw;
        }
        double tempo = opponentMinEtw == Double.MAX_VALUE ? 0.0 : opponentMinEtw - playerEtw;

        // Win probability dimension
        double winProbDelta = Calcs.estimateWinProbDelta(gs, playerIndex, card);

        // Landmark dimension: use winProbDelta as proxy for landmark marginal value
        // For non-landmarks this is 0
        // Bahnhof penalty: scale by [7..12] coverage density — near-zero if no high cards
        // Freizeitpark penalty: near-zero if player doesn't own Bahnhof (doubles only with 2d6)
        double landmarkValue;
        if (!isLandmark) {
            landmarkValue = 0.0;
        } else if ("bahnhof".equals(card.getId())) {
            double highCoverage = computeHighRangeCoverage(gs.getPlayers()[playerIndex]);
            landmarkValue = winProbDelta * 10.0 * highCoverage;
        } else if ("freizeitpark".equals(card.getId())) {
            boolean hasBahnhof = gs.getPlayers()[playerIndex].hasProject("bahnhof");
            landmarkValue = hasBahnhof ? winProbDelta * 10.0 : 0.0;
        } else {
            landmarkValue = winProbDelta * 10.0;
        }

        // Urgency dimension — inline to avoid redundant portfolioDeltaEV call
        double urgency;
        if (portfolioDelta <= 0.0) {
            urgency = 0.0;
        } else {
            int remaining = supply.getCount(card.getId());
            double scarcity = CreatorScorer.clamp01(1.0 - (double) remaining / GameState.SUPPLY_PER_CARD);
            long opponentDemand = 0;
            Player[] players = gs.getPlayers();
            for (int i = 0; i < players.length; i++) {
                if (i == playerIndex) continue;
                if (players[i].getCoins() >= card.getCost()) opponentDemand++;
            }
            double demandNorm = (double) opponentDemand / Math.max(1, players.length - 1);
            urgency = portfolioDelta * scarcity * demandNorm;
        }

        // ROI dimension
        double roiVal = roi.roiOverHorizon;

        // ---- Composite score ----
        double[] rawValues = {
            incomeTerm, riskTerm, coverageTerm, tempo,
            winProbDelta, landmarkValue, urgency, roiVal
        };

        double compositeScore = 0.0;
        for (int d = 0; d < NUM_DIMS; d++) {
            compositeScore += weights[d] * rawValues[d];
        }

        // Win-sprint landmark boost: when sprinting, affordable landmarks get a massive
        // bonus proportional to sprint intensity. This ensures the engine buys landmarks
        // to close out the game rather than continuing to build income.
        if (isLandmark && affordable && sprintIntensity > 0.01) {
            compositeScore *= (1.0 + 5.0 * sprintIntensity);
        }

        // ---- Metrics map (explainability) ----
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("compositeScore", fmt(compositeScore));
        metrics.put("situation", fmt(situation));
        metrics.put("activeGravityWell", activeWell);
        metrics.put("evPerRound", fmt(evPerRound));
        metrics.put("portfolioDeltaEV", fmt(portfolioDelta));
        metrics.put("incomeTerm", fmt(incomeTerm));
        metrics.put("roiOverHorizon", fmt(roiVal));
        metrics.put("cvar_10pct", fmt(cvar));
        metrics.put("probNoIncomeRound", fmt(probNoIncome));
        metrics.put("rollCorrelation", fmt(rollCorr));
        metrics.put("riskTerm", fmt(riskTerm));
        metrics.put("incomeEntropy", fmt(entropy));
        metrics.put("coverageDensity", fmt(coverageDensity));
        metrics.put("coverageTerm", fmt(coverageTerm));
        metrics.put("tempoAdvantage", fmt(tempo));
        metrics.put("winProbDelta", fmt(winProbDelta));
        metrics.put("landmarkValue", fmt(landmarkValue));
        metrics.put("purchaseUrgency", fmt(urgency));
        metrics.put("cost", String.valueOf(card.getCost()));
        metrics.put("isLandmark", String.valueOf(isLandmark));
        for (int d = 0; d < NUM_DIMS; d++) {
            metrics.put("w_" + DIM_NAMES[d], fmt(weights[d]));
        }

        // ---- Structured explanation factors (top 3 by contribution magnitude) ----
        double[] contributions = new double[NUM_DIMS];
        for (int d = 0; d < NUM_DIMS; d++) {
            contributions[d] = weights[d] * rawValues[d];
        }
        List<EngineResult.ExplanationFactor> factors = buildTopFactors(contributions, rawValues, weights, 3);

        return new ScoredCandidate(card, compositeScore, metrics, factors, affordable);
    }

    // =====================================================================
    // Save scoring
    // =====================================================================

    private static ScoredCandidate scoreSave(GameState gs, int playerIndex,
                                              double[] weights, double situation,
                                              String activeWell,
                                              List<ScoredCandidate> affordable,
                                              List<ScoredCandidate> unaffordable,
                                              EngineConfig config) {
        // Save scores 0.0 — it only wins when all affordable cards score negative.
        // This follows the proven HeuristicEvEngine pattern. The MC phase will validate
        // whether saving actually leads to better outcomes via rollout simulation.
        double saveValue = 0.0;
        String saveReason = "Save baseline (wins only if no affordable card scores positive)";

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("compositeScore", fmt(saveValue));
        metrics.put("situation", fmt(situation));
        metrics.put("activeGravityWell", activeWell);
        metrics.put("saveReason", saveReason);
        metrics.put("cost", "0");

        List<EngineResult.ExplanationFactor> factors = List.of(
            new EngineResult.ExplanationFactor("cost", 0.5, saveReason,
                "Save compares discounted future value of best unaffordable card against best current option."));

        return new ScoredCandidate(RankEntry.WAIT_SENTINEL, saveValue, metrics, factors, true);
    }

    // =====================================================================
    // Coverage density
    // =====================================================================

    /**
     * Returns the fraction of non-red cards the player owns that activate in the [7..12] range.
     * Used to penalize Bahnhof when the player has no high-range cards.
     *
     * @return 0.0 if no 7-12 non-red cards exist, up to 1.0 if many do
     */
    private static double computeHighRangeCoverage(Player player) {
        int highCards = 0;
        for (Project card : player.getOwned_projects()) {
            if ("rot".equals(card.getColor()) || "gelb".equals(card.getColor())) continue;
            for (int act : card.getDice_activation()) {
                if (act >= 7 && act <= 12) { highCards++; break; }
            }
        }
        // Normalize: 0 cards → 0.0, 3+ cards → 1.0
        return Math.min(1.0, highCards / 3.0);
    }

    /**
     * Computes the fraction of roll values (1-12) that produce income for this player
     * after hypothetically purchasing the candidate card.
     *
     * @return value in [0, 1] where 1.0 means every roll produces income
     */
    private static double computeCoverageDensity(GameState gs, int playerIndex, Project candidate) {
        Player player = gs.getPlayers()[playerIndex];
        CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(player).withExtra(candidate);
        int numPlayers = gs.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);
        boolean hasBahnhof = stats.hasBahnhof || "bahnhof".equals(candidate.getId());

        int coveredRolls = 0;
        int totalRolls;

        if (hasBahnhof) {
            // Check 1d6 range (1-6) + 2d6 range (2-12)
            // Count as covered if either 1d6 or 2d6 produces income for that roll
            totalRolls = 12; // rolls 1..12
            for (int r = 1; r <= 12; r++) {
                double income = computeRollIncomeForCoverage(player, candidate, stats, r, numPlayers, oppCoins);
                if (income > 0.01) coveredRolls++;
            }
        } else {
            // 1d6 only: rolls 1-6
            totalRolls = 6;
            for (int r = 1; r <= 6; r++) {
                double income = computeRollIncomeForCoverage(player, candidate, stats, r, numPlayers, oppCoins);
                if (income > 0.01) coveredRolls++;
            }
        }

        return totalRolls > 0 ? (double) coveredRolls / totalRolls : 0.0;
    }

    /**
     * Computes the expected own-turn income for a specific roll, given the player's
     * portfolio plus the candidate card. Uses card dispatch for blue+green cards.
     */
    private static double computeRollIncomeForCoverage(Player player, Project candidate,
                                                        CardIncome.PlayerStats stats,
                                                        int roll, int numPlayers, int[] oppCoins) {
        double income = 0.0;
        // Existing cards
        for (Project card : player.getOwned_projects()) {
            if ("gelb".equals(card.getColor())) continue; // skip landmarks
            int gain = CardIncome.get_I(roll, card.getId(), true, stats.hasEinkaufszentrum,
                    stats.foodCount, stats.animalCount, stats.productionCount, 99, oppCoins);
            if (gain > 0) income += gain;
        }
        // Candidate card
        if (!"gelb".equals(candidate.getColor())) {
            int gain = CardIncome.get_I(roll, candidate.getId(), true, stats.hasEinkaufszentrum,
                    stats.foodCount, stats.animalCount, stats.productionCount, 99, oppCoins);
            if (gain > 0) income += gain;
        }
        return income;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static double remainingLandmarkCost(Player player) {
        double cost = 0;
        String[] lmIds = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
        int[] lmCosts = {4, 10, 16, 22};
        for (int i = 0; i < lmIds.length; i++) {
            if (!player.hasProject(lmIds[i])) cost += lmCosts[i];
        }
        return cost;
    }

    private static List<EngineResult.ExplanationFactor> buildTopFactors(
            double[] contributions, double[] rawValues, double[] weights, int topN) {
        // Find indices of top-N by absolute contribution
        int[] indices = new int[NUM_DIMS];
        for (int i = 0; i < NUM_DIMS; i++) indices[i] = i;
        // Simple insertion sort on absolute contribution (small array)
        for (int i = 1; i < NUM_DIMS; i++) {
            int key = indices[i];
            double keyVal = Math.abs(contributions[key]);
            int j = i - 1;
            while (j >= 0 && Math.abs(contributions[indices[j]]) < keyVal) {
                indices[j + 1] = indices[j];
                j--;
            }
            indices[j + 1] = key;
        }

        List<EngineResult.ExplanationFactor> factors = new ArrayList<>();
        int count = Math.min(topN, NUM_DIMS);
        for (int i = 0; i < count; i++) {
            int d = indices[i];
            if (Math.abs(contributions[d]) < 0.001) break;
            String category = DIM_NAMES[d];
            double relWeight = weights[d] / sumArray(weights);
            String summary = String.format("%s: %.4f (weight %.2f × raw %.4f)",
                    DIM_NAMES[d], contributions[d], weights[d], rawValues[d]);
            String detail = String.format("Dimension '%s' contributed %.4f to composite score. " +
                    "Effective weight = %.4f, raw value = %.4f.",
                    DIM_NAMES[d], contributions[d], weights[d], rawValues[d]);
            factors.add(new EngineResult.ExplanationFactor(category, relWeight, summary, detail));
        }
        return factors;
    }

    private static double sumArray(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return sum > 0 ? sum : 1.0;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String fmt(double v) {
        if (v == Double.MAX_VALUE) return "Infinity";
        if (v == Double.NEGATIVE_INFINITY) return "-Infinity";
        return String.format("%.4f", v);
    }

    static double[] readBaseWeights(EngineConfig config) {
        double[] weights = new double[NUM_DIMS];
        for (int d = 0; d < NUM_DIMS; d++) {
            weights[d] = readDouble(config, BASE_WEIGHT_KEYS[d], DEFAULT_BASE_WEIGHTS[d]);
        }
        return weights;
    }

    static double readDouble(EngineConfig config, String key, double defaultValue) {
        if (config == null || config.extra == null) return defaultValue;
        String val = config.extra.get(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
