package engine.mcts;

import calcs.RankEntry;
import calcs.WinProbability;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uniform-random full-game rollout for MCTS v1.
 *
 * <p>Starting from a leaf node's game state, simulates a complete game using
 * uniform-random decisions at every step until a player wins or the turn limit
 * is reached.
 *
 * <h2>Decision policy (fully uniform random)</h2>
 * <ul>
 *   <li><b>Dice count</b> — if player owns Bahnhof: 50/50 between 1d6 and 2d6.</li>
 *   <li><b>Roll</b> — uniform in [1..6] for 1d6, uniform in [2..12] for 2d6.</li>
 *   <li><b>Funkturm</b> — if player owns Funkturm: 50/50 keep or reroll once.</li>
 *   <li><b>Bürohaus</b> — if player owns Bürohaus and rolled 6: enumerate all valid
 *       (ownCard × oppCard) non-landmark non-purple pairs plus no-swap; choose uniformly.</li>
 *   <li><b>Purchase</b> — enumerate all affordable non-landmark cards (supply > 0) plus
 *       affordable landmarks plus "save"; choose uniformly at random.</li>
 *   <li><b>Freizeitpark bonus turn</b> — if player owns Freizeitpark, the roll was
 *       doubles, and this is not already a bonus turn: same player gets one extra turn
 *       (no further chaining on second doubles).</li>
 * </ul>
 *
 * <h2>Score</h2>
 * Returns 1.0 if {@code playerPerspective} wins, 0.0 if any other player wins, or
 * {@link WinProbability#computeBaselineWinProb} if the turn limit is reached without
 * a winner.
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} — safe for concurrent use from multiple threads.
 */
public final class MctsRollout {

    /** Maximum number of turns before falling back to the softmax heuristic. */
    public static final int MAX_TURNS = 200;

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    private MctsRollout() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs a uniform-random rollout from {@code startState} until the game ends or
     * {@link #MAX_TURNS} is reached.
     *
     * @param startState      game state at the leaf node (will be deep-copied internally)
     * @param startSupply     supply tracker matching startState (immutable; cloned internally)
     * @param startingPlayer  the player whose turn it is at the start of the rollout
     * @param playerPerspective the player for whom we compute the score (root's playerIndex)
     * @return score in [0, 1] from playerPerspective's point of view
     */
    public static double simulate(GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
        GameState state   = startState.copy();
        SupplyTracker supply = startSupply; // SupplyTracker is immutable (withPurchase returns new)
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount = 0;

        while (turnCount < MAX_TURNS) {
            // ---- Dice count ----
            boolean hasBahnhof = state.getPlayers()[activePlayer].hasProject("bahnhof");
            boolean twoDice = hasBahnhof && rng.nextBoolean();

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7);
                int d2 = rng.nextInt(1, 7);
                roll    = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: keep or reroll once ----
            if (state.getPlayers()[activePlayer].hasProject("funkturm")) {
                if (rng.nextBoolean()) {
                    // Reroll
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7);
                        int d2 = rng.nextInt(1, 7);
                        roll    = d1 + d2;
                        doubles = (d1 == d2);
                    } else {
                        roll    = rng.nextInt(1, 7);
                        doubles = false;
                    }
                }
                // else: keep current roll
            }

            // ---- Apply roll ----
            int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
            for (int i = 0; i < n; i++) {
                int newCoins = state.getPlayers()[i].getCoins() + deltas[i];
                state.getPlayers()[i].setCoins(Math.max(0, newCoins));
            }

            // ---- Bürohaus: on roll 6, pick uniformly from all valid swaps + no-swap ----
            if (state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
                supply = applyBürohausRandom(state, supply, activePlayer, rng);
            }

            // ---- Purchase ----
            supply = applyPurchaseRandom(state, supply, activePlayer, rng);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && doubles) {
                // Bonus turn: same player acts again (no further chaining if bonus doubles)
                supply = playBonusTurn(state, supply, activePlayer, playerPerspective, rng);
                // Win check after bonus turn
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            // ---- Advance to next player ----
            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        // Turn limit reached — use softmax heuristic
        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Plays a Freizeitpark bonus turn for the same player.
     * Uses the same uniform-random policy as the main loop, but with {@code isBonusTurn=true}
     * so no further Freizeitpark chaining occurs even on doubles.
     */
    private static SupplyTracker playBonusTurn(GameState state, SupplyTracker supply,
                                                int activePlayer, int playerPerspective,
                                                ThreadLocalRandom rng) {
        boolean hasBahnhof = state.getPlayers()[activePlayer].hasProject("bahnhof");
        boolean twoDice    = hasBahnhof && rng.nextBoolean();

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        if (state.getPlayers()[activePlayer].hasProject("funkturm") && rng.nextBoolean()) {
            roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
        }

        int n = state.getPlayers().length;
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }

        if (state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
            supply = applyBürohausRandom(state, supply, activePlayer, rng);
        }

        supply = applyPurchaseRandom(state, supply, activePlayer, rng);
        return supply;
    }

    /**
     * Enumerates all valid Bürohaus swap pairs (non-landmark, non-purple) plus no-swap,
     * picks uniformly at random, and applies the chosen swap to {@code state} in-place.
     *
     * @return updated supply (unchanged, since swaps don't affect supply counts)
     */
    private static SupplyTracker applyBürohausRandom(GameState state, SupplyTracker supply,
                                                      int activePlayer,
                                                      ThreadLocalRandom rng) {
        Player active = state.getPlayers()[activePlayer];
        int n = state.getPlayers().length;

        // Build list of (ownCard, oppPlayerIdx, oppCard) triples
        List<int[]> swapOptions = new ArrayList<>(); // [ownListIdx, oppPlayerIdx, oppListIdx]
        List<Project> ownEligible = new ArrayList<>();
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                ownEligible.add(p);
            }
        }
        for (int oppIdx = 0; oppIdx < n; oppIdx++) {
            if (oppIdx == activePlayer) continue;
            Player opp = state.getPlayers()[oppIdx];
            for (int ci = 0; ci < opp.getOwned_projects().size(); ci++) {
                Project oppCard = opp.getOwned_projects().get(ci);
                if (!oppCard.isIs_grossprojekt() && !"lila".equals(oppCard.getColor())) {
                    for (int oi = 0; oi < ownEligible.size(); oi++) {
                        swapOptions.add(new int[]{oi, oppIdx, ci});
                    }
                }
            }
        }

        // Total choices: 1 (no-swap) + swapOptions.size()
        int totalChoices = 1 + swapOptions.size();
        int choice = rng.nextInt(totalChoices);
        if (choice == 0) return supply; // no-swap

        int[] pair = swapOptions.get(choice - 1);
        Project ownCard = ownEligible.get(pair[0]);
        int oppPlayerIdx = pair[1];
        Project oppCard = state.getPlayers()[oppPlayerIdx].getOwned_projects().get(pair[2]);

        active.getOwned_projects().remove(ownCard);
        state.getPlayers()[oppPlayerIdx].getOwned_projects().remove(oppCard);
        active.getOwned_projects().add(oppCard);
        state.getPlayers()[oppPlayerIdx].getOwned_projects().add(ownCard);

        return supply; // supply unchanged by swap
    }

    /**
     * Builds the list of affordable purchase options (non-landmark + landmark + save),
     * picks one uniformly at random, and applies the purchase to {@code state} in-place.
     *
     * @return updated supply tracker (decremented for non-landmark purchases)
     */
    private static SupplyTracker applyPurchaseRandom(GameState state, SupplyTracker supply,
                                                      int activePlayer,
                                                      ThreadLocalRandom rng) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        List<Object[]> options = new ArrayList<>(); // [Project, isLandmark]

        // Save option always available
        options.add(new Object[]{RankEntry.WAIT_SENTINEL, false});

        // Non-landmark cards from unbuilt pool
        for (Project p : state.getUnbuilt_projects()) {
            if (supply.canPurchase(p.getId()) && coins >= p.getCost()) {
                options.add(new Object[]{p, false});
            }
        }

        // Landmarks (no supply limit)
        for (String lmId : LANDMARK_IDS) {
            if (!active.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && coins >= lm.getCost()) {
                    options.add(new Object[]{lm, true});
                }
            }
        }

        // Pick uniformly
        Object[] chosen = options.get(rng.nextInt(options.size()));
        Project card = (Project) chosen[0];
        boolean isLandmark = (boolean) chosen[1];

        if (card == RankEntry.WAIT_SENTINEL) return supply; // save: no-op

        active.setCoins(active.getCoins() - card.getCost());
        active.getOwned_projects().add(card);
        if (!isLandmark) {
            supply = supply.withPurchase(card.getId());
        }

        return supply;
    }
}
