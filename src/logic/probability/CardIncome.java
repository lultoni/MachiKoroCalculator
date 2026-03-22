package logic.probability;

import java.util.function.IntToDoubleFunction;

/**
 * Pure-static primitives for per-card coin income and dice probability.
 *
 * <p>Contains the lowest-level building blocks used by the EV calculation layer:
 * probability tables, the {@link #get_I} income dispatch function, player stats,
 * and the dice-weighted EV helpers. No game state mutation; no I/O.
 *
 * <p>All methods are package-visible only — callers outside this package should go
 * through {@link ProbabilityCalc}.
 */
class CardIncome {

    // -------------------------------------------------------------------------
    // Pre-computed probability tables (O(1) lookup)
    // -------------------------------------------------------------------------

    /** P1[r] = probability of rolling r with 1d6. Valid indices: 1–6. */
    static final double[] P1 = new double[13];

    /** P2[r] = probability of rolling r with 2d6. Valid indices: 2–12. */
    static final double[] P2 = new double[13];

    static {
        for (int r = 1; r <= 6; r++)  P1[r] = 1.0 / 6.0;
        for (int r = 2; r <= 12; r++) P2[r] = (6.0 - Math.abs(r - 7)) / 36.0;
    }

    private CardIncome() {}

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
    static int get_I(int r, String p_id, boolean oop, boolean eb,
                     int f_c, int a_c, int p_c, int c, int[] co) {
        switch (p_id) {

            // --- Blue (blau): trigger every player's turn, pay from bank ---

            case "weizenfeld" -> {
                if (r != 1) return 0;
                return 1;
            }
            case "apfelplantage" -> {
                if (r != 10) return 0;
                return 3;
            }
            case "bauernhof" -> {
                if (r != 2) return 0;
                return 1;
            }
            case "wald" -> {
                if (r != 5) return 0;
                return 1;
            }
            case "bergwerk" -> {
                if (r != 9) return 0;
                return 5;
            }

            // --- Green (grün): own turn only, pay from bank ---

            case "bäckerei" -> {
                if (r != 2 && r != 3) return 0;
                if (!oop) return 0;
                return eb ? 2 : 1;
            }
            case "mini-markt" -> {
                if (r != 4) return 0;
                if (!oop) return 0;
                return eb ? 4 : 3;
            }
            case "markthalle" -> {
                if (r != 11 && r != 12) return 0;
                if (!oop) return 0;
                return f_c * 2;
            }
            case "molkerei" -> {
                if (r != 7) return 0;
                if (!oop) return 0;
                return a_c * 3;
            }
            case "möbelfabrik" -> {
                if (r != 8) return 0;
                if (!oop) return 0;
                return p_c * 3;
            }

            // --- Red (rot): active player (roller) pays the card owner.
            //     Perspective: queried player is the roller (oop=false → pays).
            //     Return is negative (roller loses coins), clamped to available coins. ---

            case "café" -> {
                if (r != 3) return 0;
                if (oop) return 0;          // owner does not pay themselves
                int cost = eb ? -2 : -1;
                if (Math.abs(cost) > c) return -c;
                return cost;
            }
            case "familienrestaurant" -> {
                if (r != 9 && r != 10) return 0;
                if (oop) return 0;
                int cost = eb ? -3 : -2;
                if (Math.abs(cost) > c) return -c;
                return cost;
            }

            // --- Purple (lila): own turn only, effects vary ---

            case "stadion" -> {
                // Takes 2 coins from EACH opponent (capped per opponent at their coins). No total cap.
                if (r != 6) return 0;
                if (!oop) return 0;
                int total = 0;
                for (int opponentCoins : co) total += Math.min(2, opponentCoins);
                return total;
            }
            case "fernsehsender" -> {
                // Takes 5 coins from the RICHEST opponent (optimal play assumption for EV).
                if (r != 6) return 0;
                if (!oop) return 0;
                int richest = 0;
                for (int opponentCoins : co) richest = Math.max(richest, opponentCoins);
                return Math.min(5, richest);
            }
            case "bürohaus" -> {
                // Card-swap effect modelled separately in immediateEV via bürohausSwapEV().
                // Returns 0 here because get_I only handles coin deltas per roll.
                return 0;
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // PlayerStats — cached per-player counts used by get_I
    // -------------------------------------------------------------------------

    /**
     * Pre-computed per-player stats for all {@link #get_I} callers.
     * Compute once per player per calculation, not once per card per roll.
     */
    static class PlayerStats {
        boolean hasEinkaufszentrum = false;
        boolean hasBahnhof        = false;
        boolean hasFreizeitpark   = false;
        boolean hasFunkturm       = false;
        int foodCount       = 0;
        int animalCount     = 0;
        int productionCount = 0;

        static PlayerStats of(Player player) {
            PlayerStats s = new PlayerStats();
            for (Project p : player.getOwned_projects()) {
                switch (p.getId()) {
                    case "einkaufszentrum" -> s.hasEinkaufszentrum = true;
                    case "bahnhof"         -> s.hasBahnhof         = true;
                    case "freizeitpark"    -> s.hasFreizeitpark    = true;
                    case "funkturm"        -> s.hasFunkturm        = true;
                }
                switch (p.getCategory()) {
                    case "food"       -> s.foodCount++;
                    case "animal"     -> s.animalCount++;
                    case "production" -> s.productionCount++;
                }
            }
            return s;
        }
    }

    // -------------------------------------------------------------------------
    // Opponent coins helpers
    // -------------------------------------------------------------------------

    /** Builds an array of coins for all players except playerIndex. */
    static int[] buildOpponentCoins(Player[] players, int excludeIndex) {
        int[] coins = new int[players.length - 1];
        int idx = 0;
        for (int i = 0; i < players.length; i++) {
            if (i != excludeIndex) coins[idx++] = players[i].getCoins();
        }
        return coins;
    }

    /** Builds a sub-array excluding the element at excludeIndex (overload for raw coin arrays). */
    static int[] buildOpponentCoins(int[] coins, int excludeIndex) {
        int[] result = new int[coins.length - 1];
        int idx = 0;
        for (int i = 0; i < coins.length; i++) {
            if (i != excludeIndex) result[idx++] = coins[i];
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // sumColorIncome — color-filtered income sum
    // -------------------------------------------------------------------------

    /**
     * Sums {@link #get_I} income for all cards owned by {@code player} that match {@code color}
     * on the given roll. Used to avoid repeating the filter-and-sum loop for each color.
     *
     * @param player   the player whose owned cards to inspect
     * @param color    card color string to match ("blau", "grün", "lila", etc.)
     * @param roll     the dice result
     * @param stats    pre-computed stats for the player
     * @param coins    current coin count used by {@code get_I} for clamping / synergy
     * @param oppCoins coins of other players (passed through to {@code get_I})
     * @return sum of income values for matching cards
     */
    static int sumColorIncome(Player player, String color, int roll,
                               PlayerStats stats, int coins, int[] oppCoins) {
        int net = 0;
        for (Project p : player.getOwned_projects()) {
            if (color.equals(p.getColor())) {
                net += get_I(roll, p.getId(), true,
                        stats.hasEinkaufszentrum,
                        stats.foodCount, stats.animalCount, stats.productionCount,
                        coins, oppCoins);
            }
        }
        return net;
    }

    // -------------------------------------------------------------------------
    // Dice-roll EV helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the weighted sum {@code Σ P(r) × payoutFn(r)} over all possible rolls.
     *
     * @param use2d6    if true, uses 2d6 probabilities (P2, rolls 2–12);
     *                  if false, uses 1d6 probabilities (P1, rolls 1–6)
     * @param payoutFn  maps a roll result to a payout value (may return 0)
     * @return expected payout over the roll distribution
     */
    static double weightedRollEV(boolean use2d6, IntToDoubleFunction payoutFn) {
        double ev = 0.0;
        if (use2d6) {
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    ev += (1.0 / 36.0) * payoutFn.applyAsDouble(d1 + d2);
                }
            }
        } else {
            for (int d = 1; d <= 6; d++) {
                ev += P1[d] * payoutFn.applyAsDouble(d);
            }
        }
        return ev;
    }

    /**
     * Returns the expected payout for the player's best dice choice on their own turn.
     * If the player has Bahnhof, computes both 1d6 and 2d6 EVs and returns the max.
     * Otherwise returns the 1d6 EV.
     *
     * @param hasBahnhof true if the player owns Bahnhof
     * @param payoutFn   maps a roll result to a payout value
     * @return EV under the optimal dice choice
     */
    static double bestDiceEV(boolean hasBahnhof, IntToDoubleFunction payoutFn) {
        double ev1 = weightedRollEV(false, payoutFn);
        if (!hasBahnhof) return ev1;
        return Math.max(ev1, weightedRollEV(true, payoutFn));
    }

    // -------------------------------------------------------------------------
    // estimateUncappedOwnTurnEV — projected income for EV horizon calculations
    // -------------------------------------------------------------------------

    /**
     * Estimates the expected blue+green coin income for {@code player} on their own turn,
     * without any coin-clamp (uses {@code c=99}). This represents the income the player
     * can expect to accumulate regardless of their current wallet, suitable as a
     * conservative floor when projecting future coin counts for EV calculations.
     *
     * <p>Red cards are excluded because their payment depends on the coin count being
     * estimated — including them would create a circular dependency.
     *
     * @param player     the player whose owned cards to evaluate
     * @param hasBahnhof true if the player owns Bahnhof (may use 2d6 on own turn)
     * @return expected blue+green income per own turn, unclamped (≥ 0)
     */
    static double estimateUncappedOwnTurnEV(Player player, boolean hasBahnhof) {
        PlayerStats stats = PlayerStats.of(player);
        IntToDoubleFunction payout = r -> {
            int blue  = sumColorIncome(player, "blau", r, stats, 99, new int[0]);
            int green = sumColorIncome(player, "grün", r, stats, 99, new int[0]);
            return blue + green;
        };
        return bestDiceEV(hasBahnhof, payout);
    }

    // -------------------------------------------------------------------------
    // singleCardEvPerRound — isolated card EV for scoring
    // -------------------------------------------------------------------------

    /**
     * Approximates the per-round EV of a single card in isolation (no synergy),
     * scaled by the number of players for blue cards.
     */
    static double singleCardEvPerRound(Project card, int numPlayers) {
        double ev = 0.0;
        // Use 2d6 probabilities as the general case (most mid/late game play is 2d6)
        for (int r = 2; r <= 12; r++) {
            int income = get_I(r, card.getId(), true, false, 1, 1, 1, 99, new int[]{5, 5, 5});
            if (income > 0) ev += P2[r] * income;
        }
        // Also cover 1-only rolls (weizenfeld, etc.) using 1d6
        for (int r = 1; r <= 6; r++) {
            int income = get_I(r, card.getId(), true, false, 1, 1, 1, 99, new int[]{5, 5, 5});
            if (income > 0) ev = Math.max(ev, P1[r] * income * ("blau".equals(card.getColor()) ? numPlayers : 1));
        }
        // Correct blue card scaling: multiply by numPlayers
        if ("blau".equals(card.getColor())) {
            ev *= numPlayers;
        }
        return ev;
    }
}
