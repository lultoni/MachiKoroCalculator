package engine;

import engine.mcts.DepthLimitedRollout;
import engine.mcts.MctsTree;
import engine.mcts.SupplyTracker;
import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;

/**
 * Variant D: depth-limited rollout with heuristic evaluation.
 *
 * <p>Identical to {@link MctsV1Engine} except that rollouts stop after
 * {@code extra.maxRolloutDepth} turns (default {@code "10"}) and score the
 * resulting state using {@link calcs.WinProbability#computeBaselineWinProb}
 * instead of simulating to game completion.
 *
 * <p>Registry {@code engineClass}: {@code "mcts-v1-depth-limited"}.
 * Registry entries: {@code mcts-v1-depth3}, {@code mcts-v1-depth7}, {@code mcts-v1-depth10}.
 */
public final class MctsDepthLimitedEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-depth-limited";

    private static final ThreadLocal<Integer> currentMaxDepth = ThreadLocal.withInitial(() -> 10);

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS Variant D — depth-limited rollout with heuristic terminal evaluation";
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        int maxDepth = Integer.parseInt(config.getExtra("maxRolloutDepth", "10"));
        currentMaxDepth.set(maxDepth);
        try {
            return super.evaluate(state, playerIndex, config);
        } finally {
            currentMaxDepth.remove();
        }
    }

    @Override
    protected MctsTree buildTree(GameState state, SupplyTracker supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        int maxDepth = currentMaxDepth.get();
        return new MctsTree(state, supply, activePlayer, playerPerspective,
                explorationConstant, DepthLimitedRollout.withMaxDepth(maxDepth));
    }
}
