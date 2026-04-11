package h2h;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        /** True if option scores are [0,1] win probabilities; false if composite scores. */
        public final boolean scoresAreWinRates;

        public DecisionDetail(List<DecisionOption> options, int iterations, double confidence) {
            this(options, iterations, confidence, true);
        }

        public DecisionDetail(List<DecisionOption> options, int iterations, double confidence,
                              boolean scoresAreWinRates) {
            this.options = options;
            this.iterations = iterations;
            this.confidence = confidence;
            this.scoresAreWinRates = scoresAreWinRates;
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
    public final boolean scoreIsWinRate;   // true = purchaseWinRate is [0,1] win probability; false = composite score
    public final int coinsAfterPurchase;
    public final String bürohausSwap;      // "ownCardId→oppCardId" or null
    public final boolean bürohausActivated; // true if Bürohaus was triggered (swap or decline)
    public final boolean funkturmRerolled;
    public final long evaluateTimeMs;
    public final DecisionDetail decisionDetail; // null for legacy logs without detail
    public final Double rollLuck;      // luck value (positive = lucky), null when luck not computed
    public final Double wrBeforeRoll;  // E[WR] across all possible rolls (pre-roll baseline)
    public final Double wrAfterRoll;   // WR after actual roll income applied
    /**
     * WR for each possible roll in ascending order.
     * For 1d6: 6 values, index 0 = roll 1 ... index 5 = roll 6.
     * For 2d6: 11 values, index 0 = roll 2 ... index 10 = roll 12.
     * Null when luck not computed.
     */
    public final double[] wrPerRoll;
    /** Per-card income breakdown: cardId → int[playerCount] deltas. Null when not computed. */
    public final Map<String, int[]> cardIncome;
    /** Expected per-round EV of the purchased card at purchase time. Null when not computed or save. */
    public final Double purchasedCardExpectedEv;

    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   boolean scoreIsWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean bürohausActivated,
                   boolean funkturmRerolled, long evaluateTimeMs,
                   DecisionDetail decisionDetail,
                   Double rollLuck, Double wrBeforeRoll, Double wrAfterRoll, double[] wrPerRoll,
                   Map<String, int[]> cardIncome, Double purchasedCardExpectedEv) {
        this.playerIndex = playerIndex;
        this.diceCount = diceCount;
        this.roll = roll;
        this.isDoubles = isDoubles;
        this.coinDeltas = coinDeltas;
        this.purchasedCardId = purchasedCardId;
        this.purchaseWinRate = purchaseWinRate;
        this.scoreIsWinRate = scoreIsWinRate;
        this.coinsAfterPurchase = coinsAfterPurchase;
        this.bürohausSwap = bürohausSwap;
        this.bürohausActivated = bürohausActivated;
        this.funkturmRerolled = funkturmRerolled;
        this.evaluateTimeMs = evaluateTimeMs;
        this.decisionDetail = decisionDetail;
        this.rollLuck = rollLuck;
        this.wrBeforeRoll = wrBeforeRoll;
        this.wrAfterRoll = wrAfterRoll;
        this.wrPerRoll = wrPerRoll;
        this.cardIncome = cardIncome;
        this.purchasedCardExpectedEv = purchasedCardExpectedEv;
    }

    /** Constructor without wrPerRoll (defaults to null). */
    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   boolean scoreIsWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean bürohausActivated,
                   boolean funkturmRerolled, long evaluateTimeMs,
                   DecisionDetail decisionDetail,
                   Double rollLuck, Double wrBeforeRoll, Double wrAfterRoll,
                   Map<String, int[]> cardIncome, Double purchasedCardExpectedEv) {
        this(playerIndex, diceCount, roll, isDoubles, coinDeltas, purchasedCardId,
                purchaseWinRate, scoreIsWinRate, coinsAfterPurchase, bürohausSwap,
                bürohausActivated, funkturmRerolled, evaluateTimeMs, decisionDetail,
                rollLuck, wrBeforeRoll, wrAfterRoll, null, cardIncome, purchasedCardExpectedEv);
    }

    /** Constructor without card income fields (defaults to null). */
    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   boolean scoreIsWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean bürohausActivated,
                   boolean funkturmRerolled, long evaluateTimeMs,
                   DecisionDetail decisionDetail,
                   Double rollLuck, Double wrBeforeRoll, Double wrAfterRoll) {
        this(playerIndex, diceCount, roll, isDoubles, coinDeltas, purchasedCardId,
                purchaseWinRate, scoreIsWinRate, coinsAfterPurchase, bürohausSwap,
                bürohausActivated, funkturmRerolled, evaluateTimeMs, decisionDetail,
                rollLuck, wrBeforeRoll, wrAfterRoll, null, null, null);
    }

    /** Legacy constructor without luck fields (defaults to null). */
    public TurnLog(int playerIndex, int diceCount, int roll, boolean isDoubles,
                   int[] coinDeltas, String purchasedCardId, double purchaseWinRate,
                   boolean scoreIsWinRate,
                   int coinsAfterPurchase, String bürohausSwap, boolean bürohausActivated,
                   boolean funkturmRerolled, long evaluateTimeMs,
                   DecisionDetail decisionDetail) {
        this(playerIndex, diceCount, roll, isDoubles, coinDeltas, purchasedCardId,
                purchaseWinRate, scoreIsWinRate, coinsAfterPurchase, bürohausSwap,
                bürohausActivated, funkturmRerolled, evaluateTimeMs, decisionDetail,
                null, null, null);
    }

    /** Returns a new TurnLog with playerIndex and coinDeltas swapped (for seat-swap remapping). */
    TurnLog remapSeats() {
        int[] swappedDeltas = (coinDeltas != null && coinDeltas.length == 2)
                ? new int[]{coinDeltas[1], coinDeltas[0]} : coinDeltas;
        // Swap per-card deltas (index 0↔1 in each int[])
        Map<String, int[]> swappedCardIncome = null;
        if (cardIncome != null) {
            swappedCardIncome = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : cardIncome.entrySet()) {
                int[] orig = e.getValue();
                swappedCardIncome.put(e.getKey(),
                        (orig != null && orig.length == 2) ? new int[]{orig[1], orig[0]} : orig);
            }
        }
        return new TurnLog(
                1 - playerIndex, diceCount, roll, isDoubles,
                swappedDeltas, purchasedCardId, purchaseWinRate, scoreIsWinRate,
                coinsAfterPurchase, bürohausSwap, bürohausActivated, funkturmRerolled,
                evaluateTimeMs, decisionDetail,
                rollLuck, wrBeforeRoll, wrAfterRoll, wrPerRoll,
                swappedCardIncome, purchasedCardExpectedEv
        );
    }
}
