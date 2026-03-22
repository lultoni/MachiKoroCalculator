package logic.probability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

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
 */
public class ProbabilityCalc {

    // -------------------------------------------------------------------------
    // Pre-computed probability tables (O(1) lookup, no switch overhead)
    // -------------------------------------------------------------------------

    /** P1[r] = probability of rolling r with 1d6. Valid indices: 1–6. */
    static final double[] P1 = new double[13];

    /** P2[r] = probability of rolling r with 2d6. Valid indices: 2–12. */
    static final double[] P2 = new double[13];

    static {
        for (int r = 1; r <= 6; r++)  P1[r] = 1.0 / 6.0;
        for (int r = 2; r <= 12; r++) P2[r] = (6.0 - Math.abs(r - 7)) / 36.0;
    }

    /** Landmark weights used in the win-probability score function (arbitrary but consistent). */
    private static final double LANDMARK_WEIGHT = 2.0;

    /**
     * Remaining-turns estimate used in win-probability scoring.
     * Represents the expected turns left in a typical game from mid-game onwards.
     */
    private static final double REMAINING_TURNS_ESTIMATE = 12.0;

    // -------------------------------------------------------------------------
    // Public probability accessors (kept for external use and tests)
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
                // Card-swap effect modelled separately in immediateEV via bürohausSwapEV().
                // Returns 0 here because get_I only handles coin deltas per roll.
                return 0;
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // PlayerStats helper — cached per-player counts used by get_I
    // -------------------------------------------------------------------------

    private static class PlayerStats {
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
    // computeNetGainForRoll
    // -------------------------------------------------------------------------

    /**
     * Computes the net coin change for the active player (playerIndex) when the given
     * roll occurs on their own turn.
     * <p>
     * Activation rules on own turn:
     * <ul>
     *   <li>Blue cards: activate for ALL players (each owner gets paid from bank).</li>
     *   <li>Green cards: activate only for the active player (oop=true).</li>
     *   <li>Purple cards: activate only for the active player (oop=true).</li>
     *   <li>Red cards: do NOT activate on the roller's own turn (they activate on opponents' turns).</li>
     * </ul>
     * On the active player's own turn they may also be paying opponents' red cards — but red cards
     * only activate when the active player rolls, and the activation cost is already included
     * in get_I by returning a negative value when oop=false.  However, for own-turn computation
     * we only apply red losses FROM opponents' cards that trigger on this roll number.
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
        PlayerStats activeStats = PlayerStats.of(activePlayer);
        int activeCoins = activePlayer.getCoins();

        // Build opponent coin array
        Player[] players = state.getPlayers();
        int n = players.length;
        int[] opponentCoins = buildOpponentCoins(players, playerIndex);

        int net = 0;

        // --- Blue cards: every player's owned blue cards pay their owner from the bank.
        //     The active player receives income from their own blue cards.
        //     Other players' blue cards pay those players — no effect on active player's coins.
        for (Project p : activePlayer.getOwned_projects()) {
            if ("blau".equals(p.getColor())) {
                net += get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount,
                        activeStats.productionCount,
                        activeCoins, opponentCoins);
            }
        }

        // --- Green cards: own turn only, owner = active player ---
        for (Project p : activePlayer.getOwned_projects()) {
            if ("grün".equals(p.getColor())) {
                net += get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount,
                        activeStats.productionCount,
                        activeCoins, opponentCoins);
            }
        }

        // --- Purple cards: own turn only, owner = active player ---
        for (Project p : activePlayer.getOwned_projects()) {
            if ("lila".equals(p.getColor())) {
                // For purple cards that steal from opponents we need the real-time coin counts.
                // We re-read opponentCoins each time since purple effects are sequential.
                int[] freshOpponentCoins = buildOpponentCoins(players, playerIndex);
                net += get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount,
                        activeStats.productionCount,
                        activeCoins + net, freshOpponentCoins);
            }
        }

        // --- Red cards: opponents' red cards activate on the active player's turn.
        //     The active player LOSES coins to each opponent who owns a red card.
        //     We sum the costs from every opponent's red cards.
        int remainingCoins = activeCoins + net; // coins after blue/green/purple income
        for (int i = 0; i < n; i++) {
            if (i == playerIndex) continue;
            Player opponent = players[i];
            PlayerStats oppStats = PlayerStats.of(opponent);
            for (Project p : opponent.getOwned_projects()) {
                if ("rot".equals(p.getColor())) {
                    // get_I from active player's perspective: oop=false (active player is NOT the owner)
                    // eb here is the OWNER's (opponent's) Einkaufszentrum
                    int loss = get_I(roll, p.getId(), false,
                            oppStats.hasEinkaufszentrum,
                            0, 0, 0,
                            Math.max(0, remainingCoins), new int[0]);
                    // loss is 0 or negative; clamp to remaining coins
                    if (loss < 0 && Math.abs(loss) > remainingCoins) {
                        loss = -remainingCoins;
                    }
                    net += loss;
                    remainingCoins += loss;
                    if (remainingCoins < 0) remainingCoins = 0;
                }
            }
        }

        return net;
    }

    /** Builds an array of coins for all players except playerIndex. */
    private static int[] buildOpponentCoins(Player[] players, int excludeIndex) {
        int[] coins = new int[players.length - 1];
        int idx = 0;
        for (int i = 0; i < players.length; i++) {
            if (i != excludeIndex) coins[idx++] = players[i].getCoins();
        }
        return coins;
    }

    // -------------------------------------------------------------------------
    // computeOpponentTurnGainForRoll
    // -------------------------------------------------------------------------

    /**
     * Computes the net coin change for the tracked player (playerIndex) when it is
     * an OPPONENT's turn and they roll {@code roll}.
     * <p>
     * On an opponent's turn the tracked player:
     * <ul>
     *   <li>Gains coins from their own blue cards (blue triggers for all players every turn).</li>
     *   <li>Gains coins from their own red cards (red triggers on the opponent/roller's turn).</li>
     *   <li>Does NOT gain from green or purple cards (those only trigger on the owner's own turn).</li>
     * </ul>
     *
     * @param state          current game state
     * @param playerIndex    the tracked player (not the active roller)
     * @param activeRollerIndex the player currently rolling
     * @param roll           dice result
     * @return net coin change for playerIndex during this opponent turn
     */
    private static int computeOpponentTurnGainForRoll(GameState state, int playerIndex,
                                                       int activeRollerIndex, int roll) {
        Player trackedPlayer = state.getPlayers()[playerIndex];
        Player activeRoller  = state.getPlayers()[activeRollerIndex];
        PlayerStats trackedStats = PlayerStats.of(trackedPlayer);
        int trackedCoins = trackedPlayer.getCoins();
        int rollerCoins  = activeRoller.getCoins();

        int net = 0;

        // Blue cards: tracked player earns from their own blue cards
        for (Project p : trackedPlayer.getOwned_projects()) {
            if ("blau".equals(p.getColor())) {
                net += get_I(roll, p.getId(), true,
                        trackedStats.hasEinkaufszentrum,
                        trackedStats.foodCount, trackedStats.animalCount,
                        trackedStats.productionCount,
                        trackedCoins, new int[]{rollerCoins});
            }
        }

        // Red cards: tracked player earns from their own red cards (roller pays them)
        for (Project p : trackedPlayer.getOwned_projects()) {
            if ("rot".equals(p.getColor())) {
                // From the tracked player's (owner's) perspective, oop=true but get_I
                // for red cards only pays when oop=false (roller's perspective). We
                // therefore compute the absolute income by querying the roller's perspective
                // and negating.
                int rollerLoss = get_I(roll, p.getId(), false,
                        trackedStats.hasEinkaufszentrum,
                        0, 0, 0,
                        Math.max(0, rollerCoins), new int[0]);
                // rollerLoss is 0 or negative; the tracked player gains its absolute value
                net += Math.abs(rollerLoss);
            }
        }

        return net;
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

        double ev1 = 0.0;
        for (int d = 1; d <= 6; d++) {
            // isDoubles=false: second roll never chains
            ev1 += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
        }

        double ev2 = 0.0;
        for (int d1 = 1; d1 <= 6; d1++) {
            for (int d2 = 1; d2 <= 6; d2++) {
                ev2 += (1.0 / 36.0) * computeNetGainForRoll(state, playerIndex, d1 + d2, false);
            }
        }

        if (forcedDiceCount == 1) return ev1;
        if (forcedDiceCount == 2) return ev2;
        // forcedDiceCount == -1: player chooses freely (needs Bahnhof)
        return hasBahnhof ? Math.max(ev1, ev2) : ev1;
    }

    // -------------------------------------------------------------------------
    // immediateEV — EV of the buyer's current turn after purchasing candidate
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

        double evTotal;

        if (!hasBahnhof) {
            // Only 1d6
            evTotal = 0.0;
            for (int d = 1; d <= 6; d++) {
                evTotal += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            }
        } else {
            // Choose best of 1d6 vs 2d6
            double ev1 = 0.0;
            for (int d = 1; d <= 6; d++) {
                ev1 += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            }

            double ev2 = 0.0;
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    double p = 1.0 / 36.0;
                    boolean isDoubles = (d1 == d2);
                    int net = computeNetGainForRoll(state, playerIndex, d1 + d2, isDoubles);
                    ev2 += p * net;
                    if (hasFreizeitpark && isDoubles) {
                        // Second roll EV; Funkturm forces same dice count (2) if present
                        int forcedDice = hasFunkturm ? 2 : -1;
                        ev2 += p * bestSecondRollEV(state, playerIndex, forcedDice);
                    }
                }
            }

            evTotal = Math.max(ev1, ev2);
        }

        // Bürohaus card-swap EV: fires on own turn when roll = 6 (lila, own-turn only).
        // bürohausSwapEV() approximates the net EV gain from trading the player's lowest-EV
        // non-landmark for the opponent's highest-EV non-landmark.
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
        double total = 0.0;

        // Own turn: blue + green + purple + red costs paid
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        if (!hasBahnhof) {
            for (int d = 1; d <= 6; d++) {
                total += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            }
        } else {
            double ev1 = 0.0;
            for (int d = 1; d <= 6; d++) {
                ev1 += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            }
            double ev2 = 0.0;
            boolean hasFreizeitpark = state.getPlayers()[playerIndex].hasProject("freizeitpark");
            boolean hasFunkturm    = state.getPlayers()[playerIndex].hasProject("funkturm");
            for (int d1 = 1; d1 <= 6; d1++) {
                for (int d2 = 1; d2 <= 6; d2++) {
                    double p = 1.0 / 36.0;
                    boolean isDoubles = (d1 == d2);
                    ev2 += p * computeNetGainForRoll(state, playerIndex, d1 + d2, isDoubles);
                    if (hasFreizeitpark && isDoubles) {
                        ev2 += p * bestSecondRollEV(state, playerIndex, hasFunkturm ? 2 : -1);
                    }
                }
            }
            total += Math.max(ev1, ev2);
        }

        // Opponent turns: tracked player gains from blue + red cards each opponent turn
        for (int opponentIdx = 0; opponentIdx < n; opponentIdx++) {
            if (opponentIdx == playerIndex) continue;

            // Assume opponents roll 1d6 unless they have Bahnhof (best case: they use 2d6 optimally).
            // For EV purposes, use their actual dice mode.
            boolean opponentHasBahnhof = state.getPlayers()[opponentIdx].hasProject("bahnhof");

            if (!opponentHasBahnhof) {
                for (int d = 1; d <= 6; d++) {
                    total += P1[d] * computeOpponentTurnGainForRoll(state, playerIndex, opponentIdx, d);
                }
            } else {
                // Opponent chooses best dice option; we compute EV of each and take max
                double oppEv1 = 0.0;
                for (int d = 1; d <= 6; d++) {
                    oppEv1 += P1[d] * computeOpponentTurnGainForRoll(state, playerIndex, opponentIdx, d);
                }
                double oppEv2 = 0.0;
                for (int d1 = 1; d1 <= 6; d1++) {
                    for (int d2 = 1; d2 <= 6; d2++) {
                        oppEv2 += (1.0 / 36.0) * computeOpponentTurnGainForRoll(
                                state, playerIndex, opponentIdx, d1 + d2);
                    }
                }
                total += Math.max(oppEv1, oppEv2);
            }
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

        // Simulate the purchase in a state copy
        GameState state = gs.copy();
        state.getPlayers()[playerIndex].getOwned_projects().add(candidate);

        entry.immediateEV          = immediateEV(gs, playerIndex, candidate, false);
        entry.immediateEV_afterCost = entry.immediateEV - candidate.getCost();
        entry.evPerRound           = evPerRound(gs, playerIndex, candidate);

        // Geometric-series ROI with L'Hôpital guard
        final double eps = 1e-9;
        double geometricSum;
        if (Math.abs(discountFactor - 1.0) < eps) {
            geometricSum = horizonTurns;
        } else {
            geometricSum = discountFactor
                    * (1.0 - Math.pow(discountFactor, horizonTurns))
                    / (1.0 - discountFactor);
        }
        entry.roiOverHorizon = entry.evPerRound * geometricSum - candidate.getCost();

        // Variance of own-turn net gain distribution
        entry.variance = computeVarianceOwnTurn(state, playerIndex);

        // Risk: P(earn 0 coins on own turn)
        entry.probNoIncomeOwnTurn  = computeProbNoIncomeOwnTurn(state, playerIndex);
        entry.probNoIncomeRound    = computeProbNoIncomeRound(state, playerIndex);

        return entry;
    }

    /**
     * Variance of the per-own-turn net gain distribution (Var = E[X²] − E[X]²).
     * Uses 1d6 if no Bahnhof, 2d6 otherwise (best-EV dice choice).
     */
    private static double computeVarianceOwnTurn(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");

        if (!hasBahnhof) {
            return computeVariance1d6(state, playerIndex);
        } else {
            double var1 = computeVariance1d6(state, playerIndex);
            double var2 = computeVariance2d6(state, playerIndex);
            // Return variance of the dice choice that yields higher EV
            double ev1 = 0.0, ev2 = 0.0;
            for (int d = 1; d <= 6; d++)
                ev1 += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            for (int d1 = 1; d1 <= 6; d1++)
                for (int d2 = 1; d2 <= 6; d2++)
                    ev2 += (1.0 / 36.0) * computeNetGainForRoll(state, playerIndex, d1 + d2, false);
            return ev1 >= ev2 ? var1 : var2;
        }
    }

    private static double computeVariance1d6(GameState state, int playerIndex) {
        double ev = 0.0, e2 = 0.0;
        for (int d = 1; d <= 6; d++) {
            double gain = computeNetGainForRoll(state, playerIndex, d, false);
            ev += P1[d] * gain;
            e2 += P1[d] * gain * gain;
        }
        return e2 - ev * ev;
    }

    private static double computeVariance2d6(GameState state, int playerIndex) {
        double ev = 0.0, e2 = 0.0;
        for (int d1 = 1; d1 <= 6; d1++) {
            for (int d2 = 1; d2 <= 6; d2++) {
                double p    = 1.0 / 36.0;
                double gain = computeNetGainForRoll(state, playerIndex, d1 + d2, false);
                ev += p * gain;
                e2 += p * gain * gain;
            }
        }
        return e2 - ev * ev;
    }

    /** P(earn 0 coins on own turn). */
    private static double computeProbNoIncomeOwnTurn(GameState state, int playerIndex) {
        boolean hasBahnhof = state.getPlayers()[playerIndex].hasProject("bahnhof");
        double probZero = 0.0;

        if (!hasBahnhof) {
            for (int d = 1; d <= 6; d++) {
                if (computeNetGainForRoll(state, playerIndex, d, false) == 0) probZero += P1[d];
            }
        } else {
            // Under 2d6 (assume player picks the higher-EV option)
            boolean use2d6 = false;
            double ev1 = 0.0, ev2 = 0.0;
            for (int d = 1; d <= 6; d++)
                ev1 += P1[d] * computeNetGainForRoll(state, playerIndex, d, false);
            for (int d1 = 1; d1 <= 6; d1++)
                for (int d2 = 1; d2 <= 6; d2++)
                    ev2 += (1.0 / 36.0) * computeNetGainForRoll(state, playerIndex, d1 + d2, false);
            use2d6 = ev2 > ev1;

            if (!use2d6) {
                for (int d = 1; d <= 6; d++) {
                    if (computeNetGainForRoll(state, playerIndex, d, false) == 0) probZero += P1[d];
                }
            } else {
                for (int d1 = 1; d1 <= 6; d1++) {
                    for (int d2 = 1; d2 <= 6; d2++) {
                        if (computeNetGainForRoll(state, playerIndex, d1 + d2, false) == 0)
                            probZero += 1.0 / 36.0;
                    }
                }
            }
        }
        return probZero;
    }

    /**
     * P(earn 0 coins over the entire round — own turn AND all opponent turns).
     * Approximated as the product of P(zero on own turn) × P(zero on each opponent turn),
     * treating turns as independent.
     */
    private static double computeProbNoIncomeRound(GameState state, int playerIndex) {
        double prob = computeProbNoIncomeOwnTurn(state, playerIndex);

        int n = state.getPlayers().length;
        for (int oppIdx = 0; oppIdx < n; oppIdx++) {
            if (oppIdx == playerIndex) continue;
            boolean oppHasBahnhof = state.getPlayers()[oppIdx].hasProject("bahnhof");
            double probZeroOppTurn = 0.0;
            if (!oppHasBahnhof) {
                for (int d = 1; d <= 6; d++) {
                    if (computeOpponentTurnGainForRoll(state, playerIndex, oppIdx, d) == 0)
                        probZeroOppTurn += P1[d];
                }
            } else {
                for (int d1 = 1; d1 <= 6; d1++) {
                    for (int d2 = 1; d2 <= 6; d2++) {
                        if (computeOpponentTurnGainForRoll(state, playerIndex, oppIdx, d1 + d2) == 0)
                            probZeroOppTurn += 1.0 / 36.0;
                    }
                }
            }
            prob *= probZeroOppTurn;
        }
        return prob;
    }

    // -------------------------------------------------------------------------
    // estimateWinProbDelta — analytical softmax or Monte Carlo
    // -------------------------------------------------------------------------

    /**
     * Estimates the change in win probability for playerIndex from buying {@code candidate}.
     *
     * <h3>Analytical mode ({@code mcSimulations == 0})</h3>
     * Uses a softmax score approximation:
     * <pre>
     *   score(p) = Σ evPerRound(card) × REMAINING_TURNS + Σ LANDMARK_WEIGHT (per built landmark)
     *   P_win(p) = exp(score_p) / Σ exp(score_j)   [numerically stable, max-subtracted]
     *   delta    = P_win(after buy) − P_win(before)
     * </pre>
     *
     * <h3>Monte Carlo mode ({@code mcSimulations > 0})</h3>
     * Runs {@code mcSimulations} parallel full-game simulations for both the
     * baseline state and the post-buy state using {@link GameSimulator}.
     * Each simulation uses its own {@link GameState#copy()} and
     * {@link ThreadLocalRandom#current()}.  Returns
     * {@code P_win(after buy) − P_win(baseline)}.
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
        if (mcSimulations > 0) {
            double baseline = mcWinRate(gs, playerIndex, mcSimulations);
            GameState stateAfter = gs.copy();
            stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
            double afterBuy = mcWinRate(stateAfter, playerIndex, mcSimulations);
            return afterBuy - baseline;
        }

        // Analytical path
        double[] scoresBefore = computeScores(gs);
        double pWinBefore = softmaxEntry(scoresBefore, playerIndex);

        GameState stateAfter = gs.copy();
        stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
        double[] scoresAfter = computeScores(stateAfter);
        double pWinAfter = softmaxEntry(scoresAfter, playerIndex);

        return pWinAfter - pWinBefore;
    }

    /**
     * Runs {@code numSims} Monte Carlo simulations in parallel and returns the
     * fraction in which {@code playerIndex} wins.
     *
     * <p>Uses {@code parallelStream} over simulation indices so the JVM's common
     * {@link java.util.concurrent.ForkJoinPool} handles thread management.
     * Each simulation gets its own {@link GameState#copy()} and uses
     * {@link ThreadLocalRandom#current()} (contention-free, per-thread RNG).
     *
     * @param state       starting state (read-only; a copy is taken per simulation)
     * @param playerIndex player whose win rate is measured
     * @param numSims     number of simulations to run
     * @return win rate in [0, 1]
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims) {
        long wins = IntStream.range(0, numSims)
                .parallel()
                .filter(i -> GameSimulator.simulate(state.copy(), ThreadLocalRandom.current())
                        == playerIndex)
                .count();
        return (double) wins / numSims;
    }

    /**
     * Computes a heuristic score for each player in the given state.
     * score(p) = Σ_card evPerRound_of_card_alone × REMAINING_TURNS + Σ_landmark LANDMARK_WEIGHT
     */
    private static double[] computeScores(GameState gs) {
        Player[] players = gs.getPlayers();
        double[] scores = new double[players.length];

        for (int i = 0; i < players.length; i++) {
            double score = 0.0;
            for (Project p : players[i].getOwned_projects()) {
                // Single-card EV contribution: use the evPerRound of this card in isolation
                // relative to a minimal state to avoid expensive full-state computation.
                score += singleCardEvPerRound(p, players.length) * REMAINING_TURNS_ESTIMATE;
                if (p.isIs_grossprojekt()) score += LANDMARK_WEIGHT;
            }
            scores[i] = score;
        }
        return scores;
    }

    /**
     * Approximates the per-round EV of a single card in isolation (no synergy),
     * scaled by the number of players for blue cards.
     */
    private static double singleCardEvPerRound(Project card, int numPlayers) {
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

    /**
     * Approximates the coin-equivalent EV of a bürohaus card-swap for the active player.
     * <p>
     * The heuristic assumes the player makes the optimal swap: they trade their
     * lowest-EV owned non-landmark establishment for the highest-EV non-landmark
     * establishment owned by any opponent. The bürohaus itself is excluded from the
     * "trade away" candidates since it is the trigger card.
     * <p>
     * Returns 0 if the swap would be neutral or unfavourable (e.g. the player already owns
     * better cards than any opponent, or opponents own no non-landmark cards).
     *
     * @param state       game state with the candidate card (bürohaus) already in the player's list
     * @param playerIndex the active player
     * @return per-activation EV gain (≥ 0; clamped below by 0)
     */
    private static double bürohausSwapEV(GameState state, int playerIndex) {
        Player active = state.getPlayers()[playerIndex];
        int n = state.getPlayers().length;

        // Worst owned non-landmark the player might give away (exclude bürohaus itself)
        double worstOwnEV = Double.MAX_VALUE;
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !p.getId().equals("bürohaus")) {
                worstOwnEV = Math.min(worstOwnEV, singleCardEvPerRound(p, n));
            }
        }
        if (worstOwnEV == Double.MAX_VALUE) worstOwnEV = 0.0; // nothing to trade away

        // Best non-landmark card any opponent owns (the card to take)
        double bestOppEV = 0.0;
        for (int i = 0; i < n; i++) {
            if (i == playerIndex) continue;
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (!p.isIs_grossprojekt()) {
                    bestOppEV = Math.max(bestOppEV, singleCardEvPerRound(p, n));
                }
            }
        }

        return Math.max(0.0, bestOppEV - worstOwnEV);
    }

    /**
     * Numerically stable softmax: returns the probability for index {@code i}.
     * Uses max-subtraction to prevent overflow.
     */
    private static double softmaxEntry(double[] scores, int index) {
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) if (s > max) max = s;

        double sumExp = 0.0;
        for (double s : scores) sumExp += Math.exp(s - max);

        return Math.exp(scores[index] - max) / sumExp;
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
     * <p>
     * When {@link RankingOptions#includeWinProbDelta} is true, win-probability delta is computed
     * analytically (fast) by default. When {@link RankingOptions#mcSimulations} &gt; 0, Monte Carlo
     * simulations are used instead — the baseline win rate is computed once and reused across all
     * candidates to avoid redundant simulation work.
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

        // Compute MC baseline win rate once (expensive) — reused for all candidates.
        double mcBaseline = 0.0;
        if (opts.includeWinProbDelta && opts.mcSimulations > 0) {
            mcBaseline = mcWinRate(gs, playerIndex, opts.mcSimulations);
        }

        for (Project candidate : gs.getUnbuilt_projects()) {
            if (candidate.getCost() > coins) continue;
            if (candidate.isIs_grossprojekt() && player.hasProject(candidate.getId())) continue;

            RankEntry entry = roiOverHorizon(gs, playerIndex, candidate,
                    opts.horizonTurns, opts.discountFactor);

            if (opts.includeWinProbDelta) {
                if (opts.mcSimulations > 0) {
                    // MC path: compare post-buy win rate against pre-computed baseline
                    GameState stateAfter = gs.copy();
                    stateAfter.getPlayers()[playerIndex].getOwned_projects().add(candidate);
                    double afterBuy = mcWinRate(stateAfter, playerIndex, opts.mcSimulations);
                    entry.winProbDelta = afterBuy - mcBaseline;
                } else {
                    // Analytical path (default)
                    entry.winProbDelta = estimateWinProbDelta(
                            gs, playerIndex, candidate, 0, 0);
                }
            }

            results.add(entry);
        }

        results.sort(Comparator.comparingDouble((RankEntry e) -> e.roiOverHorizon).reversed());
        return results;
    }

    // -------------------------------------------------------------------------
    // Legacy matrix method (kept for backward compatibility)
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
            int[] otherCoins = buildOtherCoins(playerCoins, playerIndex);

            // Count synergy categories for this player
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
                    valueMatrix[vmRow][roll - 1] += get_I(
                            roll, project.getId(), true, hasEB,
                            fCount, aCount, pCount, ownCoins, otherCoins);
                }
            }
        }
        return valueMatrix;
    }

    private static int[] buildOtherCoins(int[] coins, int excludeIndex) {
        int[] result = new int[coins.length - 1];
        int idx = 0;
        for (int i = 0; i < coins.length; i++) {
            if (i != excludeIndex) result[idx++] = coins[i];
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Package-visible bridges for GameSession (turn simulation)
    // -------------------------------------------------------------------------

    /**
     * Package-visible wrapper so {@link GameSession} can compute the coin delta
     * for the active player on their own turn without going through immediateEV.
     */
    static int computeNetGainForRollPublic(GameState state, int playerIndex, int roll) {
        return computeNetGainForRoll(state, playerIndex, roll, false);
    }

    /**
     * Package-visible wrapper so {@link GameSession} can compute the coin delta
     * for a tracked player on an opponent's turn.
     */
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
