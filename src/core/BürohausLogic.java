package core;

/**
 * Pure-static helpers for the bürohaus (lila, roll=6) card-swap mechanic.
 *
 * <p>Bürohaus lets the active player swap any one of their non-landmark establishments
 * with any non-landmark establishment owned by another player. The swap is optional.
 *
 * <p>These methods implement a greedy heuristic: always trade the lowest-EV
 * non-landmark, non-purple card the active player owns for the highest-EV
 * non-landmark, non-purple card any opponent owns, provided the swap is
 * beneficial (i.e. the opponent's card has higher EV in the active player's
 * context than the player's worst card).
 *
 * <p><b>Design decision: the greedy policy is deliberate.</b> For the Calcs/Core layer,
 * single-activation greedy EV-maximization is the correct analytical approximation.
 * Multi-turn look-ahead swap optimization belongs in the Engine layer — MCTS engines
 * model Bürohaus as a tree node ({@code BürohausNode}) and explore swap options during
 * tree search. The greedy fallback here is also used by rollout policies (Greedy, Boltzmann)
 * where a single-swap heuristic is appropriate: in practice, the swap has at most one
 * beneficial option (lowest-own vs highest-opp), and exploring worse swaps adds noise
 * without meaningful strategic diversity.
 *
 * <p>Purple (lila) cards are excluded because they are unique per player —
 * swapping one would give the recipient a second copy of a unique card,
 * which is illegal under the official rules.
 *
 * <p>Card EV is evaluated in the active player's real context (actual
 * Einkaufszentrum status, food/animal/production counts) so that synergy
 * multipliers like Markthalle and Molkerei are correctly captured.
 */
public final class BürohausLogic {

    private BürohausLogic() {}

    // -------------------------------------------------------------------------
    // Shared scan result
    // -------------------------------------------------------------------------

    /**
     * Identifies the best swap candidates: the active player's lowest-EV card
     * (excluding bürohaus itself and landmarks) and each opponent's highest-EV card,
     * both evaluated in the active player's real context (synergy-aware).
     *
     * <p><b>INVARIANT: Purple (lila) cards are EXCLUDED from both own and opponent
     * candidate lists.</b> Purple cards are unique per player (max 1 copy); swapping
     * would violate that rule. This exclusion was a bug fix — do not remove the
     * {@code "lila".equals(p.getColor())} checks below.
     */
    public static SwapCandidates findCandidates(GameState state, int playerIndex) {
        Player active = state.getPlayers()[playerIndex];
        int n = state.getPlayers().length;
        CardIncome.PlayerStats activeStats = CardIncome.PlayerStats.of(active);
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), playerIndex);

        Project worstOwn = null;
        double worstOwnEV = Double.MAX_VALUE;
        for (Project p : active.getOwned_projects()) {
            if (p.isIs_grossprojekt() || "lila".equals(p.getColor())) continue;
            double ev = CardIncome.contextualCardEvPerRound(p, activeStats, n, oppCoins);
            if (ev < worstOwnEV) { worstOwnEV = ev; worstOwn = p; }
        }

        Project bestOpp = null;
        double bestOppEV = 0.0;
        int bestOppPlayer = -1;
        for (int i = 0; i < n; i++) {
            if (i == playerIndex) continue;
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (p.isIs_grossprojekt() || "lila".equals(p.getColor())) continue;
                double ev = CardIncome.contextualCardEvPerRound(p, activeStats, n, oppCoins);
                if (ev > bestOppEV) { bestOppEV = ev; bestOpp = p; bestOppPlayer = i; }
            }
        }

        return new SwapCandidates(worstOwn, worstOwnEV, bestOpp, bestOppEV, bestOppPlayer);
    }

    public record SwapCandidates(
            Project worstOwn,  double worstOwnEV,
            Project bestOpp,   double bestOppEV,
            int bestOppPlayer) {

        /** True when a beneficial swap exists. */
        public boolean isBeneficial() {
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
    public static double swapEV(GameState state, int playerIndex) {
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
    public static String swapNote(GameState state, int playerIndex) {
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
    public static void executeSwap(GameState state, int playerIndex) {
        SwapCandidates c = findCandidates(state, playerIndex);
        if (!c.isBeneficial()) return;

        Player active   = state.getPlayers()[playerIndex];
        Player opponent = state.getPlayers()[c.bestOppPlayer()];
        active.getOwned_projects().remove(c.worstOwn());
        opponent.getOwned_projects().remove(c.bestOpp());
        active.addProject(c.bestOpp());
        opponent.addProject(c.worstOwn());
    }

    /**
     * Executes a user-chosen bürohaus card swap in-place on {@code state}.
     * The active player gives away {@code ownCard} and receives {@code oppCard}
     * from the opponent at index {@code oppPlayerIndex}.
     *
     * @param state          game state to mutate
     * @param playerIndex    the active player
     * @param ownCard        card the active player gives away
     * @param oppPlayerIndex the opponent providing the card
     * @param oppCard        card received from the opponent
     * @throws IllegalArgumentException if either card is a landmark or purple,
     *         or if the respective player does not own the card
     */
    public static void executeSwap(GameState state, int playerIndex,
                                    Project ownCard, int oppPlayerIndex, Project oppCard) {
        if (ownCard.isIs_grossprojekt() || "lila".equals(ownCard.getColor()))
            throw new IllegalArgumentException("Cannot swap landmark or purple card: " + ownCard.getId());
        if (oppCard.isIs_grossprojekt() || "lila".equals(oppCard.getColor()))
            throw new IllegalArgumentException("Cannot swap landmark or purple card: " + oppCard.getId());

        Player active   = state.getPlayers()[playerIndex];
        Player opponent = state.getPlayers()[oppPlayerIndex];

        if (!active.removeProject(ownCard))
            throw new IllegalArgumentException("Active player does not own: " + ownCard.getId());
        if (!opponent.removeProject(oppCard))
            throw new IllegalArgumentException("Opponent does not own: " + oppCard.getId());

        active.addProject(oppCard);
        opponent.addProject(ownCard);
    }

    // -------------------------------------------------------------------------
    // BitState-native swap candidate search — no toGameState() call
    // -------------------------------------------------------------------------

    /**
     * Result of a BitState-native swap scan: normal-card indices instead of Project objects.
     * Both indices are -1 when no beneficial swap exists.
     */
    public record BitSwapCandidates(int worstOwnIdx, int bestOppIdx, int bestOppPlayer) {
        public boolean isBeneficial() {
            return worstOwnIdx >= 0 && bestOppIdx >= 0;
        }
    }

    /**
     * BitState-native version of {@link #findCandidates} — no {@code toGameState()} call.
     *
     * <p>Builds {@link CardIncome.PlayerStats} directly from BitState via
     * {@link BitState#buildPlayerStats} and evaluates card EV via
     * {@link CardIncome#contextualCardEvPerRound} using
     * {@link BitStateTranslator#NORMAL_CARD_PROJECTS} for Project references.
     * Purple cards are excluded (same invariant as the GameState version).
     */
    public static BitSwapCandidates findCandidatesBit(BitState bs, int activePlayer) {
        int n = bs.getNumPlayers();

        CardIncome.PlayerStats activeStats = bs.buildPlayerStats(activePlayer);

        // Build opponent coins array
        int[] oppCoins = new int[n - 1];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            if (i != activePlayer) oppCoins[idx++] = bs.getCoins(i);
        }

        // Find worst own normal card
        int worstOwnIdx = -1;
        double worstOwnEV = Double.MAX_VALUE;
        for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
            if (bs.getCardCount(activePlayer, i) == 0) continue;
            Project card = BitStateTranslator.NORMAL_CARD_PROJECTS[i];
            double ev = CardIncome.contextualCardEvPerRound(card, activeStats, n, oppCoins);
            if (ev < worstOwnEV) { worstOwnEV = ev; worstOwnIdx = i; }
        }

        // Find best opponent normal card
        int bestOppIdx = -1;
        double bestOppEV = 0.0;
        int bestOppPlayer = -1;
        for (int i = 0; i < n; i++) {
            if (i == activePlayer) continue;
            for (int j = 0; j < BitStateTranslator.NUM_NORMAL_CARDS; j++) {
                if (bs.getCardCount(i, j) == 0) continue;
                Project card = BitStateTranslator.NORMAL_CARD_PROJECTS[j];
                double ev = CardIncome.contextualCardEvPerRound(card, activeStats, n, oppCoins);
                if (ev > bestOppEV) { bestOppEV = ev; bestOppIdx = j; bestOppPlayer = i; }
            }
        }

        if (worstOwnIdx < 0 || bestOppIdx < 0 || bestOppEV <= worstOwnEV) {
            return new BitSwapCandidates(-1, -1, -1);
        }
        return new BitSwapCandidates(worstOwnIdx, bestOppIdx, bestOppPlayer);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
