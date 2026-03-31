package engine.mcts;

import core.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all MCTS tree nodes.
 *
 * <p>Each node stores the game state at that point in the tree, a reference to its parent,
 * and its children (populated during expansion). UCT statistics (visit count and total score)
 * are accumulated during backpropagation.
 *
 * <p>The score convention throughout the tree is always from the perspective of the
 * root's {@code playerIndex}:
 * <ul>
 *   <li>Win for that player = 1.0</li>
 *   <li>Loss = 0.0</li>
 *   <li>Timeout fallback = fractional softmax value in (0, 1)</li>
 * </ul>
 */
public abstract class MctsNode {

    /** Game state at this node (post-action of the edge leading here). */
    public final GameState state;

    /** Supply tracker matching {@link #state} (tracks non-landmark copy counts). */
    public final SupplyTracker supply;

    /** Parent node; null for the root. */
    public final MctsNode parent;

    /** Expanded children, in the order they were added. */
    protected final List<MctsNode> children = new ArrayList<>();

    /** Number of times this node has been visited during MCTS. */
    public int visitCount = 0;

    /** Cumulative score from all rollouts passing through this node (from root-player perspective). */
    public double totalScore = 0.0;

    /** True if all children have been created (node is fully expanded). */
    public boolean expanded = false;

    protected MctsNode(GameState state, SupplyTracker supply, MctsNode parent) {
        this.state  = state;
        this.supply = supply;
        this.parent = parent;
    }

    // -------------------------------------------------------------------------
    // Children
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of this node's children. */
    public List<MctsNode> getChildren() {
        return java.util.Collections.unmodifiableList(children);
    }

    // -------------------------------------------------------------------------
    // UCB1 score
    // -------------------------------------------------------------------------

    /**
     * Computes the UCB1 (UCT) score for child selection.
     *
     * <p>Unvisited children return {@link Double#POSITIVE_INFINITY} so they are always
     * selected before visited ones. This guarantees every child is sampled at least once
     * before exploitation begins.
     *
     * @param explorationConstant C in the UCB1 formula (typically √2 ≈ 1.4142)
     * @return UCB1 score from the root-player perspective
     */
    public double ucb1(double explorationConstant) {
        if (visitCount == 0) return Double.POSITIVE_INFINITY;
        double exploit = totalScore / visitCount;
        double explore = explorationConstant * Math.sqrt(Math.log(parent.visitCount) / (double) visitCount);
        return exploit + explore;
    }

    /**
     * Selects the child with the highest UCB1 score.
     *
     * @param explorationConstant UCT exploration constant
     * @return best child node (never null if children list is non-empty)
     * @throws IllegalStateException if the children list is empty
     */
    public MctsNode selectBestChild(double explorationConstant) {
        if (children.isEmpty()) throw new IllegalStateException("No children to select from");
        MctsNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MctsNode child : children) {
            double s = child.ucb1(explorationConstant);
            if (s > bestScore) { bestScore = s; best = child; }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Backpropagation helper
    // -------------------------------------------------------------------------

    /**
     * Walks from this node to the root, incrementing visit counts and accumulating the score.
     *
     * @param score rollout result from root-player perspective (in [0, 1])
     */
    public void backpropagate(double score) {
        MctsNode node = this;
        while (node != null) {
            node.visitCount++;
            node.totalScore += score;
            node = node.parent;
        }
    }

    // -------------------------------------------------------------------------
    // Abstract interface for tree traversal
    // -------------------------------------------------------------------------

    /**
     * Returns true if this node is a terminal state (someone has won).
     */
    public boolean isTerminal() {
        for (core.Player p : state.getPlayers()) {
            if (GameState.hasWon(p)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[visits=" + visitCount
                + ", score=" + String.format("%.3f", visitCount > 0 ? totalScore / visitCount : 0.0)
                + ", children=" + children.size() + "]";
    }
}
