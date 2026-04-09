package engine.mcts;

import calcs.RankEntry;
import core.BitState;
import core.BitStateTranslator;

import java.util.Arrays;

/**
 * Decision node for the purchase decision at the end of a player's turn.
 *
 * <p>The active player chooses one of: buy any affordable card (regular or landmark)
 * that still has supply, or save (do nothing). Each choice becomes one child.
 *
 * <h2>Children</h2>
 * <ul>
 *   <li>One "save" child (cost 0, no supply check).</li>
 *   <li>One child per card that satisfies:
 *     <ul>
 *       <li>Normal card: {@code supply[ci] > 0} AND player's coins ≥ cost.</li>
 *       <li>Purple card: not yet owned by the active player AND player's coins ≥ cost.</li>
 *       <li>Landmark: not yet owned by the active player AND player's coins ≥ cost
 *           (landmarks have no supply limit).</li>
 *     </ul>
 *   </li>
 * </ul>
 * Each purchase child has the cost deducted, the card added, and (for normal cards)
 * the supply decremented.
 *
 * <h2>Turn transition</h2>
 * After the purchase, the turn passes to {@link #nextPlayer}. The child node is either:
 * <ul>
 *   <li>A {@link DiceChoiceNode} if {@code nextPlayer} owns Bahnhof in the child state.</li>
 *   <li>A {@link ChanceNode}(1d6) otherwise.</li>
 * </ul>
 * If the purchase wins the game ({@link BitState#hasWon}), the child is a terminal
 * {@link BuyDecisionNode} with no further children; its {@code isTerminal()} returns true.
 */
public final class BuyDecisionNode extends MctsNode {

    /** The player making the purchase decision. */
    public final int activePlayer;

    /** The player who acts next after this purchase (may differ from activePlayer only on bonus turns). */
    public final int nextPlayer;

    /**
     * Index of a child that is an instant win for the active player, or -1 if none.
     *
     * <p>Set during {@link #expand()} when a purchase creates a terminal state
     * (someone has won). When this is ≥ 0, MCTS should always select this child —
     * no amount of exploration can find a better move than an immediate win.
     *
     * <p>This field fixes a convergence issue: with limited iteration budgets spread
     * across full-turn trees (DiceChoice × ChanceNode × BuyDecisionNode), UCT may
     * not allocate enough visits to the winning child for {@code bestChild()} (most-visited)
     * to select it over "save", whose subtree accumulates visits from deeper exploration.
     */
    public int instantWinChildIndex = -1;

    /**
     * @param state        bitwise game state after roll income applied (player can now buy)
     * @param supply       supply array matching state
     * @param parent       parent node
     * @param activePlayer the buyer
     * @param nextPlayer   player index who acts after this purchase
     */
    public BuyDecisionNode(BitState state, int[] supply, MctsNode parent,
                           int activePlayer, int nextPlayer) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.nextPlayer   = nextPlayer;
    }

    /**
     * Enumerates all valid purchase options (save + affordable cards), creates one child per
     * option with the purchase applied, and appends a DiceChoiceNode / ChanceNode for the
     * next player's turn (unless the purchase wins the game).
     * No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;

        int coins = state.getCoins(activePlayer);

        // --- Save option (always present) ---
        addSaveChild();

        // --- Non-landmark cards from CANDIDATE_ITERATION_ORDER ---
        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            if (ci < BitStateTranslator.NUM_NORMAL_CARDS) {
                // Normal card
                if (supply[ci] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[ci]) continue;
                addNormalCardChild(ci, coins);
            } else {
                // Purple card (ci >= NUM_NORMAL_CARDS)
                int purpleIdx = ci - BitStateTranslator.NUM_NORMAL_CARDS;
                if (state.hasPurple(activePlayer, purpleIdx)) continue; // uniqueness
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[purpleIdx]) continue;
                addPurpleCardChild(purpleIdx, coins);
            }
        }

        // --- Landmarks (no supply limit; each player can own at most one of each) ---
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (state.hasLandmark(activePlayer, li)) continue;
            if (coins < BitStateTranslator.LANDMARK_COSTS[li]) continue;
            addLandmarkChild(li, coins);
        }

        expanded = true;
    }

    private void addSaveChild() {
        // Save: no mutation, share parent's state and supply
        MctsNode nextTurnNode = buildNextTurnNode(state, supply);
        children.add(nextTurnNode);
    }

    private void addNormalCardChild(int normalCardIndex, int coins) {
        BitState childBS = state.copy();
        childBS.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[normalCardIndex]);
        childBS.addCard(activePlayer, normalCardIndex);

        int[] childSupply = Arrays.copyOf(supply, supply.length);
        childSupply[normalCardIndex]--;

        // Check win condition (normal cards can't win, but for safety)
        if (childBS.hasWon(activePlayer)) {
            BuyDecisionNode terminal = new BuyDecisionNode(childBS, childSupply, this,
                    nextPlayer, nextPlayer);
            terminal.expanded = true;
            children.add(terminal);
            instantWinChildIndex = children.size() - 1;
            return;
        }

        children.add(buildNextTurnNode(childBS, childSupply));
    }

    private void addPurpleCardChild(int purpleIndex, int coins) {
        BitState childBS = state.copy();
        childBS.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[purpleIndex]);
        childBS.setPurple(activePlayer, purpleIndex);

        // Purple cards don't consume supply (uniqueness-limited, not pool-limited)

        if (childBS.hasWon(activePlayer)) {
            BuyDecisionNode terminal = new BuyDecisionNode(childBS, supply, this,
                    nextPlayer, nextPlayer);
            terminal.expanded = true;
            children.add(terminal);
            instantWinChildIndex = children.size() - 1;
            return;
        }

        children.add(buildNextTurnNode(childBS, supply));
    }

    private void addLandmarkChild(int landmarkIndex, int coins) {
        BitState childBS = state.copy();
        childBS.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[landmarkIndex]);
        childBS.setLandmark(activePlayer, landmarkIndex);

        // Landmarks don't consume supply

        if (childBS.hasWon(activePlayer)) {
            BuyDecisionNode terminal = new BuyDecisionNode(childBS, supply, this,
                    nextPlayer, nextPlayer);
            terminal.expanded = true;
            children.add(terminal);
            instantWinChildIndex = children.size() - 1;
            return;
        }

        children.add(buildNextTurnNode(childBS, supply));
    }

    /**
     * Builds the first node for the next player's turn: DiceChoiceNode if they own Bahnhof,
     * ChanceNode(1d6) otherwise.
     */
    private MctsNode buildNextTurnNode(BitState childBS, int[] childSupply) {
        if (childBS.hasLandmark(nextPlayer, BitStateTranslator.LM_BAHNHOF)) {
            return new DiceChoiceNode(childBS, childSupply, this, nextPlayer, false);
        } else {
            return new ChanceNode(childBS, childSupply, this, nextPlayer, false, false);
        }
    }

    @Override
    public String toString() {
        return "BuyDecisionNode[buyer=" + activePlayer + ", next=" + nextPlayer
                + ", visits=" + visitCount + "]";
    }
}
