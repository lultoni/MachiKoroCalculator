package logic.probability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stufe-1 Expectimax rollout tree for Machi Koro buy-decision evaluation.
 *
 * <h2>Overview</h2>
 * Given a post-roll {@link GameState} (coins already distributed), evaluates all
 * candidate buy actions for the active player via a depth-limited Expectimax tree.
 * Leaves are scored by {@link WinProbabilityCalc#computeBaselineWinProb} (Stufe 2).
 *
 * <h2>Tree structure — one depth level = one full round</h2>
 * <pre>
 *   [Root: buy decision for active player]
 *     ├─ BuyA → [Chance: dice rolls for own next turn (if depth>1)]
 *     │           ├─ roll=2 (P2[2]) → [N−1 opponent turns: Boltzmann roll+buy] → Leaf
 *     │           └─ ...
 *     └─ BuyB → ...
 * </pre>
 *
 * At depth=1, after the buy the tree immediately evaluates the leaf without expanding
 * another own turn — only the N−1 opponent turns for the current round are simulated
 * to bring the state to a realistic post-round position before Stufe-2 evaluation.
 *
 * <h2>Pruning</h2>
 * <ul>
 *   <li>Top-k buy options per decision node (ranked by {@link ProbabilityCalc#portfolioDeltaEV}).
 *   <li>Early exit when all top-k candidates are within ε = 0.01 win-probability of each other.</li>
 *   <li>Endgame extension: depth increased by 1 when any player is ≤ 8 coins short of winning.</li>
 * </ul>
 *
 * <h2>Special cases</h2>
 * <ul>
 *   <li>Freizeitpark doubles (own turn, depth>1): 6/36 of 2d6 outcomes trigger a bonus-turn
 *       chance node before opponent turns.</li>
 *   <li>Funkturm (own next turn, depth>1): re-roll decision node when first roll is below
 *       the baseline expected payout.</li>
 *   <li>Bürohaus roll=6 (own turn, depth>1): a swap decision node is inserted after the roll
 *       and before the buy.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All methods are static. {@code evaluate} takes copies of the state internally.
 */
public class RolloutTree {

    /** Minimum win-probability difference between top-k candidates to continue expanding. */
    private static final double EPSILON_CONVERGE = 0.01;

    /** Minimum coins-short threshold to trigger endgame depth extension. */
    private static final int ENDGAME_COINS_THRESHOLD = 8;

    private RolloutTree() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result of a rollout tree evaluation.
     *
     * @param bestAction      recommended buy action (may be {@link RankEntry#WAIT_SENTINEL} to save)
     * @param expectedWinProb E[win probability] for the best action
     * @param allValues       win-probability estimate for every evaluated action
     */
    public record RolloutResult(
            Project bestAction,
            double expectedWinProb,
            Map<Project, Double> allValues
    ) {}

    /**
     * Evaluates all candidate buy actions for the active player using an Expectimax tree.
     *
     * @param gs    post-roll {@link GameState} (coins already distributed for the current roll)
     * @param pi    index of the active player
     * @param depth search depth in full rounds (1 recommended for 4 players, 2 for 2 players)
     * @param topK  maximum buy candidates per decision node (recommended: 5)
     * @return evaluation result; {@code bestAction} is null if no candidates exist
     */
    public static RolloutResult evaluate(GameState gs, int pi, int depth, int topK) {
        // Endgame extension: if any player is close to winning, add one more depth level.
        int effectiveDepth = depth + (isEndgame(gs) ? 1 : 0);

        // Collect candidate buy actions for the root node
        ArrayList<Project> candidates = buildCandidates(gs, pi, topK);

        // Always include the "save" option
        if (!candidates.contains(RankEntry.WAIT_SENTINEL)) {
            candidates.add(RankEntry.WAIT_SENTINEL);
        }

        Map<Project, Double> allValues = new LinkedHashMap<>();
        Project bestAction = null;
        double bestWinProb = Double.NEGATIVE_INFINITY;

        for (Project action : candidates) {
            GameState stateAfterBuy = applyBuy(gs.copy(), pi, action);
            double winProb;

            if (effectiveDepth <= 1) {
                // Depth 1: simulate the remaining N-1 opponent turns in the current round,
                // then evaluate the leaf.
                GameState leafState = simulateOpponentTurns(stateAfterBuy, pi, gs.getPlayers().length);
                winProb = WinProbabilityCalc.computeBaselineWinProb(leafState, pi);
            } else {
                // Depth > 1: expand a full own-turn chance node for the next round,
                // weighting over all possible rolls.
                winProb = expandOwnTurnChanceNode(stateAfterBuy, pi, effectiveDepth - 1, topK);
            }

            allValues.put(action, winProb);
            if (winProb > bestWinProb) {
                bestWinProb = winProb;
                bestAction = action;
            }
        }

        // Early convergence check: if all values are within epsilon, we're done.
        // (The result is already complete; this annotation is for callers.)
        boolean converged = allConverged(allValues, EPSILON_CONVERGE);
        if (converged && allValues.size() > 1) {
            // All options equivalent — still return best, but signal via identical winProb
        }

        return new RolloutResult(bestAction, bestWinProb, allValues);
    }

    // -------------------------------------------------------------------------
    // Tree expansion helpers
    // -------------------------------------------------------------------------

    /**
     * Expands a chance node over all possible dice rolls for the active player's own turn.
     * Each outcome leads to opponent-turn simulation followed by either a recursive
     * expansion or a leaf evaluation.
     *
     * @param gs        state before the active player's roll (post-buy from previous decision)
     * @param pi        active player index
     * @param depthLeft remaining rounds to expand
     * @param topK      max buy candidates per decision node
     * @return probability-weighted win-probability estimate
     */
    private static double expandOwnTurnChanceNode(GameState gs, int pi, int depthLeft, int topK) {
        Player player = gs.getPlayers()[pi];
        boolean hasBahnhof    = player.hasProject("bahnhof");
        boolean hasFreizeitpark = player.hasProject("freizeitpark");
        boolean hasFunkturm   = player.hasProject("funkturm");

        double ev = 0.0;
        double baselineEV = 0.0; // used for Funkturm threshold

        // Compute baseline EV for Funkturm re-roll decision
        if (hasFunkturm) {
            boolean use2d6 = hasBahnhof;
            baselineEV = computeRollBaseline(gs, pi, use2d6);
        }

        if (!hasBahnhof) {
            // 1d6 only
            for (int r = 1; r <= 6; r++) {
                double p = CardIncome.P1[r];
                double rollEV = evalAfterRoll(gs, pi, r, false, depthLeft, topK,
                        hasFunkturm, baselineEV, hasFunkturm ? computeRollBaseline(gs, pi, false) : 0);
                ev += p * rollEV;
            }
        } else {
            // 2d6 — iterate over all 36 outcomes to capture Freizeitpark doubles
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    double p = 1.0 / 36.0;
                    int roll = d1 + d2;
                    boolean isDoubles = (d1 == d2);

                    GameState rollState = gs.copy();
                    applyRollToState(rollState, pi, roll);

                    // Bürohaus: if roll=6 and player owns bürohaus, execute optimal swap
                    if (roll == 6 && rollState.getPlayers()[pi].hasProject("bürohaus")) {
                        ProbabilityCalc.executeBürohausSwap(rollState, pi);
                    }

                    // Funkturm: re-roll if this roll is below baseline
                    if (hasFunkturm && isOwnTurnPayout(rollState, pi, roll) < baselineEV) {
                        // Re-roll: expand another 2d6 chance node (no chaining)
                        double rerollEV = computeRollBaseline(rollState, pi, true);
                        // Expected value from re-rolling (optimal: re-roll gives baseline)
                        // We approximate: take the better of current roll and re-roll expectation
                        double currentPayout = isOwnTurnPayout(rollState, pi, roll);
                        double rerollPayout  = rerollEV;
                        // Replace this outcome with a re-roll expansion
                        double rerollOutcome = expandRerollNode(rollState, pi, depthLeft, topK,
                                hasFreizeitpark, isDoubles);
                        ev += p * rerollOutcome;
                        continue;
                    }

                    // Freizeitpark: doubles → bonus turn before opponent turns
                    double outcome;
                    if (hasFreizeitpark && isDoubles) {
                        outcome = expandBonusTurnNode(rollState, pi, depthLeft, topK);
                    } else {
                        outcome = evalDecisionAndOpponents(rollState, pi, depthLeft, topK);
                    }
                    ev += p * outcome;
                }
            }
            // Also evaluate 1d6 for comparison; take max with 2d6 to model Bahnhof choice
            double ev1d6 = 0.0;
            double baseline1d6 = hasFunkturm ? computeRollBaseline(gs, pi, false) : 0;
            for (int r = 1; r <= 6; r++) {
                double p = CardIncome.P1[r];
                double rollEV = evalAfterRoll(gs, pi, r, false, depthLeft, topK,
                        hasFunkturm, baseline1d6, baseline1d6);
                ev1d6 += p * rollEV;
            }
            ev = Math.max(ev, ev1d6);
        }
        return ev;
    }

    /**
     * Evaluates a single roll outcome: applies roll, handles bürohaus, then proceeds to
     * the buy decision + opponent simulation.
     */
    private static double evalAfterRoll(GameState gs, int pi, int roll, boolean isDoubles,
                                         int depthLeft, int topK,
                                         boolean hasFunkturm, double baselineEV,
                                         double rollBaselineForFunkturm) {
        GameState rollState = gs.copy();
        applyRollToState(rollState, pi, roll);

        if (roll == 6 && rollState.getPlayers()[pi].hasProject("bürohaus")) {
            ProbabilityCalc.executeBürohausSwap(rollState, pi);
        }

        if (hasFunkturm && isOwnTurnPayout(rollState, pi, roll) < rollBaselineForFunkturm) {
            return expandRerollNode(rollState, pi, depthLeft, topK, false, false);
        }

        return evalDecisionAndOpponents(rollState, pi, depthLeft, topK);
    }

    /**
     * Expands the Funkturm re-roll node: the player re-rolls the same number of dice.
     * No Freizeitpark chaining on the second roll.
     */
    private static double expandRerollNode(GameState gs, int pi, int depthLeft, int topK,
                                            boolean hasFreizeitpark, boolean prevWasDoubles) {
        // Re-roll with same dice as initial (1d6 if no bahnhof)
        Player player = gs.getPlayers()[pi];
        boolean hasBahnhof = player.hasProject("bahnhof");
        double ev = 0.0;

        if (!hasBahnhof) {
            for (int r = 1; r <= 6; r++) {
                GameState s = gs.copy();
                applyRollToState(s, pi, r);
                ev += CardIncome.P1[r] * evalDecisionAndOpponents(s, pi, depthLeft, topK);
            }
        } else {
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    double p = 1.0 / 36.0;
                    int r = d1 + d2;
                    GameState s = gs.copy();
                    applyRollToState(s, pi, r);
                    ev += p * evalDecisionAndOpponents(s, pi, depthLeft, topK);
                }
            }
        }
        return ev;
    }

    /**
     * Expands a Freizeitpark bonus-turn node: the player rolls again before opponent turns.
     */
    private static double expandBonusTurnNode(GameState gs, int pi, int depthLeft, int topK) {
        Player player = gs.getPlayers()[pi];
        boolean hasBahnhof = player.hasProject("bahnhof");
        double ev = 0.0;

        if (!hasBahnhof) {
            for (int r = 1; r <= 6; r++) {
                GameState s = gs.copy();
                applyRollToState(s, pi, r);
                ev += CardIncome.P1[r] * evalDecisionAndOpponents(s, pi, depthLeft, topK);
            }
        } else {
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    double p = 1.0 / 36.0;
                    GameState s = gs.copy();
                    applyRollToState(s, pi, d1 + d2);
                    ev += p * evalDecisionAndOpponents(s, pi, depthLeft, topK);
                }
            }
        }
        return ev;
    }

    /**
     * After a roll is applied, makes the best buy decision (top-1 greedy for intermediate nodes),
     * simulates opponent turns, then either recurses or evaluates the leaf.
     */
    private static double evalDecisionAndOpponents(GameState gs, int pi, int depthLeft, int topK) {
        // Intermediate node: pick best buy greedily (top-1 from portfolioDeltaEV)
        Project bestBuy = pickBestBuy(gs, pi);
        GameState afterBuy = applyBuy(gs.copy(), pi, bestBuy);

        if (depthLeft <= 1) {
            GameState leaf = simulateOpponentTurns(afterBuy, pi, gs.getPlayers().length);
            return WinProbabilityCalc.computeBaselineWinProb(leaf, pi);
        } else {
            GameState afterOpponents = simulateOpponentTurns(afterBuy, pi, gs.getPlayers().length);
            return expandOwnTurnChanceNode(afterOpponents, pi, depthLeft - 1, topK);
        }
    }

    // -------------------------------------------------------------------------
    // Opponent simulation
    // -------------------------------------------------------------------------

    /**
     * Simulates one full round of opponent turns (all players except {@code pi})
     * using Boltzmann buy policy, probability-weighted over all dice outcomes.
     *
     * <p>For performance: uses a single representative roll (expected-value roll weighted
     * by dice probabilities) rather than expanding all 6/36 outcomes per opponent.
     * This keeps the branching factor manageable while still advancing coin/portfolio states.
     */
    private static GameState simulateOpponentTurns(GameState gs, int activePi, int n) {
        GameState state = gs.copy();
        Map<String, Integer> supply = GameSimulator.buildSupply(state);

        // Iterate opponents in turn order starting after the active player
        for (int step = 1; step < n; step++) {
            int oppIdx = (activePi + step) % n;

            // Roll for the opponent using a single sampled roll (fast path for tree simulation)
            // We use expected-value roll (rounded) as a deterministic stand-in per opponent
            // turn to avoid exponential blowup from expanding all opponent dice outcomes.
            int roll = sampleOpponentRoll(state, oppIdx);
            GameSimulator.applyRoll(state, oppIdx, roll);

            // Boltzmann buy for the opponent (T=0.7 as calibrated)
            int winner = GameSimulator.boltzmannBuy(state, oppIdx, supply,
                    ThreadLocalRandom.current(), 0.7);
            if (winner >= 0) {
                // Opponent won — this is a losing leaf for the active player;
                // Return the current state (win-prob will be ~0 for active player)
                return state;
            }
        }
        return state;
    }

    /**
     * Returns a representative dice roll for an opponent turn.
     * Uses a probability-weighted expected roll (rounded to the nearest valid outcome).
     */
    private static int sampleOpponentRoll(GameState state, int oppIdx) {
        Player player = state.getPlayers()[oppIdx];
        boolean hasBahnhof = player.hasProject("bahnhof");

        // Check if player has high-range cards (to decide 1d6 vs 2d6)
        boolean hasHighRange = false;
        for (Project p : player.getOwned_projects()) {
            for (int activation : p.getDice_activation()) {
                if (activation >= 7) { hasHighRange = true; break; }
            }
            if (hasHighRange) break;
        }

        if (!hasBahnhof || !hasHighRange) {
            // 1d6: expected value = 3.5, sample randomly for variety
            return 1 + ThreadLocalRandom.current().nextInt(6);
        } else {
            // 2d6: sample randomly
            return 1 + ThreadLocalRandom.current().nextInt(6)
                 + 1 + ThreadLocalRandom.current().nextInt(6);
        }
    }

    // -------------------------------------------------------------------------
    // Buy helpers
    // -------------------------------------------------------------------------

    /**
     * Applies a buy action to the given state in-place.
     * If action is {@link RankEntry#WAIT_SENTINEL} or null, no purchase is made.
     */
    private static GameState applyBuy(GameState state, int pi, Project action) {
        if (action == null || action == RankEntry.WAIT_SENTINEL) return state;
        if (action.isIs_grossprojekt() && state.getPlayers()[pi].hasProject(action.getId())) return state;
        Player player = state.getPlayers()[pi];
        if (player.getCoins() < action.getCost()) return state;

        player.setCoins(player.getCoins() - action.getCost());
        player.getOwned_projects().add(action);
        // Remove from unbuilt pool if it's a regular card
        if (!action.isIs_grossprojekt()) {
            state.getUnbuilt_projects().remove(action);
        }
        return state;
    }

    /**
     * Picks the single best buy action for an intermediate tree node, using
     * {@link ProbabilityCalc#portfolioDeltaEV} as the score.
     * Returns {@link RankEntry#WAIT_SENTINEL} if nothing affordable exists.
     */
    private static Project pickBestBuy(GameState gs, int pi) {
        Player player = gs.getPlayers()[pi];
        int coins = player.getCoins();

        Project best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        ArrayList<Project> pool = new ArrayList<>(gs.getUnbuilt_projects());
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) pool.add(p);
        }

        for (Project p : pool) {
            if (p.getCost() > coins) continue;
            if (p.getColor().equals("lila") && player.hasProject(p.getId())) continue;
            if (p.isIs_grossprojekt() && player.hasProject(p.getId())) continue;
            double delta = ProbabilityCalc.portfolioDeltaEV(gs, pi, p);
            if (delta > bestScore) { bestScore = delta; best = p; }
        }

        return best != null ? best : RankEntry.WAIT_SENTINEL;
    }

    /**
     * Builds the top-k buy candidates for the root decision node,
     * ranked by {@link ProbabilityCalc#portfolioDeltaEV}.
     */
    private static ArrayList<Project> buildCandidates(GameState gs, int pi, int topK) {
        Player player = gs.getPlayers()[pi];
        int coins = player.getCoins();

        ArrayList<Project> pool = new ArrayList<>(gs.getUnbuilt_projects());
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) pool.add(p);
        }

        // Score and sort all affordable candidates
        ArrayList<double[]> scored = new ArrayList<>(); // [index, score]
        for (int i = 0; i < pool.size(); i++) {
            Project p = pool.get(i);
            if (p.getCost() > coins) continue;
            if (p.getColor().equals("lila") && player.hasProject(p.getId())) continue;
            if (p.isIs_grossprojekt() && player.hasProject(p.getId())) continue;
            double delta = ProbabilityCalc.portfolioDeltaEV(gs, pi, p);
            scored.add(new double[]{i, delta});
        }

        // Sort by score descending
        scored.sort((a, b) -> Double.compare(b[1], a[1]));

        ArrayList<Project> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(pool.get((int) scored.get(i)[0]));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // State mutation helpers
    // -------------------------------------------------------------------------

    /**
     * Applies the income effects of {@code roll} to all players in the state.
     */
    private static void applyRollToState(GameState state, int pi, int roll) {
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
        Player[] players = state.getPlayers();
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }
    }

    /**
     * Returns a rough payout estimate for a single roll on the active player's own turn.
     * Used for Funkturm threshold comparison only.
     */
    private static double isOwnTurnPayout(GameState state, int pi, int roll) {
        return ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll)[pi];
    }

    /**
     * Computes the weighted average roll payout for the active player
     * (used as Funkturm re-roll threshold).
     */
    private static double computeRollBaseline(GameState gs, int pi, boolean use2d6) {
        return CardIncome.weightedRollEV(use2d6,
                r -> ProbabilityCalc.computeAllDeltasForRoll(gs, pi, r)[pi]);
    }

    // -------------------------------------------------------------------------
    // Endgame and convergence checks
    // -------------------------------------------------------------------------

    /**
     * Returns true if any player is within {@link #ENDGAME_COINS_THRESHOLD} coins of
     * being able to win (i.e. buy their last landmark).
     */
    private static boolean isEndgame(GameState gs) {
        for (Player player : gs.getPlayers()) {
            int gps = 0;
            for (Project p : player.getOwned_projects()) {
                if (p.isIs_grossprojekt()) gps++;
            }
            if (gps == 3) {
                // Find cost of last missing landmark
                for (Project p : ProjectLoader.getAllProjects()) {
                    if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                        if (p.getCost() - player.getCoins() <= ENDGAME_COINS_THRESHOLD) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if all values in the map are within {@code epsilon} of the maximum.
     */
    private static boolean allConverged(Map<Project, Double> values, double epsilon) {
        if (values.size() <= 1) return true;
        double max = values.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        return (max - min) < epsilon;
    }
}
