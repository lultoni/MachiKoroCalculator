package core;

import java.util.Arrays;

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
     * Constructs a turn record without doubles information (backwards-compatible).
     */
    public TurnRecord(int playerIndex, int roll, Project bought) {
        this(playerIndex, roll, bought, false, null, null, null);
    }

    /**
     * Constructs a turn record with explicit doubles flag.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles) {
        this(playerIndex, roll, bought, isDoubles, null, null, null);
    }

    /**
     * Constructs a full turn record with doubles flag and per-player coin deltas.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles, int[] coinDeltas) {
        this(playerIndex, roll, bought, isDoubles, coinDeltas, null, null);
    }

    /**
     * Constructs a complete turn record including an optional bürohaus card swap.
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles,
                      int[] coinDeltas, Project swappedAway, Project swappedIn) {
        if (playerIndex < 0) throw new IllegalArgumentException("playerIndex must be >= 0");
        if (roll < 1 || roll > 12) throw new IllegalArgumentException("roll must be 1–12, got: " + roll);
        this.playerIndex = playerIndex;
        this.roll = roll;
        this.bought = bought;
        this.isDoubles = isDoubles;
        this.coinDeltas = coinDeltas != null ? coinDeltas.clone() : null;
        this.swappedAway = swappedAway;
        this.swappedIn = swappedIn;
    }

    @Override
    public String toString() {
        String buyStr = (bought != null) ? "bought=" + bought.getId() : "no purchase";
        String doublesStr = isDoubles ? ", DOUBLES" : "";
        String deltaStr = coinDeltas != null ? ", deltas=" + Arrays.toString(coinDeltas) : "";
        String swapStr = (swappedAway != null) ? ", swap=" + swappedAway.getId() + "→" + swappedIn.getId() : "";
        return "Turn{player=" + playerIndex + ", roll=" + roll + ", " + buyStr + doublesStr + deltaStr + swapStr + "}";
    }
}
