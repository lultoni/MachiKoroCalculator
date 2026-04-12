package core;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable record of what happened in a single turn.
 *
 * <p>A turn consists of:
 * <ol>
 *   <li>A dice roll (the total shown on the dice)</li>
 *   <li>An optional project purchase (null = player saved or could not afford anything)</li>
 * </ol>
 *
 * <p>When a player owns both Bahnhof (enabling 2-dice rolls) and Freizeitpark
 * (granting a bonus turn on doubles), {@link #isDoubles} must be set to {@code true}
 * when the two dice showed the same face. {@link GameSession#nextPlayerIndex()} uses
 * this flag to grant the same player an extra turn.
 *
 * <p>{@link #coinDeltas} stores the net coin change for every player caused by this roll
 * (red card payments + blue/green/purple income, in official turn order). The array is
 * indexed by player index and may be {@code null} for records loaded from old save files
 * that predate this field.
 */
public class TurnRecord {

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Compact representation of one ranked purchase option evaluated by the AI engine. */
    public record DecisionEntry(String cardId, double score) {}

    /**
     * Snapshot of the engine's purchase decision for this turn (AI turns only, null for human turns).
     * Carries the top-N ranked options, engine iteration count, and score-type metadata needed to
     * reconstruct a {@link h2h.TurnLog.DecisionDetail} when the game is saved.
     */
    public record AiDecisionSnapshot(
            List<DecisionEntry> entries,
            int iterationsUsed,
            boolean scoresAreWinRates
    ) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Index of the player who took this turn (0-based). */
    public final int playerIndex;

    /** Dice roll total for this turn. */
    public final int roll;

    /**
     * Project bought this turn, or {@code null} if the player did not buy anything.
     * Großprojekte (gelb landmarks) are bought here too.
     */
    public final Project bought;

    /**
     * True when two dice were rolled and both showed the same face (Pasch / doubles).
     * Relevant only when the active player owns both Bahnhof and Freizeitpark:
     * in that case the player gets a bonus second turn immediately after this one.
     * For 1-die rolls or when those landmarks are not both owned, this field has no effect.
     */
    public final boolean isDoubles;

    /**
     * Net coin change per player caused by this roll's income/payment step, indexed by
     * player index. Positive = gained coins, negative = paid coins. Does NOT include the
     * purchase cost (that is tracked separately by {@link #bought}).
     *
     * <p>May be {@code null} for records loaded from save files written before this field
     * was introduced. UI code must null-check before using.
     */
    public final int[] coinDeltas;

    /**
     * The card the active player gave away during a bürohaus swap this turn, or {@code null}
     * if no swap was executed. Always null unless {@link #swappedIn} is also non-null.
     */
    public final Project swappedAway;

    /**
     * The card the active player received during a bürohaus swap this turn, or {@code null}
     * if no swap was executed. Always null unless {@link #swappedAway} is also non-null.
     */
    public final Project swappedIn;

    /**
     * Index of the opponent involved in a bürohaus swap, or -1 if no swap.
     */
    public final int swapOppPlayerIndex;

    /**
     * Number of dice rolled (1 or 2). 2 only when the player owns Bahnhof and chose 2d6.
     * Defaults to 1 for backwards-compatible constructors.
     */
    public final int diceCount;

    /**
     * Wall-clock milliseconds the AI engine spent computing this turn.
     * 0 for human turns. Populated only for AI turns in PvAI mode.
     */
    public final long evaluateTimeMs;

    /**
     * Compact snapshot of the AI engine's purchase decision for this turn.
     * Null for human turns and any turn where no engine evaluation was performed.
     * Used when saving PvAI games to reconstruct the decision detail panel.
     */
    public final AiDecisionSnapshot aiDecision;

    /**
     * Constructs a turn record without doubles information (backwards-compatible).
     */
    public TurnRecord(int playerIndex, int roll, Project bought) {
        this(playerIndex, roll, bought, false, null, null, null, -1, 1, 0L, null);
    }

    /**
     * Constructs a turn record with explicit doubles flag.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles) {
        this(playerIndex, roll, bought, isDoubles, null, null, null, -1, 1, 0L, null);
    }

    /**
     * Constructs a full turn record with doubles flag and per-player coin deltas.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles, int[] coinDeltas) {
        this(playerIndex, roll, bought, isDoubles, coinDeltas, null, null, -1, 1, 0L, null);
    }

    /**
     * Constructs a complete turn record including an optional bürohaus card swap.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles,
                      int[] coinDeltas, Project swappedAway, Project swappedIn) {
        this(playerIndex, roll, bought, isDoubles, coinDeltas, swappedAway, swappedIn, -1, 1, 0L, null);
    }

    /**
     * Constructs a fully specified turn record with all fields (no AI decision).
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles,
                      int[] coinDeltas, Project swappedAway, Project swappedIn,
                      int swapOppPlayerIndex, int diceCount) {
        this(playerIndex, roll, bought, isDoubles, coinDeltas, swappedAway, swappedIn,
                swapOppPlayerIndex, diceCount, 0L, null);
    }

    /**
     * Constructs a fully specified turn record including AI think-time and decision snapshot.
     * Used for AI turns in PvAI mode.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles,
                      int[] coinDeltas, Project swappedAway, Project swappedIn,
                      int swapOppPlayerIndex, int diceCount,
                      long evaluateTimeMs, AiDecisionSnapshot aiDecision) {
        if (playerIndex < 0) throw new IllegalArgumentException("playerIndex must be >= 0");
        if (roll < 1 || roll > 12) throw new IllegalArgumentException("roll must be 1–12, got: " + roll);
        this.playerIndex = playerIndex;
        this.roll = roll;
        this.bought = bought;
        this.isDoubles = isDoubles;
        this.coinDeltas = coinDeltas != null ? coinDeltas.clone() : null;
        this.swappedAway = swappedAway;
        this.swappedIn = swappedIn;
        this.swapOppPlayerIndex = swapOppPlayerIndex;
        this.diceCount = diceCount;
        this.evaluateTimeMs = evaluateTimeMs;
        this.aiDecision = aiDecision;
    }

    @Override
    public String toString() {
        String buyStr = (bought != null) ? "bought=" + bought.getId() : "no purchase";
        String doublesStr = isDoubles ? ", DOUBLES" : "";
        String deltaStr = coinDeltas != null ? ", deltas=" + Arrays.toString(coinDeltas) : "";
        String swapStr = (swappedAway != null)
                ? ", swap=" + swappedAway.getId() + "→" + swappedIn.getId() + "(opp=" + swapOppPlayerIndex + ")"
                : "";
        String diceStr = diceCount == 2 ? ", 2d6" : "";
        String aiStr = evaluateTimeMs > 0 ? ", aiThink=" + evaluateTimeMs + "ms" : "";
        return "Turn{player=" + playerIndex + ", roll=" + roll + ", " + buyStr + doublesStr + deltaStr + swapStr + diceStr + aiStr + "}";
    }
}
