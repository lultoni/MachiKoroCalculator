package engine.expectimax;

import calcs.Calcs;
import calcs.RankEntry;
import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;
import core.CardIncome;
import core.GameState;
import core.Project;
import core.ProjectLoader;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expectimax engine — deterministic minimax with probability-weighted chance nodes.
 *
 * <p>Exhaustively evaluates the game tree to a configurable depth (in full rounds),
 * using exact dice probabilities at chance nodes and minimax at decision nodes.
 * No random rollouts — all evaluation is deterministic.
 *
 * <p>Uses {@link BitState} internally for all state copies during recursion. The
 * expensive {@code GameState.copy()} calls in the inner loop are replaced by
 * {@code BitState.copy()} (single array copy). Leaf evaluation converts back to
 * {@code GameState} via {@code toGameState()} only when needed for heuristic scoring.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Enumerate all purchase options for the active player.</li>
 *   <li>For each option, apply the purchase and evaluate the resulting position
 *       via recursive expectimax search.</li>
 *   <li>At <b>chance nodes</b> (dice rolls): probability-weighted average of children.
 *       For 2d6 with Freizeitpark, uses correct 15-branch splitting (not the simplified
 *       11-branch model used by MCTS).</li>
 *   <li>At <b>MAX nodes</b> (our decisions): maximize score with alpha-beta pruning.</li>
 *   <li>At <b>MIN nodes</b> (opponent decisions): minimize score with alpha-beta pruning.</li>
 *   <li>At <b>leaf nodes</b> (depth limit): heuristic position evaluation.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code maxDepthRounds} — search depth in full rounds (default 2)</li>
 *   <li>{@code leafEval} — leaf evaluation function: {@code "winprob"} (default),
 *       {@code "composite"}, or {@code "accurate"} (micro MC with 50 rollouts per leaf —
 *       much higher accuracy but ~5-20ms per leaf; only practical at depth 1)</li>
 * </ul>
 *
 * <h2>Time budget</h2>
 * When {@code timeBudgetMs > 0}, uses progressive deepening: starts at depth 1 and
 * increases depth until the deadline is reached or {@code maxDepthRounds} is hit.
 * Always returns the deepest complete result. Depth 1 takes 8-60ms; depth 2 takes
 * 1.3-1.5s — so budgets under ~1s will yield depth-1 results.
 *
 * <h2>Doubles handling</h2>
 * Unlike the MCTS ChanceNode (which treats all even 2d6 sums as doubles), this engine
 * correctly splits even roll totals into doubles and non-doubles branches with exact
 * probabilities. For example, roll=8 has P(non-doubles)=4/36 and P(doubles)=1/36.
 *
 * <h2>Thread safety</h2>
 * Stateless between calls. Each evaluate() call is self-contained.
 */
public final class ExpectimaxEngine implements SimulationEngine {

    @Override
    public String id() { return "expectimax"; }

    @Override
    public String description() { return "Expectimax — deterministic minimax with probability-weighted chance nodes"; }

    /**
     * Keep if leafEval(current roll) >= E[leafEval(reroll)].
     * Uses the same leaf evaluation function as the tree search, consistent with
     * Expectimax's search-based identity. Runs at depth 0 (leaf-eval only) since
     * the search budget has already been spent in planTurn.
     */
    @Override
    public boolean decideFunkturm(engine.TurnPlan plan, GameState state, int playerIndex,
                                   int roll, boolean isDoubles, engine.EngineConfig config) {
        String evalFn = config.getExtra("leafEval", "winprob");
        BitState preRoll = BitState.fromGameState(state);
        boolean twoDice = plan.diceCount == 2;

        // Keep value: apply this roll's income, evaluate position
        BitState afterKeep = preRoll.copy();
        applyRollIncomeOnly(afterKeep, playerIndex, roll);
        double keepVal = leafEval(afterKeep, playerIndex, evalFn);

        // Reroll expected value: average leafEval over all outcomes
        double rerollVal = 0.0;
        if (!twoDice) {
            for (int r = 1; r <= 6; r++) {
                BitState s = preRoll.copy();
                applyRollIncomeOnly(s, playerIndex, r);
                rerollVal += CardIncome.P1[r] * leafEval(s, playerIndex, evalFn);
            }
        } else {
            for (int r = 2; r <= 12; r++) {
                double prob = CardIncome.P2[r];
                if (prob <= 0) continue;
                BitState s = preRoll.copy();
                applyRollIncomeOnly(s, playerIndex, r);
                rerollVal += prob * leafEval(s, playerIndex, evalFn);
            }
        }

        return keepVal >= rerollVal;
    }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = Calcs.optimalDiceCount(BitState.fromGameState(state), playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        long elapsed = System.currentTimeMillis() - start;
        return SimulationEngine.staticPlanWithInstantWinPriority(diceCount, result, state, playerIndex, elapsed);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        int maxDepthRounds = Integer.parseInt(config.getExtra("maxDepthRounds", "2"));
        String leafEval = config.getExtra("leafEval", "winprob");

        BitState bs = BitState.fromGameState(state);
        int coins = bs.getCoins(playerIndex);
        int n = bs.getNumPlayers();
        int nextPlayer = (playerIndex + 1) % n;
        int[] rootSupply = bs.buildSupplyArray();

        List<ScoredOption> scored;
        int completedDepth;

        if (config.timeBudgetMs > 0) {
            // Progressive deepening: try increasing depth until deadline
            long deadline = startTime + config.timeBudgetMs;
            scored = null;
            completedDepth = 0;

            for (int depth = 1; depth <= maxDepthRounds; depth++) {
                if (System.currentTimeMillis() >= deadline) break;

                List<ScoredOption> depthScored = evaluateAtDepth(
                        bs, rootSupply, playerIndex, nextPlayer, coins, n, depth, leafEval);

                if (System.currentTimeMillis() < deadline || scored == null) {
                    // Completed within budget, or first depth (always keep at least depth-1)
                    scored = depthScored;
                    completedDepth = depth;
                }
                // If we exceeded the deadline mid-evaluation but have a previous result, break
                if (System.currentTimeMillis() >= deadline) break;
            }

            if (scored == null) {
                // Defensive: should never happen since depth-1 always completes first
                scored = evaluateAtDepth(bs, rootSupply, playerIndex, nextPlayer, coins, n, 1, leafEval);
                completedDepth = 1;
            }
        } else {
            // Fixed depth (original path)
            scored = evaluateAtDepth(bs, rootSupply, playerIndex, nextPlayer, coins, n,
                    maxDepthRounds, leafEval);
            completedDepth = maxDepthRounds;
        }

        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(state, playerIndex, scored, coins, computeTimeMs, leafEval, completedDepth);
    }

    /**
     * Evaluates all purchase options at a specific search depth.
     *
     * @param bs          root BitState
     * @param rootSupply  supply array
     * @param playerIndex active player
     * @param nextPlayer  next player index
     * @param coins       active player's coins
     * @param n           number of players
     * @param depth       search depth in full rounds
     * @param leafEval    leaf evaluation function name
     * @return scored options for all valid purchases + save
     */
    /** Package-private for use by {@link ExpectimaxContinuousWorker}. */
    /** Forward-pruning threshold: drop options whose quick-score is below bestScore × (1 - PRUNE_THRESHOLD). */
    private static final double PRUNE_THRESHOLD = 0.30;

    List<ScoredOption> evaluateAtDepth(BitState bs, int[] rootSupply,
                                                int playerIndex, int nextPlayer, int coins,
                                                int n, int depth, String leafEval) {
        // --- Phase 1: enumerate all candidates and apply quick leaf-eval ---
        record Candidate(Project card, BitState childBS, int[] childSupply, boolean affordable) {}
        List<Candidate> candidates = new ArrayList<>();

        // Save option — always included, no pruning
        candidates.add(new Candidate(RankEntry.WAIT_SENTINEL, bs, rootSupply, true));

        // Non-landmark cards
        for (int entry : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            if (entry < BitStateTranslator.NUM_NORMAL_CARDS) {
                int ci = entry;
                if (rootSupply[ci] <= 0) continue;
                int cost = BitStateTranslator.NORMAL_CARD_COSTS[ci];
                boolean affordable = coins >= cost;

                BitState childBS = bs.copy();
                if (affordable) childBS.setCoins(playerIndex, coins - cost);
                childBS.addCard(playerIndex, ci);
                int[] childSupply = Arrays.copyOf(rootSupply, rootSupply.length);
                childSupply[ci]--;
                candidates.add(new Candidate(BitStateTranslator.NORMAL_CARD_PROJECTS[ci], childBS, childSupply, affordable));
            } else {
                int pi = entry - BitStateTranslator.NUM_NORMAL_CARDS;
                if (bs.hasPurple(playerIndex, pi)) continue;
                int cost = BitStateTranslator.PURPLE_CARD_COSTS[pi];
                boolean affordable = coins >= cost;

                BitState childBS = bs.copy();
                if (affordable) childBS.setCoins(playerIndex, coins - cost);
                childBS.setPurple(playerIndex, pi);
                candidates.add(new Candidate(BitStateTranslator.PURPLE_CARD_PROJECTS[pi], childBS, rootSupply, affordable));
            }
        }

        // Landmarks
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (bs.hasLandmark(playerIndex, li)) continue;
            int cost = BitStateTranslator.LANDMARK_COSTS[li];
            boolean affordable = coins >= cost;

            BitState childBS = bs.copy();
            if (affordable) childBS.setCoins(playerIndex, coins - cost);
            childBS.setLandmark(playerIndex, li);
            candidates.add(new Candidate(
                    ProjectLoader.getProject(BitStateTranslator.LANDMARK_IDS[li]).orElse(null),
                    childBS, rootSupply, affordable));
        }

        // --- Phase 2: quick-score all candidates with leaf-eval ---
        double[] quickScores = new double[candidates.size()];
        double bestQuick = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            quickScores[i] = leafEval(c.childBS(), c.childBS() == bs ? playerIndex : playerIndex, leafEval);
            if (quickScores[i] > bestQuick) bestQuick = quickScores[i];
        }

        // --- Phase 3: full search only on non-pruned candidates ---
        double pruneFloor = bestQuick * (1.0 - PRUNE_THRESHOLD);
        List<ScoredOption> scored = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            boolean isSave = c.card() == RankEntry.WAIT_SENTINEL;

            // Instant win: always include regardless of pruning
            if (c.affordable() && c.childBS().hasWon(playerIndex)) {
                scored.add(new ScoredOption(c.card(), 1.0, true));
                continue;
            }

            // Save option and unaffordable options always included (no pruning)
            boolean pruned = !isSave && c.affordable() && quickScores[i] < pruneFloor;

            double score;
            if (pruned || !c.affordable()) {
                score = quickScores[i];
            } else if (isSave) {
                score = Math.min(1.0, evaluateTurn(bs, rootSupply, nextPlayer, playerIndex,
                        depth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, leafEval, false));
            } else {
                score = Math.min(1.0, evaluateTurn(c.childBS(), c.childSupply(), nextPlayer, playerIndex,
                        depth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, leafEval, false));
            }
            scored.add(new ScoredOption(c.card(), score, c.affordable()));
        }

        return scored;
    }

    // -------------------------------------------------------------------------
    // Core expectimax recursion (all BitState)
    // -------------------------------------------------------------------------

    /**
     * Evaluates one player's complete turn via expectimax.
     *
     * @param bs               game state at start of this player's turn
     * @param supply           supply array (int[12])
     * @param currentPlayer    index of the player whose turn it is
     * @param perspective      index of the player we're maximizing for
     * @param depthRemaining   full rounds remaining to search
     * @param alpha            alpha bound for pruning
     * @param beta             beta bound for pruning
     * @param leafEval         leaf evaluation function name
     * @param isBonusTurn      true if this is a Freizeitpark bonus turn
     * @return evaluation score in [0, 1] from perspective player's viewpoint
     */
    private double evaluateTurn(BitState bs, int[] supply,
                                int currentPlayer, int perspective,
                                int depthRemaining, double alpha, double beta,
                                String leafEval, boolean isBonusTurn) {
        // Terminal check
        for (int p = 0; p < bs.getNumPlayers(); p++) {
            if (bs.hasWon(p)) return p == perspective ? 1.0 : 0.0;
        }

        // Depth check
        if (depthRemaining <= 0) {
            return leafEval(bs, perspective, leafEval);
        }

        boolean isMax = (currentPlayer == perspective);
        boolean hasBahnhof = bs.hasLandmark(currentPlayer, BitStateTranslator.LM_BAHNHOF);

        if (hasBahnhof) {
            // DiceChoice: MAX/MIN over {1d6, 2d6}
            double val1d6 = evaluateChanceNode(bs, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, false, isBonusTurn);

            if (isMax) {
                alpha = Math.max(alpha, val1d6);
            } else {
                beta = Math.min(beta, val1d6);
            }

            // Only evaluate 2d6 if not pruned
            if (alpha < beta) {
                double val2d6 = evaluateChanceNode(bs, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, true, isBonusTurn);
                return isMax ? Math.max(val1d6, val2d6) : Math.min(val1d6, val2d6);
            }

            return val1d6;
        } else {
            return evaluateChanceNode(bs, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, false, isBonusTurn);
        }
    }

    /**
     * Evaluates a chance node (dice roll). Computes probability-weighted average
     * over all roll outcomes.
     */
    private double evaluateChanceNode(BitState bs, int[] supply,
                                       int currentPlayer, int perspective,
                                       int depthRemaining, double alpha, double beta,
                                       String leafEval, boolean twoDice, boolean isBonusTurn) {
        boolean hasFreizeitpark = bs.hasLandmark(currentPlayer, BitStateTranslator.LM_FZP);
        boolean doublesRelevant = twoDice && hasFreizeitpark && !isBonusTurn;

        double weightedSum = 0.0;

        if (!twoDice) {
            for (int roll = 1; roll <= 6; roll++) {
                double rollValue = evaluatePostRollIncome(bs, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, roll, twoDice, false, isBonusTurn);
                weightedSum += CardIncome.P1[roll] * rollValue;
            }
        } else if (!doublesRelevant) {
            for (int roll = 2; roll <= 12; roll++) {
                double prob = CardIncome.P2[roll];
                if (prob <= 0) continue;
                double rollValue = evaluatePostRollIncome(bs, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                weightedSum += prob * rollValue;
            }
        } else {
            // 2d6 with Freizeitpark relevant: 15 branches (correct doubles splitting)
            for (int roll = 2; roll <= 12; roll++) {
                int totalWays = 6 - Math.abs(roll - 7);
                if (totalWays <= 0) continue;

                boolean canBeDoubles = (roll % 2 == 0) && (roll / 2 >= 1) && (roll / 2 <= 6);

                if (canBeDoubles) {
                    int doublesWays = 1;
                    int nonDoublesWays = totalWays - doublesWays;

                    // Doubles branch
                    double doublesProb = doublesWays / 36.0;
                    double doublesValue = evaluatePostRollIncome(bs, supply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, roll, true, true, isBonusTurn);
                    weightedSum += doublesProb * doublesValue;

                    // Non-doubles branch
                    if (nonDoublesWays > 0) {
                        double nonDoublesProb = nonDoublesWays / 36.0;
                        double nonDoublesValue = evaluatePostRollIncome(bs, supply, currentPlayer, perspective,
                                depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                        weightedSum += nonDoublesProb * nonDoublesValue;
                    }
                } else {
                    double prob = totalWays / 36.0;
                    double rollValue = evaluatePostRollIncome(bs, supply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                    weightedSum += prob * rollValue;
                }
            }
        }

        return weightedSum;
    }

    /**
     * Applies roll income to a copy of the state and proceeds to post-roll decisions
     * (Funkturm → Bürohaus → Buy → next player / bonus turn).
     *
     * <p>Uses {@link BitState#applyRoll} which includes automatic Bürohaus swap.
     * For Expectimax, Bürohaus is also a decision node — but we use the greedy swap
     * built into applyRoll to avoid combinatorial explosion at depth &gt; 1. Only at
     * depth 1 do we enumerate swap options explicitly.
     *
     * <p><b>IMPORTANT:</b> Unlike MCTS ChanceNode (which uses income-only resolution and
     * a separate BürohausNode), Expectimax uses applyRoll's greedy swap because the
     * engine already has an explicit evaluateBürohausNode method. We call applyRoll's
     * income-only part here and handle Bürohaus separately in evaluatePostFunkturm.
     */
    private double evaluatePostRollIncome(BitState bs, int[] supply,
                                           int currentPlayer, int perspective,
                                           int depthRemaining, double alpha, double beta,
                                           String leafEval, int roll, boolean twoDice,
                                           boolean isDoubles, boolean isBonusTurn) {
        // Apply roll income (without Bürohaus — that's handled in evaluatePostFunkturm)
        BitState afterRoll = bs.copy();
        applyRollIncomeOnly(afterRoll, currentPlayer, roll);

        boolean hasFunkturm = afterRoll.hasLandmark(currentPlayer, BitStateTranslator.LM_FT);

        if (hasFunkturm) {
            return evaluateFunkturmNode(bs, afterRoll, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, roll, twoDice, isDoubles, isBonusTurn);
        }

        return evaluatePostFunkturm(afterRoll, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, roll, isDoubles, isBonusTurn);
    }

    /**
     * Applies income resolution WITHOUT Bürohaus swap.
     * Delegates to {@link core.BitState#applyRollIncome} — the single authoritative
     * income-only implementation. Bürohaus is handled as a separate decision node.
     */
    private static void applyRollIncomeOnly(BitState bs, int activePlayer, int roll) {
        bs.applyRollIncome(activePlayer, roll);
    }

    /**
     * Evaluates the Funkturm keep-or-reroll decision.
     */
    private double evaluateFunkturmNode(BitState preRollState, BitState afterRollState,
                                         int[] supply, int currentPlayer, int perspective,
                                         int depthRemaining, double alpha, double beta,
                                         String leafEval, int keptRoll, boolean twoDice,
                                         boolean isDoubles, boolean isBonusTurn) {
        boolean isMax = (currentPlayer == perspective);

        // Option 1: Keep
        double keepValue = evaluatePostFunkturm(afterRollState, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, keptRoll, isDoubles, isBonusTurn);

        if (isMax) {
            alpha = Math.max(alpha, keepValue);
        } else {
            beta = Math.min(beta, keepValue);
        }

        if (alpha >= beta) return keepValue; // Prune reroll branch

        // Option 2: Reroll (from pre-roll state, Funkturm NOT offered again)
        double rerollValue = evaluateChanceNodeNoFunkturm(preRollState, supply, currentPlayer,
                perspective, depthRemaining, alpha, beta, leafEval, twoDice, isBonusTurn);

        return isMax ? Math.max(keepValue, rerollValue) : Math.min(keepValue, rerollValue);
    }

    /**
     * Evaluates a chance node where Funkturm will NOT be offered (after a reroll).
     */
    private double evaluateChanceNodeNoFunkturm(BitState bs, int[] supply,
                                                  int currentPlayer, int perspective,
                                                  int depthRemaining, double alpha, double beta,
                                                  String leafEval, boolean twoDice,
                                                  boolean isBonusTurn) {
        boolean hasFreizeitpark = bs.hasLandmark(currentPlayer, BitStateTranslator.LM_FZP);
        boolean doublesRelevant = twoDice && hasFreizeitpark && !isBonusTurn;

        double weightedSum = 0.0;

        if (!twoDice) {
            for (int roll = 1; roll <= 6; roll++) {
                double rollValue = evaluatePostRollIncomeNoFunkturm(bs, supply, currentPlayer,
                        perspective, depthRemaining, alpha, beta, leafEval, roll, false, false, isBonusTurn);
                weightedSum += CardIncome.P1[roll] * rollValue;
            }
        } else if (!doublesRelevant) {
            for (int roll = 2; roll <= 12; roll++) {
                double prob = CardIncome.P2[roll];
                if (prob <= 0) continue;
                double rollValue = evaluatePostRollIncomeNoFunkturm(bs, supply, currentPlayer,
                        perspective, depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                weightedSum += prob * rollValue;
            }
        } else {
            for (int roll = 2; roll <= 12; roll++) {
                int totalWays = 6 - Math.abs(roll - 7);
                if (totalWays <= 0) continue;
                boolean canBeDoubles = (roll % 2 == 0) && (roll / 2 >= 1) && (roll / 2 <= 6);

                if (canBeDoubles) {
                    double doublesProb = 1.0 / 36.0;
                    double doublesValue = evaluatePostRollIncomeNoFunkturm(bs, supply, currentPlayer,
                            perspective, depthRemaining, alpha, beta, leafEval, roll, true, true, isBonusTurn);
                    weightedSum += doublesProb * doublesValue;

                    int nonDoublesWays = totalWays - 1;
                    if (nonDoublesWays > 0) {
                        double nonDoublesProb = nonDoublesWays / 36.0;
                        double nonDoublesValue = evaluatePostRollIncomeNoFunkturm(bs, supply, currentPlayer,
                                perspective, depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                        weightedSum += nonDoublesProb * nonDoublesValue;
                    }
                } else {
                    double prob = totalWays / 36.0;
                    double rollValue = evaluatePostRollIncomeNoFunkturm(bs, supply, currentPlayer,
                            perspective, depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                    weightedSum += prob * rollValue;
                }
            }
        }

        return weightedSum;
    }

    /**
     * Same as evaluatePostRollIncome but skips Funkturm (after a reroll).
     */
    private double evaluatePostRollIncomeNoFunkturm(BitState bs, int[] supply,
                                                      int currentPlayer, int perspective,
                                                      int depthRemaining, double alpha, double beta,
                                                      String leafEval, int roll, boolean twoDice,
                                                      boolean isDoubles, boolean isBonusTurn) {
        BitState afterRoll = bs.copy();
        applyRollIncomeOnly(afterRoll, currentPlayer, roll);

        return evaluatePostFunkturm(afterRoll, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, roll, isDoubles, isBonusTurn);
    }

    /**
     * After Funkturm decision is resolved: handle Bürohaus (on roll 6) → Buy decision.
     */
    private double evaluatePostFunkturm(BitState bs, int[] supply,
                                         int currentPlayer, int perspective,
                                         int depthRemaining, double alpha, double beta,
                                         String leafEval, int roll, boolean isDoubles,
                                         boolean isBonusTurn) {
        boolean hasBürohaus = bs.hasPurple(currentPlayer, 2); // bürohaus = purple idx 2

        if (hasBürohaus && roll == 6) {
            return evaluateBürohausNode(bs, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);
        }

        return evaluateBuyDecision(bs, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);
    }

    /**
     * Evaluates the Bürohaus card-swap decision. MAX/MIN over {skip, all valid swap pairs}.
     * Uses card-index iteration for BitState-native enumeration.
     */
    private double evaluateBürohausNode(BitState bs, int[] supply,
                                         int currentPlayer, int perspective,
                                         int depthRemaining, double alpha, double beta,
                                         String leafEval, boolean isDoubles, boolean isBonusTurn) {
        boolean isMax = (currentPlayer == perspective);
        int numPlayers = bs.getNumPlayers();

        // Option 1: Skip swap
        double bestVal = evaluateBuyDecision(bs, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);

        if (isMax) {
            alpha = Math.max(alpha, bestVal);
        } else {
            beta = Math.min(beta, bestVal);
        }

        // Options 2..N: Each swap pair (card-index iteration, deduplicated by type)
        for (int ownCI = 0; ownCI < BitStateTranslator.NUM_NORMAL_CARDS; ownCI++) {
            if (alpha >= beta) break;
            if (bs.getCardCount(currentPlayer, ownCI) == 0) continue;

            for (int oppIdx = 0; oppIdx < numPlayers; oppIdx++) {
                if (alpha >= beta) break;
                if (oppIdx == currentPlayer) continue;

                for (int oppCI = 0; oppCI < BitStateTranslator.NUM_NORMAL_CARDS; oppCI++) {
                    if (alpha >= beta) break;
                    if (bs.getCardCount(oppIdx, oppCI) == 0) continue;

                    BitState swapped = bs.copy();
                    swapped.removeCard(currentPlayer, ownCI);
                    swapped.removeCard(oppIdx, oppCI);
                    swapped.addCard(currentPlayer, oppCI);
                    swapped.addCard(oppIdx, ownCI);

                    double swapVal = evaluateBuyDecision(swapped, supply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);

                    if (isMax) {
                        bestVal = Math.max(bestVal, swapVal);
                        alpha = Math.max(alpha, bestVal);
                    } else {
                        bestVal = Math.min(bestVal, swapVal);
                        beta = Math.min(beta, bestVal);
                    }
                }
            }
        }

        return bestVal;
    }

    /**
     * Evaluates the purchase decision. MAX/MIN over {save + all affordable cards}.
     */
    private double evaluateBuyDecision(BitState bs, int[] supply,
                                        int currentPlayer, int perspective,
                                        int depthRemaining, double alpha, double beta,
                                        String leafEval, boolean isDoubles, boolean isBonusTurn) {
        boolean isMax = (currentPlayer == perspective);
        int coins = bs.getCoins(currentPlayer);

        boolean hasFreizeitpark = bs.hasLandmark(currentPlayer, BitStateTranslator.LM_FZP);
        boolean bonusTurnAfter = isDoubles && hasFreizeitpark && !isBonusTurn;

        double bestVal = isMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        // Save option
        double saveVal = evaluateAfterBuy(bs, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, bonusTurnAfter);
        if (isMax) {
            bestVal = Math.max(bestVal, saveVal);
            alpha = Math.max(alpha, bestVal);
        } else {
            bestVal = Math.min(bestVal, saveVal);
            beta = Math.min(beta, bestVal);
        }

        // Non-landmark cards via CANDIDATE_ITERATION_ORDER
        if (alpha < beta) {
            for (int entry : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
                if (alpha >= beta) break;

                if (entry < BitStateTranslator.NUM_NORMAL_CARDS) {
                    int ci = entry;
                    if (supply[ci] <= 0) continue;
                    if (coins < BitStateTranslator.NORMAL_CARD_COSTS[ci]) continue;

                    BitState childBS = bs.copy();
                    childBS.setCoins(currentPlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[ci]);
                    childBS.addCard(currentPlayer, ci);
                    int[] childSupply = Arrays.copyOf(supply, supply.length);
                    childSupply[ci]--;

                    // Win check (buying a normal card can't win, but keep for safety)
                    double childVal = evaluateAfterBuy(childBS, childSupply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, bonusTurnAfter);

                    if (isMax) {
                        bestVal = Math.max(bestVal, childVal);
                        alpha = Math.max(alpha, bestVal);
                    } else {
                        bestVal = Math.min(bestVal, childVal);
                        beta = Math.min(beta, bestVal);
                    }
                } else {
                    int pi = entry - BitStateTranslator.NUM_NORMAL_CARDS;
                    if (bs.hasPurple(currentPlayer, pi)) continue;
                    if (coins < BitStateTranslator.PURPLE_CARD_COSTS[pi]) continue;

                    BitState childBS = bs.copy();
                    childBS.setCoins(currentPlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[pi]);
                    childBS.setPurple(currentPlayer, pi);

                    double childVal = evaluateAfterBuy(childBS, supply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, bonusTurnAfter);

                    if (isMax) {
                        bestVal = Math.max(bestVal, childVal);
                        alpha = Math.max(alpha, bestVal);
                    } else {
                        bestVal = Math.min(bestVal, childVal);
                        beta = Math.min(beta, bestVal);
                    }
                }
            }
        }

        // Landmarks
        if (alpha < beta) {
            for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
                if (alpha >= beta) break;
                if (bs.hasLandmark(currentPlayer, li)) continue;
                if (coins < BitStateTranslator.LANDMARK_COSTS[li]) continue;

                BitState childBS = bs.copy();
                childBS.setCoins(currentPlayer, coins - BitStateTranslator.LANDMARK_COSTS[li]);
                childBS.setLandmark(currentPlayer, li);

                if (childBS.hasWon(currentPlayer)) {
                    double winVal = (currentPlayer == perspective) ? 1.0 : 0.0;
                    if (isMax) {
                        bestVal = Math.max(bestVal, winVal);
                        alpha = Math.max(alpha, bestVal);
                    } else {
                        bestVal = Math.min(bestVal, winVal);
                        beta = Math.min(beta, bestVal);
                    }
                    continue;
                }

                double childVal = evaluateAfterBuy(childBS, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, bonusTurnAfter);

                if (isMax) {
                    bestVal = Math.max(bestVal, childVal);
                    alpha = Math.max(alpha, bestVal);
                } else {
                    bestVal = Math.min(bestVal, childVal);
                    beta = Math.min(beta, bestVal);
                }
            }
        }

        return bestVal;
    }

    /**
     * After a buy decision: either take a Freizeitpark bonus turn or advance to the next player.
     */
    private double evaluateAfterBuy(BitState bs, int[] supply,
                                     int currentPlayer, int perspective,
                                     int depthRemaining, double alpha, double beta,
                                     String leafEval, boolean bonusTurn) {
        if (bonusTurn) {
            return evaluateTurn(bs, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, true);
        }

        int n = bs.getNumPlayers();
        int nextPlayer = (currentPlayer + 1) % n;
        int newDepth = (nextPlayer == perspective) ? depthRemaining - 1 : depthRemaining;

        return evaluateTurn(bs, supply, nextPlayer, perspective,
                newDepth, alpha, beta, leafEval, false);
    }

    // -------------------------------------------------------------------------
    // Leaf evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluates a position at the depth limit. Uses BitState overloads for
     * heuristic scoring — no {@code toGameState()} in any branch.
     *
     * <p>Supported {@code evalFn} values:
     * <ul>
     *   <li>{@code "winprob"} (default) — fast logistic heuristic (~0.25 MAE)</li>
     *   <li>{@code "composite"} — EV+landmark+coin weighted formula</li>
     *   <li>{@code "accurate"} — micro MC (50 rollouts, only practical at depth 1)</li>
     *   <li>{@code "etw"} — ETW-ratio: bestOppETW / (myETW + bestOppETW), fully BitState-native</li>
     * </ul>
     */
    private double leafEval(BitState bs, int perspective, String evalFn) {
        if ("composite".equals(evalFn)) {
            return leafEvalComposite(bs, perspective);
        }
        if ("accurate".equals(evalFn)) {
            return Math.max(0.0, Math.min(1.0,
                    WinProbability.computeAccurateWinProb(bs, perspective)));
        }
        if ("etw".equals(evalFn)) {
            return leafEvalEtw(bs, perspective);
        }
        return Math.max(0.0, Math.min(1.0,
                WinProbability.computeBaselineWinProb(bs, perspective)));
    }

    /**
     * ETW-ratio leaf evaluator — fully BitState-native, no {@code toGameState()}.
     *
     * <p>ETW (Estimated Turns to Win) = remainingLandmarkCost / evPerRound.
     * Score = bestOppETW / (myETW + bestOppETW), in [0, 1].
     * Score > 0.5 means the perspective player leads the race to victory.
     */
    private double leafEvalEtw(BitState bs, int perspective) {
        int n = bs.getNumPlayers();
        double myETW = computeEtw(bs, perspective);
        double bestOppETW = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (i == perspective) continue;
            bestOppETW = Math.min(bestOppETW, computeEtw(bs, i));
        }
        if (myETW <= 0) return 1.0;
        if (bestOppETW >= Double.MAX_VALUE) return 1.0;
        double sum = myETW + bestOppETW;
        if (sum <= 0) return 0.5;
        return Math.max(0.0, Math.min(1.0, bestOppETW / sum));
    }

    /** ETW for one player: deficit of coins to finish all landmarks, divided by income per round. */
    private double computeEtw(BitState bs, int player) {
        int remCost = 0;
        for (int i = 0; i < BitStateTranslator.NUM_LANDMARKS; i++) {
            if (!bs.hasLandmark(player, i)) remCost += BitStateTranslator.LANDMARK_COSTS[i];
        }
        double deficit = Math.max(0, remCost - bs.getCoins(player));
        double ev = CardIncome.playerEvPerRound(bs, player);
        return deficit / Math.max(0.5, ev);
    }

    /**
     * Composite position evaluation using BitState-native landmark counting
     * and lazy GameState conversion for EV computation.
     */
    private double leafEvalComposite(BitState bs, int perspective) {
        int n = bs.getNumPlayers();

        double myScore = positionScore(bs, perspective);
        double bestOppScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (i == perspective) continue;
            bestOppScore = Math.max(bestOppScore, positionScore(bs, i));
        }

        double diff = myScore - bestOppScore;
        return sigmoid(diff / 10.0);
    }

    /**
     * Raw position score: uses CardIncome.playerEvPerRound(BitState) overload.
     */
    private double positionScore(BitState bs, int playerIndex) {
        double evPerRound = CardIncome.playerEvPerRound(bs, playerIndex);

        int landmarkCount = bs.getLandmarkCount(playerIndex);

        return evPerRound * 12.0 + landmarkCount * 15.0 + bs.getCoins(playerIndex) * 0.5;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // -------------------------------------------------------------------------
    // Result construction (uses original GameState for Calcs metrics)
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link ExpectimaxContinuousWorker}. */
    EngineResult buildResult(GameState state, int playerIndex,
                                      List<ScoredOption> scored, int coins,
                                      long computeTimeMs, String leafEval, int maxDepth) {
        List<EngineResult.Option> options = new ArrayList<>();

        for (ScoredOption s : scored) {
            boolean isSave = s.card == RankEntry.WAIT_SENTINEL;
            boolean affordable = isSave || s.affordable;

            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("expectimaxScore", String.format("%.6f", s.score));
            metrics.put("cost", isSave ? "0" : String.valueOf(s.card.getCost()));
            metrics.put("leafEval", leafEval);
            metrics.put("maxDepthRounds", String.valueOf(maxDepth));

            // Add Calcs metrics for explanation factors
            if (!isSave && affordable) {
                try {
                    metrics.put("evPerRound", String.format("%.4f",
                            Calcs.evPerRound(state, playerIndex, s.card)));
                    metrics.put("portfolioDeltaEV", String.format("%.4f",
                            Calcs.portfolioDeltaEV(state, playerIndex, s.card)));
                    metrics.put("winProbDelta", String.format("%.4f",
                            Calcs.estimateWinProbDelta(state, playerIndex, s.card)));
                    metrics.put("tempoAdvantage", String.format("%.4f",
                            Calcs.tempoAdvantage(state, playerIndex, s.card)));
                } catch (Exception ignored) {
                    // Calcs may fail for certain edge-case states; metrics are optional
                }
            }

            options.add(new EngineResult.Option(s.card, s.score, List.of(), metrics, affordable));
        }

        // Sort using standard comparator (score DESC, save last, landmarks first, cost DESC)
        options.sort(EngineResult.OPTION_COMPARATOR);

        double confidence = 0.0;
        if (options.size() >= 2) {
            confidence = Math.max(0.0, Math.min(1.0,
                    options.get(0).score - options.get(1).score));
        }

        return new EngineResult(options, confidence, 0, computeTimeMs,
                "expectimax | d=" + maxDepth + " | " + leafEval + " | " + scored.size() + " options");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Package-private for use by {@link ExpectimaxContinuousWorker}. */
    static final class ScoredOption {
        final Project card;
        final double score;
        final boolean affordable;

        ScoredOption(Project card, double score, boolean affordable) {
            this.card = card;
            this.score = score;
            this.affordable = affordable;
        }
    }
}
