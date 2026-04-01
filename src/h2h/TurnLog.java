package h2h;

/**
 * Log of a single turn in an H2H game.
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
}
