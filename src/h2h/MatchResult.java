package h2h;

import java.time.Instant;
import java.util.List;

/**
 * Result of a completed H2H match (multiple games between the same engine pairing).
 */
public final class MatchResult {

    public final String id;
    public final String date;
    public final MatchConfig config;
    public final int[] wins;
    public final double[] winRates;
    public double avgGameLength;
    public double avgEvalTimeMs;
    /** Per-engine average evaluation time in ms (indexed by player seat). */
    public double[] avgEvalTimeMsPerEngine;
    /** Index of the shortest game (by totalTurns). -1 if no games. */
    public int shortestGameIndex;
    /** Index of the longest game (by totalTurns). -1 if no games. */
    public int longestGameIndex;
    /** Turn count of the shortest game. */
    public int shortestGameTurns;
    /** Turn count of the longest game. */
    public int longestGameTurns;
    public List<GameLog> gameLogs;
    public long totalTimeMs;
    /**
     * Per-engine total luck across all games (sum of rollLuck per turn, mapped to engine seat).
     * Positive = engine was lucky overall. Zero when luck computation was disabled.
     */
    public double[] totalLuck;
    /**
     * Win rates adjusted for luck: {@code winRates[i] - totalLuck[i] / gameCount}, clamped to [0,1].
     * Equals {@code winRates} when luck was not computed.
     */
    public double[] luckAdjustedWinRates;

    public MatchResult(MatchConfig config, List<GameLog> gameLogs, long totalTimeMs) {
        this.id = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.date = Instant.now().toString();
        this.config = config;
        this.gameLogs = gameLogs;
        this.totalTimeMs = totalTimeMs;

        int n = config.playerCount();
        this.wins = new int[n];
        this.winRates = new double[n];

        int totalTurns = 0;
        double totalEvalMs = 0;
        int totalEvalCount = 0;
        double[] evalMsPerEngine = new double[n];
        int[] evalCountPerEngine = new int[n];
        double[] luckPerEngine = new double[n];
        int shortIdx = -1, longIdx = -1;
        int shortTurns = Integer.MAX_VALUE, longTurns = Integer.MIN_VALUE;
        boolean hasSeatSwap = config.seatSwap() && n == 2;
        int swapPoint = config.gameCount() / 2;

        for (GameLog log : gameLogs) {
            if (log.winnerIndex >= 0 && log.winnerIndex < n) {
                wins[log.winnerIndex]++;
            }
            totalTurns += log.totalTurns;
            // Determine if this game had swapped seats
            boolean swapped = hasSeatSwap && log.gameIndex >= swapPoint;
            for (TurnLog turn : log.turns) {
                totalEvalMs += turn.evaluateTimeMs;
                totalEvalCount++;
                if (turn.playerIndex >= 0 && turn.playerIndex < n) {
                    // Map seat index to engine index, accounting for seat swap
                    int engineIdx = swapped ? (1 - turn.playerIndex) : turn.playerIndex;
                    evalMsPerEngine[engineIdx] += turn.evaluateTimeMs;
                    evalCountPerEngine[engineIdx]++;
                    if (turn.rollLuck != null) {
                        luckPerEngine[engineIdx] += turn.rollLuck;
                    }
                }
            }
            if (log.totalTurns < shortTurns) {
                shortTurns = log.totalTurns;
                shortIdx = log.gameIndex;
            }
            if (log.totalTurns > longTurns) {
                longTurns = log.totalTurns;
                longIdx = log.gameIndex;
            }
        }

        int gameCount = gameLogs.size();
        for (int i = 0; i < n; i++) {
            winRates[i] = gameCount > 0 ? (double) wins[i] / gameCount : 0.0;
        }
        avgGameLength = gameCount > 0 ? (double) totalTurns / gameCount : 0.0;
        avgEvalTimeMs = totalEvalCount > 0 ? totalEvalMs / totalEvalCount : 0.0;

        avgEvalTimeMsPerEngine = new double[n];
        for (int i = 0; i < n; i++) {
            avgEvalTimeMsPerEngine[i] = evalCountPerEngine[i] > 0
                    ? evalMsPerEngine[i] / evalCountPerEngine[i] : 0.0;
        }
        shortestGameIndex = shortIdx;
        longestGameIndex = longIdx;
        shortestGameTurns = gameCount > 0 ? shortTurns : 0;
        longestGameTurns = gameCount > 0 ? longTurns : 0;

        // Luck aggregation: per-engine total luck and luck-adjusted win rates
        this.totalLuck = luckPerEngine;
        this.luckAdjustedWinRates = new double[n];
        for (int i = 0; i < n; i++) {
            luckAdjustedWinRates[i] = Math.max(0.0, Math.min(1.0,
                    winRates[i] - (gameCount > 0 ? totalLuck[i] / gameCount : 0.0)));
        }
    }
}
