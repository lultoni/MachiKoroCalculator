package engine.mcts;

import core.BitState;
import core.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all MCTS tree nodes.
 *
 * <p>Each node stores a {@link BitState} (packed bitwise game state) and an {@code int[]}
 * supply array. The {@link #toGameState()} method lazily converts back to {@link GameState}
 * when needed at API boundaries (result extraction, TurnPlan).
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

    /** Bitwise game state at this node (post-action of the edge leading here). */
    public final BitState state;

    /**
     * Supply array matching {@link #state}: remaining market copies per normal card (indexed 0-11).
     * Null is legal (e.g., shared reference when supply is unchanged through a roll).
     */
    public final int[] supply;

    /**
     * Parent node; null for the root.
     *
     * <p><strong>Note:</strong> This field is not final so that {@link TreeNavigator#pruneAbove}
     * can sever the parent link after tree navigation, allowing GC to collect ancestor nodes
     * and sibling subtrees. Only {@code TreeNavigator} should set this to {@code null}.
     */
    public MctsNode parent;

    /** Expanded children, in the order they were added. */
    protected final List<MctsNode> children = new ArrayList<>();

    /** Cached unmodifiable view (live — reflects subsequent additions to children). */
    private List<MctsNode> unmodifiableChildren;

    /** Number of times this node has been visited during MCTS. */
    public int visitCount = 0;

    /** Cumulative score from all rollouts passing through this node (from root-player perspective). */
    public double totalScore = 0.0;

    /** True if all children have been created (node is fully expanded). */
    public boolean expanded = false;

    /** Lazily cached GameState conversion (for API boundary: TurnPlan, result extraction). */
    private GameState _cachedGameState;

    protected MctsNode(BitState state, int[] supply, MctsNode parent) {
        this.state  = state;
        this.supply = supply;
        this.parent = parent;
    }

    /**
     * Returns a {@link GameState} representation of this node's state.
     * Lazily cached — the conversion is only done once per node.
     */
    public GameState toGameState() {
        if (_cachedGameState == null) _cachedGameState = state.toGameState();
        return _cachedGameState;
    }

    // -------------------------------------------------------------------------
    // Children
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of this node's children. */
    public List<MctsNode> getChildren() {
        if (unmodifiableChildren == null) {
            unmodifiableChildren = java.util.Collections.unmodifiableList(children);
        }
        return unmodifiableChildren;
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
        for (int p = 0; p < state.getNumPlayers(); p++) {
            if (state.hasWon(p)) return true;
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
