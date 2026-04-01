package engine.mcts;

import calcs.RankEntry;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision node for the purchase decision at the end of a player's turn.
 *
 * <p>The active player chooses one of: buy any affordable card (regular or landmark)
 * that still has supply, or save (do nothing). Each choice becomes one child.
 *
 * <h2>Children</h2>
 * <ul>
 *   <li>One "save" child using the {@link RankEntry#WAIT_SENTINEL} (cost 0, no supply check).</li>
 *   <li>One child per card that satisfies:
 *     <ul>
 *       <li>Non-landmark: in {@link GameState#getUnbuilt_projects()} AND
 *           {@link SupplyTracker#canPurchase(String)} AND player's coins ≥ cost.</li>
 *       <li>Landmark: not yet owned by the active player AND player's coins ≥ cost
 *           (landmarks have no supply limit).</li>
 *     </ul>
 *   </li>
 * </ul>
 * Each purchase child has the cost deducted, the card added to owned_projects, and
 * (for non-landmarks) the supply decremented.
 *
 * <h2>Unaffordable cards</h2>
 * Cards the player cannot afford are NOT added as children. Exploring impossible moves
 * wastes the iteration budget.
 *
 * <h2>Turn transition</h2>
 * After the purchase, the turn passes to {@link #nextPlayer}. The child node is either:
 * <ul>
 *   <li>A {@link DiceChoiceNode} if {@code nextPlayer} owns Bahnhof in the child state.</li>
 *   <li>A {@link ChanceNode}(1d6) otherwise.</li>
 * </ul>
 * If the purchase wins the game ({@link GameState#hasWon}), the child is a terminal
 * {@link BuyDecisionNode} with no further children; its {@code isTerminal()} returns true.
 *
 * <h2>UCT</h2>
 * All children are explored and exploited via UCT.
 */
public final class BuyDecisionNode extends MctsNode {

    /** The player making the purchase decision. */
    public final int activePlayer;

    /** The player who acts next after this purchase (may differ from activePlayer only on bonus turns). */
    public final int nextPlayer;

    // All 4 landmark IDs for the win-condition check and landmark purchase scan
    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    /**
     * @param state        game state after roll income applied (player can now buy)
     * @param supply       supply tracker matching state
     * @param parent       parent node
     * @param activePlayer the buyer
     * @param nextPlayer   player index who acts after this purchase
     */
    public BuyDecisionNode(GameState state, SupplyTracker supply, MctsNode parent,
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

        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // --- Save option (always present) ---
        addChild(state, supply, RankEntry.WAIT_SENTINEL, false);

        // --- Non-landmark cards from the unbuilt pool ---
        for (Project p : state.getUnbuilt_projects()) {
            if (supply.canPurchase(p.getId()) && coins >= p.getCost()) {
                // Purple cards (lila) are unique — max 1 per player per type
                if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
                addChild(state, supply, p, false);
            }
        }

        // --- Landmarks (no supply limit; each player can own at most one of each) ---
        for (String landmarkId : LANDMARK_IDS) {
            if (active.hasProject(landmarkId)) continue; // already owned
            Project lm = ProjectLoader.getProject(landmarkId).orElse(null);
            if (lm == null) continue;
            if (coins >= lm.getCost()) {
                addChild(state, supply, lm, true);
            }
        }

        expanded = true;
    }

    /**
     * Creates a child for purchasing {@code card}:
     * <ol>
     *   <li>Clones the state</li>
     *   <li>Applies the purchase (deduct coins, add to owned, decrement supply for non-landmarks)</li>
     *   <li>If the purchase wins the game, adds a terminal {@link BuyDecisionNode}</li>
     *   <li>Otherwise, appends the next-player's DiceChoiceNode / ChanceNode</li>
     * </ol>
     */
    private void addChild(GameState parentState, SupplyTracker parentSupply,
                          Project card, boolean isLandmark) {
        GameState childState = parentState.copy();
        SupplyTracker childSupply = parentSupply;
        boolean isSave = (card == RankEntry.WAIT_SENTINEL);

        if (!isSave) {
            Player childActive = childState.getPlayers()[activePlayer];
            childActive.setCoins(childActive.getCoins() - card.getCost());
            childActive.getOwned_projects().add(card);
            if (!isLandmark) {
                childSupply = childSupply.withPurchase(card.getId());
            }
        }

        // Check win condition
        if (!isSave && GameState.hasWon(childState.getPlayers()[activePlayer])) {
            // Terminal node — no further children will be created
            BuyDecisionNode terminal = new BuyDecisionNode(childState, childSupply, this,
                    nextPlayer, nextPlayer);
            terminal.expanded = true; // no children, already "done"
            children.add(terminal);
            return;
        }

        // Build next-player's turn entry point
        MctsNode nextTurnNode = buildNextTurnNode(childState, childSupply);
        children.add(nextTurnNode);
    }

    /**
     * Builds the first node for the next player's turn: DiceChoiceNode if they own Bahnhof,
     * ChanceNode(1d6) otherwise.
     */
    private MctsNode buildNextTurnNode(GameState childState, SupplyTracker childSupply) {
        Player nextP = childState.getPlayers()[nextPlayer];
        if (nextP.hasProject("bahnhof")) {
            return new DiceChoiceNode(childState, childSupply, this, nextPlayer, false);
        } else {
            return new ChanceNode(childState, childSupply, this, nextPlayer, false, false);
        }
    }

    @Override
    public String toString() {
        return "BuyDecisionNode[buyer=" + activePlayer + ", next=" + nextPlayer
                + ", visits=" + visitCount + "]";
    }
}
