package engine.rulebased;

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
 * <p>Implements a hand-crafted 2-player strategy evaluated top-to-bottom.
 * The first rule whose condition fires and whose card is affordable + in supply
 * wins. If nothing fires: save.
 *
 * <h2>Priority order</h2>
 * <ol>
 *   <li>Instant-win (3 landmarks owned, can afford 4th).</li>
 *   <li>Einkaufszentrum (shopping mall) — buy as soon as affordable.</li>
 *   <li>Funkturm (radio tower) — buy right after EKZ; reroll ability is immediately valuable.</li>
 *   <li>Fernsehsender (TV station) — buy after EKZ+Funkturm check.</li>
 *   <li>Wald — buy once, after at least 2 mini-markts are owned.</li>
 *   <li>Mini-markt — buy every copy available when coins &ge; 3.</li>
 *   <li>Bäckerei — buy whenever mini-markt is unaffordable this turn (coins &lt; 3) or supply exhausted;
 *       always buy at coins &ge; 1 — never waste tempo.</li>
 *   <li>Bahnhof + Freizeitpark — only after EKZ and Funkturm owned; buy bahnhof first,
 *       then FZP, once 75th-percentile 2-turn income projection covers both costs.</li>
 *   <li>Blue fallback (bauernhof, weizenfeld) — only after both mini-markt and bäckerei supply exhausted.</li>
 *   <li>Red fallback — one copy only; same supply gate as blue.</li>
 *   <li>Save.</li>
 * </ol>
 *
 * <p>All evaluation is BitState-native; no {@code toGameState()} in the hot path.
 */
public final class RuleBasedEngine implements SimulationEngine {

    // Normal card indices (BitStateTranslator.NORMAL_CARD_IDS order)
    private static final int IDX_WEIZENFELD         = 0;
    private static final int IDX_BAECKEREI          = 1;
    private static final int IDX_BAUERNHOF          = 2;
    private static final int IDX_WALD               = 3;
    private static final int IDX_MINI_MARKT         = 4;
    private static final int IDX_CAFE               = 5;
    private static final int IDX_FAMILIENRESTAURANT = 10;

    // Purple card indices
    private static final int PURPLE_FERNSEHSENDER = 1;

    // Landmark indices
    private static final int LM_BAHNHOF  = BitStateTranslator.LM_BAHNHOF;
    private static final int LM_EKZ      = BitStateTranslator.LM_EKZ;
    private static final int LM_FZP      = BitStateTranslator.LM_FZP;
    private static final int LM_FUNKTURM = BitStateTranslator.LM_FT;

    /** z-score for 75th percentile (mean - 0.674σ for a lower bound). */
    private static final double Z75 = 0.674;

    @Override
    public String id() { return "rule-based"; }

    @Override
    public String description() { return "Rule-Based — deterministic 2p priority strategy"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        BitState bs = BitState.fromGameState(state);
        int diceCount = bs.hasLandmark(playerIndex, LM_BAHNHOF) ? 2 : 1;
        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        TurnPlan plan = SimulationEngine.staticPlanWithInstantWinPriority(
                diceCount, result, state, playerIndex, elapsed);
        plan.scoreIsWinRate = false;
        return plan;
    }

    /**
     * Reroll if the current roll yields below-average expected income.
     * "Average" = E[income] over all possible outcomes, weighted by dice probabilities.
     */
    @Override
    public boolean decideFunkturm(engine.TurnPlan plan, GameState state, int playerIndex,
                                   int roll, boolean isDoubles, engine.EngineConfig config) {
        core.BitState bs = core.BitState.fromGameState(state);
        boolean twoDice = plan.diceCount == 2;

        double currentIncome = bs.computeActivePlayerRollIncome(playerIndex, roll);

        double expectedIncome = 0.0;
        if (!twoDice) {
            for (int r = 1; r <= 6; r++) {
                expectedIncome += core.CardIncome.P1[r] * bs.computeActivePlayerRollIncome(playerIndex, r);
            }
        } else {
            for (int r = 2; r <= 12; r++) {
                double prob = core.CardIncome.P2[r];
                if (prob <= 0) continue;
                expectedIncome += prob * bs.computeActivePlayerRollIncome(playerIndex, r);
            }
        }

        return currentIncome >= expectedIncome;
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();
        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();

        String chosen = choosePurchase(bs, supply, playerIndex);

        List<EngineResult.Option> options = new ArrayList<>();
        core.Project chosenProject = resolveProject(chosen);

        if (chosenProject != null) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("rule", chosen);
            m.put("cost", String.valueOf(chosenProject.getCost()));
            options.add(new EngineResult.Option(chosenProject, 1.0, List.of(), m, true));
        }

        Map<String, String> saveM = new LinkedHashMap<>();
        saveM.put("rule", "save");
        options.add(new EngineResult.Option(calcs.RankEntry.WAIT_SENTINEL, 0.0, List.of(), saveM, true));

        long elapsed = System.currentTimeMillis() - startTime;
        return new EngineResult(options, 0.0, 0, elapsed, "rule:" + chosen);
    }

    // -------------------------------------------------------------------------
    // Rule evaluation — strictly top-to-bottom priority
    // -------------------------------------------------------------------------

    private String choosePurchase(BitState bs, int[] supply, int p) {
        int coins = bs.getCoins(p);

        // 0. Instant win
        String win = findInstantWin(bs, p, coins);
        if (win != null) return win;

        // 1. Einkaufszentrum (shopping mall) — highest landmark priority
        if (!bs.hasLandmark(p, LM_EKZ)) {
            if (canBuyLandmark(coins, LM_EKZ)) return "einkaufszentrum";
        }

        // 2. Funkturm (radio tower) — right after EKZ; reroll ability is immediately valuable
        if (bs.hasLandmark(p, LM_EKZ) && !bs.hasLandmark(p, LM_FUNKTURM)) {
            if (canBuyLandmark(coins, LM_FUNKTURM)) return "funkturm";
        }

        // 3. Fernsehsender (TV station) — buy once affordable, after EKZ+Funkturm check
        if (!bs.hasPurple(p, PURPLE_FERNSEHSENDER)) {
            int cost = BitStateTranslator.PURPLE_CARD_COSTS[PURPLE_FERNSEHSENDER];
            if (coins >= cost) return "fernsehsender";
        }

        // 4. Wald — once, after at least 2 mini-markts are owned
        if (bs.getCardCount(p, IDX_MINI_MARKT) >= 2 && bs.getCardCount(p, IDX_WALD) == 0) {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_WALD];
            if (coins >= cost && supply[IDX_WALD] > 0) return "wald";
        }

        // 5. Mini-markt — buy every copy available when coins >= 3 (keep at least 1 coin after, cost=2)
        if (coins >= 3) {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_MINI_MARKT];
            if (coins >= cost && supply[IDX_MINI_MARKT] > 0) return "mini-markt";
        }

        // 5b. Bäckerei — buy whenever mini-markt is unaffordable this turn (coins < 3) and coins >= 1.
        //     Also buy when coins >= 3 but mini-markt supply is exhausted.
        //     Never save when a 1-coin income card can be bought — tempo loss compounds quickly.
        {
            int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_BAECKEREI];
            boolean miniMarktAffordableAndAvailable = coins >= 3 && supply[IDX_MINI_MARKT] > 0;
            if (!miniMarktAffordableAndAvailable && coins >= cost && supply[IDX_BAECKEREI] > 0)
                return "bäckerei";
        }

        // 6. Bahnhof + Freizeitpark — only after EKZ and Funkturm are owned
        if (bs.hasLandmark(p, LM_EKZ) && bs.hasLandmark(p, LM_FUNKTURM)) {
            if (!bs.hasLandmark(p, LM_BAHNHOF) || !bs.hasLandmark(p, LM_FZP)) {
                String pair = checkBahnhofFzpPair(bs, p, coins);
                if (pair != null) return pair;
            }
        }

        // 7. Blue/red income fillers — only after both mini-markt and bäckerei supply exhausted
        if (supply[IDX_MINI_MARKT] == 0 && supply[IDX_BAECKEREI] == 0) {
            // Blue: bauernhof > weizenfeld
            int bauernhofCost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_BAUERNHOF];
            if (coins >= bauernhofCost && supply[IDX_BAUERNHOF] > 0) return "bauernhof";
            int weizenfeldCost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_WEIZENFELD];
            if (coins >= weizenfeldCost && supply[IDX_WEIZENFELD] > 0) return "weizenfeld";

            // Red: one copy only; familienrestaurant if opponent has Bahnhof (likely 2d6), else café
            int opp = 1 - p;
            boolean oppLikely2d6 = bs.hasLandmark(opp, LM_BAHNHOF);
            if (oppLikely2d6) {
                int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_FAMILIENRESTAURANT];
                if (coins >= cost && supply[IDX_FAMILIENRESTAURANT] > 0
                        && bs.getCardCount(p, IDX_FAMILIENRESTAURANT) == 0) return "familienrestaurant";
            } else {
                int cost = BitStateTranslator.NORMAL_CARD_COSTS[IDX_CAFE];
                if (coins >= cost && supply[IDX_CAFE] > 0
                        && bs.getCardCount(p, IDX_CAFE) == 0) return "café";
            }
        }

        return "save";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String findInstantWin(BitState bs, int p, int coins) {
        if (bs.getLandmarkCount(p) == 3) {
            for (int i = 0; i < BitStateTranslator.NUM_LANDMARKS; i++) {
                if (!bs.hasLandmark(p, i) && coins >= BitStateTranslator.LANDMARK_COSTS[i])
                    return BitStateTranslator.LANDMARK_IDS[i];
            }
        }
        return null;
    }

    private boolean canBuyLandmark(int coins, int landmarkIdx) {
        return coins >= BitStateTranslator.LANDMARK_COSTS[landmarkIdx];
    }

    /**
     * Returns "bahnhof" or "freizeitpark" when the 75th-percentile projection of
     * (current coins + 2 turns of income) covers the combined cost of both unowned
     * landmarks. Buys bahnhof first, then freizeitpark.
     */
    private String checkBahnhofFzpPair(BitState bs, int p, int coins) {
        boolean hasBahnhof = bs.hasLandmark(p, LM_BAHNHOF);
        boolean hasFzp     = bs.hasLandmark(p, LM_FZP);

        int costBahnhof = BitStateTranslator.LANDMARK_COSTS[LM_BAHNHOF];
        int costFzp     = BitStateTranslator.LANDMARK_COSTS[LM_FZP];
        int totalNeeded = (!hasBahnhof ? costBahnhof : 0) + (!hasFzp ? costFzp : 0);

        double ev = CardIncome.playerEvPerRound(bs, p);
        double meanIn2  = ev * 2.0;
        // Conservative σ for 2 rounds: sqrt(2 * 3 * ev) — empirically reasonable for mid-game portfolios
        double stdIn2   = Math.sqrt(2.0 * 3.0 * Math.max(ev, 0.5));
        double p75      = coins + meanIn2 - Z75 * stdIn2;

        if (p75 < totalNeeded) return null;

        if (!hasBahnhof && coins >= costBahnhof) return "bahnhof";
        if (hasBahnhof && !hasFzp && coins >= costFzp) return "freizeitpark";
        return null;
    }

    private static core.Project resolveProject(String id) {
        if ("save".equals(id)) return null;
        return core.ProjectLoader.getProject(id).orElse(null);
    }
}

