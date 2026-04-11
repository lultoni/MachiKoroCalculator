package server;

/**
 * Response object for the AI's pre-computed turn in Player-vs-AI mode.
 *
 * <p>Returned by {@code GET /api/session/pvai/ai-turn} after the AI's think phase.
 * Contains everything needed to animate the AI's turn step-by-step on the frontend.
 */
public final class AiTurnResult {

    /** Number of dice the AI chose (1 or 2). */
    public final int diceCount;

    /** Total of the actual dice roll. */
    public final int rollTotal;

    /** Whether the roll is doubles (applies Freizeitpark bonus if active). */
    public final boolean isDoubles;

    /**
     * Coin deltas for all players after income resolution.
     * Indexed by player seat (0 = player 0, 1 = player 1, ...).
     */
    public final int[] coinDeltas;

    /** Whether the AI kept its Funkturm roll (null if AI does not own Funkturm). */
    public final Boolean funkturmKeep;

    /** Total of the Funkturm reroll (null if no reroll). */
    public final Integer rerollTotal;

    /** Whether the reroll is doubles (null if no reroll). */
    public final Boolean rerollIsDoubles;

    /** Card ID the AI swapped away via Bürohaus (null if no Bürohaus swap). */
    public final String bürohausOwnCardId;

    /** Card ID the AI acquired via Bürohaus swap (null if no swap). */
    public final String bürohausOppCardId;

    /** Opponent player index in Bürohaus swap (null if no swap). */
    public final Integer bürohausOppPlayer;

    /** Card ID the AI purchased (null if AI saved). */
    public final String purchasedCardId;

    /** Total iterations the engine accumulated before returning this result. */
    public final int iterationsUsed;

    /** Wall-clock milliseconds the engine thought before this result was retrieved. */
    public final long thinkTimeMs;

    public AiTurnResult(
            int diceCount, int rollTotal, boolean isDoubles,
            int[] coinDeltas,
            Boolean funkturmKeep, Integer rerollTotal, Boolean rerollIsDoubles,
            String bürohausOwnCardId, String bürohausOppCardId, Integer bürohausOppPlayer,
            String purchasedCardId,
            int iterationsUsed, long thinkTimeMs) {
        this.diceCount         = diceCount;
        this.rollTotal         = rollTotal;
        this.isDoubles         = isDoubles;
        this.coinDeltas        = coinDeltas;
        this.funkturmKeep      = funkturmKeep;
        this.rerollTotal       = rerollTotal;
        this.rerollIsDoubles   = rerollIsDoubles;
        this.bürohausOwnCardId = bürohausOwnCardId;
        this.bürohausOppCardId = bürohausOppCardId;
        this.bürohausOppPlayer = bürohausOppPlayer;
        this.purchasedCardId   = purchasedCardId;
        this.iterationsUsed    = iterationsUsed;
        this.thinkTimeMs       = thinkTimeMs;
    }
}
