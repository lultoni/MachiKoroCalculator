package engine.expectimax;

import calcs.Calcs;
import calcs.RankEntry;
import calcs.WinProbability;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;
import engine.EngineConfig;
import engine.EngineResult;
import engine.SimulationEngine;
import engine.TurnPlan;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expectimax engine — deterministic minimax with probability-weighted chance nodes.
 *
 * <p>Exhaustively evaluates the game tree to a configurable depth (in full rounds),
 * using exact dice probabilities at chance nodes and minimax at decision nodes.
 * No random rollouts — all evaluation is deterministic.
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
 *   <li>{@code leafEval} — leaf evaluation function: {@code "winprob"} (default)
 *       or {@code "composite"}</li>
 * </ul>
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

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    @Override
    public String id() { return "expectimax"; }

    @Override
    public String description() { return "Expectimax — deterministic minimax with probability-weighted chance nodes"; }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        long start = System.currentTimeMillis();
        int diceCount = Calcs.optimalDiceCount(state, playerIndex);
        EngineResult result = evaluate(state, playerIndex, config);
        EngineResult.Option top = result.topAffordableRecommendation();
        Project purchase = "_wait_".equals(top.project.getId()) ? null : top.project;
        long elapsed = System.currentTimeMillis() - start;
        return TurnPlan.staticPlan(diceCount, purchase != null ? purchase : RankEntry.WAIT_SENTINEL,
                top.score, 0, elapsed, result);
    }

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        long startTime = System.currentTimeMillis();

        int maxDepthRounds = Integer.parseInt(config.getExtra("maxDepthRounds", "2"));
        String leafEval = config.getExtra("leafEval", "winprob");

        Player active = state.getPlayers()[playerIndex];
        int coins = active.getCoins();
        int n = state.getPlayers().length;
        int nextPlayer = (playerIndex + 1) % n;

        SupplyTracker supply = SupplyTracker.fromGameState(state);

        List<ScoredOption> scored = new ArrayList<>();

        // Save option — evaluate position without buying anything
        double saveScore = Math.min(1.0, evaluateTurn(state, supply, nextPlayer, playerIndex,
                maxDepthRounds, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                leafEval, false));
        scored.add(new ScoredOption(RankEntry.WAIT_SENTINEL, saveScore, true));

        // Non-landmark cards
        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            boolean affordable = coins >= p.getCost();

            GameState childState = state.copy();
            Player childActive = childState.getPlayers()[playerIndex];
            if (affordable) {
                childActive.setCoins(childActive.getCoins() - p.getCost());
            }
            childActive.addProject(p);
            SupplyTracker childSupply = supply.withPurchase(p.getId());

            double score;
            if (!affordable) {
                // Unaffordable — evaluate at leaf level only (no search budget wasted)
                score = leafEval(childState, playerIndex, leafEval);
            } else {
                score = Math.min(1.0, evaluateTurn(childState, childSupply, nextPlayer, playerIndex,
                        maxDepthRounds, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                        leafEval, false));
            }
            scored.add(new ScoredOption(p, score, affordable));
        }

        // Landmarks
        for (String lmId : LANDMARK_IDS) {
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;
            boolean affordable = coins >= lm.getCost();

            GameState childState = state.copy();
            Player childActive = childState.getPlayers()[playerIndex];
            if (affordable) {
                childActive.setCoins(childActive.getCoins() - lm.getCost());
            }
            childActive.addProject(lm);

            // Check instant win
            if (affordable && GameState.hasWon(childActive)) {
                scored.add(new ScoredOption(lm, 1.0, true));
                continue;
            }

            double score;
            if (!affordable) {
                score = leafEval(childState, playerIndex, leafEval);
            } else {
                score = Math.min(1.0, evaluateTurn(childState, supply, nextPlayer, playerIndex,
                        maxDepthRounds, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                        leafEval, false));
            }
            scored.add(new ScoredOption(lm, score, affordable));
        }

        long computeTimeMs = System.currentTimeMillis() - startTime;
        return buildResult(state, playerIndex, scored, coins, computeTimeMs, leafEval, maxDepthRounds);
    }

    // -------------------------------------------------------------------------
    // Core expectimax recursion
    // -------------------------------------------------------------------------

    /**
     * Evaluates one player's complete turn via expectimax.
     *
     * @param state            game state at start of this player's turn
     * @param supply           supply tracker
     * @param currentPlayer    index of the player whose turn it is
     * @param perspective      index of the player we're maximizing for
     * @param depthRemaining   full rounds remaining to search
     * @param alpha            alpha bound for pruning
     * @param beta             beta bound for pruning
     * @param leafEval         leaf evaluation function name
     * @param isBonusTurn      true if this is a Freizeitpark bonus turn
     * @return evaluation score in [0, 1] from perspective player's viewpoint
     */
    private double evaluateTurn(GameState state, SupplyTracker supply,
                                int currentPlayer, int perspective,
                                int depthRemaining, double alpha, double beta,
                                String leafEval, boolean isBonusTurn) {
        // Terminal check
        for (Player p : state.getPlayers()) {
            if (GameState.hasWon(p)) {
                int winnerIdx = indexOf(state.getPlayers(), p);
                return winnerIdx == perspective ? 1.0 : 0.0;
            }
        }

        // Depth check
        if (depthRemaining <= 0) {
            return leafEval(state, perspective, leafEval);
        }

        Player current = state.getPlayers()[currentPlayer];
        boolean isMax = (currentPlayer == perspective);
        boolean hasBahnhof = current.hasProject("bahnhof");

        if (hasBahnhof) {
            // DiceChoice: MAX/MIN over {1d6, 2d6}
            double val1d6 = evaluateChanceNode(state, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, false, isBonusTurn);

            if (isMax) {
                alpha = Math.max(alpha, val1d6);
            } else {
                beta = Math.min(beta, val1d6);
            }

            // Only evaluate 2d6 if not pruned
            if (alpha < beta) {
                double val2d6 = evaluateChanceNode(state, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, true, isBonusTurn);
                return isMax ? Math.max(val1d6, val2d6) : Math.min(val1d6, val2d6);
            }

            return val1d6;
        } else {
            return evaluateChanceNode(state, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, false, isBonusTurn);
        }
    }

    /**
     * Evaluates a chance node (dice roll). Computes probability-weighted average
     * over all roll outcomes.
     *
     * <p>For 2d6 when Freizeitpark is relevant, splits even rolls into doubles
     * and non-doubles branches with exact probabilities (15 total branches).
     * When Freizeitpark is not relevant, uses standard 11 branches.
     */
    private double evaluateChanceNode(GameState state, SupplyTracker supply,
                                       int currentPlayer, int perspective,
                                       int depthRemaining, double alpha, double beta,
                                       String leafEval, boolean twoDice, boolean isBonusTurn) {
        Player current = state.getPlayers()[currentPlayer];
        boolean hasFreizeitpark = current.hasProject("freizeitpark");
        boolean doublesRelevant = twoDice && hasFreizeitpark && !isBonusTurn;

        double weightedSum = 0.0;

        if (!twoDice) {
            // 1d6: 6 branches, P = 1/6 each, no doubles
            for (int roll = 1; roll <= 6; roll++) {
                double rollValue = evaluatePostRollIncome(state, supply, currentPlayer, perspective,
                        depthRemaining, alpha, beta, leafEval, roll, twoDice, false, isBonusTurn);
                weightedSum += CardIncome.P1[roll] * rollValue;
            }
        } else if (!doublesRelevant) {
            // 2d6 without Freizeitpark relevance: 11 branches
            for (int roll = 2; roll <= 12; roll++) {
                double prob = CardIncome.P2[roll];
                if (prob <= 0) continue;
                double rollValue = evaluatePostRollIncome(state, supply, currentPlayer, perspective,
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
                    int doublesWays = 1; // exactly one way: (roll/2, roll/2)
                    int nonDoublesWays = totalWays - doublesWays;

                    // Doubles branch
                    double doublesProb = doublesWays / 36.0;
                    double doublesValue = evaluatePostRollIncome(state, supply, currentPlayer, perspective,
                            depthRemaining, alpha, beta, leafEval, roll, true, true, isBonusTurn);
                    weightedSum += doublesProb * doublesValue;

                    // Non-doubles branch (if there are non-doubles ways)
                    if (nonDoublesWays > 0) {
                        double nonDoublesProb = nonDoublesWays / 36.0;
                        double nonDoublesValue = evaluatePostRollIncome(state, supply, currentPlayer, perspective,
                                depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                        weightedSum += nonDoublesProb * nonDoublesValue;
                    }
                } else {
                    // Odd roll: never doubles
                    double prob = totalWays / 36.0;
                    double rollValue = evaluatePostRollIncome(state, supply, currentPlayer, perspective,
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
     */
    private double evaluatePostRollIncome(GameState state, SupplyTracker supply,
                                           int currentPlayer, int perspective,
                                           int depthRemaining, double alpha, double beta,
                                           String leafEval, int roll, boolean twoDice,
                                           boolean isDoubles, boolean isBonusTurn) {
        // Apply roll income
        GameState afterRoll = state.copy();
        int[] deltas = RollResolver.computeAllDeltasForRoll(afterRoll, currentPlayer, roll);
        Player[] players = afterRoll.getPlayers();
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }

        Player current = players[currentPlayer];
        boolean hasFunkturm = current.hasProject("funkturm");

        if (hasFunkturm) {
            return evaluateFunkturmNode(state, afterRoll, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, roll, twoDice, isDoubles, isBonusTurn);
        }

        return evaluatePostFunkturm(afterRoll, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, roll, isDoubles, isBonusTurn);
    }

    /**
     * Evaluates the Funkturm keep-or-reroll decision.
     * MAX/MIN over {keep current roll, reroll (new chance node from pre-roll state)}.
     */
    private double evaluateFunkturmNode(GameState preRollState, GameState afterRollState,
                                         SupplyTracker supply, int currentPlayer, int perspective,
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
     * Same probability logic as evaluateChanceNode but skips Funkturm in post-roll.
     */
    private double evaluateChanceNodeNoFunkturm(GameState state, SupplyTracker supply,
                                                  int currentPlayer, int perspective,
                                                  int depthRemaining, double alpha, double beta,
                                                  String leafEval, boolean twoDice,
                                                  boolean isBonusTurn) {
        Player current = state.getPlayers()[currentPlayer];
        boolean hasFreizeitpark = current.hasProject("freizeitpark");
        boolean doublesRelevant = twoDice && hasFreizeitpark && !isBonusTurn;

        double weightedSum = 0.0;

        if (!twoDice) {
            for (int roll = 1; roll <= 6; roll++) {
                double rollValue = evaluatePostRollIncomeNoFunkturm(state, supply, currentPlayer,
                        perspective, depthRemaining, alpha, beta, leafEval, roll, false, false, isBonusTurn);
                weightedSum += CardIncome.P1[roll] * rollValue;
            }
        } else if (!doublesRelevant) {
            for (int roll = 2; roll <= 12; roll++) {
                double prob = CardIncome.P2[roll];
                if (prob <= 0) continue;
                double rollValue = evaluatePostRollIncomeNoFunkturm(state, supply, currentPlayer,
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
                    double doublesValue = evaluatePostRollIncomeNoFunkturm(state, supply, currentPlayer,
                            perspective, depthRemaining, alpha, beta, leafEval, roll, true, true, isBonusTurn);
                    weightedSum += doublesProb * doublesValue;

                    int nonDoublesWays = totalWays - 1;
                    if (nonDoublesWays > 0) {
                        double nonDoublesProb = nonDoublesWays / 36.0;
                        double nonDoublesValue = evaluatePostRollIncomeNoFunkturm(state, supply, currentPlayer,
                                perspective, depthRemaining, alpha, beta, leafEval, roll, true, false, isBonusTurn);
                        weightedSum += nonDoublesProb * nonDoublesValue;
                    }
                } else {
                    double prob = totalWays / 36.0;
                    double rollValue = evaluatePostRollIncomeNoFunkturm(state, supply, currentPlayer,
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
    private double evaluatePostRollIncomeNoFunkturm(GameState state, SupplyTracker supply,
                                                      int currentPlayer, int perspective,
                                                      int depthRemaining, double alpha, double beta,
                                                      String leafEval, int roll, boolean twoDice,
                                                      boolean isDoubles, boolean isBonusTurn) {
        GameState afterRoll = state.copy();
        int[] deltas = RollResolver.computeAllDeltasForRoll(afterRoll, currentPlayer, roll);
        Player[] players = afterRoll.getPlayers();
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }

        return evaluatePostFunkturm(afterRoll, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, roll, isDoubles, isBonusTurn);
    }

    /**
     * After Funkturm decision is resolved: handle Bürohaus (on roll 6) → Buy decision.
     */
    private double evaluatePostFunkturm(GameState state, SupplyTracker supply,
                                         int currentPlayer, int perspective,
                                         int depthRemaining, double alpha, double beta,
                                         String leafEval, int roll, boolean isDoubles,
                                         boolean isBonusTurn) {
        Player current = state.getPlayers()[currentPlayer];
        boolean hasBürohaus = current.hasProject("bürohaus");

        if (hasBürohaus && roll == 6) {
            return evaluateBürohausNode(state, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);
        }

        return evaluateBuyDecision(state, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);
    }

    /**
     * Evaluates the Bürohaus card-swap decision. MAX/MIN over {skip, all valid swap pairs}.
     */
    private double evaluateBürohausNode(GameState state, SupplyTracker supply,
                                         int currentPlayer, int perspective,
                                         int depthRemaining, double alpha, double beta,
                                         String leafEval, boolean isDoubles, boolean isBonusTurn) {
        boolean isMax = (currentPlayer == perspective);
        Player[] players = state.getPlayers();
        Player active = players[currentPlayer];

        // Collect own eligible cards (non-landmark, non-purple), deduplicated by ID
        Map<String, Project> ownById = new LinkedHashMap<>();
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                ownById.putIfAbsent(p.getId(), p);
            }
        }

        // Collect opponent eligible cards, deduplicated by (oppIdx, cardId)
        record OppCard(int oppIdx, String cardId) {}
        Map<OppCard, Project> oppCards = new LinkedHashMap<>();
        for (int oppIdx = 0; oppIdx < players.length; oppIdx++) {
            if (oppIdx == currentPlayer) continue;
            Set<String> seen = new LinkedHashSet<>();
            for (Project p : players[oppIdx].getOwned_projects()) {
                if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                    if (seen.add(p.getId())) {
                        oppCards.put(new OppCard(oppIdx, p.getId()), p);
                    }
                }
            }
        }

        // Option 1: Skip swap
        double bestVal = evaluateBuyDecision(state, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, isDoubles, isBonusTurn);

        if (isMax) {
            alpha = Math.max(alpha, bestVal);
        } else {
            beta = Math.min(beta, bestVal);
        }

        // Options 2..N: Each swap pair
        for (Map.Entry<OppCard, Project> oppEntry : oppCards.entrySet()) {
            if (alpha >= beta) break;

            int oppPlayerIdx = oppEntry.getKey().oppIdx;
            Project oppCard = oppEntry.getValue();

            for (Project ownCard : ownById.values()) {
                if (alpha >= beta) break;

                GameState swapped = state.copy();
                swapped.getPlayers()[currentPlayer].getOwned_projects().remove(ownCard);
                swapped.getPlayers()[oppPlayerIdx].getOwned_projects().remove(oppCard);
                swapped.getPlayers()[currentPlayer].addProject(oppCard);
                swapped.getPlayers()[oppPlayerIdx].addProject(ownCard);

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

        return bestVal;
    }

    /**
     * Evaluates the purchase decision. MAX/MIN over {save + all affordable cards}.
     * After purchase, recurses to next player's turn (or bonus turn if doubles + Freizeitpark).
     */
    private double evaluateBuyDecision(GameState state, SupplyTracker supply,
                                        int currentPlayer, int perspective,
                                        int depthRemaining, double alpha, double beta,
                                        String leafEval, boolean isDoubles, boolean isBonusTurn) {
        boolean isMax = (currentPlayer == perspective);
        Player active = state.getPlayers()[currentPlayer];
        int coins = active.getCoins();
        int n = state.getPlayers().length;

        boolean hasFreizeitpark = active.hasProject("freizeitpark");
        boolean bonusTurnAfter = isDoubles && hasFreizeitpark && !isBonusTurn;

        double bestVal = isMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        // Save option
        double saveVal = evaluateAfterBuy(state, supply, currentPlayer, perspective,
                depthRemaining, alpha, beta, leafEval, bonusTurnAfter);
        if (isMax) {
            bestVal = Math.max(bestVal, saveVal);
            alpha = Math.max(alpha, bestVal);
        } else {
            bestVal = Math.min(bestVal, saveVal);
            beta = Math.min(beta, bestVal);
        }

        // Non-landmark cards
        if (alpha < beta) {
            for (Project p : state.getUnbuilt_projects()) {
                if (alpha >= beta) break;
                if (!supply.canPurchase(p.getId())) continue;
                if (coins < p.getCost()) continue;
                if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;

                GameState childState = state.copy();
                Player childActive = childState.getPlayers()[currentPlayer];
                childActive.setCoins(childActive.getCoins() - p.getCost());
                childActive.addProject(p);
                SupplyTracker childSupply = supply.withPurchase(p.getId());

                // Check win
                if (GameState.hasWon(childActive)) {
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

                double childVal = evaluateAfterBuy(childState, childSupply, currentPlayer, perspective,
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

        // Landmarks
        if (alpha < beta) {
            for (String lmId : LANDMARK_IDS) {
                if (alpha >= beta) break;
                if (active.hasProject(lmId)) continue;
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm == null || coins < lm.getCost()) continue;

                GameState childState = state.copy();
                Player childActive = childState.getPlayers()[currentPlayer];
                childActive.setCoins(childActive.getCoins() - lm.getCost());
                childActive.addProject(lm);

                if (GameState.hasWon(childActive)) {
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

                double childVal = evaluateAfterBuy(childState, supply, currentPlayer, perspective,
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
     * Depth decrements when the perspective player's turn comes around again.
     */
    private double evaluateAfterBuy(GameState state, SupplyTracker supply,
                                     int currentPlayer, int perspective,
                                     int depthRemaining, double alpha, double beta,
                                     String leafEval, boolean bonusTurn) {
        if (bonusTurn) {
            // Freizeitpark bonus turn for same player — does NOT decrement depth
            return evaluateTurn(state, supply, currentPlayer, perspective,
                    depthRemaining, alpha, beta, leafEval, true);
        }

        // Advance to next player
        int n = state.getPlayers().length;
        int nextPlayer = (currentPlayer + 1) % n;
        int newDepth = (nextPlayer == perspective) ? depthRemaining - 1 : depthRemaining;

        return evaluateTurn(state, supply, nextPlayer, perspective,
                newDepth, alpha, beta, leafEval, false);
    }

    // -------------------------------------------------------------------------
    // Leaf evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluates a position at the depth limit.
     *
     * @param state       current game state
     * @param perspective the player we're evaluating for
     * @param evalFn      "winprob" or "composite"
     * @return score in [0, 1]
     */
    private double leafEval(GameState state, int perspective, String evalFn) {
        if ("composite".equals(evalFn)) {
            return leafEvalComposite(state, perspective);
        }
        return Math.max(0.0, Math.min(1.0,
                WinProbability.computeBaselineWinProb(state, perspective)));
    }

    /**
     * Composite position evaluation. Computes a differential score between the perspective
     * player and their strongest opponent, normalized to [0, 1] via sigmoid.
     */
    private double leafEvalComposite(GameState state, int perspective) {
        Player[] players = state.getPlayers();
        int n = players.length;

        double myScore = positionScore(state, perspective);
        double bestOppScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (i == perspective) continue;
            bestOppScore = Math.max(bestOppScore, positionScore(state, i));
        }

        double diff = myScore - bestOppScore;
        return sigmoid(diff / 10.0); // Scale factor for reasonable sigmoid range
    }

    /**
     * Raw position score for a single player: EV × remaining turns + landmark progress + coins.
     */
    private double positionScore(GameState state, int playerIndex) {
        Player player = state.getPlayers()[playerIndex];
        int n = state.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), playerIndex);
        double evPerRound = CardIncome.playerEvPerRound(player, n, oppCoins);

        int landmarkCount = 0;
        for (Project p : player.getOwned_projects()) {
            if (p.isIs_grossprojekt()) landmarkCount++;
        }

        return evPerRound * 12.0 + landmarkCount * 15.0 + player.getCoins() * 0.5;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // -------------------------------------------------------------------------
    // Result construction
    // -------------------------------------------------------------------------

    private EngineResult buildResult(GameState state, int playerIndex,
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

    private static int indexOf(Player[] players, Player target) {
        for (int i = 0; i < players.length; i++) {
            if (players[i] == target) return i;
        }
        return -1;
    }

    private static final class ScoredOption {
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
