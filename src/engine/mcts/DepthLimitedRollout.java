package engine.mcts;

import calcs.WinProbability;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Depth-limited rollout for Variant D: stops after {@code maxDepth} turns and scores
 * the state using {@link WinProbability#computeBaselineWinProb} instead of simulating
 * to game completion.
 *
 * <p>Uses the same uniform-random policy as {@link MctsRollout} for all in-rollout
 * decisions (dice, Funkturm, Bürohaus, purchase).
 *
 * <h2>Hypothesis</h2>
 * Shorter rollouts with a good terminal heuristic outperform full-game rollouts for
 * the same iteration budget by visiting more tree nodes.
 */
public final class DepthLimitedRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    private DepthLimitedRollout() {}

    /**
     * Creates a {@link RolloutFn} that uses the depth-limited policy.
     *
     * @param maxDepth maximum number of turns to simulate before applying the heuristic
     */
    public static RolloutFn withMaxDepth(int maxDepth) {
        return (state, supply, startingPlayer, playerPerspective) ->
                simulate(state, supply, startingPlayer, playerPerspective, maxDepth);
    }

    // -------------------------------------------------------------------------
    // Core simulation
    // -------------------------------------------------------------------------

    static double simulate(GameState startState, SupplyTracker startSupply,
                           int startingPlayer, int playerPerspective, int maxDepth) {
        GameState state      = startState.copy();
        SupplyTracker supply = startSupply;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n            = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount    = 0;
        int depthLimit   = Math.max(1, maxDepth);

        while (turnCount < depthLimit) {
            // ---- Dice count (uniform random) ----
            boolean hasBahnhof = state.getPlayers()[activePlayer].hasProject("bahnhof");
            boolean twoDice    = hasBahnhof && rng.nextBoolean();

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7); int d2 = rng.nextInt(1, 7);
                roll = d1 + d2; doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: uniform random ----
            if (state.getPlayers()[activePlayer].hasProject("funkturm") && rng.nextBoolean()) {
                if (twoDice) {
                    int d1 = rng.nextInt(1,7); int d2 = rng.nextInt(1,7);
                    roll = d1+d2; doubles = (d1==d2);
                } else {
                    roll = rng.nextInt(1,7); doubles = false;
                }
            }

            // ---- Apply roll ----
            int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus: uniform random ----
            if (state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
                MctsRollout.applyBürohausRandomPackage(state, supply, activePlayer, rng);
            }

            // ---- Purchase: uniform random ----
            supply = MctsRollout.applyPurchaseRandomPackage(state, supply, activePlayer, rng);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (state.getPlayers()[activePlayer].hasProject("freizeitpark") && doubles) {
                // Simplified bonus turn: just apply one more action (no depth counting)
                MctsRollout.playBonusTurnPackage(state, supply, activePlayer, playerPerspective, rng);
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        // Depth limit reached — use heuristic
        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }
}
