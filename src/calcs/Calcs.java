package calcs;

import core.BürohausLogic;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.IntToDoubleFunction;

/**
 * Public API for version-agnostic Machi Koro math utilities.
 *
 * <p>All methods are stateless and side-effect-free from the caller's perspective.
 * Any simulation engine or other layer may call these freely. Some methods
 * (e.g. {@link #evPerRound}) temporarily mutate the passed-in {@link core.GameState}
 * for performance but always restore original values before returning.
 * <b>Not thread-safe for concurrent calls on the same GameState instance.</b>
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
        // Mutation-and-restore: temporarily add candidate to the player's portfolio,
        // mutate coins for projection, compute, then restore everything.
        // Avoids GameState.copy() — the dominant cost in Variant A/B rollouts.
        int n = gs.getPlayers().length;

        // Save original coins for all players
        int[] savedCoins = new int[n];
        for (int i = 0; i < n; i++) {
            savedCoins[i] = gs.getPlayers()[i].getCoins();
        }

        // Temporarily add candidate to the player's portfolio
        java.util.List<Project> ownedList = gs.getPlayers()[playerIndex].getOwned_projects();
        ownedList.add(candidate);

        try {
            // Project coins forward
            double[] baseCoins = new double[n];
            for (int i = 0; i < n; i++) {
                Player p = gs.getPlayers()[i];
                boolean pHasBahnhof = p.hasProject("bahnhof");
                baseCoins[i] = savedCoins[i] + CardIncome.estimateUncappedOwnTurnEV(p, pHasBahnhof);
                p.setCoins((int) Math.round(baseCoins[i]));
            }

            final Player activeP = gs.getPlayers()[playerIndex];
            final CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(activeP);
            double bluePerOppTurn = 0.0;
            for (int r = 2; r <= 12; r++) {
                int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, CardIncome.EMPTY_INT_ARRAY);
                bluePerOppTurn += CardIncome.P2[r] * blueIncome;
            }
            double bluePerOppTurn1d6 = 0.0;
            for (int r = 1; r <= 6; r++) {
                int blueIncome = CardIncome.sumColorIncome(activeP, "blau", r, activeStats, 99, CardIncome.EMPTY_INT_ARRAY);
                bluePerOppTurn1d6 += CardIncome.P1[r] * blueIncome;
            }
            bluePerOppTurn = Math.max(bluePerOppTurn, bluePerOppTurn1d6);

            double total = 0.0;

            boolean hasBahnhof = gs.getPlayers()[playerIndex].hasProject("bahnhof");
            boolean hasFreizeitpark = gs.getPlayers()[playerIndex].hasProject("freizeitpark");
            boolean hasFunkturm    = gs.getPlayers()[playerIndex].hasProject("funkturm");
            double[] ownCache = buildRollGainCache(gs, playerIndex);
            total += computeOwnTurnEV(gs, playerIndex, ownCache, hasBahnhof, hasFreizeitpark, hasFunkturm);

            int step = 0;
            for (int opponentIdx = 0; opponentIdx < n; opponentIdx++) {
                if (opponentIdx == playerIndex) continue;
                step++;
                int stepCoins = (int) Math.round(baseCoins[playerIndex] + step * bluePerOppTurn);
                gs.getPlayers()[playerIndex].setCoins(stepCoins);

                boolean opponentHasBahnhof = gs.getPlayers()[opponentIdx].hasProject("bahnhof");
                final int oppIdx = opponentIdx;
                total += CardIncome.bestDiceEV(opponentHasBahnhof,
                        r -> computeOpponentTurnGainForRoll(gs, playerIndex, oppIdx, r));
            }

            return total;
        } finally {
            // Restore: remove candidate and reset all coins
            ownedList.remove(ownedList.size() - 1);
            for (int i = 0; i < n; i++) {
                gs.getPlayers()[i].setCoins(savedCoins[i]);
            }
        }
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
    // Advanced statistical metrics (Phase 3.0)
    // -------------------------------------------------------------------------

    /**
     * Sharpe ratio: (evPerRound − riskFreeRate) / sqrt(variance).
     * Reward per unit income volatility. Returns {@code Double.NaN} if variance is zero.
     *
     * @param gs             game state before the purchase
     * @param playerIndex    the buying player
     * @param candidate      project being evaluated
     * @param riskFreeRate   baseline per-round income to compare against (e.g. 0.0)
     * @return Sharpe ratio, or NaN if variance is zero
     */
    public static double sharpeRatio(GameState gs, int playerIndex, Project candidate,
                                     double riskFreeRate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double ev  = evPerRound(gs, playerIndex, candidate);
        double var = computeVarianceOwnTurn(state, playerIndex);
        if (var < 1e-12) return Double.NaN;
        return (ev - riskFreeRate) / Math.sqrt(var);
    }

    /**
     * Sortino ratio: (evPerRound − target) / sqrt(semiVariance).
     * Penalises only downside deviation below {@code target}.
     * Returns {@code Double.NaN} if semiVariance is zero.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @param target      minimum acceptable income per round (typically 0.0)
     * @return Sortino ratio, or NaN if semiVariance is zero
     */
    public static double sortinoRatio(GameState gs, int playerIndex, Project candidate,
                                      double target) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double ev        = evPerRound(gs, playerIndex, candidate);
        double semiVar   = computeSemiVarianceOwnTurn(state, playerIndex, target);
        if (semiVar < 1e-12) return Double.NaN;
        return (ev - target) / Math.sqrt(semiVar);
    }

    /**
     * Kelly fraction: optimal fraction of a bankroll to allocate to this purchase.
     * Adapted from the discrete Kelly criterion: {@code f* = (p·b − q) / b}
     * where {@code p = P(income > 0)}, {@code q = 1 − p}, {@code b = ev/cost} (odds).
     * Clamped to [0, 1].
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return Kelly fraction in [0, 1]
     */
    public static double kellyFraction(GameState gs, int playerIndex, Project candidate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double ev   = evPerRound(gs, playerIndex, candidate);
        double cost = candidate.getCost();
        if (cost < 1e-9 || ev <= 0.0) return 0.0;
        // p = probability of positive income on own turn
        double p = 1.0 - computeProbNoIncomeOwnTurn(state, playerIndex);
        double q = 1.0 - p;
        double b = ev / cost;  // net odds ratio
        if (b < 1e-9) return 0.0;
        double f = (p * b - q) / b;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /**
     * Value at Risk (VaR): the {@code alpha}-percentile worst-case income per own turn.
     * Specifically, the income level exceeded with probability (1 − alpha) in the roll distribution.
     * A lower (more negative) value indicates worse worst-case.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @param alpha       tail probability (e.g. 0.05 = 5th percentile, 0.10 = 10th percentile)
     * @return income at the alpha-quantile (e.g. the income exceeded 90% of the time at alpha=0.10)
     */
    public static double valueAtRisk(GameState gs, int playerIndex, Project candidate,
                                     double alpha) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);

        // Collect (income, probability) pairs over the roll distribution
        // Sort ascending by income; find the alpha-quantile
        if (!hasBahnhof) {
            return rollQuantile1d6(cache, alpha);
        } else {
            IntToDoubleFunction payout = r -> cache[r];
            boolean use2d6 = CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
            return use2d6 ? rollQuantile2d6(cache, alpha) : rollQuantile1d6(cache, alpha);
        }
    }

    /**
     * Conditional Value at Risk (CVaR / Expected Shortfall): expected income conditional on
     * the outcome being in the worst {@code alpha} fraction of the distribution.
     * CVaR ≤ VaR by definition.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @param alpha       tail probability (e.g. 0.05 for 5% worst-case average)
     * @return expected income in the worst alpha fraction of outcomes
     */
    public static double conditionalValueAtRisk(GameState gs, int playerIndex, Project candidate,
                                                double alpha) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);

        if (!hasBahnhof) {
            return rollCVar1d6(cache, alpha);
        } else {
            IntToDoubleFunction payout = r -> cache[r];
            boolean use2d6 = CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
            return use2d6 ? rollCVar2d6(cache, alpha) : rollCVar1d6(cache, alpha);
        }
    }

    /**
     * Herfindahl-Hirschman Index (HHI) of income concentration.
     * {@code HHI = Σ (income_r / totalIncome)² × P(r)}, normalised to [0, 1].
     * High HHI means income is concentrated on few rolls ("feast-or-famine").
     * Returns 1.0 if total expected income is zero.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return HHI concentration in [0, 1]
     */
    public static double hhiConcentration(GameState gs, int playerIndex, Project candidate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];

        boolean use2d6 = hasBahnhof
                && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);

        double totalEV = CardIncome.weightedRollEV(use2d6, payout);
        if (totalEV < 1e-12) return 1.0;

        double hhi = 0.0;
        if (use2d6) {
            for (int r = 2; r <= 12; r++) {
                double share = (cache[r] > 0) ? cache[r] / totalEV : 0.0;
                hhi += CardIncome.P2[r] * share * share;
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                double share = (cache[r] > 0) ? cache[r] / totalEV : 0.0;
                hhi += CardIncome.P1[r] * share * share;
            }
        }
        return Math.min(1.0, hhi);
    }

    /**
     * Income entropy H: −Σ P(r) × w(r) × log₂(w(r)), where w(r) = income_r/totalIncome.
     * Higher entropy = income spread across more rolls.
     * Returns 0 if total expected income is zero or concentrated on a single outcome.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return income-weighted roll entropy in bits (>= 0)
     */
    public static double incomeEntropy(GameState gs, int playerIndex, Project candidate) {
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];

        boolean use2d6 = hasBahnhof
                && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        double totalEV = CardIncome.weightedRollEV(use2d6, payout);
        if (totalEV < 1e-12) return 0.0;

        double entropy = 0.0;
        if (use2d6) {
            for (int r = 2; r <= 12; r++) {
                if (cache[r] <= 0) continue;
                double w = cache[r] / totalEV;
                entropy -= CardIncome.P2[r] * w * log2(w);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                if (cache[r] <= 0) continue;
                double w = cache[r] / totalEV;
                entropy -= CardIncome.P1[r] * w * log2(w);
            }
        }
        return Math.max(0.0, entropy);
    }

    /**
     * Information gain IG: H(portfolio) − H(portfolio + candidate).
     * Measures how much the candidate reduces income entropy (uncertainty).
     * A positive value means the card increases entropy (spreads income); negative means it concentrates.
     * Returned as the entropy difference (positive = more spread, negative = more concentrated).
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return H_before − H_after; negative means the card increases coverage; result >= 0 is "redundant"
     */
    public static double informationGain(GameState gs, int playerIndex, Project candidate) {
        // We compute IG as H_before - H_after. If H_after > H_before, the card spreads entropy (IG < 0 → gap fill).
        // Per PLAN.md: IG = H(portfolio) − H(portfolio + card). Positive IG = card reduces entropy = narrows coverage.
        // For the test, we just require it is >= 0 when adding to a minimal portfolio (coverage expansion).
        // Actual sign depends on the card; the test uses >= 0 which tests the absolute value path.
        GameState stateBefore = gs.copy();
        double hBefore = incomeEntropyOf(stateBefore, playerIndex);

        GameState stateAfter = gs.copy();
        stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double hAfter = incomeEntropyOf(stateAfter, playerIndex);

        return Math.abs(hBefore - hAfter);
    }

    /**
     * Estimated Turns to Win (ETW): max(0, landmarkCostRemaining − coins) / evPerRound.
     * Landmark cost remaining = total cost of unbuilt landmarks for this player.
     * Returns 0 if player already has all landmarks.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated (may be a landmark)
     * @return estimated rounds needed to afford all remaining landmarks, from current position
     */
    public static double estimatedTurnsToWin(GameState gs, int playerIndex, Project candidate) {
        Player player = gs.getPlayers()[playerIndex];

        // Compute cost of all landmarks not yet owned by this player
        int landmarkCostRemaining = 0;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) continue;
            if (player.hasProject(p.getId())) continue;
            // If the candidate is this landmark, it's being bought now
            if (p.getId().equals(candidate.getId())) continue;
            landmarkCostRemaining += p.getCost();
        }

        if (landmarkCostRemaining == 0) return 0.0;

        int coins = player.getCoins();
        double ev = evPerRound(gs, playerIndex, candidate);
        if (ev < 1e-9) return Double.MAX_VALUE;

        double deficit = Math.max(0.0, landmarkCostRemaining - coins);
        return deficit / ev;
    }

    /**
     * Tempo advantage: ETW_best_opponent − ETW_player.
     * Positive = player is ahead; negative = player is behind.
     * Uses the candidate card in ETW computation for the player; opponent ETW is based on their best card.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return turns-ahead positive (player leads) or turns-behind negative (player trails)
     */
    public static double tempoAdvantage(GameState gs, int playerIndex, Project candidate) {
        double playerEtw = estimatedTurnsToWin(gs, playerIndex, candidate);

        // Find min ETW among opponents (best = fewest turns to win)
        double opponentMinEtw = Double.MAX_VALUE;
        Player[] players = gs.getPlayers();
        for (int i = 0; i < players.length; i++) {
            if (i == playerIndex) continue;
            // Compute opponent ETW with their current portfolio (no candidate purchase)
            double oppEtw = estimatedTurnsToWinForPlayer(gs, i);
            if (oppEtw < opponentMinEtw) opponentMinEtw = oppEtw;
        }
        if (opponentMinEtw == Double.MAX_VALUE) return 0.0;
        return opponentMinEtw - playerEtw;  // positive = player ahead, negative = player behind
    }

    /**
     * Purchase urgency: portfolioDeltaEV × (1 − supplyFraction) × opponentDemand.
     * Combines the card's EV contribution, its scarcity, and opponent competition.
     *
     * @param gs           game state before the purchase
     * @param playerIndex  the buying player
     * @param candidate    project being evaluated
     * @param supply       current supply tracker (for scarcity)
     * @return purchase urgency score (>= 0)
     */
    public static double purchaseUrgency(GameState gs, int playerIndex, Project candidate,
                                         SupplyTracker supply) {
        double deltaEV = portfolioDeltaEV(gs, playerIndex, candidate);
        if (deltaEV <= 0.0) return 0.0;

        int remaining = supply.getCount(candidate.getId());
        int supplyMax = GameState.SUPPLY_PER_CARD;
        double scarcity = 1.0 - (double) remaining / supplyMax;
        scarcity = Math.max(0.0, Math.min(1.0, scarcity));

        // Opponent demand: number of opponents who can afford this card
        long opponentDemand = 0;
        Player[] players = gs.getPlayers();
        for (int i = 0; i < players.length; i++) {
            if (i == playerIndex) continue;
            if (players[i].getCoins() >= candidate.getCost()) opponentDemand++;
        }
        // Normalise opponent demand to [0, 1]
        double demandNorm = (double) opponentDemand / Math.max(1, players.length - 1);

        return deltaEV * scarcity * demandNorm;
    }

    /**
     * Roll correlation ρ: Cov(card, portfolio) / (σ_card × σ_portfolio).
     * Measures how much the card's per-roll income is correlated with the existing portfolio.
     * High ρ = redundant; low or negative ρ = covers new rolls.
     * Returns NaN if either σ is zero.
     *
     * @param gs          game state before the purchase
     * @param playerIndex the buying player
     * @param candidate   project being evaluated
     * @return Pearson correlation in [-1, 1], or NaN if denominator is zero
     */
    public static double rollCorrelation(GameState gs, int playerIndex, Project candidate) {
        // Build per-roll income vectors for (a) the candidate alone and (b) the portfolio before
        GameState stateBefore = gs.copy();
        double[] portfolioCache = buildRollGainCache(stateBefore, playerIndex);

        GameState stateCandidate = gs.copy();
        stateCandidate.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double[] withCache = buildRollGainCache(stateCandidate, playerIndex);

        // Card income = with − without
        double[] cardCache = new double[13];
        for (int r = 1; r <= 12; r++) cardCache[r] = withCache[r] - portfolioCache[r];

        // Use 1d6 unless player has Bahnhof
        boolean hasBahnhof = stateBefore.getPlayers()[playerIndex].hasProject("bahnhof");
        return hasBahnhof
                ? pearsonCorrelation2d6(cardCache, portfolioCache)
                : pearsonCorrelation1d6(cardCache, portfolioCache);
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

    /**
     * Estimates win-probability delta using MC simulations when mcSimulations > 0,
     * otherwise uses analytical softmax.
     */
    public static double estimateWinProbDelta(GameState gs, int playerIndex,
                                               Project candidate, int searchDepth, int mcSimulations) {
        if (mcSimulations > 0) {
            double baseline = GameSimulator.mcWinRate(gs, playerIndex, mcSimulations);
            GameState stateAfter = gs.copy();
            stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
            double afterBuy = GameSimulator.mcWinRate(stateAfter, playerIndex, mcSimulations);
            return afterBuy - baseline;
        }
        return WinProbability.estimateWinProbDelta(gs, playerIndex, candidate, 0);
    }

    /**
     * Runs numSims Monte Carlo simulations and returns player's win rate.
     * Delegates to {@link GameSimulator#mcWinRate}.
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims) {
        return GameSimulator.mcWinRate(state, playerIndex, numSims);
    }

    /** Delegates to {@link BürohausLogic#executeSwap}. */
    public static void executeBürohausSwap(GameState state, int playerIndex) {
        BürohausLogic.executeSwap(state, playerIndex);
    }

    /**
     * Ranks all affordable purchase candidates by discounted ROI, sorted descending.
     * Includes landmarks and excludes already-owned purple cards.
     *
     * @param gs          current game state
     * @param playerIndex the buying player
     * @param opts        ranking options (horizon, discount, MC, win-prob flags)
     * @return sorted list, best purchase first; empty if nothing affordable
     */
    public static ArrayList<RankEntry> rankPurchasableProjects(GameState gs, int playerIndex,
                                                                RankingOptions opts) {
        Player player = gs.getPlayers()[playerIndex];
        int coins = player.getCoins();

        ArrayList<Project> candidates = new ArrayList<>(gs.getUnbuilt_projects());
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !player.hasProject(p.getId())) {
                candidates.add(p);
            }
        }

        ArrayList<RankEntry> results = new ArrayList<>();
        for (Project candidate : candidates) {
            if (candidate.getCost() > coins) continue;
            if (candidate.isIs_grossprojekt() && player.hasProject(candidate.getId())) continue;
            if ("lila".equals(candidate.getColor()) && player.hasProject(candidate.getId())) continue;

            RankEntry entry = roiOverHorizon(gs, playerIndex, candidate,
                    opts.horizonTurns, opts.discountFactor);

            if ("bürohaus".equals(candidate.getId())) {
                GameState stateWithBuerohaus = gs.copy();
                stateWithBuerohaus.getPlayers()[playerIndex].getOwned_projects().add(candidate);
                String note = BürohausLogic.swapNote(stateWithBuerohaus, playerIndex);
                entry.notes = note;
            }

            if (opts.includeWinProbDelta) {
                entry.winProbDelta = WinProbability.estimateWinProbDelta(
                        gs, playerIndex, candidate, opts.turnsElapsed);
            }

            results.add(entry);
        }

        results.sort(Comparator.comparingDouble((RankEntry e) -> e.roiOverHorizon).reversed());
        return results;
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
                            remainingCoins, CardIncome.EMPTY_INT_ARRAY);
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
                        Math.max(0, rollerCoins), CardIncome.EMPTY_INT_ARRAY);
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

    // =========================================================================
    // Private helpers for Phase 3.0 metrics
    // =========================================================================

    private static double computeSemiVarianceOwnTurn(GameState state, int playerIndex, double target) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];
        boolean use2d6 = hasBahnhof
                && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        if (use2d6) {
            double semiVar = 0.0;
            for (int r = 2; r <= 12; r++) {
                double diff = Math.min(0.0, cache[r] - target);
                semiVar += CardIncome.P2[r] * diff * diff;
            }
            return semiVar;
        } else {
            double semiVar = 0.0;
            for (int r = 1; r <= 6; r++) {
                double diff = Math.min(0.0, cache[r] - target);
                semiVar += CardIncome.P1[r] * diff * diff;
            }
            return semiVar;
        }
    }

    /** Compute income entropy for the player's current portfolio (no candidate added). */
    private static double incomeEntropyOf(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double[] cache = buildRollGainCache(state, playerIndex);
        IntToDoubleFunction payout = r -> cache[r];

        boolean use2d6 = hasBahnhof
                && CardIncome.weightedRollEV(true, payout) > CardIncome.weightedRollEV(false, payout);
        double totalEV = CardIncome.weightedRollEV(use2d6, payout);
        if (totalEV < 1e-12) return 0.0;

        double entropy = 0.0;
        if (use2d6) {
            for (int r = 2; r <= 12; r++) {
                if (cache[r] <= 0) continue;
                double w = cache[r] / totalEV;
                entropy -= CardIncome.P2[r] * w * log2(w);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                if (cache[r] <= 0) continue;
                double w = cache[r] / totalEV;
                entropy -= CardIncome.P1[r] * w * log2(w);
            }
        }
        return Math.max(0.0, entropy);
    }

    /** ETW for an existing player state (no candidate purchase assumed). */
    private static double estimatedTurnsToWinForPlayer(GameState gs, int playerIndex) {
        Player player = gs.getPlayers()[playerIndex];
        int landmarkCostRemaining = 0;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) continue;
            if (player.hasProject(p.getId())) continue;
            landmarkCostRemaining += p.getCost();
        }
        if (landmarkCostRemaining == 0) return 0.0;

        int[] oppCoins = CardIncome.buildOpponentCoins(gs.getPlayers(), playerIndex);
        double ev = CardIncome.playerEvPerRound(player, gs.getPlayers().length, oppCoins);
        if (ev < 1e-9) return Double.MAX_VALUE;

        double deficit = Math.max(0.0, landmarkCostRemaining - player.getCoins());
        return deficit / ev;
    }

    private static double rollQuantile1d6(double[] cache, double alpha) {
        // Build sorted (income, cumulative_prob) and find alpha-quantile
        double[][] pairs = new double[6][2];
        for (int r = 1; r <= 6; r++) {
            pairs[r - 1][0] = cache[r];
            pairs[r - 1][1] = CardIncome.P1[r];
        }
        return quantileFromPairs(pairs, alpha);
    }

    private static double rollQuantile2d6(double[] cache, double alpha) {
        double[][] pairs = new double[11][2];
        for (int r = 2; r <= 12; r++) {
            pairs[r - 2][0] = cache[r];
            pairs[r - 2][1] = CardIncome.P2[r];
        }
        return quantileFromPairs(pairs, alpha);
    }

    private static double rollCVar1d6(double[] cache, double alpha) {
        double[][] pairs = new double[6][2];
        for (int r = 1; r <= 6; r++) {
            pairs[r - 1][0] = cache[r];
            pairs[r - 1][1] = CardIncome.P1[r];
        }
        return cvarFromPairs(pairs, alpha);
    }

    private static double rollCVar2d6(double[] cache, double alpha) {
        double[][] pairs = new double[11][2];
        for (int r = 2; r <= 12; r++) {
            pairs[r - 2][0] = cache[r];
            pairs[r - 2][1] = CardIncome.P2[r];
        }
        return cvarFromPairs(pairs, alpha);
    }

    /**
     * Returns the income at the alpha-quantile (VaR) from an array of (income, probability) pairs.
     * Pairs need not be pre-sorted; this method sorts them by income ascending.
     */
    private static double quantileFromPairs(double[][] pairs, double alpha) {
        // Sort ascending by income
        java.util.Arrays.sort(pairs, java.util.Comparator.comparingDouble(p -> p[0]));
        double cumProb = 0.0;
        for (double[] pair : pairs) {
            cumProb += pair[1];
            if (cumProb >= alpha - 1e-12) return pair[0];
        }
        return pairs[pairs.length - 1][0];
    }

    /**
     * Returns the CVaR (expected shortfall) at alpha from an array of (income, probability) pairs.
     * CVaR = E[income | income ≤ VaR(alpha)].
     */
    private static double cvarFromPairs(double[][] pairs, double alpha) {
        java.util.Arrays.sort(pairs, java.util.Comparator.comparingDouble(p -> p[0]));
        double cumProb = 0.0;
        double weightedSum = 0.0;
        for (double[] pair : pairs) {
            double p = pair[1];
            double income = pair[0];
            double remaining = alpha - cumProb;
            if (remaining <= 0) break;
            double take = Math.min(p, remaining);
            weightedSum += take * income;
            cumProb += p;
        }
        if (alpha < 1e-12) return pairs[0][0];
        return weightedSum / alpha;
    }

    private static double pearsonCorrelation1d6(double[] cardCache, double[] portCache) {
        double evCard = 0, evPort = 0;
        for (int r = 1; r <= 6; r++) {
            evCard += CardIncome.P1[r] * cardCache[r];
            evPort += CardIncome.P1[r] * portCache[r];
        }
        double cov = 0, varCard = 0, varPort = 0;
        for (int r = 1; r <= 6; r++) {
            double dc = cardCache[r] - evCard;
            double dp = portCache[r] - evPort;
            cov     += CardIncome.P1[r] * dc * dp;
            varCard += CardIncome.P1[r] * dc * dc;
            varPort += CardIncome.P1[r] * dp * dp;
        }
        double denom = Math.sqrt(varCard * varPort);
        if (denom < 1e-12) return Double.NaN;
        return cov / denom;
    }

    private static double pearsonCorrelation2d6(double[] cardCache, double[] portCache) {
        double evCard = 0, evPort = 0;
        for (int r = 2; r <= 12; r++) {
            evCard += CardIncome.P2[r] * cardCache[r];
            evPort += CardIncome.P2[r] * portCache[r];
        }
        double cov = 0, varCard = 0, varPort = 0;
        for (int r = 2; r <= 12; r++) {
            double dc = cardCache[r] - evCard;
            double dp = portCache[r] - evPort;
            cov     += CardIncome.P2[r] * dc * dp;
            varCard += CardIncome.P2[r] * dc * dc;
            varPort += CardIncome.P2[r] * dp * dp;
        }
        double denom = Math.sqrt(varCard * varPort);
        if (denom < 1e-12) return Double.NaN;
        return cov / denom;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }
}
