package engine.mcts;

import core.BitState;
import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;
import engine.TurnPlan;

/**
 * Variant E: adaptive iteration budget.
 *
 * <p>Runs a uniform iteration budget on the full-turn tree. The original adaptive
 * focused-phase algorithm (concentrating iterations on close top-2 children) was
 * removed because it doesn't apply cleanly to full-turn trees where BuyDecisionNodes
 * are spread across roll branches.
 *
 * <p>Tree structure and rollout policy are identical to {@link MctsV1Engine}.
 *
 * <p>Registry {@code engineClass}: {@code "mcts-v1-adaptive"}.
 */
public final class MctsAdaptiveEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-adaptive";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS Variant E — adaptive iteration budget concentrating on close races";
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startMs = System.currentTimeMillis();
        double explorationConstant = Double.parseDouble(
                config.getExtra("explorationConstant", "1.4142"));

        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();
        MctsTree tree = buildFullTurnTree(bs, supply, playerIndex, playerIndex, explorationConstant);

        int totalBudget     = config.iterations > 0 ? config.iterations : 100;

        // For full-turn trees, run all iterations uniformly (adaptive focus on buy decisions
        // doesn't apply cleanly since BuyDecisionNodes are spread across roll branches)
        tree.runIterations(totalBudget);

        long computeTimeMs = System.currentTimeMillis() - startMs;
        return buildResult(state, playerIndex, tree, tree.getIterationsPerformed(), computeTimeMs, config);
    }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long startMs = System.currentTimeMillis();
        double explorationConstant = Double.parseDouble(
                config.getExtra("explorationConstant", "1.4142"));

        BitState bs = BitState.fromGameState(state);
        int[] supply = bs.buildSupplyArray();
        MctsTree tree = buildFullTurnTree(bs, supply, playerIndex, playerIndex, explorationConstant);

        int totalBudget = config.iterations > 0 ? config.iterations : 100;

        // For full-turn eval, just run all iterations (adaptive focus is for buy decisions only)
        tree.runIterations(totalBudget);

        long computeTimeMs = System.currentTimeMillis() - startMs;

        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        int diceCount = 1;
        if (hasBahnhof && tree.fullTurnRoot instanceof DiceChoiceNode diceNode) {
            if (diceNode.expanded && diceNode.getChildren().size() == 2) {
                MctsNode best = MctsTree.bestChild(diceNode);
                diceCount = (diceNode.getChildren().indexOf(best) == 1) ? 2 : 1;
            }
        }

        return new TurnPlan(tree, diceCount, tree.getIterationsPerformed(), computeTimeMs);
    }
}
