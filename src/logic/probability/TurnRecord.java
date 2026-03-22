package logic.probability;

/**
 * Immutable record of what happened in a single turn.
 *
 * <p>A turn consists of:
 * <ol>
 *   <li>A dice roll (the total shown on the dice)</li>
 *   <li>An optional project purchase (null = player saved or could not afford anything)</li>
 * </ol>
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
     * @param playerIndex index of the active player (0-based)
     * @param roll        dice total (1–12)
     * @param bought      project purchased this turn, or null
     */
    public TurnRecord(int playerIndex, int roll, Project bought) {
        if (playerIndex < 0) throw new IllegalArgumentException("playerIndex must be >= 0");
        if (roll < 1 || roll > 12) throw new IllegalArgumentException("roll must be 1–12, got: " + roll);
        this.playerIndex = playerIndex;
        this.roll = roll;
        this.bought = bought;
    }

    @Override
    public String toString() {
        String buyStr = (bought != null) ? "bought=" + bought.getId() : "no purchase";
        return "Turn{player=" + playerIndex + ", roll=" + roll + ", " + buyStr + "}";
    }
}
