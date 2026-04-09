package engine.mcts;

import core.BitState;

/**
 * Decision node for the Funkturm "keep or reroll" choice.
 *
 * <p>Present after a roll is resolved iff the active player owns Funkturm on this branch.
 * The player may keep the current roll (and proceed to the normal post-roll sequence) or
 * reroll (getting a new dice outcome via a fresh {@link ChanceNode}).
 *
 * <h2>Children</h2>
 * Exactly two children, created during expansion:
 * <ol>
 *   <li>Child 0 — "keep": proceeds to the {@link #afterKeepNode} provided at construction time.
 *       No state mutation is applied here — the state already reflects the current roll's income.</li>
 *   <li>Child 1 — "reroll": links to a new {@link ChanceNode} with the same dice count
 *       ({@code twoDice}) and the same {@code isBonusTurn} flag.
 *       The reroll {@code ChanceNode} uses the pre-roll state ({@link #stateBeforeRoll})
 *       so the new roll starts from a clean pre-income baseline.</li>
 * </ol>
 *
 * <h2>UCT</h2>
 * Both children are explored and exploited via UCT, exactly like any other decision node.
 * There is no greedy policy here.
 *
 * <h2>Note on parent references</h2>
 * The {@code afterKeepNode} is constructed by {@link ChanceNode#buildChild} with a null
 * parent, then re-parented to this {@code FunkturmNode} when {@link #expand()} inserts it
 * into children.  All other children are constructed with {@code this} as parent directly.
 *
 * <h2>Supply through rolls</h2>
 * Supply does not change during rolls (no purchases), so the supply array is shared
 * between pre-roll and post-roll nodes. {@code supplyBeforeRoll} was removed in the
 * Phase 4 migration — {@code supply} from the parent suffices for the reroll branch.
 */
public final class FunkturmNode extends MctsNode {

    /** Index of the active player who owns Funkturm. */
    public final int activePlayer;

    /** True if the original roll used 2 dice (determines dice count of the reroll ChanceNode). */
    public final boolean twoDice;

    /** The state BEFORE the current roll's income was applied (used for the reroll branch). */
    public final BitState stateBeforeRoll;

    /**
     * The node to attach as the "keep" child. Pre-built by the ChanceNode when building this
     * FunkturmNode; its parent reference is null at construction time and is updated to
     * {@code this} when {@link #expand()} inserts it.
     */
    private final MctsNode afterKeepNode;

    /**
     * The roll value that was kept (used for debug display only).
     */
    public final int keptRoll;

    /**
     * True if this Funkturm decision is part of a Freizeitpark bonus turn.
     */
    public final boolean isBonusTurn;

    /**
     * @param stateAfterRoll  bitwise state with current roll's income already applied
     * @param supply          supply array matching stateAfterRoll
     * @param parent          parent ChanceNode
     * @param activePlayer    the player who owns Funkturm
     * @param twoDice         true if the roll used 2 dice
     * @param keptRoll        the dice total of the current roll
     * @param afterKeepNode   the node for the "keep" branch (Bürohaus, BuyDecision, or DiceChoice)
     * @param isBonusTurn     true if this is a Freizeitpark bonus turn
     */
    public FunkturmNode(BitState stateAfterRoll, int[] supply, MctsNode parent,
                        int activePlayer, boolean twoDice, int keptRoll,
                        MctsNode afterKeepNode, boolean isBonusTurn) {
        super(stateAfterRoll, supply, parent);
        // stateBeforeRoll is the ChanceNode's pre-roll state — we reach it via parent
        this.stateBeforeRoll  = parent != null ? parent.state : stateAfterRoll;
        this.activePlayer     = activePlayer;
        this.twoDice          = twoDice;
        this.keptRoll         = keptRoll;
        this.afterKeepNode    = afterKeepNode;
        this.isBonusTurn      = isBonusTurn;
    }

    /**
     * Creates the two children: "keep" and "reroll".
     * Re-parents the pre-built {@code afterKeepNode} to {@code this}.
     * No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;

        // Child 0: keep — use the pre-built afterKeepNode, re-parented to this node
        MctsNode keepChild = reparent(afterKeepNode, this);
        children.add(keepChild);

        // Child 1: reroll — new ChanceNode branching from the pre-roll state.
        // Supply is unchanged through rolls, so use this.supply (same as parent's supply).
        children.add(new ChanceNode(stateBeforeRoll, supply, this,
                activePlayer, twoDice, isBonusTurn));

        expanded = true;
    }

    /**
     * Returns a copy of {@code node} with its parent set to {@code newParent}.
     *
     * <p>Because MctsNode parent is final, we reconstruct the node. Rather than deep-copying
     * the full sub-tree (which hasn't been expanded yet), we use a thin wrapper that delegates
     * all calls and only updates the parent field. Since expansion hasn't happened yet, the
     * children list is empty and the only difference is the parent reference.
     */
    private static MctsNode reparent(MctsNode node, MctsNode newParent) {
        if (node instanceof BürohausNode b) {
            return new BürohausNode(b.state, b.supply, newParent, b.activePlayer, b.afterBuyNode);
        } else if (node instanceof BuyDecisionNode bd) {
            return new BuyDecisionNode(bd.state, bd.supply, newParent,
                    bd.activePlayer, bd.nextPlayer);
        } else if (node instanceof DiceChoiceNode dc) {
            return new DiceChoiceNode(dc.state, dc.supply, newParent,
                    dc.activePlayer, dc.isBonusTurn);
        } else if (node instanceof ChanceNode cn) {
            return new ChanceNode(cn.state, cn.supply, newParent,
                    cn.activePlayer, cn.twoDice, cn.isBonusTurn);
        }
        // Fallback: return as-is (shouldn't happen in practice)
        return node;
    }

    @Override
    public String toString() {
        return "FunkturmNode[player=" + activePlayer + ", keptRoll=" + keptRoll
                + ", bonus=" + isBonusTurn + ", visits=" + visitCount + "]";
    }
}
