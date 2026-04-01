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
    public final List<GameLog> gameLogs;
    public long totalTimeMs;

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

        for (GameLog log : gameLogs) {
            if (log.winnerIndex >= 0 && log.winnerIndex < n) {
                wins[log.winnerIndex]++;
            }
            totalTurns += log.totalTurns;
            for (TurnLog turn : log.turns) {
                totalEvalMs += turn.evaluateTimeMs;
                totalEvalCount++;
            }
        }

        int gameCount = gameLogs.size();
        for (int i = 0; i < n; i++) {
            winRates[i] = gameCount > 0 ? (double) wins[i] / gameCount : 0.0;
        }
        avgGameLength = gameCount > 0 ? (double) totalTurns / gameCount : 0.0;
        avgEvalTimeMs = totalEvalCount > 0 ? totalEvalMs / totalEvalCount : 0.0;
    }
}
