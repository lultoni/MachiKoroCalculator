package logic.probability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.IntToDoubleFunction;
import java.util.stream.IntStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure-static math engine for Machi Koro base-game buy-decision analysis.
 * <p>
 * No I/O, no state mutation of passed-in GameState objects (always work on copies),
 * no imports from the legacy {@code logic.*} layer.
 * <p>
 * All EV figures are in coins. Positive = income for the player; negative = payment by the player.
 * <p>
 * Convention used throughout {@link #get_I}:
 * <ul>
 *   <li>{@code oop} = "owner of project" — {@code true} when the queried player owns the card.</li>
 *   <li>For red (rot) cards, {@code oop=false} → the queried player is the active roller who pays.
 *       The return value is negative (coin loss for the roller).</li>
 *   <li>For blue (blau) cards, the return is always positive regardless of {@code oop}.</li>
 * </ul>
 * <p>
 * Implementation is split across package-private helper classes:
 * <ul>
 *   <li>{@link CardIncome} — probability tables, {@code get_I}, {@code PlayerStats},
 *       {@code buildOpponentCoins}, {@code sumColorIncome}, {@code weightedRollEV},
 *       {@code bestDiceEV}, {@code singleCardEvPerRound}.</li>
 *   <li>{@link WinProbabilityCalc} — {@code computeScores}, {@code softmaxEntry},
 *       {@code mcWinRate}, {@code estimateWinProbDelta}, {@code computeBaselineWinProb}.</li>
 *   <li>{@link BürohausLogic} — bürohaus card-swap EV, swap note generation,
 *       and swap execution.</li>
 * </ul>
 */
public class ProbabilityCalc {

    // Expose probability tables for tests and external callers
    /** P1[r] = probability of rolling r with 1d6. Valid indices: 1–6. */
    static final double[] P1 = CardIncome.P1;

    /** P2[r] = probability of rolling r with 2d6. Valid indices: 2–12. */
    static final double[] P2 = CardIncome.P2;

    // -------------------------------------------------------------------------
    // Public probability accessors
    // -------------------------------------------------------------------------

    /**
     * @param r roll value
     * @return probability of rolling r with 1d6 (1/6 for r in 1..6, 0 otherwise)
     */
    public static double get_P1(int r) {
        return (r >= 0 && r < P1.length) ? P1[r] : 0.0;
    }

    /**
     * @param r roll value
     * @return probability of rolling r with 2d6 (bell-curve, r in 2..12, 0 otherwise)
     */
    public static double get_P2(int r) {
        return (r >= 0 && r < P2.length) ? P2[r] : 0.0;
    }

    // -------------------------------------------------------------------------
    // get_I — per-card income/cost for a single roll (delegates to CardIncome)
    // -------------------------------------------------------------------------

    /**
     * Returns the coin income (positive) or cost (negative) for the queried player
     * when roll {@code r} occurs and project {@code p_id} activates.
     *
     * @param r    dice roll result
     * @param p_id project ID (matches keys in projects.json)
     * @param oop  true if the queried player is the owner of this project
     * @param eb   true if the owner has Einkaufszentrum built
     * @param f_c  number of FOOD-category projects owned by the player
     * @param a_c  number of ANIMAL-category projects owned by the player
     * @param p_c  number of PRODUCTION-category projects owned by the player
     * @param c    current coins of the queried player (used for inability-to-pay clamping)
     * @param co   current coins of each other player (used for Stadion/Fernsehsender)
     * @return coin delta for the queried player; 0 if the card does not activate on this roll
     */
    public static int get_I(int r, String p_id, boolean oop, boolean eb,
                             int f_c, int a_c, int p_c, int c, int[] co) {
        return CardIncome.get_I(r, p_id, oop, eb, f_c, a_c, p_c, c, co);
    }

    // -------------------------------------------------------------------------
    // computeNetGainForRoll
    // -------------------------------------------------------------------------

    /**
     * Computes the net coin change for the active player (playerIndex) when the given
     * roll occurs on their own turn.
     *
     * <p>Processing order matches the official rules: <b>Red → Blue → Green → Purple</b>.
     * Red card payments are deducted first (against coins held before any income is received),
     * then blue and green income is credited, then purple effects fire last.
     *
     * <p>When multiple red card owners trigger on the same roll, payments are collected in
     * <b>counter-clockwise</b> order from the active player, as specified by the rules.
     * Earlier claimants in that order are paid in full; later claimants receive whatever
     * remains if the active player runs out of coins.
     *
     * @param state       current game state (with candidate already added to player's projects)
     * @param playerIndex the active player (roller)
     * @param roll        the dice result (1–12)
     * @param isDoubles   true when 2 dice were rolled and both showed the same face
     * @return net coin change for the active player
     */
    private static int computeNetGainForRoll(GameState state, int playerIndex,
                                              int roll, boolean isDoubles) {
        Player activePlayer = state.getPlayers()[playerIndex];
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(activePlayer);
        int activeCoins = activePlayer.getCoins();

        Player[] players = state.getPlayers();
        int n = players.length;

        int net = 0;

        // --- Red cards (FIRST, per rules: Rot → Blau & Grün → Violett).
        //     Opponents' red cards are paid counter-clockwise from the active player.
        //     Payment is deducted from coins held BEFORE any income this turn.
        int remainingCoins = activeCoins;
        for (int step = 1; step < n; step++) {
            int opponentIdx = (playerIndex - step + n) % n; // counter-clockwise
            Player opponent = players[opponentIdx];
            CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(opponent);
            for (Project p : opponent.getOwned_projects()) {
                if ("rot".equals(p.getColor())) {
                    int loss = CardIncome.get_I(roll, p.getId(), false,
                            oppStats.hasEinkaufszentrum,
                            0, 0, 0,
                            remainingCoins, new int[0]);
                    if (loss < 0 && -loss > remainingCoins) loss = -remainingCoins;
                    net += loss;
                    remainingCoins += loss;
                    if (remainingCoins < 0) remainingCoins = 0;
                }
            }
        }

        // --- Blue cards: own blue cards pay from the bank regardless of red losses.
        int[] opponentCoins = CardIncome.buildOpponentCoins(players, playerIndex);
        net += CardIncome.sumColorIncome(activePlayer, "blau", roll, activeStats, activeCoins, opponentCoins);

        // --- Green cards: own-turn only, pay from bank.
        net += CardIncome.sumColorIncome(activePlayer, "grün", roll, activeStats, activeCoins, opponentCoins);

        // --- Purple cards: own-turn only, fire last.
        for (Project p : activePlayer.getOwned_projects()) {
            if ("lila".equals(p.getColor())) {
                int[] freshOpponentCoins = CardIncome.buildOpponentCoins(players, playerIndex);
                net += CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount,
                        activeStats.productionCount,
                        activeCoins + net, freshOpponentCoins);
            }
        }

        return net;
    }

    // -------------------------------------------------------------------------
    // computeOpponentTurnGainForRoll
    // -------------------------------------------------------------------------

    /**
     * Computes the net coin change for the tracked player (playerIndex) when it is
     * an OPPONENT's turn and they roll {@code roll}.
     * <p>
     * On an opponent's turn the tracked player gains from their blue cards and red cards only.
     *
     * @param state             current game state
     * @param playerIndex       the tracked player (not the active roller)
     * @param activeRollerIndex the player currently rolling
     * @param roll              dice result
     * @return net coin change for playerIndex during this opponent turn
     */
    private static int computeOpponentTurnGainForRoll(GameState state, int playerIndex,
                                                       int activeRollerIndex, int roll) {
        Player trackedPlayer = state.getPlayers()[playerIndex];
        Player activeRoller  = state.getPlayers()[activeRollerIndex];
        CardIncome.PlayerStats trackedStats = CardIncome.PlayerStats.of(trackedPlayer);
        int trackedCoins = trackedPlayer.getCoins();
        int rollerCoins  = activeRoller.getCoins();

        int net = 0;

        // Blue cards: tracked player earns from their own blue cards
        net += CardIncome.sumColorIncome(trackedPlayer, "blau", roll, trackedStats,
                trackedCoins, new int[]{rollerCoins});

        // Red cards: tracked player earns from their own red cards (roller pays them)
        for (Project p : trackedPlayer.getOwned_projects()) {
            if ("rot".equals(p.getColor())) {
                int rollerLoss = CardIncome.get_I(roll, p.getId(), false,
                        trackedStats.hasEinkaufszentrum,
                        0, 0, 0,
                        Math.max(0, rollerCoins), new int[0]);
                net += Math.abs(rollerLoss);
            }
        }

        return net;
    }

    // -------------------------------------------------------------------------
    // funkturmEV — EV gain from Funkturm re-roll option
    // -------------------------------------------------------------------------

    /**
     * Returns the expected coin gain from the Funkturm (radio tower) re-roll option on a
     * single turn, given the payout function {@code g(r)} and the number of dice used.
     *
     * <p>Funkturm allows the player to discard their first roll and roll again <em>once</em>
     * if they don't like the outcome. The optimal strategy is to re-roll whenever the first
     * roll is below the expected value of re-rolling (which equals E_baseline, the plain
     * expected value without Funkturm).
     *
     * <p>Correct formula:
     * <pre>
     *   E[Funkturm] = E_baseline + Σ_{r : g(r) &lt; E_baseline} P(r) × (E_baseline − g(r))
     * </pre>
     *
     * @param use2d6    if true, compute using 2d6 distribution; otherwise 1d6
     * @param payoutFn  maps a roll result to the coin payout for that roll
     * @return EV of one turn under the Funkturm re-roll policy
     */
    private static double funkturmEV(boolean use2d6, IntToDoubleFunction payoutFn) {
        double baseline = CardIncome.weightedRollEV(use2d6, payoutFn);
        double gain = 0.0;
        if (use2d6) {
            for (int r = 2; r <= 12; r++) {
                double g = payoutFn.applyAsDouble(r);
                if (g < baseline) gain += P2[r] * (baseline - g);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                double g = payoutFn.applyAsDouble(r);
                if (g < baseline) gain += P1[r] * (baseline - g);
            }
        }
        return baseline + gain;
    }

    // -------------------------------------------------------------------------
    // bestSecondRollEV
    // -------------------------------------------------------------------------

    /**
     * Returns the EV of the best possible re-roll triggered by Freizeitpark (doubles).
     * No double-chaining: doubles on the second roll do not grant a third turn.
     *
     * @param state           game state with candidate already purchased
     * @param playerIndex     the rolling player
     * @param forcedDiceCount -1 = player may choose freely (Bahnhof present);
     *                        1  = must use 1 die;
     *                        2  = must use 2 dice (Funkturm forces same count as initial roll)
     * @return expected net gain from the re-roll
     */
    private static double bestSecondRollEV(GameState state, int playerIndex, int forcedDiceCount) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];

        if (forcedDiceCount == 1) return CardIncome.weightedRollEV(false, payout);
        if (forcedDiceCount == 2) return CardIncome.weightedRollEV(true, payout);
        return hasBahnhof ? CardIncome.bestDiceEV(true, payout) : CardIncome.weightedRollEV(false, payout);
    }

    // -------------------------------------------------------------------------
    // buildRollGainCache — precompute roll payouts for a single-player/state pair
    // -------------------------------------------------------------------------

    /**
     * Precomputes the net coin gain (isDoubles=false) for the active player over all
     * possible roll values 1–12 and stores them in a {@code double[13]} array.
     * Index 0 is unused; indices 1–6 cover 1d6 rolls, indices 2–12 cover 2d6 rolls.
     *
     * <p>Because {@link #computeNetGainForRoll}'s {@code isDoubles} parameter is unused
     * in the computation (it was a reserved hook), the cache is valid for both the 1d6 and
     * 2d6 roll paths. The array is built once per call to {@link #immediateEV} /
     * {@link #evPerRound} and reused for every probability-weighted sum within that call.
     */
    private static double[] buildRollGainCache(GameState state, int playerIndex) {
        double[] cache = new double[13];
        for (int r = 1; r <= 12; r++) {
            cache[r] = computeNetGainForRoll(state, playerIndex, r, false);
        }
        return cache;
    }

    // -------------------------------------------------------------------------
    // computeOwnTurnEV — shared Bahnhof/Freizeitpark/Funkturm logic
    // -------------------------------------------------------------------------

    /**
     * Returns the expected coin gain for the active player on their own turn, given a
     * precomputed roll-gain cache. Centralises the Bahnhof/Freizeitpark/Funkturm decision
     * logic that was previously duplicated across {@link #immediateEV} and {@link #evPerRound}.
     *
     * @param state           game state (with candidate already purchased)
     * @param playerIndex     active player
     * @param cache           roll-gain array from {@link #buildRollGainCache}; index = roll value
     * @param hasBahnhof      whether the player owns Bahnhof
     * @param hasFreizeitpark whether the player owns Freizeitpark
     * @param hasFunkturm     whether the player owns Funkturm
     * @return expected coin gain on the own turn (EV, excluding opponent turns)
     */
    private static double computeOwnTurnEV(GameState state, int playerIndex,
                                           double[] cache,
                                           boolean hasBahnhof,
                                           boolean hasFreizeitpark,
                                           boolean hasFunkturm) {
        IntToDoubleFunction payout = r -> cache[r];

        if (!hasBahnhof) {
            return hasFunkturm
                    ? funkturmEV(false, payout)
                    : CardIncome.weightedRollEV(false, payout);
        }

        // 1d6 path
        double ev1 = hasFunkturm
                ? funkturmEV(false, payout)
                : CardIncome.weightedRollEV(false, payout);

        // 2d6 path
        double ev2 = 0.0;
        for (int d1 = 1; d1 <= 6; d1++) {
            for (int d2 = 1; d2 <= 6; d2++) {
                double p = 1.0 / 36.0;
                boolean isDoubles = (d1 == d2);
                ev2 += p * cache[d1 + d2];
                if (hasFreizeitpark && isDoubles) {
                    ev2 += p * bestSecondRollEV(state, playerIndex, hasFunkturm ? 2 : -1);
                }
            }
        }
        if (hasFunkturm) {
            ev2 = Math.max(ev2, funkturmEV(true, payout));
        }

        return Math.max(ev1, ev2);
    }

    // -------------------------------------------------------------------------
    // immediateEV — EV of the buyer's current turn
    // -------------------------------------------------------------------------

    /**
     * Returns the best expected coin gain for playerIndex on their current turn,
     * assuming they just bought {@code candidate}.
     * <p>
     * Accounts for: 1d6 vs 2d6 choice (Bahnhof), Einkaufszentrum bonuses (via get_I),
     * Freizeitpark second-roll on doubles, Funkturm same-dice forced reroll.
     *
     * @param gs              game state before the purchase
     * @param playerIndex     the buying player
     * @param candidate       project being purchased (simulated as owned)
     * @param returnAfterCost if true, subtracts candidate.getCost() from the result
     * @return best EV for this turn (optionally net of purchase cost)
     */
    public static double immediateEV(GameState gs, int playerIndex,
                                     Project candidate, boolean returnAfterCost) {
        GameState state = gs.copy();
        Player player = state.getPlayers()[playerIndex];
        player.getOwned_projects().add(candidate);

        boolean hasBahnhof    = player.hasProject("bahnhof");
        boolean hasFreizeitpark = player.hasProject("freizeitpark");
        boolean hasFunkturm   = player.hasProject("funkturm");

        double[] cache = buildRollGainCache(state, playerIndex);
        double evTotal = computeOwnTurnEV(state, playerIndex, cache,
                hasBahnhof, hasFreizeitpark, hasFunkturm);

        // Bürohaus card-swap EV: fires on own turn when roll = 6 (lila, own-turn only).
        if (player.hasProject("bürohaus")) {
            double swapEV = bürohausSwapEV(state, playerIndex);
            double p6 = hasBahnhof ? P2[6] : P1[6];
            evTotal += p6 * swapEV;
        }

        if (returnAfterCost) return evTotal - candidate.getCost();
        return evTotal;
    }

    // -------------------------------------------------------------------------
    // evPerRound
    // -------------------------------------------------------------------------

    /**
     * Returns the expected net coin gain for playerIndex over a full round
     * (playerIndex's own turn + N−1 opponent turns), assuming {@code candidate} is already owned.
     * <p>
     * Blue cards contribute on every turn. Green and purple contribute only on own turn.
     * Red cards contribute (as income) on every opponent's turn.
     * <p>
     * Coin counts are adjusted to a projected value before evaluation:
     * {@code projectedCoins = currentCoins + estimateUncappedOwnTurnEV}. This avoids the
     * static-snapshot bias where a player with 0 coins appears permanently unable to pay red
     * cards, when in reality they will accumulate income before the triggering roll fires.
     * {@code immediateEV} is <em>not</em> affected — it models the current turn with current coins.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the player to evaluate
     * @param candidate   project being purchased (simulated as owned)
     * @return expected coins gained over one full round
     */
    public static double evPerRound(GameState gs, int playerIndex, Project candidate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);

        // Project each player's coins forward by their expected per-turn blue+green income.
        // This corrects the static-snapshot bias in red card clamping for the EV horizon.
        // Step-index correction: the active player accumulates additional blue income
        // on each prior opponent turn before a red card fires. We precompute the active
        // player's expected blue-only income per opponent turn (P(r) × blue cards for each r),
        // then add step × bluePerOppTurn when evaluating opponent turn #step.
        int n = state.getPlayers().length;
        double[] baseCoins = new double[n];
        for (int i = 0; i < n; i++) {
            Player p = state.getPlayers()[i];
            boolean pHasBahnhof = p.hasProject("bahnhof");
            baseCoins[i] = p.getCoins()
                    + CardIncome.estimateUncappedOwnTurnEV(p, pHasBahnhof);
            p.setCoins((int) Math.round(baseCoins[i]));
        }
        // Blue income the active player earns per opponent turn (from blue cards firing
        // on each opponent's turn). Used for step-aware coin projection below.
        final Player activeP = state.getPlayers()[playerIndex];
        final CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(activeP);
        double bluePerOppTurn = 0.0;
        for (int r = 2; r <= 12; r++) {
            int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, new int[0]);
            bluePerOppTurn += CardIncome.P2[r] * blueIncome;
        }
        // Also capture roll-1 blue income (weizenfeld) via 1d6 distribution
        double bluePerOppTurn1d6 = 0.0;
        for (int r = 1; r <= 6; r++) {
            int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, new int[0]);
            bluePerOppTurn1d6 += CardIncome.P1[r] * blueIncome;
        }
        bluePerOppTurn = Math.max(bluePerOppTurn, bluePerOppTurn1d6);

        double total = 0.0;

        // Own turn: blue + green + purple + red costs paid
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        boolean hasFreizeitpark = state.getPlayers()[playerIndex].hasProject("freizeitpark");
        boolean hasFunkturm    = state.getPlayers()[playerIndex].hasProject("funkturm");
        double[] ownCache = buildRollGainCache(state, playerIndex);
        total += computeOwnTurnEV(state, playerIndex, ownCache, hasBahnhof, hasFreizeitpark, hasFunkturm);

        // Opponent turns: tracked player gains from blue + red cards each opponent turn.
        // For each step, the active player has accumulated 'step × bluePerOppTurn' coins
        // since the round started, improving their ability to pay red cards.
        int step = 0;
        for (int opponentIdx = 0; opponentIdx < n; opponentIdx++) {
            if (opponentIdx == playerIndex) continue;
            step++;
            // Update active player's projected coins for this step
            int stepCoins = (int) Math.round(baseCoins[playerIndex] + step * bluePerOppTurn);
            state.getPlayers()[playerIndex].setCoins(stepCoins);

            boolean opponentHasBahnhof = state.getPlayers()[opponentIdx].hasProject("bahnhof");
            final int oppIdx = opponentIdx;
            total += CardIncome.bestDiceEV(opponentHasBahnhof,
                    r -> computeOpponentTurnGainForRoll(state, playerIndex, oppIdx, r));
        }

        return total;
    }

    // -------------------------------------------------------------------------
    // roiOverHorizon — discounted ROI and variance
    // -------------------------------------------------------------------------

    /**
     * Computes discounted ROI for buying {@code candidate} over {@code horizonTurns} rounds,
     * along with variance and other risk metrics, and returns a fully populated {@link RankEntry}.
     *
     * <p>Formula: {@code ROI = evPerRound × γ × (1 − γ^T) / (1 − γ) − cost}
     * (L'Hôpital branch when γ ≈ 1).
     *
     * @param gs             game state before the purchase
     * @param playerIndex    the buying player
     * @param candidate      project being evaluated
     * @param horizonTurns   number of rounds to look ahead (T)
     * @param discountFactor per-round discount factor γ (0 < γ ≤ 1)
     * @return populated RankEntry
     */
    public static RankEntry roiOverHorizon(GameState gs, int playerIndex, Project candidate,
                                            int horizonTurns, double discountFactor) {
        RankEntry entry = new RankEntry();
        entry.project = candidate;

        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);

        entry.immediateEV          = immediateEV(gs, playerIndex, candidate, false);
        entry.immediateEV_afterCost = entry.immediateEV - candidate.getCost();
        entry.evPerRound           = evPerRound(gs, playerIndex, candidate);
        entry.portfolioDeltaEV     = portfolioDeltaEV(gs, playerIndex, candidate);

        // Geometric-series ROI with L'Hôpital guard
        double geometricSum = geometricSum(horizonTurns, discountFactor);
        entry.roiOverHorizon = entry.evPerRound * geometricSum - candidate.getCost();

        entry.variance             = computeVarianceOwnTurn(state, playerIndex);
        entry.probNoIncomeOwnTurn  = computeProbNoIncomeOwnTurn(state, playerIndex);
        entry.probNoIncomeRound    = computeProbNoIncomeRound(state, playerIndex);

        return entry;
    }

    private static double computeVarianceOwnTurn(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        if (!hasBahnhof) return computeVariance1d6(state, playerIndex);
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];
        boolean use2d6 = CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        return use2d6 ? computeVariance2d6(cache) : computeVariance1d6(cache);
    }

    private static double computeVariance1d6(GameState state, int playerIndex) {
        double[] cache = buildRollGainCache(state, playerIndex);
        return computeVariance1d6(cache);
    }

    private static double computeVariance1d6(double[] cache) {
        double ev = 0.0, e2 = 0.0;
        for (int d = 1; d <= 6; d++) {
            double gain = cache[d];
            ev += P1[d] * gain;
            e2 += P1[d] * gain * gain;
        }
        return e2 - ev * ev;
    }

    private static double computeVariance2d6(GameState state, int playerIndex) {
        double[] cache = buildRollGainCache(state, playerIndex);
        return computeVariance2d6(cache);
    }

    private static double computeVariance2d6(double[] cache) {
        double ev = 0.0, e2 = 0.0;
        for (int d1 = 1; d1 <= 6; d1++) {
            for (int d2 = 1; d2 <= 6; d2++) {
                double p    = 1.0 / 36.0;
                double gain = cache[d1 + d2];
                ev += p * gain;
                e2 += p * gain * gain;
            }
        }
        return e2 - ev * ev;
    }

    private static double computeProbNoIncomeOwnTurn(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];
        boolean use2d6 = hasBahnhof && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        return CardIncome.weightedRollEV(use2d6, r -> payout.applyAsDouble(r) == 0 ? 1.0 : 0.0);
    }

    private static double computeProbNoIncomeRound(GameState state, int playerIndex) {
        double prob = computeProbNoIncomeOwnTurn(state, playerIndex);
        int n = state.getPlayers().length;
        for (int oppIdx = 0; oppIdx < n; oppIdx++) {
            if (oppIdx == playerIndex) continue;
            boolean oppHasBahnhof = state.getPlayers()[oppIdx].hasProject("bahnhof");
            final int oi = oppIdx;
            double probZeroOppTurn = CardIncome.weightedRollEV(oppHasBahnhof,
                    r -> computeOpponentTurnGainForRoll(state, playerIndex, oi, r) == 0 ? 1.0 : 0.0);
            prob *= probZeroOppTurn;
        }
        return prob;
    }

    // -------------------------------------------------------------------------
    // Win probability (delegates to WinProbabilityCalc)
    // -------------------------------------------------------------------------

    /**
     * Returns the baseline win probability for {@code playerIndex} in the current state,
     * using the analytical softmax score approximation (no Monte Carlo).
     *
     * @param gs          current game state
     * @param playerIndex the player whose win probability to estimate
     * @return estimated win probability in [0, 1]
     */
    public static double computeBaselineWinProb(GameState gs, int playerIndex) {
        return WinProbabilityCalc.computeBaselineWinProb(gs, playerIndex);
    }

    /**
     * Returns the synergy-aware expected-coin-per-round for {@code playerIndex}'s current portfolio.
     * This evaluates all owned cards together (own turn + opponent turns), accounting for
     * Einkaufszentrum, food/animal/production counts. No candidate card is added.
     */
    public static double portfolioEvPerRound(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);
        return CardIncome.playerEvPerRound(player, gs.getPlayers().length, oppCoins);
    }

    /**
     * Returns the marginal per-round EV gain from adding {@code candidate} to the player's
     * current portfolio.
     *
     * <p>Formula:
     * <pre>
     *   portfolioDeltaEV = playerEvPerRound(portfolio + candidate)
     *                    − playerEvPerRound(portfolio)
     * </pre>
     *
     * <p>Unlike {@link #evPerRound}, which evaluates only the candidate card's own income
     * in the player's synergy context, this method captures cross-card interactions:
     * <ul>
     *   <li>Buying Bauernhof increases Molkerei's value (animal count rises).</li>
     *   <li>Buying a food card increases Markthalle's value.</li>
     *   <li>Buying Bahnhof increases all 7–12 cards' effective EV (2d6 now available).</li>
     * </ul>
     *
     * <p>Uses {@link CardIncome#playerEvPerRound} for both measurements, so all synergies
     * (multipliers, opponent coins, dice-distribution choice) are reflected correctly.
     * Allocation cost: two {@link CardIncome.PlayerStats} objects; no {@link GameState#copy()}.
     *
     * @param gs          current game state (candidate not yet owned)
     * @param playerIndex the buying player
     * @param candidate   the card being considered
     * @return marginal EV per round (can be negative if the card competes with owned cards)
     */
    public static double portfolioDeltaEV(GameState gs, int playerIndex, Project candidate) {
        Player player = gs.getPlayers()[playerIndex];
        int n = gs.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);

        double before = CardIncome.playerEvPerRound(player, n, oppCoins);

        // Temporarily add candidate to evaluate the post-purchase portfolio.
        // Use withExtra for PlayerStats — no GameState.copy() needed for the EV calculation.
        // playerEvPerRound needs an actual Player object, so we do a lightweight list add/remove.
        player.getOwned_projects().add(candidate);
        double after = CardIncome.playerEvPerRound(player, n, oppCoins);
        player.getOwned_projects().remove(player.getOwned_projects().size() - 1);

        return after - before;
    }

    /**
     * Returns the optimal dice count (1 or 2) for the active player on their own turn,
     * based on a comparison of expected net gain under each dice distribution.
     * <p>
     * If the player does not own Bahnhof, always returns 1 (no choice available).
     * If 2d6 yields strictly higher EV, returns 2; otherwise returns 1.
     * A tie ({@code |ev1 - ev2| < 1e-6}) is treated as 1d6 (no need to switch).
     *
     * @param gs          current game state (no candidate added — evaluates current portfolio)
     * @param playerIndex the active player
     * @return 1 if 1d6 is optimal or equal, 2 if 2d6 is strictly better
     */
    public static int optimalDiceCount(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        if (!player.hasProject("bahnhof")) return 1;
        double[] cache = buildRollGainCache(gs, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];
        double ev1 = CardIncome.weightedRollEV(false, payout);
        double ev2 = CardIncome.weightedRollEV(true,  payout);
        return (ev2 - ev1 > 1e-6) ? 2 : 1;
    }

    /**
     * Estimates the change in win probability for playerIndex from buying {@code candidate}.
     *
     * <h3>Analytical mode ({@code mcSimulations == 0})</h3>
     * Uses a softmax score approximation.
     *
     * <h3>Monte Carlo mode ({@code mcSimulations > 0})</h3>
     * Runs parallel full-game simulations for baseline and post-buy state.
     *
     * @param gs             game state before purchase
     * @param playerIndex    the buying player
     * @param candidate      project being evaluated
     * @param searchDepth    reserved for future Expectimax (ignored)
     * @param mcSimulations  number of MC simulations per state; 0 = analytical only
     * @return estimated win probability delta (positive = better for playerIndex)
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex,
                                               Project candidate,
                                               int searchDepth, int mcSimulations) {
        return WinProbabilityCalc.estimateWinProbDelta(gs, playerIndex, candidate, mcSimulations, 0);
    }

    /**
     * Runs {@code numSims} Monte Carlo simulations in parallel and returns the
     * fraction in which {@code playerIndex} wins.
     *
     * @param state       starting state (read-only; a copy is taken per simulation)
     * @param playerIndex player whose win rate is measured
     * @param numSims     number of simulations to run
     * @return win rate in [0, 1]
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims) {
        return WinProbabilityCalc.mcWinRate(state, playerIndex, numSims, 0.0);
    }

    /**
     * Runs {@code numSims} Monte Carlo simulations in parallel with the specified
     * Boltzmann temperature for the buy policy, and returns the win rate.
     *
     * @param state       starting state (read-only; a copy is taken per simulation)
     * @param playerIndex player whose win rate is measured
     * @param numSims     number of simulations to run
     * @param temperature Boltzmann temperature (0.0 = greedy, 0.7 = recommended exploration)
     * @return win rate in [0, 1]
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims, double temperature) {
        return WinProbabilityCalc.mcWinRate(state, playerIndex, numSims, temperature);
    }

    // -------------------------------------------------------------------------
    // Bürohaus helpers (delegates to BürohausLogic)
    // -------------------------------------------------------------------------

    /**
     * Approximates the per-activation coin-equivalent EV of a bürohaus card-swap.
     * Returns {@code max(0, bestOppCardEV − worstOwnCardEV)}.
     *
     * @param state       game state with bürohaus already in the player's owned list
     * @param playerIndex the active player
     * @return per-activation EV gain (≥ 0)
     */
    public static double bürohausSwapEV(GameState state, int playerIndex) {
        return BürohausLogic.swapEV(state, playerIndex);
    }

    /**
     * Returns a human-readable description of the best bürohaus swap, or {@code null} if
     * no beneficial swap exists (e.g. "Swap your Weizenfeld for P1's Bergwerk").
     *
     * @param state       game state with bürohaus already in the active player's owned list
     * @param playerIndex the active player
     * @return swap description, or {@code null} if no swap is beneficial
     */
    public static String bürohausSwapNote(GameState state, int playerIndex) {
        return BürohausLogic.swapNote(state, playerIndex);
    }

    /**
     * Executes the optimal bürohaus card swap in-place on {@code state}.
     * Removes the active player's lowest-EV non-landmark and gives it to the opponent
     * who holds the highest-EV non-landmark; that card moves to the active player.
     * No-ops if no beneficial swap exists.
     *
     * @param state       game state to mutate
     * @param playerIndex the active player
     */
    public static void executeBürohausSwap(GameState state, int playerIndex) {
        BürohausLogic.executeSwap(state, playerIndex);
    }

    // -------------------------------------------------------------------------
    // rankPurchasableProjects
    // -------------------------------------------------------------------------

    /**
     * Returns a ranked list of all projects in the unbuilt pool that playerIndex can currently afford,
     * sorted by discounted ROI descending.
     * <p>
     * Each {@link RankEntry} is fully populated with immediateEV, evPerRound, roiOverHorizon,
     * variance, probNoIncomeOwnTurn, probNoIncomeRound, and optionally winProbDelta.
     *
     * @param gs          current game state
     * @param playerIndex the buying player
     * @param opts        ranking options (horizon, discount factor, MC simulations, win-prob flag)
     * @return sorted list, best purchase first; empty if nothing is affordable
     */
    public static ArrayList<RankEntry> rankPurchasableProjects(GameState gs, int playerIndex,
                                                                RankingOptions opts) {
        Player player = gs.getPlayers()[playerIndex];
        int coins = player.getCoins();

        ArrayList<RankEntry> results = new ArrayList<>();

        double mcBaseline = 0.0;
        if (opts.includeWinProbDelta && opts.mcSimulations > 0) {
            mcBaseline = WinProbabilityCalc.mcWinRate(gs, playerIndex, opts.mcSimulations, opts.mcExplorationTemp);
        }

        // Unbuilt pool (regular + lila cards already pre-filtered into this list)
        ArrayList<Project> candidates = new ArrayList<>(gs.getUnbuilt_projects());
        // Add unowned GPs (landmarks) — they are never in the unbuilt pool but are always purchasable
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                candidates.add(p);
            }
        }

        for (Project candidate : candidates) {
            if (candidate.getCost() > coins) continue;
            if (candidate.isIs_grossprojekt() && player.hasProject(candidate.getId())) continue;
            if (candidate.getColor().equals("lila") && player.hasProject(candidate.getId())) continue;

            RankEntry entry = roiOverHorizon(gs, playerIndex, candidate,
                    opts.horizonTurns, opts.discountFactor);

            // Bürohaus: populate notes with actionable swap advice
            if ("bürohaus".equals(candidate.getId())) {
                GameState stateWithBuerohaus = gs.copy();
                stateWithBuerohaus.getPlayers()[playerIndex].getOwned_projects().add(candidate);
                entry.notes = bürohausSwapNote(stateWithBuerohaus, playerIndex);
            }

            // Synergy lookahead: show best partner card that increases this card's value
            String synergyNote = computeSynergyNote(gs, playerIndex, candidate, candidates,
                    gs.getPlayers().length);
            if (synergyNote != null) {
                entry.notes = entry.notes == null ? synergyNote : entry.notes + "  |  " + synergyNote;
            }

            // Two-turn lookahead: recommend best follow-up card after buying this one
            String twoTurnNote = computeTwoTurnNote(gs, playerIndex, candidate, candidates,
                    opts.horizonTurns, opts.discountFactor);
            if (twoTurnNote != null) {
                entry.notes = entry.notes == null ? twoTurnNote : entry.notes + "  |  " + twoTurnNote;
            }

            // Win-prob delta: analytical path (MC path handled below via adaptiveMCRefinement)
            if (opts.includeWinProbDelta && opts.mcSimulations == 0) {
                entry.winProbDelta = WinProbabilityCalc.estimateWinProbDelta(
                        gs, playerIndex, candidate, 0, opts.turnsElapsed);
            }

            results.add(entry);
        }

        results.sort(Comparator.comparingDouble((RankEntry e) -> e.roiOverHorizon).reversed());

        // Stufe-3: adaptive MC budget for top-k candidates (only when MC mode is active)
        if (opts.includeWinProbDelta && opts.mcSimulations > 0) {
            adaptiveMCRefinement(results, gs, playerIndex, opts, mcBaseline);
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Stufe-3: Adaptive MC budget allocation
    // -------------------------------------------------------------------------

    /**
     * Maximum number of top candidates to validate with Monte Carlo (Stufe-3).
     */
    private static final int MC_TOP_K = 5;

    /**
     * Win-probability distance threshold below which all top-k candidates are
     * considered "close" and receive equal MC budget.
     */
    private static final double MC_EQUAL_BUDGET_EPSILON = 0.02;

    /**
     * Win-probability lead threshold above which the leader is considered dominant
     * and the remaining budget is focused on chasers.
     */
    private static final double MC_DOMINANT_LEAD_THRESHOLD = 0.05;

    /**
     * Simulations to allocate per candidate in the equal-budget case.
     */
    private static final int MC_SIMS_PER_CANDIDATE_EQUAL = 2500;

    /**
     * Stufe-3: Validates the top-k candidates from the Stufe-1/2 ranking using Monte Carlo
     * simulation and replaces their {@link RankEntry#winProbDelta} with MC-derived estimates.
     *
     * <h3>Budget allocation strategy</h3>
     * <ol>
     *   <li>Compute analytical win-prob estimates for all top-k candidates.</li>
     *   <li>Compute pairwise spread (max − min win-prob across top-k).</li>
     *   <li>If spread ≤ ε = 0.02 (all close): allocate {@link #MC_SIMS_PER_CANDIDATE_EQUAL}
     *       sims to each candidate equally.</li>
     *   <li>If one candidate leads by &gt; 0.05 (dominant): skip the leader (already known best)
     *       and allocate budget to the remaining chasers (equal split among them).</li>
     *   <li>Otherwise: equal budget across all top-k.</li>
     * </ol>
     *
     * <p>Results overwrite the {@link RankEntry#winProbDelta} for evaluated candidates.
     * Candidates outside top-k retain their analytical estimate (or 0 if not computed).
     * After MC refinement, the list is re-sorted by {@link RankEntry#winProbDelta} descending
     * for the top-k positions only (overall ROI sort preserved for non-top-k entries).
     *
     * @param results     sorted ranking list (modified in-place)
     * @param gs          current game state
     * @param playerIndex the buying player
     * @param opts        ranking options
     * @param mcBaseline  pre-computed MC baseline win rate for the current state
     */
    private static void adaptiveMCRefinement(ArrayList<RankEntry> results, GameState gs,
                                              int playerIndex, RankingOptions opts,
                                              double mcBaseline) {
        int k = Math.min(MC_TOP_K, results.size());
        if (k == 0) return;

        // Step 1: compute analytical estimates for all top-k to determine budget allocation
        double[] analyticalWinProbs = new double[k];
        for (int i = 0; i < k; i++) {
            analyticalWinProbs[i] = WinProbabilityCalc.estimateWinProbDelta(
                    gs, playerIndex, results.get(i).project, 0, opts.turnsElapsed);
        }

        // Step 2: find leader index and spread
        int leaderIdx = 0;
        double maxWP = analyticalWinProbs[0];
        double minWP = analyticalWinProbs[0];
        for (int i = 1; i < k; i++) {
            if (analyticalWinProbs[i] > maxWP) { maxWP = analyticalWinProbs[i]; leaderIdx = i; }
            if (analyticalWinProbs[i] < minWP)   minWP = analyticalWinProbs[i];
        }
        double spread = maxWP - minWP;

        // Step 3: determine which candidates to validate with MC
        boolean[] validate = new boolean[k];
        int validateCount;
        if (spread <= MC_EQUAL_BUDGET_EPSILON || analyticalWinProbs[leaderIdx] - minWP <= MC_DOMINANT_LEAD_THRESHOLD) {
            // All close or leader not dominant enough: validate all top-k
            java.util.Arrays.fill(validate, true);
            validateCount = k;
        } else {
            // Leader is dominant (>0.05 lead): skip leader, focus on chasers
            java.util.Arrays.fill(validate, true);
            validate[leaderIdx] = false;
            validateCount = k - 1;
        }

        if (validateCount == 0) return;

        // Step 4: run MC for each validated candidate
        // MC sims per candidate: allocate total budget equally among validated candidates
        int simsPerCandidate = MC_SIMS_PER_CANDIDATE_EQUAL;

        for (int i = 0; i < k; i++) {
            if (!validate[i]) {
                // For the dominant leader, keep analytical estimate
                results.get(i).winProbDelta = analyticalWinProbs[leaderIdx];
                continue;
            }
            RankEntry entry = results.get(i);
            GameState stateAfter = gs.copy();
            stateAfter.getPlayers()[playerIndex].getOwned_projects().add(entry.project);
            double afterBuy = WinProbabilityCalc.mcWinRate(stateAfter, playerIndex,
                    simsPerCandidate, opts.mcExplorationTemp);
            entry.winProbDelta = afterBuy - mcBaseline;
        }
    }

    /**
     * Returns a ranked list of ALL candidate projects (affordable and not), sorted by ROI descending.
     * Each entry's {@link RankEntry#affordable} flag indicates whether the player can currently buy it.
     * Win-probability delta is never computed for unaffordable cards (would be hypothetical only).
     *
     * @param gs          current game state
     * @param playerIndex the buying player
     * @param opts        ranking options (horizon, discount factor; mcSimulations ignored for unaffordable cards)
     * @return sorted list, best ROI first; affordable cards appear before unaffordable at equal ROI
     */
    public static ArrayList<RankEntry> rankAllProjects(GameState gs, int playerIndex,
                                                        RankingOptions opts) {
        Player player = gs.getPlayers()[playerIndex];
        int coins = player.getCoins();

        // Build MC baseline once for affordable cards (if win-prob requested)
        double mcBaseline = 0.0;
        if (opts.includeWinProbDelta && opts.mcSimulations > 0) {
            mcBaseline = WinProbabilityCalc.mcWinRate(gs, playerIndex, opts.mcSimulations, opts.mcExplorationTemp);
        }

        ArrayList<Project> candidates = new ArrayList<>(gs.getUnbuilt_projects());
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                candidates.add(p);
            }
        }

        ArrayList<RankEntry> results = new ArrayList<>();

        for (Project candidate : candidates) {
            if (candidate.isIs_grossprojekt() && player.hasProject(candidate.getId())) continue;
            if (candidate.getColor().equals("lila") && player.hasProject(candidate.getId())) continue;

            boolean canAfford = candidate.getCost() <= coins;
            RankEntry entry = roiOverHorizon(gs, playerIndex, candidate,
                    opts.horizonTurns, opts.discountFactor);
            entry.affordable = canAfford;

            if ("bürohaus".equals(candidate.getId()) && canAfford) {
                GameState stateWithBuerohaus = gs.copy();
                stateWithBuerohaus.getPlayers()[playerIndex].getOwned_projects().add(candidate);
                entry.notes = bürohausSwapNote(stateWithBuerohaus, playerIndex);
            }

            // Synergy lookahead: show best partner card that increases this card's value
            String synergyNote = computeSynergyNote(gs, playerIndex, candidate, candidates,
                    gs.getPlayers().length);
            if (synergyNote != null) {
                entry.notes = entry.notes == null ? synergyNote : entry.notes + "  |  " + synergyNote;
            }

            // Two-turn lookahead: recommend best follow-up card after buying this one
            if (canAfford) {
                String twoTurnNote = computeTwoTurnNote(gs, playerIndex, candidate, candidates,
                        opts.horizonTurns, opts.discountFactor);
                if (twoTurnNote != null) {
                    entry.notes = entry.notes == null ? twoTurnNote : entry.notes + "  |  " + twoTurnNote;
                }
            }

            if (canAfford && opts.includeWinProbDelta) {
                if (opts.mcSimulations > 0) {
                    GameState stateAfter = gs.copy();
                    stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
                    double afterBuy = WinProbabilityCalc.mcWinRate(stateAfter, playerIndex, opts.mcSimulations, opts.mcExplorationTemp);
                    entry.winProbDelta = afterBuy - mcBaseline;
                } else {
                    entry.winProbDelta = WinProbabilityCalc.estimateWinProbDelta(
                            gs, playerIndex, candidate, 0, opts.turnsElapsed);
                }
            }

            results.add(entry);
        }

        results.sort(Comparator.comparingDouble((RankEntry e) -> e.roiOverHorizon).reversed());

        // Synthetic "Wait/Save" entry: insert after all real cards.
        // ROI(wait) = ROI(best unaffordable card) − 1 turn of missed income.
        // This shows whether saving for a better card beats the best current buy.
        addWaitEntryIfUseful(results, gs, playerIndex, opts);

        return results;
    }

    /**
     * If there are any unaffordable cards in {@code results}, computes a synthetic "Wait/Save"
     * entry whose ROI represents saving coins for one turn to buy the best card currently
     * out of reach. The entry is inserted in sorted order if its ROI is competitive with
     * at least one affordable card, or if no affordable cards exist.
     */
    private static void addWaitEntryIfUseful(ArrayList<RankEntry> results,
                                              GameState gs, int playerIndex,
                                              RankingOptions opts) {
        // Find the best unaffordable card by ROI
        RankEntry bestUnaffordable = null;
        for (RankEntry e : results) {
            if (!e.affordable) {
                if (bestUnaffordable == null || e.roiOverHorizon > bestUnaffordable.roiOverHorizon) {
                    bestUnaffordable = e;
                }
            }
        }
        if (bestUnaffordable == null) return; // nothing to save for

        // Current portfolio EV per round (no new purchase)
        double currentEvPerRound = portfolioEvPerRound(gs, playerIndex);
        if (currentEvPerRound <= 0) return; // can't estimate turns to save

        // Coins needed and expected turns to accumulate them
        int coinsNeeded = bestUnaffordable.project.getCost() - gs.getPlayers()[playerIndex].getCoins();
        if (coinsNeeded <= 0) return; // already affordable (shouldn't happen)

        double turnsToSave = coinsNeeded / currentEvPerRound;

        // ROI(wait) = ROI(best unaffordable) − turnsToSave × currentEvPerRound (opportunity cost)
        double waitROI = bestUnaffordable.roiOverHorizon - turnsToSave * currentEvPerRound;

        RankEntry waitEntry = new RankEntry();
        waitEntry.project = RankEntry.WAIT_SENTINEL;
        waitEntry.evPerRound = currentEvPerRound;
        waitEntry.roiOverHorizon = waitROI;
        waitEntry.affordable = false;
        waitEntry.notes = gui.newui.Strings.waitEntryNotes(
                bestUnaffordable.project.getLocalizedName(), turnsToSave);

        // Insert in sorted order
        int insertIdx = results.size();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).roiOverHorizon < waitROI) {
                insertIdx = i;
                break;
            }
        }
        results.add(insertIdx, waitEntry);
    }

    // -------------------------------------------------------------------------
    // Synergy lookahead
    // -------------------------------------------------------------------------

    /**
     * Minimum synergy gain (in coins per round) required to show a synergy note.
     * Gains below this threshold are not reported to avoid noise.
     */
    private static final double SYNERGY_THRESHOLD = 0.05;

    /**
     * Computes a human-readable synergy note for {@code card} explaining which unowned card
     * from the pool would most increase {@code card}'s per-round EV if also purchased.
     *
     * <p>The synergy gain is estimated using {@link CardIncome#contextualCardEvPerRound} with a
     * modified {@link CardIncome.PlayerStats} that includes the partner card — no
     * {@link GameState#copy()} is required.
     *
     * <p>Synergy exists between:
     * <ul>
     *   <li>Markthalle ↔ any food card (increases {@code f_c} which scales Markthalle by 2×)</li>
     *   <li>Molkerei ↔ any animal card (increases {@code a_c} which scales Molkerei by 3×)</li>
     *   <li>Möbelfabrik ↔ any production card (increases {@code p_c} which scales Möbelfabrik by 3×)</li>
     *   <li>Bäckerei / Mini-Markt ↔ Einkaufszentrum (adds +1/+1 per activation)</li>
     * </ul>
     *
     * @param gs          current game state (player does NOT yet own {@code card})
     * @param playerIndex the player evaluating card purchases
     * @param card        the card being evaluated
     * @param candidates  all candidate cards in the pool (including landmarks)
     * @param n           total player count
     * @return synergy note string, or {@code null} if no significant synergy found
     */
    static String computeSynergyNote(GameState gs, int playerIndex, Project card,
                                     ArrayList<Project> candidates, int n) {
        Player player = gs.getPlayers()[playerIndex];
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);

        // Stats as if the player already owns 'card'
        CardIncome.PlayerStats baseStats = buildStatsWithCard(player, card);

        double baseEv = CardIncome.contextualCardEvPerRound(card, baseStats, n, oppCoins);
        if (baseEv <= 0) return null; // card has no relevant synergy income

        Project bestPartner = null;
        double bestGain = SYNERGY_THRESHOLD;

        for (Project partner : candidates) {
            if (partner == card) continue;
            if (partner.isIs_grossprojekt()) continue; // landmarks don't affect category counts
            if ("einkaufszentrum".equals(partner.getId())) {
                // Einkaufszentrum is a landmark — handled above
                continue;
            }
            // Check if adding partner as owned changes the EV of card
            CardIncome.PlayerStats statsWithPartner = buildStatsWithCards(player, card, partner);
            double evWithPartner = CardIncome.contextualCardEvPerRound(card, statsWithPartner, n, oppCoins);
            double gain = evWithPartner - baseEv;
            if (gain > bestGain) {
                bestGain = gain;
                bestPartner = partner;
            }
        }

        // Also check Einkaufszentrum (green/store cards benefit from it)
        if ("grün".equals(card.getColor()) && "store".equals(card.getCategory())) {
            if (!player.hasProject("einkaufszentrum")) {
                CardIncome.PlayerStats statsWithEkz = buildStatsWithEkz(player, card);
                double evWithEkz = CardIncome.contextualCardEvPerRound(card, statsWithEkz, n, oppCoins);
                double gain = evWithEkz - baseEv;
                if (gain > bestGain) {
                    bestGain = gain;
                    // Use the Einkaufszentrum project as partner
                    bestPartner = ProjectLoader.getProject("einkaufszentrum").orElse(null);
                }
            }
        }

        if (bestPartner == null) return null;
        return gui.newui.Strings.synergyNote(bestPartner.getLocalizedName(), bestGain);
    }

    /**
     * Two-turn lookahead: given that the player buys {@code cardA} this turn, finds the
     * best follow-up card to buy on a subsequent turn (using post-A portfolio EV).
     *
     * <p>Uses the same {@link CardIncome#contextualCardEvPerRound} as the synergy note but
     * evaluates the second card B in the context of the player already owning A.
     * No {@link GameState#copy()} is performed — only {@link CardIncome.PlayerStats} are built.
     *
     * @param gs             current game state (before buying A)
     * @param playerIndex    the active player
     * @param cardA          the card being evaluated (first buy)
     * @param candidates     all candidate cards in the pool
     * @param horizonTurns   horizon for ROI computation
     * @param discountFactor discount factor γ
     * @return a note string like "Danach: Bergwerk (ROI +4.2)", or {@code null} if no useful follow-up
     */
    static String computeTwoTurnNote(GameState gs, int playerIndex, Project cardA,
                                     ArrayList<Project> candidates,
                                     int horizonTurns, double discountFactor) {
        Player player = gs.getPlayers()[playerIndex];
        int n = gs.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);

        // Stats after owning cardA (simulates having bought it)
        CardIncome.PlayerStats statsAfterA = buildStatsWithCard(player, cardA);

        // Geometric sum for ROI formula
        double geometricSum = geometricSum(horizonTurns, discountFactor);

        Project bestB = null;
        double bestRoiB = 0.5; // minimum threshold: only suggest if ROI > 0.5

        for (Project cardB : candidates) {
            if (cardB == cardA) continue;
            if (player.hasProject(cardB.getId())) continue;
            if (cardB.getColor().equals("lila") && player.hasProject(cardB.getId())) continue;

            double evB;
            if (cardB.isIs_grossprojekt()) {
                // For Bahnhof: evaluate the synergy gain it brings to cardA's income.
                // Instead of evB(cardB), compute the EV boost that cardB gives to cardA
                // when both are owned: i.e. contextualCardEvPerRound(cardA, statsWithAB) vs
                // contextualCardEvPerRound(cardA, statsAfterA). This captures "Bahnhof makes
                // Bergwerk 2× better because it activates on 9 via 2d6."
                if ("bahnhof".equals(cardB.getId()) && !player.hasProject("bahnhof")) {
                    CardIncome.PlayerStats statsAfterAB = buildStatsWithCards(player, cardA, cardB);
                    double evAWithBahnhof    = CardIncome.contextualCardEvPerRound(cardA, statsAfterAB, n, oppCoins);
                    double evAWithoutBahnhof = CardIncome.contextualCardEvPerRound(cardA, statsAfterA, n, oppCoins);
                    double synergyGain = evAWithBahnhof - evAWithoutBahnhof;
                    // The synergy gain is the annualized benefit of buying Bahnhof for card A only.
                    // ROI = synergyGain × geometricSum - cost(Bahnhof).
                    // This under-counts Bahnhof's value for other owned cards, but gives a
                    // conservative lower bound: if even just for cardA it's worthwhile, recommend it.
                    double roiSynergy = synergyGain * geometricSum - cardB.getCost();
                    if (roiSynergy > bestRoiB) {
                        bestRoiB = roiSynergy;
                        bestB = cardB;
                    }
                }
                // Other landmarks (EKZ, FP, Funkturm) are handled well by the normal ranking
                // and have no direct synergy with cardA's per-card EV — skip them.
                continue;
            }

            evB = CardIncome.contextualCardEvPerRound(cardB, statsAfterA, n, oppCoins);
            double roiB = evB * geometricSum - cardB.getCost();

            if (roiB > bestRoiB) {
                bestRoiB = roiB;
                bestB = cardB;
            }
        }

        if (bestB == null) return null;
        return gui.newui.Strings.twoTurnNote(bestB.getLocalizedName(), bestRoiB);
    }


    /** Geometric-series sum γ + γ² + … + γ^T = γ(1−γ^T)/(1−γ), with L'Hôpital guard for γ≈1. */
    static double geometricSum(int horizonTurns, double discountFactor) {
        if (Math.abs(discountFactor - 1.0) < 1e-9) return horizonTurns;
        return discountFactor * (1.0 - Math.pow(discountFactor, horizonTurns)) / (1.0 - discountFactor);
    }

    private static CardIncome.PlayerStats buildStatsWithCard(Player player, Project extra) {
        return CardIncome.PlayerStats.of(player).withExtra(extra);
    }

    /** Creates PlayerStats for {@code player} as if they also own {@code extra1} and {@code extra2}. */
    private static CardIncome.PlayerStats buildStatsWithCards(Player player, Project extra1, Project extra2) {
        return CardIncome.PlayerStats.of(player).withExtra(extra1, extra2);
    }

    /** Creates PlayerStats for {@code player} as if they also own {@code extra} and Einkaufszentrum. */
    private static CardIncome.PlayerStats buildStatsWithEkz(Player player, Project extra) {
        Project ekz = ProjectLoader.getProject("einkaufszentrum").orElseThrow();
        return CardIncome.PlayerStats.of(player).withExtra(extra, ekz);
    }

    // -------------------------------------------------------------------------

    /**
     * Gibt eine Erwartungswert-Matrix zurück, welche für jeden Spieler und jede Projekt-Farbe
     * eine Zeile mit allen Würfelzahlen enthält.
     *
     * @param playerProjects Liste der Projektlisten aller Spieler (2–4).
     * @param playerCoins    Münzanzahlen der Spieler.
     * @return (players×4) × 12 matrix of income values per roll per player-color combination.
     */
    public static int[][] values_per_r_per_p(ArrayList<Project[]> playerProjects, int[] playerCoins) {
        if (playerProjects == null || playerProjects.size() < 2 || playerProjects.size() > 4) {
            throw new IllegalArgumentException("Player count must be between 2 and 4.");
        }

        final int PROJECT_COLORS = 4;
        final int ROWS_PER_COLOR = playerProjects.size();
        final int ROLL_COUNT = 12;

        int[][] valueMatrix = new int[PROJECT_COLORS * ROWS_PER_COLOR][ROLL_COUNT];

        for (int playerIndex = 0; playerIndex < playerProjects.size(); playerIndex++) {
            Project[] projects = playerProjects.get(playerIndex);
            int ownCoins = playerCoins[playerIndex];
            int[] otherCoins = CardIncome.buildOpponentCoins(playerCoins, playerIndex);

            boolean hasEB = false;
            int fCount = 0, aCount = 0, pCount = 0;
            for (Project p : projects) {
                if ("einkaufszentrum".equals(p.getId())) hasEB = true;
                switch (p.getCategory()) {
                    case "food"       -> fCount++;
                    case "animal"     -> aCount++;
                    case "production" -> pCount++;
                }
            }

            for (Project project : projects) {
                int colorIdx = get_project_color_index(project.getColor());
                if (colorIdx < 0 || colorIdx >= PROJECT_COLORS) continue;
                int vmRow = colorIdx * ROWS_PER_COLOR + playerIndex;
                if (vmRow >= valueMatrix.length) continue;

                for (int roll = 1; roll <= ROLL_COUNT; roll++) {
                    valueMatrix[vmRow][roll - 1] += CardIncome.get_I(
                            roll, project.getId(), true, hasEB,
                            fCount, aCount, pCount, ownCoins, otherCoins);
                }
            }
        }
        return valueMatrix;
    }

    // -------------------------------------------------------------------------
    // Package-visible bridges for GameSession and GameSimulator
    // -------------------------------------------------------------------------

    /**
     * Computes the coin delta for every player on a single roll, respecting the
     * official processing order (Red → Blue/Green → Purple) and counter-clockwise
     * red-card payment priority.
     *
     * <p>This is the single authoritative method for applying a roll to all players.
     *
     * @param state         current game state (coins reflect pre-roll values)
     * @param activePlayer  index of the rolling player
     * @param roll          dice total (1–12)
     * @return delta array indexed by player; positive = gained, negative = lost
     */
    public static int[] computeAllDeltasForRoll(GameState state, int activePlayer, int roll) {
        Player[] players = state.getPlayers();
        int n = players.length;
        int[] deltas = new int[n];

        // Step 1: Red card payments (counter-clockwise, sequential).
        int rollerCoins = players[activePlayer].getCoins();
        for (int step = 1; step < n; step++) {
            int oppIdx = (activePlayer - step + n) % n;
            Player opponent = players[oppIdx];
            CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(opponent);
            for (Project p : opponent.getOwned_projects()) {
                if ("rot".equals(p.getColor())) {
                    int loss = CardIncome.get_I(roll, p.getId(), false,
                            oppStats.hasEinkaufszentrum, 0, 0, 0,
                            rollerCoins, new int[0]);
                    if (loss < 0 && -loss > rollerCoins) loss = -rollerCoins;
                    int gain = -loss;
                    deltas[activePlayer] += loss;
                    deltas[oppIdx]       += gain;
                    rollerCoins += loss;
                    if (rollerCoins < 0) rollerCoins = 0;
                }
            }
        }

        // Step 2: Blue card income for every player.
        for (int i = 0; i < n; i++) {
            Player player = players[i];
            CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(player);
            int[] otherCoins = CardIncome.buildOpponentCoins(players, i);
            deltas[i] += CardIncome.sumColorIncome(player, "blau", roll, stats, player.getCoins(), otherCoins);
        }

        // Step 3: Green and purple income for the active player.
        Player active = players[activePlayer];
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(active);
        int[] opponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
        deltas[activePlayer] += CardIncome.sumColorIncome(active, "grün", roll, activeStats,
                active.getCoins(), opponentCoins);
        for (Project p : active.getOwned_projects()) {
            if ("lila".equals(p.getColor())) {
                int[] freshOpponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
                deltas[activePlayer] += CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount, activeStats.productionCount,
                        active.getCoins() + deltas[activePlayer], freshOpponentCoins);
            }
        }

        return deltas;
    }

    /**
     * Package-visible wrapper so {@link GameSession} can compute the coin delta
     * for the active player on their own turn without going through immediateEV.
     *
     * @deprecated Prefer {@link #computeAllDeltasForRoll} which correctly handles
     *             counter-clockwise red card payment ordering across all players.
     */
    @Deprecated
    static int computeNetGainForRollPublic(GameState state, int playerIndex, int roll) {
        return computeNetGainForRoll(state, playerIndex, roll, false);
    }

    /**
     * Package-visible wrapper so {@link GameSession} can compute the coin delta
     * for a tracked player on an opponent's turn.
     *
     * @deprecated Prefer {@link #computeAllDeltasForRoll} which correctly handles
     *             counter-clockwise red card payment ordering across all players.
     */
    @Deprecated
    static int computeOpponentTurnGainForRollPublic(GameState state, int playerIndex,
                                                     int activeRollerIndex, int roll) {
        return computeOpponentTurnGainForRoll(state, playerIndex, activeRollerIndex, roll);
    }

    /**
     * Maps a color string to an index: blau=0, rot=1, grün=2, lila=3, gelb=4, unknown=-1.
     */
    private static int get_project_color_index(String color) {
        return switch (color) {
            case "blau" -> 0;
            case "rot"  -> 1;
            case "grün" -> 2;
            case "lila" -> 3;
            case "gelb" -> 4;
            default     -> -1;
        };
    }
}
