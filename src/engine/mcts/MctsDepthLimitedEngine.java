package engine.mcts;

import core.BitState;
import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;
import engine.TurnPlan;

/**
 * Variant D: depth-limited rollout with heuristic evaluation.
 *
 * <p>Identical to {@link MctsV1Engine} except that rollouts stop after
 * {@code extra.maxRolloutDepth} turns (default {@code "10"}) and score the
 * resulting state using {@link calcs.WinProbability#computeBaselineWinProb}
 * instead of simulating to game completion.
 *
 * <p>When {@code extra.terminalEval} is {@code "hybrid"}, uses
 * {@link calcs.WinProbability#computeHybridWinProb} (5 MC rollouts) for
 * higher accuracy at the terminal. This is slower (~1-3ms per terminal)
 * and is recommended only with low iteration counts.
 *
 * <p>Registry {@code engineClass}: {@code "mcts-v1-depth-limited"}.
 */
public final class MctsDepthLimitedEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-depth-limited";

    private static final ThreadLocal<Integer> currentMaxDepth = ThreadLocal.withInitial(() -> 10);
    private static final ThreadLocal<Boolean> useHybrid = ThreadLocal.withInitial(() -> false);

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
        boolean hybrid = "hybrid".equals(config.getExtra("terminalEval", "heuristic"));
        currentMaxDepth.set(maxDepth);
        useHybrid.set(hybrid);
        try {
            return super.evaluate(state, playerIndex, config);
        } finally {
            currentMaxDepth.remove();
            useHybrid.remove();
        }
    }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        int maxDepth = Integer.parseInt(config.getExtra("maxRolloutDepth", "10"));
        boolean hybrid = "hybrid".equals(config.getExtra("terminalEval", "heuristic"));
        currentMaxDepth.set(maxDepth);
        useHybrid.set(hybrid);
        try {
            return super.evaluateFullTurn(state, playerIndex, config);
        } finally {
            currentMaxDepth.remove();
            useHybrid.remove();
        }
    }

    @Override
    protected MctsTree buildTree(BitState bs, int[] supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        int maxDepth = currentMaxDepth.get();
        BitRolloutFn rollout = useHybrid.get()
                ? BitMctsRollout.withMaxDepthHybrid(maxDepth)
                : BitMctsRollout.withMaxDepth(maxDepth);
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, rollout);
    }

    @Override
    protected MctsTree buildFullTurnTree(BitState bs, int[] supply,
                                          int activePlayer, int playerPerspective,
                                          double explorationConstant) {
        int maxDepth = currentMaxDepth.get();
        BitRolloutFn rollout = useHybrid.get()
                ? BitMctsRollout.withMaxDepthHybrid(maxDepth)
                : BitMctsRollout.withMaxDepth(maxDepth);
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, rollout, false, true);
    }

    @Override
    protected BitRolloutFn buildRolloutFn(EngineConfig config) {
        int maxDepth = Integer.parseInt(config.getExtra("maxRolloutDepth", "10"));
        boolean hybrid = "hybrid".equals(config.getExtra("terminalEval", "heuristic"));
        return hybrid ? BitMctsRollout.withMaxDepthHybrid(maxDepth)
                      : BitMctsRollout.withMaxDepth(maxDepth);
    }
}
