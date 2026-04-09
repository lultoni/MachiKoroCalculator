package engine.mcts;

import core.BitState;

/**
 * Functional interface for BitState-native MCTS rollout strategies.
 *
 * <p>Replaces the legacy {@code RolloutFn} interface that accepted {@code (GameState, SupplyTracker)}.
 * All rollout implementations now operate directly on {@link BitState} + {@code int[] supply},
 * eliminating the double conversion at the tree→rollout boundary.
 *
 * <p>Implementations must be thread-safe (called concurrently from multiple MCTS iterations)
 * and must not mutate the provided {@code bs} or {@code supply} — they should copy them first.
 */
@FunctionalInterface
public interface BitRolloutFn {

    /**
     * Simulates one full game from the given leaf state.
     *
     * @param bs                bitwise game state at the leaf (must be copied before mutation)
     * @param supply            supply array indexed by normal card index (must be copied before mutation)
     * @param startingPlayer    the player whose turn it is at the start of the rollout
     * @param playerPerspective the player for whom we compute the score (root's playerIndex)
     * @return score in [0, 1] from playerPerspective's point of view
     */
    double simulate(BitState bs, int[] supply, int startingPlayer, int playerPerspective);
}
