package engine.mcts;

import core.BitState;

/**
 * Variant C engine: MCTS with greedy tree selection at {@link engine.mcts.BuyDecisionNode}.
 *
 * <p>All nodes except {@link engine.mcts.BuyDecisionNode} use full UCT for selection.
 * At {@code BuyDecisionNode}, the child with the highest current win rate is always selected
 * (argmax exploitation, no exploration bonus). Rollout = uniform random (same as v1).
 *
 * <h2>Hypothesis</h2>
 * UCT exploration overhead at the purchase decision is not worth it; argmax over empirical
 * win rates is a sufficient selector when the rollout quality is high enough.
 */
public final class MctsGreedyTreeEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-greedy-tree";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS Variant C — greedy BuyDecisionNode selection, UCT elsewhere, uniform-random rollout";
    }

    @Override
    protected MctsTree buildTree(BitState bs, int[] supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, BitMctsRollout::simulateBit, true /* greedyBuySelection */);
    }

    @Override
    protected MctsTree buildFullTurnTree(BitState bs, int[] supply,
                                          int activePlayer, int playerPerspective,
                                          double explorationConstant) {
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, BitMctsRollout::simulateBit, true, true);
    }
}
