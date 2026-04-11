package engine.mcts;

import core.BitStateTranslator;
import engine.NavigationEvent;

import java.util.List;

/**
 * Navigates an MCTS tree from a current root to a new root determined by a
 * {@link NavigationEvent} (a lock-in event such as a human "Buy" click or an AI
 * turn completing).
 *
 * <h2>Navigation path</h2>
 * Starting from the current root, the navigator walks the tree level-by-level,
 * matching each node type to the corresponding decision in the event:
 *
 * <ol>
 *   <li>{@link DiceChoiceNode} → child[0] = 1d6, child[1] = 2d6</li>
 *   <li>{@link ChanceNode} → child matching (rollTotal, isDoubles) via
 *       {@link MctsTree#navigateToRoll}</li>
 *   <li>{@link FunkturmNode} → child[0] = keep, child[1] = reroll; if reroll,
 *       navigate the reroll ChanceNode and then force child[0] on any subsequent
 *       FunkturmNode (once-per-turn invariant #6)</li>
 *   <li>{@link BürohausNode} → child[0] = no-swap; remaining children matched
 *       by comparing pre/post card counts</li>
 *   <li>{@link BuyDecisionNode} → child matched by card-count comparison (same
 *       logic as {@code TurnPlan.inferPurchase})</li>
 * </ol>
 *
 * If navigation succeeds, the returned node is the new root for continued search
 * (typically the next player's DiceChoiceNode or ChanceNode).
 *
 * <h2>Failure</h2>
 * Returns {@code null} if navigation fails at any step (unexpanded node, child
 * not yet explored). The caller should then fall back to a fresh
 * {@link MctsContinuousWorker#init}.
 */
public final class TreeNavigator {

    /** Utility class — no instances. */
    private TreeNavigator() {}

    /**
     * Navigates the tree from {@code currentRoot} to the position described by
     * {@code event}. Returns the new root node (ready for continued MCTS), or
     * {@code null} if any step along the path has not been explored.
     *
     * @param currentRoot the MCTS tree root before the lock-in event
     * @param event       describes which path to follow (dice, roll, funkturm, etc.)
     * @return the new root node, or {@code null} if navigation failed
     */
    public static MctsNode navigate(MctsNode currentRoot, NavigationEvent event) {
        if (event.forceReset()) return null;

        MctsNode node = currentRoot;

        // ----------------------------------------------------------------
        // 1. DiceChoiceNode: pick 1d6 (child[0]) or 2d6 (child[1])
        // ----------------------------------------------------------------
        if (node instanceof DiceChoiceNode) {
            if (!node.expanded || node.getChildren().isEmpty()) return null;
            if (event.diceCount() == null) return null;
            int childIdx = (event.diceCount() == 2) ? 1 : 0;
            if (childIdx >= node.getChildren().size()) return null;
            node = node.getChildren().get(childIdx);
        }

        // ----------------------------------------------------------------
        // 2. ChanceNode: match by roll total + isDoubles
        // ----------------------------------------------------------------
        if (node instanceof ChanceNode cn) {
            if (!cn.expanded) return null;
            if (event.rollTotal() == null || event.isDoubles() == null) return null;
            node = MctsTree.navigateToRoll(cn, event.rollTotal(), event.isDoubles());
            if (node == null) return null;
        }

        // ----------------------------------------------------------------
        // 3. FunkturmNode: keep or reroll
        // ----------------------------------------------------------------
        if (node instanceof FunkturmNode fn) {
            if (!fn.expanded || fn.getChildren().size() < 1) return null;
            boolean keep = (event.funkturmKeep() == null) || event.funkturmKeep();
            if (keep) {
                // Keep: follow child[0]
                node = fn.getChildren().get(0);
            } else {
                // Reroll: follow child[1] → new ChanceNode
                if (fn.getChildren().size() < 2) return null;
                MctsNode rerollChance = fn.getChildren().get(1);
                if (!(rerollChance instanceof ChanceNode rcn)) return null;
                if (!rcn.expanded) return null;
                if (event.rerollTotal() == null || event.rerollIsDoubles() == null) return null;
                node = MctsTree.navigateToRoll(rcn, event.rerollTotal(), event.rerollIsDoubles());
                if (node == null) return null;

                // After reroll, force keep on any subsequent FunkturmNode (invariant #6)
                if (node instanceof FunkturmNode fn2) {
                    if (!fn2.expanded || fn2.getChildren().isEmpty()) return null;
                    node = fn2.getChildren().get(0); // force keep
                }
            }
        }

        // ----------------------------------------------------------------
        // 4. BürohausNode: no-swap (child[0]) or matched swap
        // ----------------------------------------------------------------
        if (node instanceof BürohausNode bn) {
            if (!bn.expanded || bn.getChildren().isEmpty()) return null;
            if (event.bürohausOwnCardId() == null) {
                // No swap: child[0]
                node = bn.getChildren().get(0);
            } else {
                // Find the swap child matching own+opp card IDs
                node = findSwapChild(bn, event.bürohausOwnCardId(), event.bürohausOppCardId());
                if (node == null) return null;
            }
        }

        // ----------------------------------------------------------------
        // 5. BuyDecisionNode: match by card-count comparison
        // ----------------------------------------------------------------
        if (node instanceof BuyDecisionNode buyNode) {
            if (!buyNode.expanded || buyNode.getChildren().isEmpty()) return null;
            node = findPurchaseChild(buyNode, event.purchasedCardId());
            if (node == null) return null;
        }

        return node;
    }

    /**
     * Severs the parent link on {@code newRoot}, allowing GC to collect the
     * former ancestor nodes and their sibling subtrees.
     *
     * <p><strong>Only {@code TreeNavigator} should call this method.</strong>
     * The parent link is only used for backpropagation, which no longer needs to
     * traverse into pruned ancestors once they are severed.
     *
     * @param newRoot the node that has just become the new tree root
     */
    public static void pruneAbove(MctsNode newRoot) {
        newRoot.parent = null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the BürohausNode child that matches the given swap (own card ID swapped
     * away, opp card ID received). Compares pre/post card counts on the active player.
     */
    private static MctsNode findSwapChild(BürohausNode bn, String ownCardId, String oppCardId) {
        List<MctsNode> children = bn.getChildren();
        int ownIdx = BitStateTranslator.normalCardIndex(ownCardId);
        int oppIdx = BitStateTranslator.normalCardIndex(oppCardId);
        if (ownIdx < 0 || oppIdx < 0) return null;

        int active = bn.activePlayer;
        for (int i = 1; i < children.size(); i++) { // child[0] = no-swap
            MctsNode child = children.get(i);
            // Active player lost own card, gained opp card
            if (child.state.getCardCount(active, ownIdx) < bn.state.getCardCount(active, ownIdx)
                    && child.state.getCardCount(active, oppIdx) > bn.state.getCardCount(active, oppIdx)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Finds the BuyDecisionNode child that corresponds to the given purchased card ID.
     * Uses card-count comparison (invariant #10: count-based, not contains-based).
     *
     * @param buyNode       the buy decision node
     * @param purchasedCardId card ID of the purchased card, or null for save
     * @return the matching child node, or null if not found
     */
    private static MctsNode findPurchaseChild(BuyDecisionNode buyNode, String purchasedCardId) {
        List<MctsNode> children = buyNode.getChildren();
        if (children.isEmpty()) return null;

        int player = buyNode.activePlayer;

        if (purchasedCardId == null || "_wait_".equals(purchasedCardId)) {
            // Save: child[0] (always the save child)
            return children.get(0);
        }

        // Try normal card
        int normalIdx = BitStateTranslator.normalCardIndex(purchasedCardId);
        if (normalIdx >= 0) {
            for (MctsNode child : children) {
                if (child.state.getCardCount(player, normalIdx)
                        > buyNode.state.getCardCount(player, normalIdx)) {
                    return child;
                }
            }
            return null;
        }

        // Try purple card
        int purpleIdx = BitStateTranslator.purpleCardIndex(purchasedCardId);
        if (purpleIdx >= 0) {
            for (MctsNode child : children) {
                if (child.state.hasPurple(player, purpleIdx)
                        && !buyNode.state.hasPurple(player, purpleIdx)) {
                    return child;
                }
            }
            return null;
        }

        // Try landmark
        int landmarkIdx = BitStateTranslator.landmarkIndex(purchasedCardId);
        if (landmarkIdx >= 0) {
            for (MctsNode child : children) {
                if (child.state.hasLandmark(player, landmarkIdx)
                        && !buyNode.state.hasLandmark(player, landmarkIdx)) {
                    return child;
                }
            }
            return null;
        }

        return null; // unknown card ID
    }
}
