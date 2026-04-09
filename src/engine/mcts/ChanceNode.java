package engine.mcts;

import core.BitState;
import core.BitStateTranslator;

import java.util.ArrayList;
import java.util.List;

/**
 * Chance node representing all possible outcomes of a dice roll.
 *
 * <h2>Children</h2>
 * <ul>
 *   <li><b>1d6</b> ({@code twoDice=false}): 6 children for rolls 1–6.</li>
 *   <li><b>2d6, doubles irrelevant</b> ({@code twoDice=true}, no Freizeitpark or bonus turn):
 *       11 children for rolls 2–12.</li>
 *   <li><b>2d6, doubles relevant</b> ({@code twoDice=true}, Freizeitpark owned, not bonus turn):
 *       up to 15 children. Odd rolls get 1 child (never doubles). Rolls 2 and 12 get 1 child
 *       each (always doubles, since there's only one way to make them: 1+1 and 6+6). Even
 *       rolls 4, 6, 8, 10 get 2 children each: one doubles branch (1/36 probability) and one
 *       non-doubles branch ((totalWays−1)/36 probability).</li>
 * </ul>
 * For each outcome, the child's {@code BitState} has all coin deltas applied
 * via {@link BitState#applyRoll}.
 *
 * <h2>Doubles and Freizeitpark</h2>
 * When rolling 2d6 and doubles are relevant, even roll totals are split into two
 * children with exact probabilities. For example, roll=8 has 5 ways total: 1 doubles
 * (4+4, P=1/36) and 4 non-doubles (P=4/36). The doubles branch triggers a bonus turn;
 * the non-doubles branch proceeds normally.
 *
 * <h2>Child metadata</h2>
 * When doubles are relevant, {@link #childRollValues} and {@link #childIsDoubles} store
 * per-child metadata to support {@link MctsTree#navigateToRoll(ChanceNode, int, boolean)}.
 * When doubles are irrelevant, these lists are null and children map 1:1 to roll values.
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
 * depends on what the active player owns IN THAT BRANCH's state, not in the
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
     * Per-child roll values. Non-null only when doubles are relevant (2d6 + Freizeitpark + not bonus).
     * In that case, children may not map 1:1 to roll sums (even rolls have 2 children).
     * When null, children[i] corresponds to roll (minRoll + i).
     */
    List<Integer> childRollValues;

    /**
     * Per-child doubles flag. Non-null only when {@link #childRollValues} is non-null.
     * {@code childIsDoubles.get(i)} is true if child i is the doubles branch for that roll.
     */
    List<Boolean> childIsDoubles;

    /**
     * @param state        bitwise game state BEFORE the roll is applied (coins still pre-roll)
     * @param supply       supply array matching state
     * @param parent       parent node
     * @param activePlayer index of the rolling player
     * @param twoDice      true for 2d6, false for 1d6
     * @param isBonusTurn  true if this is a Freizeitpark bonus turn
     */
    public ChanceNode(BitState state, int[] supply, MctsNode parent,
                      int activePlayer, boolean twoDice, boolean isBonusTurn) {
        super(state, supply, parent);
        this.activePlayer = activePlayer;
        this.twoDice      = twoDice;
        this.isBonusTurn  = isBonusTurn;
    }

    /**
     * Creates one child per possible roll outcome, applies coin deltas, and hooks
     * up Funkturm, Bürohaus, and Freizeitpark bonus-turn nodes as required.
     *
     * <p>When doubles are relevant (2d6 + Freizeitpark + not bonus turn), even rolls
     * are split into doubles/non-doubles branches with exact probabilities. This produces
     * up to 15 children instead of 11. Metadata lists ({@link #childRollValues},
     * {@link #childIsDoubles}) are populated to support tree navigation.
     *
     * <p>No-ops if already expanded.
     */
    public void expand() {
        if (expanded) return;

        boolean doublesRelevant = twoDice
                && state.hasLandmark(activePlayer, BitStateTranslator.LM_FZP)
                && !isBonusTurn;

        if (!twoDice) {
            // 1d6: 6 children, rolls 1-6
            for (int roll = 1; roll <= 6; roll++) {
                buildChild(roll, false);
            }
        } else if (!doublesRelevant) {
            // 2d6, no doubles splitting: 11 children, rolls 2-12
            for (int roll = 2; roll <= 12; roll++) {
                buildChild(roll, false);
            }
        } else {
            // 2d6 with doubles splitting: up to 15 children
            childRollValues = new ArrayList<>(15);
            childIsDoubles  = new ArrayList<>(15);
            for (int roll = 2; roll <= 12; roll++) {
                boolean canBeDoubles = (roll % 2 == 0) && (roll / 2 >= 1) && (roll / 2 <= 6);
                if (canBeDoubles) {
                    int totalWays = 6 - Math.abs(roll - 7);
                    int nonDoublesWays = totalWays - 1; // 1 way is always doubles

                    // Non-doubles branch first (if any non-doubles ways exist)
                    if (nonDoublesWays > 0) {
                        buildChild(roll, false);
                        childRollValues.add(roll);
                        childIsDoubles.add(false);
                    }

                    // Doubles branch (always exactly 1 way)
                    buildChild(roll, true);
                    childRollValues.add(roll);
                    childIsDoubles.add(true);
                } else {
                    // Odd roll: never doubles
                    buildChild(roll, false);
                    childRollValues.add(roll);
                    childIsDoubles.add(false);
                }
            }
        }
        expanded = true;
    }

    private void buildChild(int roll, boolean isDoubles) {
        // 1. Apply roll to a copy of the BitState
        //    Note: applyRoll handles income resolution AND Bürohaus greedy swap.
        //    But for tree nodes, Bürohaus is a decision node, not an automatic swap.
        //    So we use applyRollNoSwap (income only), then build Bürohaus node if needed.
        BitState childBS = state.copy();
        applyRollIncomeOnly(childBS, activePlayer, roll);

        boolean hasFunkturm  = childBS.hasLandmark(activePlayer, BitStateTranslator.LM_FT);
        boolean hasBürohaus  = childBS.hasPurple(activePlayer, 2); // bürohaus = purple idx 2
        boolean hasFreizeit  = childBS.hasLandmark(activePlayer, BitStateTranslator.LM_FZP);

        // 2. Determine next node in sequence:
        //    Funkturm → Bürohaus (on roll 6) → Buy; or bonus turn on doubles+Freizeitpark
        MctsNode nextNode;

        if (hasFunkturm) {
            // Funkturm: player may keep or reroll — creates a FunkturmNode
            nextNode = buildFunkturmOrBeyond(childBS, supply, roll,
                    hasBürohaus, hasFreizeit, isDoubles);
        } else if (hasBürohaus && roll == 6) {
            // No Funkturm but has Bürohaus and rolled 6
            nextNode = new BürohausNode(childBS, supply, this, activePlayer,
                    buildBuyOrBonusNode(childBS, supply, hasFreizeit, isDoubles));
        } else if (hasFreizeit && isDoubles && !isBonusTurn) {
            // Doubles bonus turn (no Funkturm, no Bürohaus)
            nextNode = buildBonusTurnNode(childBS, supply);
        } else {
            nextNode = new BuyDecisionNode(childBS, supply, this, activePlayer,
                    nextPlayerAfter());
        }

        children.add(nextNode);
    }

    /**
     * Applies income resolution to the BitState without the automatic Bürohaus swap.
     *
     * <p>This follows the same resolution order as {@link BitState#applyRoll}
     * (Red → Blue → Green → Purple coins) but skips the Bürohaus swap because
     * in the MCTS tree, Bürohaus is a decision node where the player chooses
     * which cards to swap (rather than the greedy automatic swap used in rollouts).
     */
    private static void applyRollIncomeOnly(BitState bs, int activePlayer, int roll) {
        int numPlayers = bs.getNumPlayers();
        // We call applyRoll which does income + greedy swap. But we need income only.
        // Since the tree handles Bürohaus via BürohausNode, we need to NOT auto-swap.
        // Workaround: apply roll, then undo the swap if it happened.
        // Better approach: replicate income logic without swap.
        // For correctness, let's replicate the income logic here.

        int[] deltas = new int[numPlayers];
        boolean activeHasEKZ = bs.hasLandmark(activePlayer, BitStateTranslator.LM_EKZ);

        // Step 1: Red card payments (counter-clockwise, sequential)
        int rollerCoins = bs.getCoins(activePlayer);
        for (int step = 1; step < numPlayers; step++) {
            int oppIdx = (activePlayer - step + numPlayers) % numPlayers;
            boolean oppHasEKZ = bs.hasLandmark(oppIdx, BitStateTranslator.LM_EKZ);
            int oppRedIncome = computeRedIncome(bs, oppIdx, oppHasEKZ, roll, rollerCoins);
            if (oppRedIncome > 0) {
                deltas[activePlayer] -= oppRedIncome;
                deltas[oppIdx] += oppRedIncome;
                rollerCoins -= oppRedIncome;
                if (rollerCoins < 0) rollerCoins = 0;
            }
        }

        // Step 2: Blue card income for every player
        for (int p = 0; p < numPlayers; p++) {
            deltas[p] += computeBlueIncome(bs, p, roll);
        }

        // Step 3: Green income for active player only
        deltas[activePlayer] += computeGreenIncome(bs, activePlayer, activeHasEKZ, roll);

        // Step 4: Purple income for active player (stadion, fernsehsender — roll 6 only)
        if (roll == 6) {
            // Stadion
            if (bs.hasPurple(activePlayer, 0)) {
                int total = 0;
                for (int p = 0; p < numPlayers; p++) {
                    if (p == activePlayer) continue;
                    total += Math.min(2, bs.getCoins(p));
                }
                deltas[activePlayer] += total;
            }
            // Fernsehsender
            if (bs.hasPurple(activePlayer, 1)) {
                int richest = 0;
                for (int p = 0; p < numPlayers; p++) {
                    if (p == activePlayer) continue;
                    int oppCoins = bs.getCoins(p);
                    if (oppCoins > richest) richest = oppCoins;
                }
                deltas[activePlayer] += Math.min(5, richest);
            }
        }

        // Apply deltas with clamping
        for (int p = 0; p < numPlayers; p++) {
            bs.setCoins(p, Math.max(0, bs.getCoins(p) + deltas[p]));
        }
    }

    private static int computeRedIncome(BitState bs, int oppIdx, boolean oppHasEKZ, int roll, int rollerCoins) {
        int totalGain = 0;
        // café (idx 5): activates on roll 3
        if (roll == 3) {
            int count = bs.getCardCount(oppIdx, 5);
            if (count > 0) {
                int perCopy = oppHasEKZ ? 2 : 1;
                int demand = count * perCopy;
                int actual = Math.min(demand, rollerCoins);
                totalGain += actual;
                rollerCoins -= actual;
            }
        }
        // familienrestaurant (idx 10): activates on roll 9, 10
        if (roll == 9 || roll == 10) {
            int count = bs.getCardCount(oppIdx, 10);
            if (count > 0) {
                int perCopy = oppHasEKZ ? 3 : 2;
                int demand = count * perCopy;
                int actual = Math.min(demand, rollerCoins);
                totalGain += actual;
            }
        }
        return totalGain;
    }

    private static int computeBlueIncome(BitState bs, int player, int roll) {
        int income = 0;
        if (roll == 1) income += bs.getCardCount(player, 0);       // weizenfeld
        if (roll == 2) income += bs.getCardCount(player, 2);       // bauernhof
        if (roll == 5) income += bs.getCardCount(player, 3);       // wald
        if (roll == 9) income += bs.getCardCount(player, 8) * 5;   // bergwerk
        if (roll == 10) income += bs.getCardCount(player, 9) * 3;  // apfelplantage
        return income;
    }

    private static int computeGreenIncome(BitState bs, int player, boolean hasEKZ, int roll) {
        int income = 0;
        if (roll == 2 || roll == 3) {
            income += bs.getCardCount(player, 1) * (hasEKZ ? 2 : 1); // bäckerei
        }
        if (roll == 4) {
            income += bs.getCardCount(player, 4) * (hasEKZ ? 4 : 3); // mini-markt
        }
        if (roll == 7) {
            int count = bs.getCardCount(player, 6);                    // molkerei
            if (count > 0) income += count * 3 * bs.animalCount(player);
        }
        if (roll == 8) {
            int count = bs.getCardCount(player, 7);                    // möbelfabrik
            if (count > 0) income += count * 3 * bs.productionCount(player);
        }
        if (roll == 11 || roll == 12) {
            int count = bs.getCardCount(player, 11);                   // markthalle
            if (count > 0) income += count * 2 * bs.foodCount(player);
        }
        return income;
    }

    /**
     * Builds the post-roll node sequence starting from Funkturm (if owned).
     * Inside the Funkturm "keep" branch the sequence continues to Bürohaus / buy / bonus.
     * Inside the Funkturm "reroll" branch a new ChanceNode is inserted.
     */
    private MctsNode buildFunkturmOrBeyond(
            BitState childBS, int[] supply, int currentRoll,
            boolean hasBürohaus, boolean hasFreizeit, boolean isDoubles) {
        // The "after Funkturm keep" node
        MctsNode afterFunkturm = buildBürohausOrBuyOrBonus(
                childBS, supply, currentRoll, hasBürohaus, hasFreizeit, isDoubles);
        return new FunkturmNode(childBS, supply, this, activePlayer, twoDice,
                currentRoll, afterFunkturm, isBonusTurn);
    }

    /** Builds the node that comes after Funkturm keep decision (Bürohaus or Buy or Bonus). */
    private MctsNode buildBürohausOrBuyOrBonus(
            BitState childBS, int[] supply, int roll,
            boolean hasBürohaus, boolean hasFreizeit, boolean isDoubles) {
        if (hasBürohaus && roll == 6) {
            MctsNode afterSwap = buildBuyOrBonusNode(childBS, supply, hasFreizeit, isDoubles);
            return new BürohausNode(childBS, supply, null /* parent set by FunkturmNode */, activePlayer, afterSwap);
        } else if (hasFreizeit && isDoubles && !isBonusTurn) {
            return buildBonusTurnNode(childBS, supply);
        } else {
            return new BuyDecisionNode(childBS, supply, null /* parent set by FunkturmNode */, activePlayer,
                    nextPlayerAfter());
        }
    }

    /**
     * Builds the node after a Bürohaus swap decision: bonus turn or buy.
     */
    private MctsNode buildBuyOrBonusNode(
            BitState childBS, int[] supply,
            boolean hasFreizeit, boolean isDoubles) {
        if (hasFreizeit && isDoubles && !isBonusTurn) {
            return buildBonusTurnNode(childBS, supply);
        }
        return new BuyDecisionNode(childBS, supply, null /* parent assigned during expansion */,
                activePlayer, nextPlayerAfter());
    }

    /**
     * Builds the Freizeitpark bonus turn node: DiceChoiceNode if the player owns Bahnhof,
     * otherwise ChanceNode(1d6). The bonus turn has {@code isBonusTurn=true}.
     */
    private MctsNode buildBonusTurnNode(BitState childBS, int[] supply) {
        if (childBS.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF)) {
            return new DiceChoiceNode(childBS, supply, this, activePlayer, true);
        } else {
            return new ChanceNode(childBS, supply, this, activePlayer, false, true);
        }
    }

    /**
     * Returns the player index who acts next after the current player completes their turn.
     */
    private int nextPlayerAfter() {
        return (activePlayer + 1) % state.getNumPlayers();
    }

    @Override
    public String toString() {
        return "ChanceNode[player=" + activePlayer + ", twoDice=" + twoDice
                + ", bonus=" + isBonusTurn + ", visits=" + visitCount + "]";
    }
}
