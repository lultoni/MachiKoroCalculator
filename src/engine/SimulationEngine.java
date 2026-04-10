package engine;

import core.GameState;

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
 * <h2>Layer contract</h2>
 * Engines may import from {@code calcs.*} and {@code core.*}.
 * Engines must NOT import from {@code ui.*} or {@code iface.*}.
 */
public interface SimulationEngine {

    /**
     * Returns the stable machine-readable identifier for this engine class
     * (e.g. {@code "mcts-v1"}). Must match the {@code "engine"} field in the registry JSON.
     */
    String id();

    /**
     * Returns a human-readable description of this engine for display in settings UI.
     */
    String description();

    /**
     * Evaluates the current game state and returns ranked purchase options for the
     * specified player.
     *
     * @param state       current game state (read-only; engine must copy before mutating)
     * @param playerIndex index of the player to advise (0-based)
     * @param config      engine-specific configuration (iterations, time budget, etc.)
     * @return ranked evaluation result; never null
     */
    EngineResult evaluate(GameState state, int playerIndex, EngineConfig config);

    /**
     * Evaluates a full turn from the start: dice choice, Funkturm, Bürohaus, purchase.
     *
     * <p>For H2H testing: roots the MCTS tree at {@link engine.mcts.DiceChoiceNode}
     * (if player has Bahnhof) or {@link engine.mcts.ChanceNode} (1d6 only), runs iterations,
     * then the match runner navigates the tree based on actual dice outcomes to extract
     * all decisions.
     *
     * <p>Returns a {@link TurnPlan} containing all decisions. The default implementation
     * throws {@link UnsupportedOperationException}; MCTS engines override this.
     *
     * @param state       current game state (read-only)
     * @param playerIndex index of the active player
     * @param config      engine configuration
     * @return turn plan with all decisions; never null
     */
    default TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        throw new UnsupportedOperationException("Engine does not support full-turn evaluation");
    }

    /**
     * Build a static TurnPlan from an EngineResult, with instant-win priority.
     *
     * <p>Non-MCTS engines evaluate the board <b>before</b> the dice roll and lock in a purchase
     * decision using pre-roll coins. This means they may miss an instant-win landmark if the
     * player can only afford it after roll income. This helper overrides the purchase to target
     * the winning landmark when the player has 3 landmarks — MatchRunner's affordability gate
     * ({@code getCoins() >= cost}) safely degrades to a save if the roll doesn't provide
     * enough income.
     *
     * <p>MCTS engines don't need this because their tree branches per roll outcome.
     */
    static TurnPlan staticPlanWithInstantWinPriority(
            int diceCount, EngineResult result, GameState state, int playerIndex, long elapsed) {
        core.Player player = state.getPlayers()[playerIndex];
        core.Project winLm = core.GameState.findInstantWinLandmark(player);
        if (winLm == null && player.getLandmarkCount() == 3) {
            // Player has 3 landmarks but can't afford the 4th pre-roll — still target it
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
