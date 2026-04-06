package h2h;

import java.util.List;

/**
 * Log of a single H2H game, recording each turn and the outcome.
 *
 * <p><b>Index convention:</b> As stored on disk, {@code finalCoins}, {@code landmarkCounts},
 * and {@code TurnLog.playerIndex}/{@code coinDeltas} are in <b>game-seat space</b>
 * (seat 0/1 within that specific game). The {@code winnerIndex} is already remapped
 * to <b>engine-seat space</b> by {@link MatchRunner}. Use {@link #remapToEngineSeats}
 * before sending to the frontend so all indices are consistent (engine-relative).
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

    /**
     * Returns a new GameLog with all seat-indexed data remapped to engine-seat space.
     * Call this for games where seats were swapped (gameIndex ≥ gameCount/2 with seatSwap=true)
     * before sending to the frontend.
     *
     * <p>Remaps: {@code finalCoins[0]↔[1]}, {@code landmarkCounts[0]↔[1]},
     * and each turn's {@code playerIndex} and {@code coinDeltas[0]↔[1]}.
     * {@code winnerIndex} is already in engine-seat space and is kept as-is.
     */
    public GameLog remapToEngineSeats() {
        GameLog remapped = new GameLog(this.gameIndex);
        remapped.winnerIndex = this.winnerIndex;     // already engine-relative
        remapped.totalTurns = this.totalTurns;
        remapped.timeoutWin = this.timeoutWin;
        if (this.finalCoins != null && this.finalCoins.length == 2) {
            remapped.finalCoins = new int[]{this.finalCoins[1], this.finalCoins[0]};
        } else {
            remapped.finalCoins = this.finalCoins;
        }
        if (this.landmarkCounts != null && this.landmarkCounts.length == 2) {
            remapped.landmarkCounts = new int[]{this.landmarkCounts[1], this.landmarkCounts[0]};
        } else {
            remapped.landmarkCounts = this.landmarkCounts;
        }
        for (TurnLog t : this.turns) {
            remapped.turns.add(t.remapSeats());
        }
        return remapped;
    }
}
