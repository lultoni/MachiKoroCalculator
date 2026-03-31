package engine.mcts;

import core.GameState;

/**
 * Functional interface for MCTS rollout strategies.
 *
 * <p>An implementation simulates one full game starting from {@code state} and returns
 * a score in [0, 1] from {@code playerPerspective}'s point of view.
 *
 * <p>Implementations must be thread-safe (called concurrently from multiple MCTS iterations)
 * and must not mutate the provided {@code state} or {@code supply} — they should copy them first.
 */
@FunctionalInterface
public interface RolloutFn {

    /**
     * Simulates one full game from the given leaf state.
     *
     * @param state             game state at the leaf (must be copied before mutation)
     * @param supply            supply tracker matching state (immutable)
     * @param startingPlayer    the player whose turn it is at the start of the rollout
     * @param playerPerspective the player for whom we compute the score (root's playerIndex)
     * @return score in [0, 1] from playerPerspective's point of view
     */
    double simulate(GameState state, SupplyTracker supply,
                    int startingPlayer, int playerPerspective);
}
