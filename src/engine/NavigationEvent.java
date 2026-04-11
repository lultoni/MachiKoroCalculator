package engine;

import core.GameState;

/**
 * A lock-in event that tells a {@link ContinuousWorker} to navigate its internal state
 * to a new game position.
 *
 * <p>Lock-in events occur when a player's full turn is committed (human clicks "Buy",
 * AI's turn is applied). They are distinct from preview events (die face selection,
 * hover) which do NOT trigger navigation.
 *
 * <h2>MCTS navigation fields</h2>
 * The fields {@code diceCount} through {@code purchasedCardId} describe the exact path
 * through the MCTS tree to reach the new root. Non-MCTS workers ignore these and only
 * use {@code newState} and {@code playerIndex} to rebuild their internal state.
 *
 * <h2>Force reset</h2>
 * Set {@code forceReset = true} when the state change cannot be expressed as a tree
 * navigation (e.g. undo, session load). Workers must discard existing state and
 * reinitialize from {@code newState}.
 */
public record NavigationEvent(
        GameState newState,
        int playerIndex,

        // MCTS tree navigation fields (null = unknown or not applicable)
        Integer diceCount,          // 1 or 2
        Integer rollTotal,          // sum of dice (1–12)
        Boolean isDoubles,          // true if both dice showed the same face
        Boolean funkturmKeep,       // true = keep roll, false = reroll; null = no Funkturm
        Integer rerollTotal,        // reroll result after Funkturm; null = no reroll
        Boolean rerollIsDoubles,    // doubles flag for the reroll; null = no reroll
        String bürohausOwnCardId,   // card swapped away; null = no swap
        String bürohausOppCardId,   // card received from opponent; null = no swap
        Integer bürohausOppPlayer,  // opponent seat index for swap; null = no swap
        String purchasedCardId,     // card bought (by id); null = save / no purchase

        boolean forceReset          // true = skip navigation, rebuild from newState
) {

    /** Convenience factory: create a force-reset event from a new state. */
    public static NavigationEvent forceReset(GameState newState, int playerIndex) {
        return new NavigationEvent(
                newState, playerIndex,
                null, null, null, null, null, null,
                null, null, null, null,
                true);
    }
}
