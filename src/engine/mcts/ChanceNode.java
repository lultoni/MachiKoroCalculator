package engine.mcts;

import core.GameState;
import core.Player;
import core.RollResolver;

/**
 * Chance node representing all possible outcomes of a dice roll.
 *
 * <h2>Children</h2>
 * <ul>
 *   <li><b>1d6</b> ({@code twoDice=false}): 6 children for rolls 1–6.</li>
 *   <li><b>2d6</b> ({@code twoDice=true}): 11 children for rolls 2–12.</li>
 * </ul>
 * For each outcome, the child's {@code GameState} has all coin deltas applied
 * via {@link RollResolver#computeAllDeltasForRoll}.
 *
 * <h2>Doubles and Freizeitpark</h2>
 * When rolling 2d6, outcomes 2, 4, 6, 8, 10, 12 are doubles (d1 == d2 for exactly
 * those sums). If the active player owns Freizeitpark on this branch AND the roll
 * is doubles AND this is NOT already a bonus turn, the child is not a
 * {@link BuyDecisionNode} directly — instead it inserts a {@link DiceChoiceNode}
 * (or plain {@link ChanceNode} if no Bahnhof) for the <em>same player</em> with
 * {@code isBonusTurn=true}. The bonus turn itself follows the normal turn sequence
 * but no further chaining is allowed even if it also rolls doubles.
 *
 * <p><b>KNOWN BUG (UNFIXED):</b> {@link #isDoublesRoll(int)} treats ALL even 2d6 sums
 * as 100% doubles. In reality, only 1 out of (6−|sum−7|) ways to make an even sum is
 * doubles (e.g. roll=8 has P(doubles)=1/5, but this code assumes 1/1). This overestimates
 * the value of Freizeitpark in the tree phase. The rollout phase ({@code MctsRollout})
 * correctly uses d1==d2. The {@code ExpectimaxEngine} handles this correctly with 15-branch
 * chance nodes. See ARCHITECTURE.md Section 7.1 for the full description of this bug.
 *
 * <h2>Bürohaus on roll 6</h2>
 * If the active player owns Bürohaus on this branch and the roll is 6, the child
 * node is a {@link BürohausNode} rather than a {@link FunkturmNode} /
 * {@link BuyDecisionNode}. The Bürohaus node then leads to the normal post-swap
 * sequence.
 *
 * <h2>Funkturm</h2>
 * If the active player owns Funkturm on this branch, the child after applying the
 * roll is a {@link FunkturmNode} (keep or reroll). The Funkturm check comes before
 * the Bürohaus check in the sequence.
 *
 * <h2>Branch-Dependent Node Insertion</h2>
 * INVARIANT: Which special nodes (Funkturm, Bürohaus, Freizeitpark bonus) appear
 * depends on what the active player owns IN THAT BRANCH's GameState, not in the
 * root state. Each branch may have different node structures because earlier
 * purchases change the player's portfolio.
 */
public final class ChanceNode extends MctsNode {

    /** Index of the active player for this roll. */
    public final int activePlayer;

    /** True if rolling 2 dice. */
    public final boolean twoDice;

    /** True if this roll is part of a Freizeitpark bonus turn. */
    public final boolean isBonusTurn;

    /**
     * @param state        game state BEFORE the roll is applied (coins still pre-roll)
     * @param supply       supply tracker matching state
     * @param parent       parent node
     * @param activePlayer index of the rolling player
     * @param twoDice      true for 2d6, false for 1d6
     * @param isBonusTurn  true if this is a Freizeitpark bonus turn
     */
    public ChanceNode(GameState state, SupplyTracker supply, MctsNode parent,
                      int activePlayer, boolean twoDice, boolean isBonusTurn) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.twoDice      = twoDice;
        this.isBonusTurn  = isBonusTurn;
    }

    /**
     * Creates one child per possible roll outcome, applies coin deltas, and hooks
     * up Funkturm, Bürohaus, and Freizeitpark bonus-turn nodes as required.
     * No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;

        int minRoll = twoDice ? 2 : 1;
        int maxRoll = twoDice ? 12 : 6;

        for (int roll = minRoll; roll <= maxRoll; roll++) {
            buildChild(roll);
        }
        expanded = true;
    }

    private void buildChild(int roll) {
        // 1. Apply roll to a copy of the game state
        GameState childState = state.copy();
        int[] deltas = RollResolver.computeAllDeltasForRoll(childState, activePlayer, roll);
        Player[] players = childState.getPlayers();
        for (int i = 0; i < players.length; i++) {
            int newCoins = players[i].getCoins() + deltas[i];
            players[i].setCoins(Math.max(0, newCoins));
        }

        boolean hasFunkturm  = players[activePlayer].hasProject("funkturm");
        boolean hasBürohaus  = players[activePlayer].hasProject("bürohaus");
        boolean hasFreizeit  = players[activePlayer].hasProject("freizeitpark");
        boolean isDoubles    = twoDice && (roll % 2 == 0) && (roll >= 2) && (roll <= 12)
                               && isDoublesRoll(roll);

        // 2. Determine next node in sequence:
        //    Funkturm → Bürohaus (on roll 6) → Buy; or bonus turn on doubles+Freizeitpark
        MctsNode nextNode;

        if (hasFunkturm) {
            // Funkturm: player may keep or reroll — creates a FunkturmNode
            nextNode = buildFunkturmOrBeyond(childState, supply, roll,
                    hasBürohaus, hasFreizeit, isDoubles);
        } else if (hasBürohaus && roll == 6) {
            // No Funkturm but has Bürohaus and rolled 6
            nextNode = new BürohausNode(childState, supply, this, activePlayer,
                    buildBuyOrBonusNode(childState, supply, hasFreizeit, isDoubles));
        } else if (hasFreizeit && isDoubles && !isBonusTurn) {
            // Doubles bonus turn (no Funkturm, no Bürohaus)
            nextNode = buildBonusTurnNode(childState, supply);
        } else {
            nextNode = new BuyDecisionNode(childState, supply, this, activePlayer,
                    nextPlayerAfter(childState));
        }

        children.add(nextNode);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * BUG: Treats every even 2d6 sum as a doubles roll. This is INCORRECT.
     * Correct doubles probability for sum S is 1/(6−|S−7|). For example:
     * - Roll 8 can be 2+6, 3+5, 4+4, 5+3, 6+2 (5 ways) → P(doubles) = 1/5
     * - Roll 2 can only be 1+1 (1 way) → P(doubles) = 1/1
     *
     * The fix requires splitting even-sum branches into doubles/non-doubles children
     * with weighted probabilities, similar to how ExpectimaxEngine does it with 15
     * branches. See ARCHITECTURE.md Section 7.1 for the planned fix.
     *
     * DO NOT "fix" this by simply removing the doubles check — that would make
     * Freizeitpark bonus turns never trigger in the tree, which is worse.
     */
    private static boolean isDoublesRoll(int roll) {
        // Doubles: d1 == d2, so roll must be even and roll/2 must be in [1,6].
        return (roll % 2 == 0) && (roll / 2 >= 1) && (roll / 2 <= 6);
    }

    /**
     * Builds the post-roll node sequence starting from Funkturm (if owned).
     * Inside the Funkturm "keep" branch the sequence continues to Bürohaus / buy / bonus.
     * Inside the Funkturm "reroll" branch a new ChanceNode is inserted.
     */
    private MctsNode buildFunkturmOrBeyond(
            GameState childState, SupplyTracker supply, int currentRoll,
            boolean hasBürohaus, boolean hasFreizeit, boolean isDoubles) {
        // The "after Funkturm keep" node
        MctsNode afterFunkturm = buildBürohausOrBuyOrBonus(
                childState, supply, currentRoll, hasBürohaus, hasFreizeit, isDoubles);
        return new FunkturmNode(childState, supply, this, activePlayer, twoDice,
                currentRoll, afterFunkturm, isBonusTurn);
    }

    /** Builds the node that comes after Funkturm keep decision (Bürohaus or Buy or Bonus). */
    private MctsNode buildBürohausOrBuyOrBonus(
            GameState childState, SupplyTracker supply, int roll,
            boolean hasBürohaus, boolean hasFreizeit, boolean isDoubles) {
        if (hasBürohaus && roll == 6) {
            MctsNode afterSwap = buildBuyOrBonusNode(childState, supply, hasFreizeit, isDoubles);
            return new BürohausNode(childState, supply, null /* parent set by FunkturmNode */, activePlayer, afterSwap);
        } else if (hasFreizeit && isDoubles && !isBonusTurn) {
            return buildBonusTurnNode(childState, supply);
        } else {
            return new BuyDecisionNode(childState, supply, null /* parent set by FunkturmNode */, activePlayer,
                    nextPlayerAfter(childState));
        }
    }

    /**
     * Builds the node after a Bürohaus swap decision: bonus turn or buy.
     */
    private MctsNode buildBuyOrBonusNode(
            GameState childState, SupplyTracker supply,
            boolean hasFreizeit, boolean isDoubles) {
        if (hasFreizeit && isDoubles && !isBonusTurn) {
            return buildBonusTurnNode(childState, supply);
        }
        return new BuyDecisionNode(childState, supply, null /* parent assigned during expansion */,
                activePlayer, nextPlayerAfter(childState));
    }

    /**
     * Builds the Freizeitpark bonus turn node: DiceChoiceNode if the player owns Bahnhof,
     * otherwise ChanceNode(1d6). The bonus turn has {@code isBonusTurn=true}.
     */
    private MctsNode buildBonusTurnNode(GameState childState, SupplyTracker supply) {
        Player[] ps = childState.getPlayers();
        if (ps[activePlayer].hasProject("bahnhof")) {
            return new DiceChoiceNode(childState, supply, this, activePlayer, true);
        } else {
            return new ChanceNode(childState, supply, this, activePlayer, false, true);
        }
    }

    /**
     * Returns the player index who acts next after the current player completes their turn.
     */
    private int nextPlayerAfter(GameState childState) {
        return (activePlayer + 1) % childState.getPlayers().length;
    }

    @Override
    public String toString() {
        return "ChanceNode[player=" + activePlayer + ", twoDice=" + twoDice
                + ", bonus=" + isBonusTurn + ", visits=" + visitCount + "]";
    }
}
