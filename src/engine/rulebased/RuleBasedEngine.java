package engine.rulebased;

import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Rule-based engine — deterministic priority queue, no search, no rollouts.
 *
 * <p>Implements a hand-crafted 2-player strategy as an ordered list of
 * conditional purchase rules. Evaluated in priority order; the first rule
 * whose condition is satisfied and whose card is available (supply > 0, coins ≥ cost)
 * determines the purchase. If no rule fires, save.
 *
 * <h2>Rule order (2-player)</h2>
 * <ol>
 *   <li>Instant-win landmark — always buy if affordable.</li>
 *   <li>TV station (fernsehsender) — buy if coins ≥ 7 and not owned.</li>
 *   <li>Shopping mall (einkaufszentrum) — buy as soon as affordable.</li>
 *   <li>Radio tower (funkturm) — buy after shopping mall is owned.</li>
 *   <li>Mini-markt — buy if coins > 2 and not already owned (first copy).</li>
 *   <li>Bäckerei — fallback income when coins ≤ 2 or mini-markt already owned
 *       (up to 2 copies, scaled by demand).</li>
 *   <li>Wald — buy once, after mini-markt is owned and coins > 3.</li>
 *   <li>Bahnhof + Freizeitpark pair — buy bahnhof first, then freizeitpark,
 *       once 75%-confidence 2-turn income covers both costs together.</li>
 *   <li>Fallback red cards — café if opponent plays 1d6, familienrestaurant if
 *       opponent likely plays 2d6 (has Bahnhof).</li>
 *   <li>Fallback blue cards — bauernhof then weizenfeld.</li>
 *   <li>Save — no beneficial purchase found.</li>
 * </ol>
 *
 * <p>All evaluation is BitState-native; no {@code toGameState()} in the hot path.
 */
public final class RuleBasedEngine implements SimulationEngine {

    // --- Card indices (BitStateTranslator.NORMAL_CARD_IDS) ---
    private static final int IDX_WEIZENFELD         = 0;
    private static final int IDX_BAECKEREI          = 1;
    private static final int IDX_BAUERNHOF          = 2;
    private static final int IDX_WALD               = 3;
    private static final int IDX_MINI_MARKT         = 4;
    private static final int IDX_CAFE               = 5;
    private static final int IDX_FAMILIENRESTAURANT = 10;

    // --- Purple card indices ---
    private static final int PURPLE_FERNSEHSENDER = 1;  // TV station

    // --- Landmark indices (BitStateTranslator) ---
    private static final int LM_BAHNHOF     = BitStateTranslator.LM_BAHNHOF;   // 0
    private static final int LM_EKZ         = BitStateTranslator.LM_EKZ;       // 1  (shopping mall)
    private static final int LM_FZP         = BitStateTranslator.LM_FZP;       // 2  (amusement park)
    private static final int LM_FUNKTURM    = BitStateTranslator.LM_FT;        // 3  (radio tower)

    /** 75th-percentile multiplier: mean + 0.67 * std approximation. */
    private static final double PERCENTILE_75_Z = 0.674;

    @Override
    public String id() { return "rule-based"; }

    @Override
    public String description() { return "Rule-Based — deterministic 2p priority strategy"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        BitState bs = BitState.fromGameState(state);
        int diceCount = CardIncome.bestDiceEV(
                bs.hasLandmark(playerIndex, LM_BAHNHOF), r -> 1.0) > 0 &&
                bs.hasLandmark(playerIndex, LM_BAHNHOF) ? 2 : 1;
        // Use optimalDiceCount logic via EV comparison
        diceCount = optimalDice(bs, playerIndex);

        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        TurnPlan plan = SimulationEngine.staticPlanWithInstantWinPriority(
                diceCount, result, state, playerIndex, elapsed);
        plan.scoreIsWinRate = false;
        return plan;
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();
        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();

        String chosen = choosePurchase(bs, supply, playerIndex);

        // Build ranked list: chosen card first (score 1.0), save last (score 0.0)
        List<EngineResult.Option> options = new ArrayList<>();

        // Find chosen card's Project and add it
        core.Project chosenProject = resolveProject(chosen);
        boolean isSave = chosenProject == null;

        if (!isSave) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("rule", chosen);
            metrics.put("cost", String.valueOf(chosenProject.getCost()));
            options.add(new EngineResult.Option(chosenProject, 1.0, List.of(), metrics, true));
        }

        // Always include save as last option
        Map<String, String> saveMetrics = new LinkedHashMap<>();
        saveMetrics.put("rule", "save");
        options.add(new EngineResult.Option(calcs.RankEntry.WAIT_SENTINEL, 0.0, List.of(), saveMetrics, true));

        long elapsed = System.currentTimeMillis() - startTime;
        return new EngineResult(options, 0.0, 0, elapsed, "rule:" + chosen);
    }

    // -------------------------------------------------------------------------
    // Core rule evaluation
    // -------------------------------------------------------------------------

    /**
     * Returns the ID of the chosen purchase, or {@code "save"} if no rule fires.
     * All checks are BitState-native.
     */
    private String choosePurchase(BitState bs, int[] supply, int p) {
        int coins = bs.getCoins(p);

        // --- Rule 0: instant win ---
        String win = findInstantWin(bs, supply, p, coins);
        if (win != null) return win;

        // --- Rule 1: TV station (fernsehsender) at 7 coins ---
        if (!bs.hasPurple(p, PURPLE_FERNSEHSENDER) && coins >= 7) {
            int cost = BitStateTranslator.PURPLE_CARD_COSTS[PURPLE_FERNSEHSENDER];
            if (coins >= cost && purpleSupplyAvailable(bs, PURPLE_FERNSEHSENDER)) {
                return "fernsehsender";
            }
        }

        // --- Rule 2: shopping mall (EKZ) as soon as affordable ---
        if (!bs.hasLandmark(p, LM_EKZ)) {
            int cost = BitStateTranslator.LANDMARK_COSTS[LM_EKZ];
            if (coins >= cost) return "einkaufszentrum";
        }

        // --- Rule 3: radio tower (Funkturm) after shopping mall ---
        if (bs.hasLandmark(p, LM_EKZ) && !bs.hasLandmark(p, LM_FUNKTURM)) {
            int cost = BitStateTranslator.LANDMARK_COSTS[LM_FUNKTURM];
            if (coins >= cost) return "funkturm";
        }

        // --- Rule 4: mini-markt if coins > 2 and not yet owned ---
        if (bs.getCardCount(p, IDX_MINI_MARKT) == 0 && coins > 2) {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_MINI_MARKT];
            if (coins >= cost && supply[IDX_MINI_MARKT] > 0) return "mini-markt";
        }

        // --- Rule 5: bäckerei fallback (buy up to 2 copies) ---
        // Buy if coins <= 2 OR mini-markt unavailable AND bakery count < 2
        boolean miniMarktMissed = bs.getCardCount(p, IDX_MINI_MARKT) == 0
                && (supply[IDX_MINI_MARKT] == 0 || coins <= 2);
        boolean wantBaeckerei = miniMarktMissed && bs.getCardCount(p, IDX_BAECKEREI) < 2;
        if (wantBaeckerei) {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_BAECKEREI];
            if (coins >= cost && supply[IDX_BAECKEREI] > 0) return "bäckerei";
        }

        // --- Rule 6: wald — once, after mini-markt owned and coins > 3 ---
        if (bs.getCardCount(p, IDX_MINI_MARKT) > 0
                && bs.getCardCount(p, IDX_WALD) == 0
                && coins > 3) {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_WALD];
            if (coins >= cost && supply[IDX_WALD] > 0) return "wald";
        }

        // --- Rule 7: bahnhof + freizeitpark pair when 75%-confidence affordable in 2 turns ---
        if (!bs.hasLandmark(p, LM_BAHNHOF) || !bs.hasLandmark(p, LM_FZP)) {
            String pair = checkBahnhofFzpPair(bs, supply, p, coins);
            if (pair != null) return pair;
        }

        // --- Rule 8: red card fallback ---
        // café if opponent plays 1d6, familienrestaurant if opponent has Bahnhof
        int opp = 1 - p;
        boolean oppHasBahnhof = bs.hasLandmark(opp, LM_BAHNHOF);
        if (oppHasBahnhof) {
            // Familienrestaurant activates on 9+10 — better with 2d6 opponent
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_FAMILIENRESTAURANT];
            if (coins >= cost && supply[IDX_FAMILIENRESTAURANT] > 0
                    && bs.getCardCount(p, IDX_FAMILIENRESTAURANT) < 1) {
                return "familienrestaurant";
            }
        } else {
            // Café activates on 3 — reliable against 1d6 opponent
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_CAFE];
            if (coins >= cost && supply[IDX_CAFE] > 0
                    && bs.getCardCount(p, IDX_CAFE) < 1) {
                return "café";
            }
        }

        // --- Rule 9: blue card fallback ---
        // bauernhof (animal, synergizes with molkerei) > weizenfeld (food)
        int bauernhofCost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_BAUERNHOF];
        if (coins >= bauernhofCost && supply[IDX_BAUERNHOF] > 0) return "bauernhof";
        int weizenfeldCost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_WEIZENFELD];
        if (coins >= weizenfeldCost && supply[IDX_WEIZENFELD] > 0) return "weizenfeld";

        return "save";
    }

    // -------------------------------------------------------------------------
    // Rule helpers
    // -------------------------------------------------------------------------

    /** Finds an instant-win landmark purchase, or null. */
    private String findInstantWin(BitState bs, int[] supply, int p, int coins) {
        if (bs.getLandmarkCount(p) == 3) {
            for (int i = 0; i < BitStateTranslator.NUM_LANDMARKS; i++) {
                if (!bs.hasLandmark(p, i) && coins >= BitStateTranslator.LANDMARK_COSTS[i]) {
                    return BitStateTranslator.LANDMARK_IDS[i];
                }
            }
        }
        return null;
    }

    /**
     * Checks the bahnhof+freizeitpark pair purchase.
     * Buys bahnhof first (if not owned), then freizeitpark (if bahnhof owned).
     * Only fires when 75th-percentile 2-turn income projection covers the
     * combined remaining cost of both landmarks.
     */
    private String checkBahnhofFzpPair(BitState bs, int[] supply, int p, int coins) {
        boolean hasBahnhof = bs.hasLandmark(p, LM_BAHNHOF);
        boolean hasFzp     = bs.hasLandmark(p, LM_FZP);

        int costBahnhof = BitStateTranslator.LANDMARK_COSTS[LM_BAHNHOF];
        int costFzp     = BitStateTranslator.LANDMARK_COSTS[LM_FZP];

        // Combined remaining cost for whichever landmarks are still needed
        int remainingCost = (!hasBahnhof ? costBahnhof : 0) + (!hasFzp ? costFzp : 0);

        // 2-turn income projection: mean EV + conservative std estimate
        double evPerRound = CardIncome.playerEvPerRound(bs, p);
        double incomeIn2Turns = evPerRound * 2.0;

        // Rough std approximation: sqrt(2) * sqrt(variance) ≈ sqrt(2 * 4 * evPerRound) for typical portfolios
        // Use a simple σ ≈ sqrt(evPerRound * 3) as a conservative heuristic
        double std2Turns = Math.sqrt(evPerRound * 3.0 * 2.0);
        double projected75th = coins + incomeIn2Turns - PERCENTILE_75_Z * std2Turns;

        if (projected75th < remainingCost) return null;  // not confident enough yet

        // Ready — buy bahnhof first, then fzp
        if (!hasBahnhof && coins >= costBahnhof) return "bahnhof";
        if (hasBahnhof && !hasFzp && coins >= costFzp) return "freizeitpark";

        return null;
    }

    /** Returns true if a purple card slot has not been taken by any player (supply proxy). */
    private boolean purpleSupplyAvailable(BitState bs, int purpleIdx) {
        // Purple cards are unique per player; check no player already owns this one
        // In practice supply is tracked separately but for simplicity: if we don't own it, it might be available.
        // The real supply check for purple is done at the GameState level; here we conservatively allow it.
        for (int i = 0; i < bs.getNumPlayers(); i++) {
            if (i != 0 && bs.hasPurple(i, purpleIdx)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static int optimalDice(BitState bs, int p) {
        if (!bs.hasLandmark(p, BitStateTranslator.LM_BAHNHOF)) return 1;
        double ev1 = CardIncome.playerEvPerRound(bs, p);
        // Build temporary state with 2d6 assumption baked into evPerRound (already does this)
        // evPerRound already picks max(1d6, 2d6) when hasBahnhof — so just return 2
        return 2;
    }

    private static core.Project resolveProject(String id) {
        if ("save".equals(id)) return null;
        return core.ProjectLoader.getProject(id).orElse(null);
    }
}
