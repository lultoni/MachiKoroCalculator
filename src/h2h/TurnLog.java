package h2h;

/**
 * Log of a single turn in an H2H game.
 *
 * <p>All indices are in <b>engine-seat space</b> (0 = engine A, 1 = engine B)
 * after seat-swap remapping in {@link MatchRunner#playGame}. The frontend can
 * use {@code playerIndex} and {@code coinDeltas} directly without swap awareness.
 */
public final class TurnLog {

    public final int playerIndex;
    public final int diceCount;
    public final int roll;
    public final boolean isDoubles;
    public final int[] coinDeltas;
    public final String purchasedCardId;   // null = save
    public final double purchaseWinRate;
    public final int coinsAfterPurchase;
    public final String bürohausSwap;      // "cardA→cardB" or null
    public final boolean funkturmRerolled;
    public final long evaluateTimeMs;

    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean funkturmRerolled,
                   long evaluateTimeMs) {
        this.playerIndex = playerIndex;
        this.diceCount = diceCount;
        this.roll = roll;
        this.isDoubles = isDoubles;
        this.coinDeltas = coinDeltas;
        this.purchasedCardId = purchasedCardId;
        this.purchaseWinRate = purchaseWinRate;
        this.coinsAfterPurchase = coinsAfterPurchase;
        this.bürohausSwap = bürohausSwap;
        this.funkturmRerolled = funkturmRerolled;
        this.evaluateTimeMs = evaluateTimeMs;
    }

    /** Returns a new TurnLog with playerIndex and coinDeltas swapped (for seat-swap remapping). */
    TurnLog remapSeats() {
        int[] swappedDeltas = (coinDeltas != null && coinDeltas.length == 2)
                ? new int[]{coinDeltas[1], coinDeltas[0]} : coinDeltas;
        return new TurnLog(
                1 - playerIndex, diceCount, roll, isDoubles,
                swappedDeltas, purchasedCardId, purchaseWinRate,
                coinsAfterPurchase, bürohausSwap, funkturmRerolled,
                evaluateTimeMs
        );
    }
}
