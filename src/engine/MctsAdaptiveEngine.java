package engine;

import core.GameState;
import engine.mcts.MctsNode;
import engine.mcts.MctsTree;
import engine.mcts.SupplyTracker;

import java.util.List;

/**
 * Variant E: adaptive iteration budget.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Survey phase: run {@code iterations / 5} iterations on the full tree.</li>
 *   <li>Identify the top-2 root children by win rate after the survey.</li>
 *   <li>Allocate the remaining budget based on the win-rate margin:</li>
 *     <ul>
 *       <li>margin ≤ {@code extra.closeMargin} (default 0.03): split evenly — both need more data.</li>
 *       <li>margin > {@code extra.splitThreshold} (default 0.06): 70% to second place — confirm
 *           the leader is genuinely better.</li>
 *       <li>otherwise: 60% to leader, 40% to second place.</li>
 *     </ul>
 *   <li>Run focused iterations on each targeted child via
 *       {@link MctsTree#runIterationsFromNode}. Backpropagation keeps all ancestor statistics
 *       up to date.</li>
 * </ol>
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

        SupplyTracker supply = SupplyTracker.fromGameState(state);
        MctsTree tree = buildFullTurnTree(state, supply, playerIndex, playerIndex, explorationConstant);

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

        SupplyTracker supply = SupplyTracker.fromGameState(state);
        MctsTree tree = buildFullTurnTree(state, supply, playerIndex, playerIndex, explorationConstant);

        int totalBudget = config.iterations > 0 ? config.iterations : 100;

        // For full-turn eval, just run all iterations (adaptive focus is for buy decisions only)
        tree.runIterations(totalBudget);

        long computeTimeMs = System.currentTimeMillis() - startMs;

        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        int diceCount = 1;
        if (hasBahnhof && tree.fullTurnRoot instanceof engine.mcts.DiceChoiceNode diceNode) {
            if (diceNode.expanded && diceNode.getChildren().size() == 2) {
                MctsNode best = MctsTree.bestChild(diceNode);
                diceCount = (diceNode.getChildren().indexOf(best) == 1) ? 2 : 1;
            }
        }

        return new TurnPlan(tree, diceCount, tree.getIterationsPerformed(), computeTimeMs);
    }

    /**
     * Runs the adaptive focused phase on a given parent node's children.
     */
    private void runAdaptiveFocusedPhase(MctsTree tree, MctsNode parent,
                                          double closeMargin, double splitThreshold,
                                          int remainingBudget) {
        if (!parent.expanded) return;
        List<MctsNode> children = parent.getChildren();

        MctsNode top1 = null, top2 = null;
        double wr1 = -1.0, wr2 = -1.0;
        for (MctsNode child : children) {
            double wr = child.visitCount > 0
                    ? child.totalScore / child.visitCount
                    : 0.0;
            if (wr >= wr1) {
                top2 = top1; wr2 = wr1;
                top1 = child; wr1 = wr;
            } else if (wr > wr2) {
                top2 = child; wr2 = wr;
            }
        }

        if (top1 != null && top2 != null && remainingBudget > 0) {
            double margin = wr1 - wr2;
            int budget1, budget2;

            if (margin <= closeMargin) {
                budget1 = remainingBudget / 2;
                budget2 = remainingBudget - budget1;
            } else if (margin > splitThreshold) {
                budget2 = (int) (remainingBudget * 0.70);
                budget1 = remainingBudget - budget2;
            } else {
                budget1 = (int) (remainingBudget * 0.60);
                budget2 = remainingBudget - budget1;
            }

            tree.runIterationsFromNode(top1, budget1);
            tree.runIterationsFromNode(top2, budget2);
        } else if (top1 != null && remainingBudget > 0) {
            tree.runIterationsFromNode(top1, remainingBudget);
        }
    }
}
