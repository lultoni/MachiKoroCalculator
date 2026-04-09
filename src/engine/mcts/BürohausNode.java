package engine.mcts;

import core.BitState;
import core.BitStateTranslator;

import java.util.Arrays;

/**
 * Decision node for the Bürohaus card-swap choice (triggers on roll 6).
 *
 * <p>Present after a roll of 6 is resolved iff the active player owns Bürohaus on this branch.
 * The player may execute any valid swap or pass (no-swap).
 *
 * <h2>Children</h2>
 * <ol>
 *   <li>Child 0 — "no-swap": no state mutation; proceeds directly to {@link #afterBuyNode}.</li>
 *   <li>Children 1..N — one per valid (ownCard × oppCard) pair:
 *       <ul>
 *         <li>{@code ownCard} ∈ active player's owned cards: NOT a landmark, NOT purple (lila)</li>
 *         <li>{@code oppCard} ∈ any opponent's owned cards: NOT a landmark, NOT purple (lila)</li>
 *       </ul>
 *       For each pair the child state has the swap applied (ownCard moved to opponent;
 *       oppCard moved to active player).
 *   </li>
 * </ol>
 * Each child proceeds to a copy of {@link #afterBuyNode} re-parented to the child.
 *
 * <h2>Deduplication</h2>
 * Card-index iteration gives implicit deduplication — each normal card type (index 0-11)
 * appears at most once, regardless of how many copies the player owns.
 *
 * <h2>UCT</h2>
 * All children (no-swap + all swap pairs) are explored and exploited via UCT.
 */
public final class BürohausNode extends MctsNode {

    /** Index of the active player who owns Bürohaus. */
    public final int activePlayer;

    /**
     * The node that follows a swap (or no-swap) decision. Pre-built by the ChanceNode.
     * May be a {@link BuyDecisionNode} or a {@link DiceChoiceNode} for a Freizeitpark
     * bonus turn. Re-parented to each child during expansion.
     */
    final MctsNode afterBuyNode;

    /**
     * @param state        bitwise game state after roll income applied (where Bürohaus swap is offered)
     * @param supply       supply array matching state
     * @param parent       parent node (ChanceNode or FunkturmNode's keep branch proxy)
     * @param activePlayer index of the Bürohaus owner
     * @param afterBuyNode node to proceed to after the swap decision
     */
    public BürohausNode(BitState state, int[] supply, MctsNode parent,
                        int activePlayer, MctsNode afterBuyNode) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.afterBuyNode = afterBuyNode;
    }

    /**
     * Enumerates all valid swap pairs and the no-swap option, creates one child per option.
     * No-ops if already expanded.
     *
     * <p>Uses card-index iteration (0-11 for normal cards). Purple cards and landmarks
     * are excluded by design — this iterates only normal card indices. Deduplication
     * is implicit: each card index represents one card type.
     */
    public void expand() {
        if (expanded) return;

        int numPlayers = state.getNumPlayers();

        // Child 0: no-swap — attach a re-parented copy of afterBuyNode
        children.add(reparentAfterBuy(state, supply, this));

        // Enumerate own normal cards (non-purple, non-landmark by definition of normal cards)
        for (int ownCI = 0; ownCI < BitStateTranslator.NUM_NORMAL_CARDS; ownCI++) {
            if (state.getCardCount(activePlayer, ownCI) == 0) continue;

            // Enumerate opponent normal cards
            for (int oppIdx = 0; oppIdx < numPlayers; oppIdx++) {
                if (oppIdx == activePlayer) continue;
                for (int oppCI = 0; oppCI < BitStateTranslator.NUM_NORMAL_CARDS; oppCI++) {
                    if (state.getCardCount(oppIdx, oppCI) == 0) continue;

                    // Execute swap via BitState
                    BitState swapped = state.copy();
                    swapped.removeCard(activePlayer, ownCI);
                    swapped.removeCard(oppIdx, oppCI);
                    swapped.addCard(activePlayer, oppCI);
                    swapped.addCard(oppIdx, ownCI);

                    children.add(reparentAfterBuy(swapped, supply, this));
                }
            }
        }

        expanded = true;
    }

    /**
     * Creates a shallow copy of {@link #afterBuyNode} re-parented to {@code newParent},
     * using {@code childState} as the state. The afterBuyNode template provides the node
     * type; each swap child needs its own instance so their statistics are tracked independently.
     */
    private MctsNode reparentAfterBuy(BitState childState, int[] childSupply,
                                       MctsNode newParent) {
        if (afterBuyNode instanceof BuyDecisionNode bd) {
            return new BuyDecisionNode(childState, childSupply, newParent,
                    bd.activePlayer, bd.nextPlayer);
        } else if (afterBuyNode instanceof DiceChoiceNode dc) {
            return new DiceChoiceNode(childState, childSupply, newParent,
                    dc.activePlayer, dc.isBonusTurn);
        } else if (afterBuyNode instanceof ChanceNode cn) {
            return new ChanceNode(childState, childSupply, newParent,
                    cn.activePlayer, cn.twoDice, cn.isBonusTurn);
        }
        // Fallback: BuyDecisionNode for next player
        int nextPlayer = (activePlayer + 1) % childState.getNumPlayers();
        return new BuyDecisionNode(childState, childSupply, newParent, activePlayer, nextPlayer);
    }

    @Override
    public String toString() {
        return "BürohausNode[player=" + activePlayer
                + ", swapOptions=" + (children.isEmpty() ? "?" : children.size() - 1)
                + ", visits=" + visitCount + "]";
    }
}
