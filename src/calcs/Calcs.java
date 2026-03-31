package calcs;

import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import java.util.function.IntToDoubleFunction;

/**
 * Public API for version-agnostic Machi Koro math utilities.
 *
 * <p>All methods are stateless and side-effect-free. Any simulation engine or other
 * layer may call these freely. The {@link core.GameState} passed in is never mutated
 * except where a copy is taken internally.
 *
 * <p>EV figures are in coins. Positive = income; negative = payment.
 */
public final class Calcs {

    private Calcs() {}

    // -------------------------------------------------------------------------
    // Probability accessors
    // -------------------------------------------------------------------------

    /** Returns P(roll = r | 1d6) — 1/6 for r in 1..6, 0 otherwise. */
    public static double get_P1(int r) {
        return (r >= 0 && r < CardIncome.P1.length) ? CardIncome.P1[r] : 0.0;
    }

    /** Returns P(roll = r | 2d6) — bell-curve for r in 2..12, 0 otherwise. */
    public static double get_P2(int r) {
        return (r >= 0 && r < CardIncome.P2.length) ? CardIncome.P2[r] : 0.0;
    }

    // -------------------------------------------------------------------------
    // get_I — per-card income/cost for a single roll
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
    // computeAllDeltasForRoll — authoritative roll resolution
    // -------------------------------------------------------------------------

    /**
     * Computes the coin delta for every player on a single roll.
     * Delegates to {@link core.RollResolver#computeAllDeltasForRoll}.
     *
     * @param state         current game state (coins reflect pre-roll values)
     * @param activePlayer  index of the rolling player
     * @param roll          dice total (1–12)
     * @return delta array indexed by player; positive = gained, negative = lost
     */
    public static int[] computeAllDeltasForRoll(GameState state, int activePlayer, int roll) {
        return RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
    }

    // -------------------------------------------------------------------------
    // Geometric sum helper
    // -------------------------------------------------------------------------

    /**
     * Geometric-series sum γ + γ² + … + γ^T = γ(1−γ^T)/(1−γ), with L'Hôpital guard for γ≈1.
     *
     * @param horizonTurns  T (number of turns to look ahead)
     * @param discountFactor γ (per-turn discount, 0 < γ ≤ 1)
     * @return discounted sum
     */
    public static double geometricSum(int horizonTurns, double discountFactor) {
        if (Math.abs(discountFactor - 1.0) < 1e-9) return horizonTurns;
        return discountFactor * (1.0 - Math.pow(discountFactor, horizonTurns)) / (1.0 - discountFactor);
    }

    // -------------------------------------------------------------------------
    // immediateEV
    // -------------------------------------------------------------------------

    /**
     * Returns the best expected coin gain for playerIndex on their current turn,
     * assuming they just bought {@code candidate}.
     *
     * <p>Accounts for: 1d6 vs 2d6 choice (Bahnhof), Einkaufszentrum bonuses (via get_I),
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

        // Bürohaus card-swap EV
        if (player.hasProject("bürohaus")) {
            double swapEV = core.BürohausLogic.swapEV(state, playerIndex);
            double p6 = hasBahnhof ? CardIncome.P2[6] : CardIncome.P1[6];
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
     * (own turn + N−1 opponent turns), assuming {@code candidate} is already owned.
     *
     * <p>Blue cards contribute on every turn. Green and purple contribute only on own turn.
     * Red cards contribute (as income) on every opponent's turn.
     *
     * <p>Coin counts are projected forward by each player's expected per-turn income
     * to avoid static-snapshot bias.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the player to evaluate
     * @param candidate   project being purchased (simulated as owned)
     * @return expected coins gained over one full round
     */
    public static double evPerRound(GameState gs, int playerIndex, Project candidate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);

        int n = state.getPlayers().length;
        double[] baseCoins = new double[n];
        for (int i = 0; i < n; i++) {
            Player p = state.getPlayers()[i];
            boolean pHasBahnhof = p.hasProject("bahnhof");
            baseCoins[i] = p.getCoins() + CardIncome.estimateUncappedOwnTurnEV(p, pHasBahnhof);
            p.setCoins((int) Math.round(baseCoins[i]));
        }

        final Player activeP = state.getPlayers()[playerIndex];
        final CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(activeP);
        double bluePerOppTurn = 0.0;
        for (int r = 2; r <= 12; r++) {
            int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, new int[0]);
            bluePerOppTurn += CardIncome.P2[r] * blueIncome;
        }
        double bluePerOppTurn1d6 = 0.0;
        for (int r = 1; r <= 6; r++) {
            int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, new int[0]);
            bluePerOppTurn1d6 += CardIncome.P1[r] * blueIncome;
        }
        bluePerOppTurn = Math.max(bluePerOppTurn, bluePerOppTurn1d6);

        double total = 0.0;

        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        boolean hasFreizeitpark = state.getPlayers()[playerIndex].hasProject("freizeitpark");
        boolean hasFunkturm    = state.getPlayers()[playerIndex].hasProject("funkturm");
        double[] ownCache = buildRollGainCache(state, playerIndex);
        total += computeOwnTurnEV(state, playerIndex, ownCache, hasBahnhof, hasFreizeitpark, hasFunkturm);

        int step = 0;
        for (int opponentIdx = 0; opponentIdx < n; opponentIdx++) {
            if (opponentIdx == playerIndex) continue;
            step++;
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
    // roiOverHorizon — discounted ROI and risk metrics
    // -------------------------------------------------------------------------

    /**
     * Computes discounted ROI for buying {@code candidate} over {@code horizonTurns} rounds,
     * along with variance and other risk metrics.
     *
     * <p>Formula: {@code ROI = evPerRound × γ × (1 − γ^T) / (1 − γ) − cost}
     *
     * @param gs             game state before the purchase
     * @param playerIndex    the buying player
     * @param candidate      project being evaluated
     * @param horizonTurns   number of rounds to look ahead (T)
     * @param discountFactor per-round discount factor γ (0 < γ ≤ 1)
     * @return populated {@link RankEntry}
     */
    public static RankEntry roiOverHorizon(GameState gs, int playerIndex, Project candidate,
                                            int horizonTurns, double discountFactor) {
        RankEntry entry = new RankEntry();
        entry.project = candidate;

        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);

        entry.immediateEV           = immediateEV(gs, playerIndex, candidate, false);
        entry.immediateEV_afterCost = entry.immediateEV - candidate.getCost();
        entry.evPerRound            = evPerRound(gs, playerIndex, candidate);
        entry.portfolioDeltaEV      = portfolioDeltaEV(gs, playerIndex, candidate);

        double geoSum = geometricSum(horizonTurns, discountFactor);
        entry.roiOverHorizon = entry.evPerRound * geoSum - candidate.getCost();

        entry.variance             = computeVarianceOwnTurn(state, playerIndex);
        entry.probNoIncomeOwnTurn  = computeProbNoIncomeOwnTurn(state, playerIndex);
        entry.probNoIncomeRound    = computeProbNoIncomeRound(state, playerIndex);

        return entry;
    }

    // -------------------------------------------------------------------------
    // Portfolio EV helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the synergy-aware expected-coin-per-round for {@code playerIndex}'s current portfolio.
     */
    public static double portfolioEvPerRound(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);
        return CardIncome.playerEvPerRound(player, gs.getPlayers().length, oppCoins);
    }

    /**
     * Returns the marginal per-round EV gain from adding {@code candidate} to the player's portfolio.
     */
    public static double portfolioDeltaEV(GameState gs, int playerIndex, Project candidate) {
        Player player = gs.getPlayers()[playerIndex];
        int n = gs.getPlayers().length;
        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);

        double before = CardIncome.playerEvPerRound(player, n, oppCoins);
        player.getOwned_projects().add(candidate);
        double after = CardIncome.playerEvPerRound(player, n, oppCoins);
        player.getOwned_projects().remove(player.getOwned_projects().size() - 1);
        return after - before;
    }

    // -------------------------------------------------------------------------
    // optimalDiceCount
    // -------------------------------------------------------------------------

    /**
     * Returns the optimal dice count (1 or 2) for the active player on their own turn.
     * If the player does not own Bahnhof, always returns 1.
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

    // -------------------------------------------------------------------------
    // Win probability (analytical softmax)
    // -------------------------------------------------------------------------

    /**
     * Returns the baseline win probability for {@code playerIndex} using the analytical
     * softmax score approximation (no simulation).
     */
    public static double computeBaselineWinProb(GameState gs, int playerIndex) {
        return WinProbability.computeBaselineWinProb(gs, playerIndex);
    }

    /**
     * Estimates the change in win probability for playerIndex from buying {@code candidate},
     * using the analytical softmax only (no Monte Carlo).
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex, Project candidate) {
        return WinProbability.estimateWinProbDelta(gs, playerIndex, candidate, 0);
    }

    // -------------------------------------------------------------------------
    // values_per_r_per_p — income matrix
    // -------------------------------------------------------------------------

    /**
     * Returns a matrix of per-roll income values for each player/color combination.
     * Used for the income matrix display.
     *
     * @param playerProjects list of project arrays per player (2–4 players)
     * @param playerCoins    current coin counts indexed by player
     * @return (players×4) × 12 matrix of income values per roll per player-color combination
     */
    public static int[][] values_per_r_per_p(java.util.ArrayList<Project[]> playerProjects,
                                              int[] playerCoins) {
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
                int colorIdx = getProjectColorIndex(project.getColor());
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

    private static int getProjectColorIndex(String color) {
        return switch (color) {
            case "blau" -> 0;
            case "rot"  -> 1;
            case "grün" -> 2;
            case "lila" -> 3;
            case "gelb" -> 4;
            default     -> -1;
        };
    }

    // =========================================================================
    // Private helpers (own-turn EV, variance, probNoIncome)
    // =========================================================================

    private static double[] buildRollGainCache(GameState state, int playerIndex) {
        double[] cache = new double[13];
        for (int r = 1; r <= 12; r++) {
            cache[r] = computeNetGainForRoll(state, playerIndex, r);
        }
        return cache;
    }

    private static int computeNetGainForRoll(GameState state, int playerIndex, int roll) {
        Player activePlayer = state.getPlayers()[playerIndex];
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(activePlayer);
        int activeCoins = activePlayer.getCoins();

        Player[] players = state.getPlayers();
        int n = players.length;
        int net = 0;

        // Red cards (counter-clockwise, before any income)
        int remainingCoins = activeCoins;
        for (int step = 1; step < n; step++) {
            int opponentIdx = (playerIndex - step + n) % n;
            Player opponent = players[opponentIdx];
            CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(opponent);
            for (Project p : opponent.getOwned_projects()) {
                if ("rot".equals(p.getColor())) {
                    int loss = CardIncome.get_I(roll, p.getId(), false,
                            oppStats.hasEinkaufszentrum, 0, 0, 0,
                            remainingCoins, new int[0]);
                    if (loss < 0 && -loss > remainingCoins) loss = -remainingCoins;
                    net += loss;
                    remainingCoins += loss;
                    if (remainingCoins < 0) remainingCoins = 0;
                }
            }
        }

        // Blue cards
        int[] opponentCoins = CardIncome.buildOpponentCoins(players, playerIndex);
        net += CardIncome.sumColorIncome(activePlayer, "blau", roll, activeStats, activeCoins, opponentCoins);

        // Green cards
        net += CardIncome.sumColorIncome(activePlayer, "grün", roll, activeStats, activeCoins, opponentCoins);

        // Purple cards
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

    private static int computeOpponentTurnGainForRoll(GameState state, int playerIndex,
                                                       int activeRollerIndex, int roll) {
        Player trackedPlayer = state.getPlayers()[playerIndex];
        Player activeRoller  = state.getPlayers()[activeRollerIndex];
        CardIncome.PlayerStats trackedStats = CardIncome.PlayerStats.of(trackedPlayer);
        int trackedCoins = trackedPlayer.getCoins();
        int rollerCoins  = activeRoller.getCoins();

        int net = 0;

        net += CardIncome.sumColorIncome(trackedPlayer, "blau", roll, trackedStats,
                trackedCoins, new int[]{rollerCoins});

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

    private static double funkturmEV(boolean use2d6, IntToDoubleFunction payoutFn) {
        double baseline = CardIncome.weightedRollEV(use2d6, payoutFn);
        double gain = 0.0;
        if (use2d6) {
            for (int r = 2; r <= 12; r++) {
                double g = payoutFn.applyAsDouble(r);
                if (g < baseline) gain += CardIncome.P2[r] * (baseline - g);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                double g = payoutFn.applyAsDouble(r);
                if (g < baseline) gain += CardIncome.P1[r] * (baseline - g);
            }
        }
        return baseline + gain;
    }

    private static double bestSecondRollEV(GameState state, int playerIndex, int forcedDiceCount) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];

        if (forcedDiceCount == 1) return CardIncome.weightedRollEV(false, payout);
        if (forcedDiceCount == 2) return CardIncome.weightedRollEV(true, payout);
        return hasBahnhof ? CardIncome.bestDiceEV(true, payout) : CardIncome.weightedRollEV(false, payout);
    }

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

        double ev1 = hasFunkturm
                ? funkturmEV(false, payout)
                : CardIncome.weightedRollEV(false, payout);

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

    private static double computeVarianceOwnTurn(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        if (!hasBahnhof) return computeVariance1d6(buildRollGainCache(state, playerIndex));
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];
        boolean use2d6 = CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        return use2d6 ? computeVariance2d6(cache) : computeVariance1d6(cache);
    }

    private static double computeVariance1d6(double[] cache) {
        double ev = 0.0, e2 = 0.0;
        for (int d = 1; d <= 6; d++) {
            double gain = cache[d];
            ev += CardIncome.P1[d] * gain;
            e2 += CardIncome.P1[d] * gain * gain;
        }
        return e2 - ev * ev;
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
}
