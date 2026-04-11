package server;

import h2h.GameLog;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved Player-vs-AI game, stored in {@code data/pvai-games.json}.
 *
 * <p>Reuses {@link GameLog} (and its {@link h2h.TurnLog} entries) so the frontend
 * can replay it with the existing {@code H2hGameReplay} component.
 *
 * <p>Seat convention: seat indices match the original game seats.
 * {@code playerNames[humanPlayerIndex]} = human name; {@code playerNames[1-humanPlayerIndex]} = engine id.
 */
public final class PvAiGameRecord {

    /** Unique identifier for this saved game. */
    public final String id;

    /** ISO-8601 timestamp when the game was saved. */
    public final String date;

    /**
     * Display names for each player seat: [humanName, engineId] or [engineId, humanName]
     * depending on humanPlayerIndex.
     */
    public final String[] playerNames;

    /** Which seat the human occupies (0 or 1). */
    public final int humanPlayerIndex;

    /** Engine identifier used by the AI (e.g. "mcts-v1"). */
    public final String engineId;

    /** Index of the winning player (0 or 1). */
    public final int winnerIndex;

    /** Total turns played. */
    public final int totalTurns;

    /** Coins at game end, indexed by seat. */
    public final int[] finalCoins;

    /** Landmark count at game end, indexed by seat. */
    public final int[] landmarkCounts;

    /** Full turn log with luck analysis and WR tracking. */
    public final GameLog gameLog;

    /** Per-player total luck (sum of rollLuck across all turns). */
    public final double[] totalLuck;

    public PvAiGameRecord(String humanName, String engineId, int humanPlayerIndex,
                          GameLog gameLog) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.date = Instant.now().toString();
        this.engineId = engineId;
        this.humanPlayerIndex = humanPlayerIndex;
        this.winnerIndex = gameLog.winnerIndex;
        this.totalTurns = gameLog.totalTurns;
        this.finalCoins = gameLog.finalCoins;
        this.landmarkCounts = gameLog.landmarkCounts;
        this.gameLog = gameLog;

        int n = 2;
        this.playerNames = new String[n];
        this.playerNames[humanPlayerIndex] = humanName;
        this.playerNames[1 - humanPlayerIndex] = engineId;

        // Sum luck per player seat
        double[] luck = new double[n];
        for (h2h.TurnLog t : gameLog.turns) {
            if (t.rollLuck != null) luck[t.playerIndex] += t.rollLuck;
        }
        this.totalLuck = luck;
    }
}
