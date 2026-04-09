package engine.mcts;

import core.BitState;

/**
 * Decision node for the Bahnhof dice-count choice: 1d6 or 2d6.
 *
 * <p>Present at the start of the active player's turn iff that player owns
 * Bahnhof <em>on this branch</em> (i.e. in the {@link #state} stored here).
 * If the player does not own Bahnhof, a {@link ChanceNode} is used directly
 * without a {@code DiceChoiceNode}.
 *
 * <h2>Children</h2>
 * Exactly two children, created during expansion:
 * <ol>
 *   <li>Child 0 — "1d6": a {@link ChanceNode} with {@code twoDice=false}</li>
 *   <li>Child 1 — "2d6": a {@link ChanceNode} with {@code twoDice=true}</li>
 * </ol>
 * No state mutation occurs at this node — the dice count choice has no immediate
 * game effect; the effect is felt via which {@link ChanceNode} children are explored.
 */
public final class DiceChoiceNode extends MctsNode {

    /**
     * Index of the player who will roll (the active player for this turn).
     * This does NOT have to be playerIndex (the player we are advising);
     * opponents also have full DiceChoiceNode / ChanceNode sequences.
     */
    public final int activePlayer;

    /**
     * True when this is a bonus turn (Freizeitpark doubles — the same player gets an
     * extra turn). No further bonus chaining is allowed on a bonus turn.
     */
    public final boolean isBonusTurn;

    /**
     * @param state       bitwise game state at the start of this dice-choice moment
     * @param supply      supply array matching state
     * @param parent      parent node in the tree
     * @param activePlayer index of the player who is choosing dice count
     * @param isBonusTurn true if this is a Freizeitpark bonus turn
     */
    public DiceChoiceNode(BitState state, int[] supply, MctsNode parent,
                          int activePlayer, boolean isBonusTurn) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.isBonusTurn  = isBonusTurn;
    }

    /**
     * Creates both children (1d6 and 2d6 ChanceNodes) and marks this node as expanded.
     * No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;
        children.add(new ChanceNode(state, supply, this, activePlayer, false, isBonusTurn));
        children.add(new ChanceNode(state, supply, this, activePlayer, true,  isBonusTurn));
        expanded = true;
    }

    @Override
    public String toString() {
        return "DiceChoiceNode[player=" + activePlayer + ", bonus=" + isBonusTurn
                + ", visits=" + visitCount + "]";
    }
}
