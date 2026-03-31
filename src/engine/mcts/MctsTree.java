package engine.mcts;

import calcs.WinProbability;
import core.GameState;
import core.Player;

import java.util.List;

/**
 * MCTS tree manager: runs UCT selection → expansion → rollout → backpropagation
 * for a given number of iterations (or until a time budget is exhausted).
 *
 * <h2>Root node</h2>
 * The root is always a {@link BuyDecisionNode} representing the purchase decision
 * available to {@code playerIndex} right now. This is the node whose children correspond
 * to the ranked purchase options returned in {@link engine.EngineResult}.
 *
 * <h2>UCT selection</h2>
 * Traverses the tree from root to a leaf by repeatedly picking the child with the
 * highest UCB1 score. Unvisited children are selected first (UCB1 = +∞).
 *
 * <h2>Expansion</h2>
 * At a leaf node: if the node has never been visited, run a rollout from it directly.
 * If it has been visited and is not terminal, expand it (create all children) and
 * select one of the new children via UCB1 (which will all have visitCount 0 → +∞,
 * so the first is always selected first).
 *
 * <h2>Rollout</h2>
 * Delegates to {@link MctsRollout#simulate} for a uniform-random full-game simulation.
 *
 * <h2>Backpropagation</h2>
 * Calls {@link MctsNode#backpropagate} on the selected leaf, walking up to root.
 */
public final class MctsTree {

    /** UCT exploration constant C in UCB1 = exploit + C × sqrt(ln(N_parent) / N_child). */
    private final double explorationConstant;

    /** The player we are advising (root player). Score perspective throughout the tree. */
    private final int playerPerspective;

    /** The player whose turn it is at the root (the active player making a purchase). */
    private final int activePlayer;

    /** Root node: BuyDecisionNode for the current purchase decision. */
    public final BuyDecisionNode root;

    /** Total iterations performed so far. */
    private int iterationsPerformed = 0;

    /** Rollout strategy — defaults to the uniform-random MctsRollout. */
    private final RolloutFn rolloutFn;

    /**
     * When true, {@link BuyDecisionNode} child selection uses argmax over win rate
     * (greedy exploitation) instead of UCT. All other node types still use UCT.
     */
    private final boolean greedyBuySelection;

    /**
     * @param rootState           game state at the purchase decision point
     * @param rootSupply          supply tracker matching rootState
     * @param activePlayer        the player making the purchase decision
     * @param playerPerspective   the player we are advising (score perspective)
     * @param explorationConstant C in UCB1 formula (typically √2 ≈ 1.4142)
     */
    public MctsTree(GameState rootState, SupplyTracker rootSupply,
                    int activePlayer, int playerPerspective,
                    double explorationConstant) {
        this(rootState, rootSupply, activePlayer, playerPerspective, explorationConstant,
                MctsRollout::simulate);
    }

    /**
     * Full constructor accepting a custom rollout function.
     *
     * @param rolloutFn custom rollout strategy (e.g. greedy, Boltzmann)
     */
    public MctsTree(GameState rootState, SupplyTracker rootSupply,
                    int activePlayer, int playerPerspective,
                    double explorationConstant, RolloutFn rolloutFn) {
        this(rootState, rootSupply, activePlayer, playerPerspective, explorationConstant,
                rolloutFn, false);
    }

    /**
     * Full constructor accepting a custom rollout function and optional greedy buy selection.
     *
     * @param rolloutFn          custom rollout strategy
     * @param greedyBuySelection if true, {@link BuyDecisionNode} uses greedy (argmax) selection
     */
    public MctsTree(GameState rootState, SupplyTracker rootSupply,
                    int activePlayer, int playerPerspective,
                    double explorationConstant, RolloutFn rolloutFn,
                    boolean greedyBuySelection) {
        this.explorationConstant = explorationConstant;
        this.playerPerspective   = playerPerspective;
        this.activePlayer        = activePlayer;
        this.rolloutFn           = rolloutFn;
        this.greedyBuySelection  = greedyBuySelection;
        int nextPlayer = (activePlayer + 1) % rootState.getPlayers().length;
        this.root = new BuyDecisionNode(rootState, rootSupply, null, activePlayer, nextPlayer);
    }

    // -------------------------------------------------------------------------
    // Run iterations
    // -------------------------------------------------------------------------

    /**
     * Runs {@code count} MCTS iterations.
     */
    public void runIterations(int count) {
        for (int i = 0; i < count; i++) {
            runOneIteration();
        }
        iterationsPerformed += count;
    }

    /**
     * Runs iterations until {@code System.currentTimeMillis() >= deadlineMs}.
     *
     * @return number of iterations performed
     */
    public int runUntilDeadline(long deadlineMs) {
        int count = 0;
        while (System.currentTimeMillis() < deadlineMs) {
            runOneIteration();
            count++;
        }
        iterationsPerformed += count;
        return count;
    }

    /** Returns the total number of iterations completed. */
    public int getIterationsPerformed() {
        return iterationsPerformed;
    }

    // -------------------------------------------------------------------------
    // Single iteration
    // -------------------------------------------------------------------------

    private void runOneIteration() {
        // 1. Selection: walk tree via UCB1 to a leaf
        MctsNode leaf = select(root);

        // 2. Expansion: if the leaf has been visited and is not terminal, expand it
        if (!leaf.isTerminal()) {
            if (!leaf.expanded) {
                expand(leaf);
            }
            // After expansion (or if already expanded), pick the first unvisited child.
            // This correctly handles the case where select() returned a node because it had
            // unvisited children — we must pick one of those, not arbitrarily pick index 0.
            MctsNode firstUnvisited = firstUnvisitedChild(leaf);
            if (firstUnvisited != null) {
                leaf = firstUnvisited;
            }
        }

        // 3. Rollout (or terminal scoring)
        double score;
        if (leaf.isTerminal()) {
            // Someone has already won in this state
            score = terminalScore(leaf);
        } else {
            score = rolloutFn.simulate(
                    leaf.state, leaf.supply,
                    getActivePlayerForNode(leaf),
                    playerPerspective);
        }

        // 4. Backpropagation
        leaf.backpropagate(score);
    }

    // -------------------------------------------------------------------------
    // Selection
    // -------------------------------------------------------------------------

    /**
     * Walks from {@code node} toward a leaf using UCB1, returning the first unexpanded node
     * or a node with unvisited children.
     * When {@code greedyBuySelection} is true, {@link BuyDecisionNode} children are selected
     * by argmax over win rate (greedy exploitation) instead of UCT.
     */
    private MctsNode select(MctsNode node) {
        while (true) {
            if (!node.expanded) return node;
            List<MctsNode> children = node.getChildren();
            if (children.isEmpty()) return node;
            for (MctsNode child : children) {
                if (child.visitCount == 0) return node;
            }
            // All children visited: use greedy for BuyDecisionNode if flag is set
            if (greedyBuySelection && node instanceof BuyDecisionNode) {
                node = selectGreedyChild(children);
            } else {
                node = node.selectBestChild(explorationConstant);
            }
        }
    }

    /** Selects the child with the highest win rate (greedy / argmax exploitation). */
    private static MctsNode selectGreedyChild(List<MctsNode> children) {
        MctsNode best = children.get(0);
        double bestRate = best.visitCount > 0 ? best.totalScore / best.visitCount : 0.0;
        for (int i = 1; i < children.size(); i++) {
            MctsNode c = children.get(i);
            double rate = c.visitCount > 0 ? c.totalScore / c.visitCount : 0.0;
            if (rate > bestRate) { bestRate = rate; best = c; }
        }
        return best;
    }

    /** Returns the first child of {@code node} with {@code visitCount == 0}, or null if all are visited. */
    private static MctsNode firstUnvisitedChild(MctsNode node) {
        for (MctsNode child : node.getChildren()) {
            if (child.visitCount == 0) return child;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Expansion
    // -------------------------------------------------------------------------

    private void expand(MctsNode node) {
        if (node instanceof BuyDecisionNode bd) {
            bd.expand();
        } else if (node instanceof ChanceNode cn) {
            cn.expand();
        } else if (node instanceof DiceChoiceNode dc) {
            dc.expand();
        } else if (node instanceof FunkturmNode fn) {
            fn.expand();
        } else if (node instanceof BürohausNode bn) {
            bn.expand();
        }
        // MctsNode base: no-op (shouldn't happen)
    }

    // -------------------------------------------------------------------------
    // Terminal scoring
    // -------------------------------------------------------------------------

    private double terminalScore(MctsNode node) {
        for (int i = 0; i < node.state.getPlayers().length; i++) {
            if (GameState.hasWon(node.state.getPlayers()[i])) {
                return (i == playerPerspective) ? 1.0 : 0.0;
            }
        }
        // No winner found (shouldn't happen for isTerminal() nodes) → use heuristic
        return WinProbability.computeBaselineWinProb(node.state, playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Determines the active player for a given node's rollout starting point.
     * For BuyDecisionNode and BürohausNode, it's the buyer/swapper.
     * For ChanceNode and DiceChoiceNode, it's the rolling player.
     * For FunkturmNode, it's the Funkturm owner.
     */
    private int getActivePlayerForNode(MctsNode node) {
        if (node instanceof BuyDecisionNode bd) return bd.activePlayer;
        if (node instanceof BürohausNode bn)    return bn.activePlayer;
        if (node instanceof ChanceNode cn)      return cn.activePlayer;
        if (node instanceof DiceChoiceNode dc)  return dc.activePlayer;
        if (node instanceof FunkturmNode fn)    return fn.activePlayer;
        return activePlayer; // fallback
    }
}
