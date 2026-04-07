package h2h;

import java.util.List;

/**
 * Log of a single turn in an H2H game.
 *
 * <p>All indices are in <b>engine-seat space</b> (0 = engine A, 1 = engine B)
 * after seat-swap remapping in {@link MatchRunner#playGame}. The frontend can
 * use {@code playerIndex} and {@code coinDeltas} directly without swap awareness.
 */
public final class TurnLog {

    // -------------------------------------------------------------------------
    // DecisionOption — compact summary of one evaluated purchase candidate
    // -------------------------------------------------------------------------

    /**
     * A compact summary of a single purchase option evaluated by the engine.
     * Stored per turn for replay UI display. All fields are Gson-serializable.
     */
    public static final class DecisionOption {
        public final String cardId;     // project id or "_wait_" for save
        public final double score;      // engine's primary score
        public final boolean chosen;    // true for the option the engine picked

        public DecisionOption(String cardId, double score, boolean chosen) {
            this.cardId = cardId;
            this.score = score;
            this.chosen = chosen;
        }
    }

    // -------------------------------------------------------------------------
    // DecisionDetail — the "why" behind the purchase decision
    // -------------------------------------------------------------------------

    /**
     * Compact engine decision detail for replay display.
     * Contains the top-N evaluated alternatives and engine metadata.
     * Null when the engine did not produce evaluation data (defensive fallback turns).
     */
    public static final class DecisionDetail {
        /** Top-N options evaluated, sorted by score descending. Typically 3-5 entries. */
        public final List<DecisionOption> options;

        /** Number of iterations/rollouts the engine performed. */
        public final int iterations;

        /** Engine confidence in [0,1], or NaN if unavailable. */
        public final double confidence;

        public DecisionDetail(List<DecisionOption> options, int iterations, double confidence) {
            this.options = options;
            this.iterations = iterations;
            this.confidence = confidence;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    public final int playerIndex;
    public final int diceCount;
    public final int roll;
    public final boolean isDoubles;
    public final int[] coinDeltas;
    public final String purchasedCardId;   // null = save
    public final double purchaseWinRate;
    public final int coinsAfterPurchase;
    public final String bürohausSwap;      // "ownCardId→oppCardId" or null
    public final boolean bürohausActivated; // true if Bürohaus was triggered (swap or decline)
    public final boolean funkturmRerolled;
    public final long evaluateTimeMs;
    public final DecisionDetail decisionDetail; // null for legacy logs without detail

    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean bürohausActivated,
                   boolean funkturmRerolled, long evaluateTimeMs,
                   DecisionDetail decisionDetail) {
        this.playerIndex = playerIndex;
        this.diceCount = diceCount;
        this.roll = roll;
        this.isDoubles = isDoubles;
        this.coinDeltas = coinDeltas;
        this.purchasedCardId = purchasedCardId;
        this.purchaseWinRate = purchaseWinRate;
        this.coinsAfterPurchase = coinsAfterPurchase;
        this.bürohausSwap = bürohausSwap;
        this.bürohausActivated = bürohausActivated;
        this.funkturmRerolled = funkturmRerolled;
        this.evaluateTimeMs = evaluateTimeMs;
        this.decisionDetail = decisionDetail;
    }

    /** Returns a new TurnLog with playerIndex and coinDeltas swapped (for seat-swap remapping). */
    TurnLog remapSeats() {
        int[] swappedDeltas = (coinDeltas != null && coinDeltas.length == 2)
                ? new int[]{coinDeltas[1], coinDeltas[0]} : coinDeltas;
        return new TurnLog(
                1 - playerIndex, diceCount, roll, isDoubles,
                swappedDeltas, purchasedCardId, purchaseWinRate,
                coinsAfterPurchase, bürohausSwap, bürohausActivated, funkturmRerolled,
                evaluateTimeMs, decisionDetail
        );
    }
}
