package core;

import java.util.function.IntToDoubleFunction;

/**
 * Pure-static primitives for per-card coin income and dice probability.
 *
 * <p>Contains the lowest-level building blocks used by the EV calculation layer:
 * probability tables, the {@link #get_I} income dispatch function, player stats,
 * and the dice-weighted EV helpers. No game state mutation; no I/O.
 */
public class CardIncome {

    // -------------------------------------------------------------------------
    // Pre-computed probability tables (O(1) lookup)
    // -------------------------------------------------------------------------

    /** Shared empty int array — avoids repeated {@code new int[0]} allocation in hot paths. */
    public static final int[] EMPTY_INT_ARRAY = new int[0];

    /** P1[r] = probability of rolling r with 1d6. Valid indices: 1–6. */
    public static final double[] P1 = new double[13];

    /** P2[r] = probability of rolling r with 2d6. Valid indices: 2–12. */
    public static final double[] P2 = new double[13];

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
    public static int get_I(int r, String p_id, boolean oop, boolean eb,
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
                // Card-swap effect modelled separately via BürohausLogic.
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
    public static class PlayerStats {
        public boolean hasEinkaufszentrum = false;
        public boolean hasBahnhof        = false;
        public boolean hasFreizeitpark   = false;
        public boolean hasFunkturm       = false;
        public int foodCount       = 0;
        public int animalCount     = 0;
        public int productionCount = 0;

        public static PlayerStats of(Player player) {
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

        /** Returns a copy of this stats with additional projects applied. */
        public PlayerStats withExtra(Project... extras) {
            PlayerStats s = new PlayerStats();
            s.hasEinkaufszentrum = this.hasEinkaufszentrum;
            s.hasBahnhof         = this.hasBahnhof;
            s.hasFreizeitpark    = this.hasFreizeitpark;
            s.hasFunkturm        = this.hasFunkturm;
            s.foodCount          = this.foodCount;
            s.animalCount        = this.animalCount;
            s.productionCount    = this.productionCount;
            for (Project p : extras) {
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
    public static int[] buildOpponentCoins(Player[] players, int excludeIndex) {
        int[] coins = new int[players.length - 1];
        int idx = 0;
        for (int i = 0; i < players.length; i++) {
            if (i != excludeIndex) coins[idx++] = players[i].getCoins();
        }
        return coins;
    }

    /** Builds a sub-array excluding the element at excludeIndex (overload for raw coin arrays). */
    public static int[] buildOpponentCoins(int[] coins, int excludeIndex) {
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
     * on the given roll.
     */
    public static int sumColorIncome(Player player, String color, int roll,
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
    public static double weightedRollEV(boolean use2d6, IntToDoubleFunction payoutFn) {
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
     */
    public static double bestDiceEV(boolean hasBahnhof, IntToDoubleFunction payoutFn) {
        double ev1 = weightedRollEV(false, payoutFn);
        if (!hasBahnhof) return ev1;
        return Math.max(ev1, weightedRollEV(true, payoutFn));
    }

    // -------------------------------------------------------------------------
    // estimateUncappedOwnTurnEV — projected income for EV horizon calculations
    // -------------------------------------------------------------------------

    /**
     * Estimates the expected blue+green coin income for {@code player} on their own turn,
     * without any coin-clamp (uses {@code c=99}). Suitable as a conservative floor when
     * projecting future coin counts for EV calculations.
     *
     * <p><b>Why c=99 is correct (not an approximation):</b> Blue and green cards pay from
     * the bank, so the player's own coin count is irrelevant for their income. The {@code c}
     * parameter in {@link #get_I} only matters for red cards (clamping the roller's payment
     * to their available coins), and red cards are intentionally excluded here because their
     * payment depends on the coin count being estimated — including them would create a
     * circular dependency.
     */
    public static double estimateUncappedOwnTurnEV(Player player, boolean hasBahnhof) {
        PlayerStats stats = PlayerStats.of(player);
        IntToDoubleFunction payout = r -> {
            int blue  = sumColorIncome(player, "blau", r, stats, 99, EMPTY_INT_ARRAY);
            int green = sumColorIncome(player, "grün", r, stats, 99, EMPTY_INT_ARRAY);
            return blue + green;
        };
        return bestDiceEV(hasBahnhof, payout);
    }

    // -------------------------------------------------------------------------
    // playerEvPerRound — synergy-aware per-round EV for scoring
    // -------------------------------------------------------------------------

    /**
     * Estimates the expected coin income per full round for {@code player}, accounting for
     * their actual card synergies (Einkaufszentrum bonuses, category multipliers, opponent
     * coin counts for purple cards).
     *
     * <p><b>Dice strategy (whole-portfolio, not per-card):</b> The player makes ONE dice
     * choice per turn that applies to ALL cards simultaneously. For own-turn income (green,
     * purple, and blue-on-own-turn), we compute total portfolio income under 1d6 vs 2d6 and
     * take the max. This is critical — the old per-card max approach overestimated EV by
     * 40-80% for mixed portfolios (low-range + high-range cards with Bahnhof).
     *
     * <p>For opponent-turn income (blue + red), we use 1d6 as the opponent's assumed dice
     * choice. This is a reasonable fast-path assumption for a heuristic scorer; the opponent's
     * actual dice choice depends on their portfolio, which would require full game state.
     *
     * <p><b>Red card handling:</b> Red cards are evaluated from the roller's perspective
     * ({@code oop=false}) and negated to get the owner's income. The roller's coin count
     * is estimated as the average of the {@code opponentCoins} array.
     *
     * <p><b>Why c=99 for non-red cards:</b> Blue and green cards pay from the bank, so the
     * player's own coin count is irrelevant for their income.
     */
    public static double playerEvPerRound(Player player, int numPlayers, int[] opponentCoins) {
        PlayerStats stats = PlayerStats.of(player);

        // Average opponent coins for red card clamping
        int avgOppCoins = 99; // default: assume opponents can pay
        if (opponentCoins.length > 0) {
            int sum = 0;
            for (int c : opponentCoins) sum += c;
            avgOppCoins = Math.max(1, sum / opponentCoins.length);
        }

        // --- Own-turn income: single dice choice across all cards ---
        // Compute total portfolio income for each possible roll, then take max(1d6_ev, 2d6_ev)
        double ownTurn1d6 = 0.0;
        for (int r = 1; r <= 6; r++) {
            double rollIncome = portfolioIncomeForRoll(player, stats, r, true, avgOppCoins, opponentCoins);
            ownTurn1d6 += P1[r] * rollIncome;
        }

        double ownTurnEv;
        if (stats.hasBahnhof) {
            double ownTurn2d6 = 0.0;
            for (int r = 2; r <= 12; r++) {
                double rollIncome = portfolioIncomeForRoll(player, stats, r, true, avgOppCoins, opponentCoins);
                ownTurn2d6 += P2[r] * rollIncome;
            }
            ownTurnEv = Math.max(ownTurn1d6, ownTurn2d6);
        } else {
            ownTurnEv = ownTurn1d6;
        }

        // --- Opponent-turn income: blue + red ---
        // On each opponent's turn, blue cards fire (income from bank) and red cards fire
        // (income from roller). Opponent dice choice is assumed 1d6 for the fast heuristic.
        double oppTurnEv = 0.0;
        for (int r = 1; r <= 6; r++) {
            double rollIncome = portfolioIncomeForRoll(player, stats, r, false, avgOppCoins, opponentCoins);
            oppTurnEv += P1[r] * rollIncome;
        }
        oppTurnEv *= (numPlayers - 1);

        return ownTurnEv + oppTurnEv;
    }

    /**
     * Computes total portfolio income for a single roll value, from the owner's perspective.
     *
     * @param player       the card owner
     * @param stats        pre-computed player stats
     * @param roll         the dice result (1-12)
     * @param isOwnTurn    true if this is the owner's turn (green/purple activate), false for opponent turn
     * @param avgOppCoins  average opponent coins for red card clamping
     * @param oppCoins     opponent coin array for purple cards
     * @return total positive income from all activating cards
     */
    private static double portfolioIncomeForRoll(Player player, PlayerStats stats, int roll,
                                                  boolean isOwnTurn, int avgOppCoins, int[] oppCoins) {
        double income = 0.0;
        for (Project card : player.getOwned_projects()) {
            String color = card.getColor();
            if ("gelb".equals(color)) continue;

            if ("rot".equals(color)) {
                // Red cards: fire on opponent turns only
                if (isOwnTurn) continue;
                int rollerLoss = get_I(roll, card.getId(), false, stats.hasEinkaufszentrum,
                        stats.foodCount, stats.animalCount, stats.productionCount,
                        avgOppCoins, oppCoins);
                int ownerGain = -rollerLoss;
                if (ownerGain > 0) income += ownerGain;
            } else if ("grün".equals(color) || "lila".equals(color)) {
                // Green/purple: fire on own turn only
                if (!isOwnTurn) continue;
                int inc = get_I(roll, card.getId(), true, stats.hasEinkaufszentrum,
                        stats.foodCount, stats.animalCount, stats.productionCount,
                        99, oppCoins);
                if (inc > 0) income += inc;
            } else {
                // Blue: fires on all turns
                int inc = get_I(roll, card.getId(), true, stats.hasEinkaufszentrum,
                        stats.foodCount, stats.animalCount, stats.productionCount,
                        99, oppCoins);
                if (inc > 0) income += inc;
            }
        }
        return income;
    }

    // -------------------------------------------------------------------------
    // contextualCardEvPerRound — synergy-aware single-card EV
    // -------------------------------------------------------------------------

    /**
     * Computes the per-round EV of a single card in the context of a specific player's
     * actual stats (Einkaufszentrum, food/animal/production counts, opponent coins).
     *
     * <p><b>Dice strategy:</b> Uses the caller-provided {@code use2d6} flag to determine
     * which dice distribution to use. This should reflect the player's actual optimal dice
     * choice given their full portfolio, NOT this card in isolation. Callers that have the
     * full portfolio context should pass the result of
     * {@code bestDiceEV(true, portfolioPayout) > bestDiceEV(false, portfolioPayout)}.
     * Callers without portfolio context can pass {@code stats.hasBahnhof} as a conservative
     * approximation.
     *
     * <p>Red cards are evaluated from the roller's perspective ({@code oop=false}) and
     * negated to get the owner's income, using average opponent coins for clamping.
     *
     * <p>This method correctly reflects synergy multipliers: a Markthalle owned by
     * a player with 3 food cards yields 3× the income of a Markthalle in a generic
     * reference state.
     */
    public static double contextualCardEvPerRound(Project card, PlayerStats stats,
                                            int numPlayers, int[] oppCoins) {
        boolean isRed = "rot".equals(card.getColor());

        // Average opponent coins for red card clamping
        int avgOppCoins = 99;
        if (isRed && oppCoins.length > 0) {
            int sum = 0;
            for (int c : oppCoins) sum += c;
            avgOppCoins = Math.max(1, sum / oppCoins.length);
        }

        // Compute EV under 1d6 distribution
        double ev1d6 = 0.0;
        for (int r = 1; r <= 6; r++) {
            ev1d6 += P1[r] * singleCardIncomeForRoll(card, stats, r, isRed, avgOppCoins, oppCoins);
        }

        double ev;
        if (stats.hasBahnhof) {
            // Compute EV under 2d6 distribution
            double ev2d6 = 0.0;
            for (int r = 2; r <= 12; r++) {
                ev2d6 += P2[r] * singleCardIncomeForRoll(card, stats, r, isRed, avgOppCoins, oppCoins);
            }
            // NOTE: This still takes per-card max. For contextualCardEvPerRound, this is
            // intentional — this function returns the card's EV given the BEST dice choice
            // for that card. The caller (GameSimulator buy ranking, BürohausLogic) uses this
            // to rank individual cards against each other. The portfolio-level dice choice
            // correction happens in playerEvPerRound.
            ev = Math.max(ev2d6, ev1d6);
        } else {
            ev = ev1d6;
        }

        // Scale by turn frequency
        return switch (card.getColor()) {
            case "blau" -> ev * numPlayers;           // fires every player's turn
            case "rot"  -> ev * (numPlayers - 1);     // fires on each opponent's turn
            default     -> ev;                         // grün/lila: own turn only
        };
    }

    /** Income from a single card on a single roll (helper for contextualCardEvPerRound). */
    private static double singleCardIncomeForRoll(Project card, PlayerStats stats, int roll,
                                                   boolean isRed, int avgOppCoins, int[] oppCoins) {
        if (isRed) {
            int rollerLoss = get_I(roll, card.getId(), false, stats.hasEinkaufszentrum,
                    stats.foodCount, stats.animalCount, stats.productionCount,
                    avgOppCoins, oppCoins);
            return Math.max(0, -rollerLoss);
        } else {
            int income = get_I(roll, card.getId(), true, stats.hasEinkaufszentrum,
                    stats.foodCount, stats.animalCount, stats.productionCount,
                    99, oppCoins);
            return Math.max(0, income);
        }
    }

}
