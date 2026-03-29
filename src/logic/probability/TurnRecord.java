package logic.probability;

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
     * Constructs a turn record without doubles information (backwards-compatible).
     *
     * @param playerIndex index of the active player (0-based)
     * @param roll        dice total (1–12)
     * @param bought      project purchased this turn, or null
     */
    public TurnRecord(int playerIndex, int roll, Project bought) {
        this(playerIndex, roll, bought, false);
    }

    /**
     * Constructs a turn record with explicit doubles flag.
     *
     * @param playerIndex index of the active player (0-based)
     * @param roll        dice total (1–12)
     * @param bought      project purchased this turn, or null
     * @param isDoubles   true if two dice were rolled and both showed the same face
     */
    public TurnRecord(int playerIndex, int roll, Project bought, boolean isDoubles) {
        if (playerIndex < 0) throw new IllegalArgumentException("playerIndex must be >= 0");
        if (roll < 1 || roll > 12) throw new IllegalArgumentException("roll must be 1–12, got: " + roll);
        this.playerIndex = playerIndex;
        this.roll = roll;
        this.bought = bought;
        this.isDoubles = isDoubles;
    }

    @Override
    public String toString() {
        String buyStr = (bought != null) ? "bought=" + bought.getId() : "no purchase";
        String doublesStr = isDoubles ? ", DOUBLES" : "";
        return "Turn{player=" + playerIndex + ", roll=" + roll + ", " + buyStr + doublesStr + "}";
    }
}
