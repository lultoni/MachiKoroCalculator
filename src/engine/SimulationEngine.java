package engine;

import core.GameState;
import core.Project;

/**
 * Contract for all Machi Koro strategy engines.
 *
 * <p>An engine is a self-contained strategy implementation that, given the current
 * game state and the index of the player to advise, returns a ranked list of purchase
 * options with scores and explanation data.
 *
 * <p>Implementations must be stateless between calls — all mutable state is passed in
 * via {@link GameState} and {@link EngineConfig}. The engine registry instantiates each
 * engine class once and reuses the instance across calls.
 *
 * <h2>Full-turn decision flow</h2>
 * <ol>
 *   <li>{@link #planTurn} — decide dice count (pre-roll, may start tree search)</li>
 *   <li>MatchRunner rolls dice</li>
 *   <li>{@link #decideFunkturm} — keep or reroll (called only if player owns Funkturm)</li>
 *   <li>MatchRunner applies roll income to state</li>
 *   <li>{@link #decideBürohaus} — swap decision (called only if player owns Bürohaus and rolled 6)</li>
 *   <li>{@link #decidePurchase} — which card to buy, with actual post-roll coins</li>
 * </ol>
 *
 * <p>MCTS engines implement all steps via pre-built tree navigation. Non-MCTS engines
 * evaluate each decision directly on the current state at call time.
 *
 * <h2>Layer contract</h2>
 * Engines may import from {@code calcs.*} and {@code core.*}.
 * Engines must NOT import from {@code ui.*} or {@code iface.*}.
 */
public interface SimulationEngine {

    String id();
    String description();

    /**
     * Evaluates the current game state and returns ranked purchase options.
     * Called by the UI/API layer for standalone ranking display.
     *
     * @param state       current game state (read-only; engine must copy before mutating)
     * @param playerIndex index of the player to advise (0-based)
     * @param config      engine-specific configuration
     * @return ranked evaluation result; never null
     */
    EngineResult evaluate(GameState state, int playerIndex, EngineConfig config);

    /**
     * Step 1 of a full turn: decide dice count and start any pre-roll search.
     *
     * <p>Called before the dice are rolled. Returns a {@link TurnPlan} that carries
     * the dice count and, for MCTS engines, the pre-built search tree for subsequent
     * decision steps. Non-MCTS engines return a minimal plan with only diceCount set.
     *
     * <p>The default implementation uses {@link calcs.Calcs#optimalDiceCount} on the
     * current BitState. Override for MCTS tree construction or custom dice strategy.
     */
    default TurnPlan planTurn(GameState state, int playerIndex, EngineConfig config) {
        int diceCount = calcs.Calcs.optimalDiceCount(
                core.BitState.fromGameState(state), playerIndex);
        return TurnPlan.staticPlan(diceCount, calcs.RankEntry.WAIT_SENTINEL, 0.0, 0, 0L);
    }

    /**
     * Step 3 of a full turn: decide whether to use Funkturm to reroll.
     *
     * <p>Called after the dice have been rolled, before income is applied.
     * {@code plan} carries the current tree position for MCTS engines.
     * {@code state} is the pre-income state.
     *
     * @param plan        turn plan from {@link #planTurn} (may contain MCTS tree position)
     * @param state       pre-income game state
     * @param playerIndex the active player
     * @param roll        the actual dice roll total
     * @param isDoubles   whether the dice showed doubles
     * @param config      engine config
     * @return true to keep the roll, false to reroll
     */
    default boolean decideFunkturm(TurnPlan plan, GameState state, int playerIndex,
                                    int roll, boolean isDoubles, EngineConfig config) {
        // Default: keep the roll
        return true;
    }

    /**
     * Step 5 of a full turn: decide whether and how to execute a Bürohaus swap.
     *
     * <p>Called after income has been applied to {@code state} (post-roll).
     * Only called when the active player owns Bürohaus and rolled 6.
     *
     * @param plan        turn plan from {@link #planTurn}
     * @param state       post-income game state
     * @param playerIndex the active player
     * @param config      engine config
     * @return swap decision; return {@link BürohausDecision#noSwap()} to skip
     */
    default BürohausDecision decideBürohaus(TurnPlan plan, GameState state,
                                             int playerIndex, EngineConfig config) {
        // Default: greedy swap
        core.BürohausLogic.SwapCandidates c =
                core.BürohausLogic.findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return BürohausDecision.noSwap();
        return new BürohausDecision(c.worstOwn(), c.bestOpp(), c.bestOppPlayer());
    }

    /**
     * Step 6 of a full turn: decide which card to purchase.
     *
     * <p>Called after income and any Bürohaus swap have been applied to {@code state}.
     * {@code state} reflects the actual post-roll, post-swap coin count.
     *
     * @param plan        turn plan from {@link #planTurn}
     * @param state       post-income, post-swap game state
     * @param playerIndex the active player
     * @param config      engine config
     * @return card to buy, or {@link calcs.RankEntry#WAIT_SENTINEL} to save
     */
    default Project decidePurchase(TurnPlan plan, GameState state,
                                    int playerIndex, EngineConfig config) {
        EngineResult result = evaluate(state, playerIndex, config);
        EngineResult.Option top = result.topAffordableRecommendation();
        plan.purchaseWinRate = top.score;
        plan.engineResult = result;
        Project p = top.project;
        return "_wait_".equals(p.getId()) ? calcs.RankEntry.WAIT_SENTINEL : p;
    }

    /**
     * Carries a Bürohaus swap decision: which own card to give away, which opponent card
     * to take, and from which opponent.
     */
    record BürohausDecision(Project ownCard, Project oppCard, int oppPlayerIndex) {
        public static BürohausDecision noSwap() { return new BürohausDecision(null, null, -1); }
        public boolean isSwap() { return ownCard != null && oppCard != null && oppPlayerIndex >= 0; }
    }

    // -------------------------------------------------------------------------
    // Legacy full-turn method — used by MCTS engines only
    // -------------------------------------------------------------------------

    /**
     * Legacy entry point for MCTS engines: builds the full-turn tree pre-roll and
     * returns a TurnPlan that supports tree navigation via navigateRoll/navigateReroll.
     *
     * <p>Non-MCTS engines do NOT need to override this. MatchRunner calls
     * {@link #planTurn} instead, then drives the per-step decision methods.
     *
     * <p>MCTS engines override this AND override {@link #planTurn} to delegate here,
     * so that MatchRunner can use the unified flow.
     */
    default TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        throw new UnsupportedOperationException("Engine does not support full-turn evaluation");
    }

    /**
     * Build a static TurnPlan from an EngineResult, with instant-win priority.
     * Kept for compatibility; used by non-MCTS engines that still call evaluate() once.
     */
    static TurnPlan staticPlanWithInstantWinPriority(
            int diceCount, EngineResult result, GameState state, int playerIndex, long elapsed) {
        core.Player player = state.getPlayers()[playerIndex];
        core.Project winLm = core.GameState.findInstantWinLandmark(player);
        if (winLm == null && player.getLandmarkCount() == 3) {
            String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
            for (String lmId : LANDMARK_IDS) {
                if (!player.hasProject(lmId)) {
                    winLm = core.ProjectLoader.getProject(lmId).orElse(null);
                    break;
                }
            }
        }
        if (winLm != null) {
            return TurnPlan.staticPlan(diceCount, winLm, 1.0, result.iterationsUsed, elapsed, result);
        }
        EngineResult.Option top = result.topAffordableRecommendation();
        core.Project purchase = "_wait_".equals(top.project.getId()) ? null : top.project;
        return TurnPlan.staticPlan(diceCount,
                purchase != null ? purchase : calcs.RankEntry.WAIT_SENTINEL,
                top.score, result.iterationsUsed, elapsed, result);
    }
}
