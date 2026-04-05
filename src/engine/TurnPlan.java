package engine;

import calcs.RankEntry;
import core.Player;
import core.Project;
import engine.mcts.*;

import java.util.List;

/**
 * Encapsulates a full-turn MCTS evaluation and provides progressive decision extraction.
 *
 * <p>Created by rooting an MCTS tree at DiceChoiceNode (or ChanceNode if no Bahnhof)
 * and running iterations. The match runner then navigates the tree step by step based
 * on actual dice outcomes.
 *
 * <h2>Usage flow</h2>
 * <ol>
 *   <li>Engine creates TurnPlan via {@code evaluateFullTurn()}</li>
 *   <li>Match runner reads {@link #diceCount}</li>
 *   <li>Match runner rolls dice, calls {@link #navigateRoll}</li>
 *   <li>If Funkturm: match runner reads {@link #funkturmKeep}; if reroll, rolls again and calls
 *       {@link #navigateReroll}</li>
 *   <li>Match runner reads {@link #bürohausOwnCard}, {@link #purchase}, etc.</li>
 * </ol>
 *
 * <h2>Key invariants</h2>
 * <ul>
 *   <li><b>Funkturm once per turn:</b> After a reroll ({@link #navigateReroll}), if the tree
 *       creates another FunkturmNode on the reroll branch, it is forced to "keep" (child 0).
 *       The official rule allows only one Funkturm reroll per turn.</li>
 *   <li><b>Defensive fallback:</b> If MCTS didn't explore a branch (node unexpanded or null child),
 *       the plan falls back to saving ({@code RankEntry.WAIT_SENTINEL}). This is safe but suboptimal.</li>
 *   <li><b>Static plans:</b> Non-MCTS engines use {@link #staticPlan} where navigateRoll/navigateReroll
 *       are no-ops — all decisions are pre-populated.</li>
 * </ul>
 */
public final class TurnPlan {

    // ---- Decisions (populated progressively) ----

    /** Number of dice to roll (1 or 2). */
    public int diceCount;

    /** Whether player should keep the roll (true) or Funkturm-reroll (false). */
    public boolean funkturmKeep = true;

    /** Whether this turn has a Funkturm decision to make. */
    public boolean hasFunkturmChoice = false;

    /** Whether this turn has a Bürohaus swap to make. */
    public boolean hasBürohausChoice = false;

    /** Player's card to swap away. Null if no swap. */
    public Project bürohausOwnCard;

    /** Opponent player index for swap. -1 if no swap. */
    public int bürohausOppPlayer = -1;

    /** Opponent's card to take. Null if no swap. */
    public Project bürohausOppCard;

    /** Card to purchase. Null means save. */
    public Project purchase;

    /** Engine's win rate for the chosen purchase. */
    public double purchaseWinRate;

    /** MCTS iterations used. */
    public int iterationsUsed;

    /** Computation time in ms. */
    public long computeTimeMs;

    // ---- Internal ----
    private final MctsTree tree;
    private MctsNode currentNode; // tracks our position in the tree

    TurnPlan(MctsTree tree, int diceCount, int iterationsUsed, long computeTimeMs) {
        this.tree = tree;
        this.diceCount = diceCount;
        this.iterationsUsed = iterationsUsed;
        this.computeTimeMs = computeTimeMs;
        this.currentNode = tree.fullTurnRoot;
    }

    /**
     * Creates a TurnPlan with pre-populated decisions (no tree navigation).
     *
     * <p>Used by non-MCTS engines that make decisions heuristically rather than
     * via tree search. {@link #navigateRoll} and {@link #navigateReroll} are no-ops
     * on static plans — all decisions must be set before returning.
     *
     * @param diceCount      1 or 2
     * @param purchase       card to buy, or {@link RankEntry#WAIT_SENTINEL} for save
     * @param purchaseWinRate engine's confidence / score for the purchase
     * @param iterationsUsed 0 for heuristic engines
     * @param computeTimeMs  wall-clock computation time
     */
    public static TurnPlan staticPlan(int diceCount, Project purchase,
                                       double purchaseWinRate, int iterationsUsed,
                                       long computeTimeMs) {
        TurnPlan plan = new TurnPlan(diceCount, iterationsUsed, computeTimeMs);
        plan.purchase = purchase;
        plan.purchaseWinRate = purchaseWinRate;
        return plan;
    }

    /** Private constructor for static plans (no tree). */
    private TurnPlan(int diceCount, int iterationsUsed, long computeTimeMs) {
        this.tree = null;
        this.currentNode = null;
        this.diceCount = diceCount;
        this.iterationsUsed = iterationsUsed;
        this.computeTimeMs = computeTimeMs;
    }

    /**
     * After the match runner rolls the dice, navigate the tree to the roll outcome
     * and extract Funkturm, Bürohaus, and purchase decisions.
     *
     * @param roll    actual dice total
     */
    public void navigateRoll(int roll) {
        // Static plans have decisions pre-populated — nothing to navigate
        if (tree == null) return;

        // Get the ChanceNode for our diceCount
        MctsNode chanceNode;
        if (currentNode instanceof DiceChoiceNode diceNode) {
            if (!diceNode.expanded) diceNode.expand();
            int childIdx = (diceCount == 2) ? 1 : 0;
            chanceNode = diceNode.getChildren().get(childIdx);
        } else if (currentNode instanceof ChanceNode) {
            chanceNode = currentNode;
        } else {
            // Unexpected node type — use defaults
            purchase = RankEntry.WAIT_SENTINEL;
            return;
        }

        // Navigate to the specific roll child
        MctsNode rollChild = MctsTree.navigateToRoll((ChanceNode) chanceNode, roll);
        if (rollChild == null) {
            purchase = RankEntry.WAIT_SENTINEL;
            return;
        }

        currentNode = rollChild;
        extractRemainingDecisions();
    }

    /**
     * After a Funkturm reroll, navigate to the new roll outcome and extract remaining decisions.
     *
     * @param newRoll the reroll dice total
     */
    public void navigateReroll(int newRoll) {
        // Static plans have decisions pre-populated — nothing to navigate
        if (tree == null) return;

        // currentNode should be at a FunkturmNode; child 1 = reroll = new ChanceNode
        if (currentNode instanceof FunkturmNode fn) {
            if (!fn.expanded || fn.getChildren().size() < 2) {
                purchase = RankEntry.WAIT_SENTINEL;
                return;
            }
            MctsNode rerollChance = fn.getChildren().get(1);
            if (rerollChance instanceof ChanceNode rerollCN) {
                MctsNode rollChild = MctsTree.navigateToRoll(rerollCN, newRoll);
                if (rollChild != null) {
                    currentNode = rollChild;
                    extractRemainingDecisions(true);
                    return;
                }
            }
        }
        // Fallback
        purchase = RankEntry.WAIT_SENTINEL;
    }

    // -------------------------------------------------------------------------
    // Internal decision extraction
    // -------------------------------------------------------------------------

    private void extractRemainingDecisions() {
        extractRemainingDecisions(false);
    }

    private void extractRemainingDecisions(boolean afterReroll) {
        // Walk through node chain: FunkturmNode? → BürohausNode? → BuyDecisionNode

        if (currentNode instanceof FunkturmNode fn) {
            if (!fn.expanded || fn.getChildren().isEmpty()) {
                // Unexpanded FunkturmNode — default to keep/save
                purchase = RankEntry.WAIT_SENTINEL;
                return;
            }

            if (afterReroll) {
                // INVARIANT: Funkturm can only be used ONCE per turn. After a reroll,
                // the tree may create another FunkturmNode on the reroll branch — we
                // must force "keep" (child 0) to prevent a second reroll. DO NOT
                // change this to allow recursive Funkturm usage.
                currentNode = fn.getChildren().get(0);
            } else {
                hasFunkturmChoice = true;
                MctsNode bestFn = MctsTree.bestChild(fn);
                if (bestFn != null) {
                    int idx = fn.getChildren().indexOf(bestFn);
                    funkturmKeep = (idx == 0); // child 0 = keep, child 1 = reroll
                } else {
                    funkturmKeep = true;
                }

                if (!funkturmKeep) {
                    // Reroll chosen — caller must roll again and call navigateReroll()
                    return;
                }

                // Keep: advance to keep child
                currentNode = fn.getChildren().get(0);
            }
        }

        if (currentNode instanceof BürohausNode bn) {
            hasBürohausChoice = true;
            if (!bn.expanded || bn.getChildren().isEmpty()) {
                // Unexpanded — no swap, fall through to buy default
                purchase = RankEntry.WAIT_SENTINEL;
                return;
            }
            MctsNode bestBn = MctsTree.bestChild(bn);
            if (bestBn != null) {
                int bestIdx = bn.getChildren().indexOf(bestBn);
                if (bestIdx == 0) {
                    // No swap
                    bürohausOwnCard = null;
                    bürohausOppPlayer = -1;
                    bürohausOppCard = null;
                } else {
                    extractBürohausSwap(bn, bestBn);
                }
                currentNode = bestBn;
            } else {
                currentNode = bn.getChildren().get(0);
            }
        }

        if (currentNode instanceof BuyDecisionNode buyNode) {
            if (!buyNode.expanded || buyNode.getChildren().isEmpty()) {
                purchase = RankEntry.WAIT_SENTINEL;
                purchaseWinRate = 0.0;
                return;
            }
            MctsNode bestBuy = MctsTree.bestChild(buyNode);
            if (bestBuy != null) {
                purchase = inferPurchase(buyNode, bestBuy);
                purchaseWinRate = bestBuy.visitCount > 0
                        ? bestBuy.totalScore / bestBuy.visitCount : 0.0;
            } else {
                purchase = RankEntry.WAIT_SENTINEL;
                purchaseWinRate = 0.0;
            }
        } else {
            // Shouldn't happen, but safe fallback
            purchase = RankEntry.WAIT_SENTINEL;
            purchaseWinRate = 0.0;
        }
    }

    private void extractBürohausSwap(BürohausNode bn, MctsNode bestChild) {
        Player parentActive = bn.state.getPlayers()[bn.activePlayer];
        Player childActive = bestChild.state.getPlayers()[bn.activePlayer];

        for (Project p : parentActive.getOwned_projects()) {
            if (!childActive.getOwned_projects().contains(p)) {
                bürohausOwnCard = p;
                break;
            }
        }
        for (Project p : childActive.getOwned_projects()) {
            if (!parentActive.getOwned_projects().contains(p)) {
                bürohausOppCard = p;
                break;
            }
        }
        for (int i = 0; i < bn.state.getPlayers().length; i++) {
            if (i == bn.activePlayer) continue;
            List<Project> parentOpp = bn.state.getPlayers()[i].getOwned_projects();
            List<Project> childOpp = bestChild.state.getPlayers()[i].getOwned_projects();
            if (parentOpp.size() != childOpp.size()
                    || !parentOpp.containsAll(childOpp)) {
                bürohausOppPlayer = i;
                break;
            }
        }
    }

    private Project inferPurchase(BuyDecisionNode buyNode, MctsNode bestChild) {
        Player before = buyNode.state.getPlayers()[buyNode.activePlayer];
        Player after = bestChild.state.getPlayers()[buyNode.activePlayer];
        for (Project p : after.getOwned_projects()) {
            if (!before.getOwned_projects().contains(p)) return p;
        }
        return RankEntry.WAIT_SENTINEL;
    }
}
