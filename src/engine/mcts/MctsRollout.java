package engine.mcts;

import calcs.WinProbability;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

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
        SupplyTracker.MutableSupplyTracker supply = startSupply.toMutable();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount = 0;
        int[] deltas = new int[n];

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
            RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
            for (int i = 0; i < n; i++) {
                int newCoins = state.getPlayers()[i].getCoins() + deltas[i];
                state.getPlayers()[i].setCoins(Math.max(0, newCoins));
            }

            // ---- Bürohaus: on roll 6, pick uniformly from all valid swaps + no-swap ----
            if (state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
                applyBürohausRandomPackage(state, activePlayer, rng);
            }

            // ---- Purchase ----
            applyPurchaseRandomPackage(state, supply, activePlayer, rng);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && doubles) {
                // Bonus turn: same player acts again (no further chaining if bonus doubles)
                playBonusTurnPackage(state, supply, activePlayer, playerPerspective, rng, deltas);
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
    static void playBonusTurnPackage(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                int activePlayer, int playerPerspective,
                                                ThreadLocalRandom rng, int[] deltas) {
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
        RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }

        if (state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
            applyBürohausRandomPackage(state, activePlayer, rng);
        }

        applyPurchaseRandomPackage(state, supply, activePlayer, rng);
    }

    /**
     * Counts all valid Bürohaus swap pairs (non-landmark, non-purple) plus no-swap,
     * picks uniformly at random via count-then-index (no list allocation), and applies
     * the chosen swap to {@code state} in-place.
     */
    static void applyBürohausRandomPackage(GameState state,
                                                      int activePlayer,
                                                      ThreadLocalRandom rng) {
        Player active = state.getPlayers()[activePlayer];
        int n = state.getPlayers().length;

        // Count eligible own cards
        int ownCount = 0;
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) ownCount++;
        }
        if (ownCount == 0) return; // no swap possible

        // Count eligible opponent cards
        int oppCardCount = 0;
        for (int oppIdx = 0; oppIdx < n; oppIdx++) {
            if (oppIdx == activePlayer) continue;
            for (Project p : state.getPlayers()[oppIdx].getOwned_projects()) {
                if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) oppCardCount++;
            }
        }
        if (oppCardCount == 0) return; // no opponent card to swap with

        int totalSwaps = ownCount * oppCardCount;
        int totalChoices = 1 + totalSwaps; // 0 = no-swap
        int choice = rng.nextInt(totalChoices);
        if (choice == 0) return; // no-swap

        // Decode choice: swap index (choice - 1) maps to (ownIdx, oppCard)
        int swapIdx = choice - 1;
        int ownTarget = swapIdx / oppCardCount;
        int oppTarget = swapIdx % oppCardCount;

        // Find own card at index ownTarget
        Project ownCard = null;
        int oi = 0;
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                if (oi == ownTarget) { ownCard = p; break; }
                oi++;
            }
        }

        // Find opponent card at flat index oppTarget
        Project oppCard = null;
        int oppPlayerIdx = -1;
        int ci = 0;
        outer:
        for (int oppIdx = 0; oppIdx < n; oppIdx++) {
            if (oppIdx == activePlayer) continue;
            for (Project p : state.getPlayers()[oppIdx].getOwned_projects()) {
                if (!p.isIs_grossprojekt() && !"lila".equals(p.getColor())) {
                    if (ci == oppTarget) { oppCard = p; oppPlayerIdx = oppIdx; break outer; }
                    ci++;
                }
            }
        }

        // Execute swap
        if (ownCard != null && oppCard != null) {
            active.getOwned_projects().remove(ownCard);
            state.getPlayers()[oppPlayerIdx].getOwned_projects().remove(oppCard);
            active.addProject(oppCard);
            state.getPlayers()[oppPlayerIdx].addProject(ownCard);
        }
    }

    /**
     * Counts affordable purchase options (non-landmark + landmark + save), picks one
     * uniformly at random via count-then-index (no list allocation), and applies the
     * purchase to {@code state} in-place.
     */
    static void applyPurchaseRandomPackage(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                                      int activePlayer,
                                                      ThreadLocalRandom rng) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // Count eligible options: 1 (save) + non-landmarks + landmarks
        int count = 1; // save is always option 0

        for (Project p : state.getUnbuilt_projects()) {
            if (supply.canPurchase(p.getId()) && coins >= p.getCost()) {
                if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
                count++;
            }
        }

        int landmarkStart = count; // index where landmarks begin
        for (String lmId : LANDMARK_IDS) {
            if (!active.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && coins >= lm.getCost()) {
                    count++;
                }
            }
        }

        // Pick uniformly
        int choice = rng.nextInt(count);
        if (choice == 0) return; // save

        // Walk again to find the chosen option
        if (choice < landmarkStart) {
            // Non-landmark card at index (choice - 1)
            int idx = 1;
            for (Project p : state.getUnbuilt_projects()) {
                if (supply.canPurchase(p.getId()) && coins >= p.getCost()) {
                    if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
                    if (idx == choice) {
                        active.setCoins(coins - p.getCost());
                        active.addProject(p);
                        supply.purchase(p.getId());
                        return;
                    }
                    idx++;
                }
            }
        } else {
            // Landmark at index (choice - landmarkStart)
            int idx = landmarkStart;
            for (String lmId : LANDMARK_IDS) {
                if (!active.hasProject(lmId)) {
                    Project lm = ProjectLoader.getProject(lmId).orElse(null);
                    if (lm != null && coins >= lm.getCost()) {
                        if (idx == choice) {
                            active.setCoins(coins - lm.getCost());
                            active.addProject(lm);
                            return;
                        }
                        idx++;
                    }
                }
            }
        }
    }
}
