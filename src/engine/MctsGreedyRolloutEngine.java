package engine;

import core.GameState;
import engine.mcts.GreedyRollout;
import engine.mcts.MctsTree;
import engine.mcts.SupplyTracker;

import java.util.List;

/**
 * Variant A engine: MCTS with a greedy rollout policy.
 *
 * <p>Identical to {@link MctsV1Engine} except the rollout phase uses
 * {@link GreedyRollout#simulate} instead of uniform-random decisions.
 * The tree phase (UCT selection, expansion, backpropagation) is unchanged.
 *
 * <p>Hypothesis: informed rollouts converge faster than uniform-random for the same
 * iteration budget, so the same number of iterations yields better recommendations.
 */
public final class MctsGreedyRolloutEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-greedy-rollout";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS Variant A — full UCT tree with greedy rollout policy";
    }

    @Override
    protected MctsTree buildTree(GameState state, SupplyTracker supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        return new MctsTree(state, supply, activePlayer, playerPerspective,
                explorationConstant, GreedyRollout::simulate);
    }

    @Override
    protected MctsTree buildFullTurnTree(GameState state, SupplyTracker supply,
                                          int activePlayer, int playerPerspective,
                                          double explorationConstant) {
        return new MctsTree(state, supply, activePlayer, playerPerspective,
                explorationConstant, GreedyRollout::simulate, false, true);
    }
}
