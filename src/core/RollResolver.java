package core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the coin effects of a single dice roll across all players.
 *
 * <p>This is the single authoritative implementation of the Machi Koro income rules:
 * <b>Red → Blue/Green → Purple</b>, with counter-clockwise priority for multiple red claims.
 *
 * <p><b>CRITICAL INVARIANT — DO NOT CHANGE THE PROCESSING ORDER.</b>
 * The order matters for game correctness: red payments must deduct from the roller's coins
 * BEFORE blue/green income is added. This means a roller at 0 coins pays nothing to red
 * card owners, even if they'd later receive blue/green income on the same roll.
 * Counter-clockwise red payment order means earlier opponents get paid in full; later
 * opponents may get nothing if the roller runs out of coins.
 */
public final class RollResolver {

    private RollResolver() {}

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
        int[] deltas = new int[state.getPlayers().length];
        computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        return deltas;
    }

    /**
     * Computes coin deltas into a pre-allocated array, avoiding allocation on every call.
     * The array must have length >= number of players. Contents are zeroed before use.
     *
     * @param state         current game state
     * @param activePlayer  index of the rolling player
     * @param roll          dice total (1–12)
     * @param deltas        pre-allocated output array (will be zeroed and filled)
     */
    public static void computeAllDeltasForRoll(GameState state, int activePlayer, int roll,
                                                int[] deltas) {
        Player[] players = state.getPlayers();
        int n = players.length;
        java.util.Arrays.fill(deltas, 0, n, 0);

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
                            rollerCoins, CardIncome.EMPTY_INT_ARRAY);
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
                // TODO(expansions): freshOpponentCoins reads base coins (player.getCoins()),
                // ignoring red/blue/green deltas already accumulated this roll. Correct for
                // the base game because no red/blue/green card activates on roll 6. Breaks
                // with Harbor expansion (Flower Shop on 6, Loan Office on 5-6). Fix: use
                // players[i].getCoins() + deltas[i] instead of players[i].getCoins().
                int[] freshOpponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
                int gain = CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount, activeStats.productionCount,
                        active.getCoins() + deltas[activePlayer], freshOpponentCoins);
                deltas[activePlayer] += gain;

                // For "take from opponent" purple cards, also subtract from the affected opponent(s).
                // get_I returns the roller's gain; we must mirror it as a loss for the source.
                if (gain > 0 && roll == 6) {
                    switch (p.getId()) {
                        case "stadion" -> {
                            // Takes 2 from EACH opponent
                            for (int i = 0; i < n; i++) {
                                if (i != activePlayer) {
                                    int loss = -Math.min(2, players[i].getCoins());
                                    deltas[i] += loss;
                                }
                            }
                        }
                        case "fernsehsender" -> {
                            // Takes 5 from the RICHEST opponent
                            int richest = 0;
                            int richestIdx = -1;
                            for (int i = 0; i < n; i++) {
                                if (i != activePlayer && players[i].getCoins() > richest) {
                                    richest = players[i].getCoins();
                                    richestIdx = i;
                                }
                            }
                            if (richestIdx >= 0) {
                                deltas[richestIdx] -= Math.min(5, richest);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes per-card income attribution for a single roll.
     *
     * <p>Replicates the same Red → Blue/Green → Purple order as
     * {@link #computeAllDeltasForRoll}, but returns a map of card ID to per-player deltas.
     * Duplicate cards (e.g., 3x Weizenfeld) accumulate into the same entry.
     *
     * <p><b>Consistency invariant:</b> For each player, the sum of all per-card deltas
     * must equal the corresponding entry from {@code computeAllDeltasForRoll}.
     *
     * <p>Only for post-game analysis (MatchRunner replay). NOT for engine hot paths.
     *
     * @param state         current game state (coins reflect pre-roll values)
     * @param activePlayer  index of the rolling player
     * @param roll          dice total (1–12)
     * @return map of card ID → int[playerCount] deltas attributed to that card
     */
    public static Map<String, int[]> attributeIncomePerCard(GameState state, int activePlayer, int roll) {
        Player[] players = state.getPlayers();
        int n = players.length;
        Map<String, int[]> result = new LinkedHashMap<>();

        // Step 1: Red card payments (counter-clockwise, sequential)
        int rollerCoins = players[activePlayer].getCoins();
        for (int step = 1; step < n; step++) {
            int oppIdx = (activePlayer - step + n) % n;
            Player opponent = players[oppIdx];
            CardIncome.PlayerStats oppStats = CardIncome.PlayerStats.of(opponent);
            for (Project p : opponent.getOwned_projects()) {
                if ("rot".equals(p.getColor())) {
                    int loss = CardIncome.get_I(roll, p.getId(), false,
                            oppStats.hasEinkaufszentrum, 0, 0, 0,
                            rollerCoins, CardIncome.EMPTY_INT_ARRAY);
                    if (loss < 0 && -loss > rollerCoins) loss = -rollerCoins;
                    int gain = -loss;
                    if (gain != 0) {
                        int[] deltas = result.computeIfAbsent(p.getId(), k -> new int[n]);
                        deltas[activePlayer] += loss;
                        deltas[oppIdx]       += gain;
                    }
                    rollerCoins += loss;
                    if (rollerCoins < 0) rollerCoins = 0;
                }
            }
        }

        // Step 2: Blue card income for every player
        for (int i = 0; i < n; i++) {
            Player player = players[i];
            CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(player);
            int[] otherCoins = CardIncome.buildOpponentCoins(players, i);
            for (Project p : player.getOwned_projects()) {
                if ("blau".equals(p.getColor())) {
                    int income = CardIncome.get_I(roll, p.getId(), true,
                            stats.hasEinkaufszentrum, stats.foodCount, stats.animalCount,
                            stats.productionCount, player.getCoins(), otherCoins);
                    if (income != 0) {
                        int[] deltas = result.computeIfAbsent(p.getId(), k -> new int[n]);
                        deltas[i] += income;
                    }
                }
            }
        }

        // Step 3: Green income for the active player
        Player active = players[activePlayer];
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(active);
        int[] opponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
        for (Project p : active.getOwned_projects()) {
            if ("grün".equals(p.getColor())) {
                int income = CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum, activeStats.foodCount, activeStats.animalCount,
                        activeStats.productionCount, active.getCoins(), opponentCoins);
                if (income != 0) {
                    int[] deltas = result.computeIfAbsent(p.getId(), k -> new int[n]);
                    deltas[activePlayer] += income;
                }
            }
        }

        // Step 4: Purple income for the active player
        // Coins are taken from opponent(s) — record both the gain and the opponent loss.
        int activeDeltaSoFar = 0;
        for (int[] d : result.values()) activeDeltaSoFar += d[activePlayer];
        for (Project p : active.getOwned_projects()) {
            if ("lila".equals(p.getColor())) {
                int[] freshOpponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
                int income = CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount, activeStats.productionCount,
                        active.getCoins() + activeDeltaSoFar, freshOpponentCoins);
                if (income != 0) {
                    int[] deltas = result.computeIfAbsent(p.getId(), k -> new int[n]);
                    deltas[activePlayer] += income;
                    activeDeltaSoFar += income;
                    // Also record per-opponent losses for "steal" purple cards
                    if (roll == 6) {
                        switch (p.getId()) {
                            case "stadion" -> {
                                for (int i = 0; i < n; i++) {
                                    if (i != activePlayer)
                                        deltas[i] -= Math.min(2, players[i].getCoins());
                                }
                            }
                            case "fernsehsender" -> {
                                int richest = 0, richestIdx = -1;
                                for (int i = 0; i < n; i++) {
                                    if (i != activePlayer && players[i].getCoins() > richest) {
                                        richest = players[i].getCoins(); richestIdx = i;
                                    }
                                }
                                if (richestIdx >= 0) deltas[richestIdx] -= Math.min(5, richest);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }
}
