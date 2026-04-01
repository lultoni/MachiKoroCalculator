package core;

/**
 * Resolves the coin effects of a single dice roll across all players.
 *
 * <p>This is the single authoritative implementation of the Machi Koro income rules:
 * <b>Red → Blue/Green → Purple</b>, with counter-clockwise priority for multiple red claims.
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
                int[] freshOpponentCoins = CardIncome.buildOpponentCoins(players, activePlayer);
                deltas[activePlayer] += CardIncome.get_I(roll, p.getId(), true,
                        activeStats.hasEinkaufszentrum,
                        activeStats.foodCount, activeStats.animalCount, activeStats.productionCount,
                        active.getCoins() + deltas[activePlayer], freshOpponentCoins);
            }
        }

        return deltas;
    }
}
