package engine.mcts;

import core.GameState;
import core.Player;
import core.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @param state        game state after roll income applied (where Bürohaus swap is offered)
     * @param supply       supply tracker matching state
     * @param parent       parent node (ChanceNode or FunkturmNode's keep branch proxy)
     * @param activePlayer index of the Bürohaus owner
     * @param afterBuyNode node to proceed to after the swap decision
     */
    public BürohausNode(GameState state, SupplyTracker supply, MctsNode parent,
                        int activePlayer, MctsNode afterBuyNode) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.afterBuyNode = afterBuyNode;
    }

    /**
     * Enumerates all valid swap pairs and the no-swap option, creates one child per option.
     * No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;

        Player[] players = state.getPlayers();
        Player active = players[activePlayer];

        // Collect unique eligible own card types: non-landmark, non-purple.
        // Deduplicate by ID — multiple copies of the same card produce identical swap states.
        Map<String, Project> ownById = new LinkedHashMap<>();
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                ownById.putIfAbsent(p.getId(), p);
            }
        }

        // Collect unique eligible opponent card types per opponent, deduplicated by ID.
        // Each entry: (oppPlayerIndex, oppCardId) → representative Project
        record OppCard(int oppIdx, String cardId) {}
        Map<OppCard, Project> oppCards = new LinkedHashMap<>();
        for (int oppIdx = 0; oppIdx < players.length; oppIdx++) {
            if (oppIdx == activePlayer) continue;
            Set<String> seen = new LinkedHashSet<>();
            for (Project p : players[oppIdx].getOwned_projects()) {
                if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                    if (seen.add(p.getId())) {
                        oppCards.put(new OppCard(oppIdx, p.getId()), p);
                    }
                }
            }
        }

        // Child 0: no-swap — attach a re-parented copy of afterBuyNode
        children.add(reparentAfterBuy(state, supply, this));

        // Children 1..N: one per unique (ownCardType × oppCardType) pair
        for (Map.Entry<OppCard, Project> oppEntry : oppCards.entrySet()) {
            int oppPlayerIdx = oppEntry.getKey().oppIdx;
            Project oppCard = oppEntry.getValue();
            for (Project ownCard : ownById.values()) {
                GameState swapped = state.copy();
                swapped.getPlayers()[activePlayer].getOwned_projects().remove(ownCard);
                swapped.getPlayers()[oppPlayerIdx].getOwned_projects().remove(oppCard);
                swapped.getPlayers()[activePlayer].addProject(oppCard);
                swapped.getPlayers()[oppPlayerIdx].addProject(ownCard);

                children.add(reparentAfterBuy(swapped, supply, this));
            }
        }

        expanded = true;
    }

    /**
     * Creates a shallow copy of {@link #afterBuyNode} re-parented to {@code newParent},
     * using {@code childState} as the state. The afterBuyNode template provides the node
     * type; each swap child needs its own instance so their statistics are tracked independently.
     */
    private MctsNode reparentAfterBuy(GameState childState, SupplyTracker childSupply,
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
        int nextPlayer = (activePlayer + 1) % childState.getPlayers().length;
        return new BuyDecisionNode(childState, childSupply, newParent, activePlayer, nextPlayer);
    }

    @Override
    public String toString() {
        return "BürohausNode[player=" + activePlayer
                + ", swapOptions=" + (children.isEmpty() ? "?" : children.size() - 1)
                + ", visits=" + visitCount + "]";
    }
}
