package logic.probability;

/**
 * Pure-static helpers for the bürohaus (lila, roll=6) card-swap mechanic.
 *
 * <p>Bürohaus lets the active player swap any one of their non-landmark establishments
 * with any non-landmark establishment owned by another player. The swap is optional.
 *
 * <p>These methods implement a greedy heuristic: always trade the lowest-EV
 * card the active player owns for the highest-EV card any opponent owns,
 * provided the swap is beneficial (i.e. the opponent's card has higher EV
 * in the active player's context than the player's worst card).
 *
 * <p>Card EV is evaluated in the active player's real context (actual
 * Einkaufszentrum status, food/animal/production counts) so that synergy
 * multipliers like Markthalle and Molkerei are correctly captured.
 *
 * @see CardIncome#contextualCardEvPerRound
 */
final class BürohausLogic {

    private BürohausLogic() {}

    // -------------------------------------------------------------------------
    // Shared scan result
    // -------------------------------------------------------------------------

    /**
     * Identifies the best swap candidates: the active player's lowest-EV card
     * (excluding bürohaus itself and landmarks) and each opponent's highest-EV card,
     * both evaluated in the active player's real context (synergy-aware).
     *
     * @param state       current game state
     * @param playerIndex the active player
     * @return a {@link SwapCandidates} record, or one with null fields if no swap is possible
     */
    private static SwapCandidates findCandidates(GameState state, int playerIndex) {
        Player active = state.getPlayers()[playerIndex];
        int n = state.getPlayers().length;
        // Use active player's real stats so Einkaufszentrum, food/animal/production
        // multipliers are applied correctly when evaluating which cards to swap.
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(active);
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), playerIndex);

        Project worstOwn = null;
        double worstOwnEV = Double.MAX_VALUE;
        for (Project p : active.getOwned_projects()) {
            if (p.isIs_grossprojekt() || p.getId().equals("bürohaus")) continue;
            double ev = CardIncome.contextualCardEvPerRound(p, activeStats, n, oppCoins);
            if (ev < worstOwnEV) { worstOwnEV = ev; worstOwn = p; }
        }

        Project bestOpp = null;
        double bestOppEV = 0.0;
        int bestOppPlayer = -1;
        for (int i = 0; i < n; i++) {
            if (i == playerIndex) continue;
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) continue;
                // Evaluate the opponent's card in the active player's context:
                // what would this card be worth if the active player owned it?
                double ev = CardIncome.contextualCardEvPerRound(p, activeStats, n, oppCoins);
                if (ev > bestOppEV) { bestOppEV = ev; bestOpp = p; bestOppPlayer = i; }
            }
        }

        return new SwapCandidates(worstOwn, worstOwnEV, bestOpp, bestOppEV, bestOppPlayer);
    }

    private record SwapCandidates(
            Project worstOwn,  double worstOwnEV,
            Project bestOpp,   double bestOppEV,
            int bestOppPlayer) {

        /** True when a beneficial swap exists. */
        boolean isBeneficial() {
            return worstOwn != null && bestOpp != null && bestOppEV > worstOwnEV;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Approximates the per-activation coin-equivalent EV of a bürohaus swap.
     * Returns {@code max(0, bestOppCardEV − worstOwnCardEV)}.
     *
     * @param state       game state with bürohaus already in the active player's owned list
     * @param playerIndex the active player
     * @return per-activation EV gain (≥ 0)
     */
    static double swapEV(GameState state, int playerIndex) {
        SwapCandidates c = findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return 0.0;
        return c.bestOppEV() - c.worstOwnEV();
    }

    /**
     * Returns a human-readable description of the best bürohaus swap,
     * or {@code null} if no beneficial swap exists.
     *
     * @param state       game state with bürohaus already in the active player's owned list
     * @param playerIndex the active player
     * @return swap description such as "Swap your Weizenfeld for P1's Bergwerk", or {@code null}
     */
    static String swapNote(GameState state, int playerIndex) {
        SwapCandidates c = findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return null;

        String oppName = state.getPlayers()[c.bestOppPlayer()].getName();
        return "Swap your " + capitalize(c.worstOwn().getId())
                + " for " + oppName + "'s " + capitalize(c.bestOpp().getId());
    }

    /**
     * Executes the optimal bürohaus card swap in-place on {@code state}.
     * Removes the active player's lowest-EV non-landmark and gives it to the opponent
     * who owns the highest-EV non-landmark; that card moves to the active player.
     * No-ops if no beneficial swap exists.
     *
     * @param state       game state to mutate
     * @param playerIndex the active player
     */
    static void executeSwap(GameState state, int playerIndex) {
        SwapCandidates c = findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return;

        Player active   = state.getPlayers()[playerIndex];
        Player opponent = state.getPlayers()[c.bestOppPlayer()];
        active.getOwned_projects().remove(c.worstOwn());
        opponent.getOwned_projects().remove(c.bestOpp());
        active.getOwned_projects().add(c.bestOpp());
        opponent.getOwned_projects().add(c.worstOwn());
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
