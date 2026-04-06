package engine.mcts;

import calcs.WinProbability;
import core.GameState;
import core.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Full-turn root: DiceChoiceNode or ChanceNode when the tree models an entire turn.
     * When set, {@link #runIterations} starts selection from this node instead of {@link #root}.
     * Null for standard purchase-only evaluation.
     */
    public final MctsNode fullTurnRoot;

    /** Total iterations performed so far. */
    private int iterationsPerformed = 0;

    /** Rollout strategy — defaults to the uniform-random MctsRollout. */
    private final RolloutFn rolloutFn;

    /**
     * When true, {@link BuyDecisionNode} child selection uses argmax over win rate
     * (greedy exploitation) instead of UCT. All other node types still use UCT.
     */
    private final boolean greedyBuySelection;

    // -------------------------------------------------------------------------
    // Profiling counters (nanoseconds, accumulated across all iterations)
    // -------------------------------------------------------------------------

    private boolean profilingEnabled = false;
    private long selectionNs;
    private long expansionNs;
    private long rolloutNs;
    private long backpropNs;

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
        this.fullTurnRoot = null;
    }

    /**
     * Full-turn constructor: builds a tree rooted at DiceChoiceNode (if player has Bahnhof)
     * or ChanceNode (1d6 only). Used for H2H full-turn evaluation.
     *
     * <p>The {@code root} field is still a BuyDecisionNode for API compatibility, but
     * iterations run from {@code fullTurnRoot} instead.
     *
     * @param rootState          game state at the start of the player's turn
     * @param rootSupply         supply tracker
     * @param activePlayer       the player whose turn it is
     * @param playerPerspective  the player we are advising
     * @param explorationConstant UCB1 C value
     * @param rolloutFn          rollout strategy
     * @param greedyBuySelection if true, BuyDecisionNode uses greedy selection
     * @param fullTurn           must be true (distinguishes from other constructors)
     */
    public MctsTree(GameState rootState, SupplyTracker rootSupply,
                    int activePlayer, int playerPerspective,
                    double explorationConstant, RolloutFn rolloutFn,
                    boolean greedyBuySelection, boolean fullTurn) {
        this.explorationConstant = explorationConstant;
        this.playerPerspective   = playerPerspective;
        this.activePlayer        = activePlayer;
        this.rolloutFn           = rolloutFn;
        this.greedyBuySelection  = greedyBuySelection;

        boolean hasBahnhof = rootState.getPlayers()[activePlayer].hasProject("bahnhof");
        if (hasBahnhof) {
            this.fullTurnRoot = new DiceChoiceNode(rootState, rootSupply, null, activePlayer, false);
        } else {
            this.fullTurnRoot = new ChanceNode(rootState, rootSupply, null, activePlayer, false, false);
        }

        // root field: not directly meaningful for full-turn trees, but set it to a dummy
        // to avoid null. The real tree navigation goes through fullTurnRoot.
        int nextPlayer = (activePlayer + 1) % rootState.getPlayers().length;
        this.root = new BuyDecisionNode(rootState, rootSupply, null, activePlayer, nextPlayer);
    }

    /**
     * Enables per-phase timing collection. Call before running iterations.
     * Adds ~5-10% overhead from {@code System.nanoTime()} calls per iteration.
     */
    public void enableProfiling() {
        profilingEnabled = true;
        selectionNs = 0;
        expansionNs = 0;
        rolloutNs   = 0;
        backpropNs  = 0;
    }

    /**
     * Returns profiling stats (selection/expansion/rollout/backprop time in ms).
     * Returns empty map if profiling was not enabled.
     */
    public Map<String, Long> getProfilingStats() {
        if (!profilingEnabled) return Map.of();
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("selectionMs",  selectionNs / 1_000_000);
        stats.put("expansionMs",  expansionNs / 1_000_000);
        stats.put("rolloutMs",    rolloutNs   / 1_000_000);
        stats.put("backpropMs",   backpropNs  / 1_000_000);
        stats.put("totalMs",      (selectionNs + expansionNs + rolloutNs + backpropNs) / 1_000_000);
        return stats;
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

    /**
     * Runs {@code count} iterations, but forces selection to start from {@code startNode}
     * instead of the root. Use this to concentrate budget on a specific subtree.
     *
     * <p>Backpropagation still walks all the way up to the root, so win rates at all
     * ancestor nodes (including root) are updated correctly.
     *
     * @param startNode the node to start selection from (must be in this tree)
     * @param count     number of iterations to run
     */
    public void runIterationsFromNode(MctsNode startNode, int count) {
        for (int i = 0; i < count; i++) {
            runOneIterationFrom(startNode);
        }
        iterationsPerformed += count;
    }

    // -------------------------------------------------------------------------
    // Single iteration
    // -------------------------------------------------------------------------

    private void runOneIteration() {
        long t0, t1;
        MctsNode iterRoot = fullTurnRoot != null ? fullTurnRoot : root;

        // 1. Selection: walk tree via UCB1 to a leaf
        if (profilingEnabled) { t0 = System.nanoTime(); } else { t0 = 0; }
        MctsNode leaf = select(iterRoot);
        if (profilingEnabled) { selectionNs += System.nanoTime() - t0; }

        // 2. Expansion: if the leaf has been visited and is not terminal, expand it
        if (!leaf.isTerminal()) {
            if (!leaf.expanded) {
                if (profilingEnabled) { t0 = System.nanoTime(); } else { t0 = 0; }
                expand(leaf);
                if (profilingEnabled) { expansionNs += System.nanoTime() - t0; }
            }
            MctsNode firstUnvisited = firstUnvisitedChild(leaf);
            if (firstUnvisited != null) {
                leaf = firstUnvisited;
            }
        }

        // 3. Rollout (or terminal scoring)
        if (profilingEnabled) { t0 = System.nanoTime(); } else { t0 = 0; }
        double score;
        if (leaf.isTerminal()) {
            score = terminalScore(leaf);
        } else {
            score = rolloutFn.simulate(
                    leaf.state, leaf.supply,
                    getActivePlayerForNode(leaf),
                    playerPerspective);
        }
        if (profilingEnabled) { rolloutNs += System.nanoTime() - t0; }

        // 4. Backpropagation
        if (profilingEnabled) { t0 = System.nanoTime(); } else { t0 = 0; }
        leaf.backpropagate(score);
        if (profilingEnabled) { backpropNs += System.nanoTime() - t0; }
    }

    private void runOneIterationFrom(MctsNode startNode) {
        MctsNode leaf = select(startNode);

        if (!leaf.isTerminal()) {
            if (!leaf.expanded) {
                expand(leaf);
            }
            MctsNode firstUnvisited = firstUnvisitedChild(leaf);
            if (firstUnvisited != null) {
                leaf = firstUnvisited;
            }
        }

        double score;
        if (leaf.isTerminal()) {
            score = terminalScore(leaf);
        } else {
            score = rolloutFn.simulate(
                    leaf.state, leaf.supply,
                    getActivePlayerForNode(leaf),
                    playerPerspective);
        }

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
     *
     * <p>Special case: if a {@link BuyDecisionNode} has an instant-win child
     * ({@code instantWinChildIndex >= 0}), that child is always selected. No amount of
     * exploration can find a better move than an immediate win.
     */
    private MctsNode select(MctsNode node) {
        while (true) {
            if (!node.expanded) return node;
            List<MctsNode> children = node.getChildren();
            if (children.isEmpty()) return node;

            // Instant-win short-circuit: always select the winning child
            if (node instanceof BuyDecisionNode bd && bd.instantWinChildIndex >= 0) {
                node = children.get(bd.instantWinChildIndex);
                continue;
            }

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

    /**
     * Selects the child with the highest win rate (greedy / argmax exploitation).
     * On equal win rates, prefers later children over earlier ones (save is children[0]).
     */
    private static MctsNode selectGreedyChild(List<MctsNode> children) {
        MctsNode best = children.get(0);
        double bestRate = best.visitCount > 0 ? best.totalScore / best.visitCount : 0.0;
        for (int i = 1; i < children.size(); i++) {
            MctsNode c = children.get(i);
            double rate = c.visitCount > 0 ? c.totalScore / c.visitCount : 0.0;
            if (rate >= bestRate) { bestRate = rate; best = c; }
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

    // -------------------------------------------------------------------------
    // Tree navigation (for H2H full-turn evaluation)
    // -------------------------------------------------------------------------

    /**
     * Returns the child of a decision node that received the most visits.
     * For decision nodes (DiceChoice, Funkturm, Bürohaus, BuyDecision), the most-visited
     * child represents the engine's preferred action.
     *
     * <p>On equal visit counts, breaks ties by win rate (higher is better). This prevents
     * the systematic save bias that occurs in {@link BuyDecisionNode} where save is always
     * children[0] and the old strict-greater comparison defaulted to the first child on ties.
     *
     * <p>Special case: if the node is a {@link BuyDecisionNode} with an instant-win child,
     * that child is returned unconditionally regardless of visit counts.
     *
     * @param node an expanded decision node
     * @return the most-visited child, or null if no children
     */
    public static MctsNode bestChild(MctsNode node) {
        List<MctsNode> children = node.getChildren();
        if (children.isEmpty()) return null;

        // Instant-win short-circuit
        if (node instanceof BuyDecisionNode bd && bd.instantWinChildIndex >= 0) {
            return children.get(bd.instantWinChildIndex);
        }

        MctsNode best = children.get(0);
        double bestRate = best.visitCount > 0 ? best.totalScore / best.visitCount : 0.0;
        for (int i = 1; i < children.size(); i++) {
            MctsNode c = children.get(i);
            if (c.visitCount > best.visitCount) {
                best = c;
                bestRate = c.visitCount > 0 ? c.totalScore / c.visitCount : 0.0;
            } else if (c.visitCount == best.visitCount) {
                double rate = c.visitCount > 0 ? c.totalScore / c.visitCount : 0.0;
                if (rate > bestRate) {
                    best = c;
                    bestRate = rate;
                }
            }
        }
        return best;
    }

    /**
     * Navigates to the child of a ChanceNode that corresponds to a specific roll value.
     *
     * <p>When doubles are irrelevant (no metadata), children map 1:1 to roll values:
     * for 1d6 → children[0]=roll 1, ...; for 2d6 → children[0]=roll 2, ....
     *
     * <p>When doubles are relevant (metadata present), searches metadata for the matching
     * (roll, isDoubles=false) child. This overload defaults isDoubles to false, which is
     * correct for 1d6 and for callers that don't track individual dice.
     *
     * @param chanceNode an expanded ChanceNode
     * @param roll       the actual dice roll total
     * @return the child node for that roll, or null if not found / not expanded
     */
    public static MctsNode navigateToRoll(ChanceNode chanceNode, int roll) {
        return navigateToRoll(chanceNode, roll, false);
    }

    /**
     * Navigates to the child of a ChanceNode that corresponds to a specific roll value
     * and doubles status.
     *
     * <p>When the ChanceNode has doubles-split children (metadata lists are non-null),
     * searches for the child matching both {@code roll} and {@code isDoubles}. When
     * metadata is null (doubles irrelevant), falls back to simple index arithmetic.
     *
     * @param chanceNode an expanded ChanceNode
     * @param roll       the actual dice roll total
     * @param isDoubles  true if the roll was doubles (d1 == d2)
     * @return the child node for that roll, or null if not found / not expanded
     */
    public static MctsNode navigateToRoll(ChanceNode chanceNode, int roll, boolean isDoubles) {
        if (!chanceNode.expanded) return null;
        List<MctsNode> children = chanceNode.getChildren();

        if (chanceNode.childRollValues != null) {
            // Doubles-relevant ChanceNode: search metadata
            for (int i = 0; i < chanceNode.childRollValues.size(); i++) {
                if (chanceNode.childRollValues.get(i) == roll
                        && chanceNode.childIsDoubles.get(i) == isDoubles) {
                    return children.get(i);
                }
            }
            return null;
        }

        // Simple layout: children[roll - minRoll]
        int minRoll = chanceNode.twoDice ? 2 : 1;
        int index = roll - minRoll;
        if (index < 0 || index >= children.size()) return null;
        return children.get(index);
    }
}
