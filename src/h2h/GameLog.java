package h2h;

import java.util.List;

/**
 * Log of a single H2H game, recording each turn and the outcome.
 */
public final class GameLog {

    public final int gameIndex;
    public int winnerIndex = -1;
    public int totalTurns;
    public boolean timeoutWin;
    public final List<TurnLog> turns = new java.util.ArrayList<>();
    public int[] finalCoins;
    public int[] landmarkCounts;

    public GameLog(int gameIndex) {
        this.gameIndex = gameIndex;
    }
}
