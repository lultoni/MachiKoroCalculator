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
     * Win rates adjusted for luck using per-game weighted scoring.
     *
     * <p>Each game's contribution depends on the winner's luck:
     * <ul>
     *   <li>Loss → 0.0 (always)</li>
     *   <li>Win with luck ≥ -5% → 1.0 (no bonus for neutral/lucky wins)</li>
     *   <li>Win with luck &lt; -5% → 1.0 + bonus (reward for outperforming bad luck)</li>
     * </ul>
     *
     * <p>Bonus uses a power curve: {@code (((-luck - 0.05) / 0.95) ^ 1.3)},
     * giving accelerating rewards — e.g. -30% luck → +0.24, -50% → +0.50, -100% → +1.00.
     * Final values normalized so all players sum to 1.0 (zero-sum property).
     *
     * <p>Equals {@code winRates} when luck was not computed.
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

        // Luck aggregation: per-engine total luck (unchanged — used for display)
        this.totalLuck = luckPerEngine;

        // Per-game luck-weighted win rates: wins against bad luck score > 1.0
        this.luckAdjustedWinRates = new double[n];
        boolean hasLuckData = false;
        for (double l : luckPerEngine) { if (l != 0.0) { hasLuckData = true; break; } }

        if (hasLuckData && gameCount > 0) {
            double[] weightedScores = new double[n];
            for (GameLog log : gameLogs) {
                boolean swapped = hasSeatSwap && log.gameIndex >= swapPoint;
                // Compute per-engine luck for this game
                double[] gameLuck = new double[n];
                for (TurnLog turn : log.turns) {
                    if (turn.rollLuck != null && turn.playerIndex >= 0 && turn.playerIndex < n) {
                        int engineIdx = swapped ? (1 - turn.playerIndex) : turn.playerIndex;
                        gameLuck[engineIdx] += turn.rollLuck;
                    }
                }
                // Score: winner gets 1.0 + bonus(luck), loser gets 0.0
                // Note: winnerIndex is already in engine-seat space (remapped by MatchRunner)
                if (log.winnerIndex >= 0 && log.winnerIndex < n) {
                    weightedScores[log.winnerIndex] += 1.0 + luckWinBonus(gameLuck[log.winnerIndex]);
                }
            }
            for (int i = 0; i < n; i++) {
                luckAdjustedWinRates[i] = weightedScores[i] / gameCount;
            }
            // Normalize so rates sum to 1.0 (preserves zero-sum property)
            double sum = 0;
            for (double r : luckAdjustedWinRates) sum += r;
            if (sum > 0) {
                for (int i = 0; i < n; i++) {
                    luckAdjustedWinRates[i] /= sum;
                }
            }
        } else {
            // No luck data — fall back to raw win rates
            System.arraycopy(winRates, 0, luckAdjustedWinRates, 0, n);
        }
    }

    /**
     * Bonus score for winning a game despite bad luck.
     *
     * <p>Power curve with a dead zone: no bonus until luck &lt; -5%,
     * then accelerating bonus up to +1.0 at -100% luck.
     *
     * @param gameLuck total luck for the winning engine in this game (negative = unlucky)
     * @return bonus in [0, 1]: 0 for neutral/lucky wins, up to 1.0 for extremely unlucky wins
     */
    static double luckWinBonus(double gameLuck) {
        double threshold = 0.05;
        if (gameLuck >= -threshold) return 0.0;
        double raw = (-gameLuck - threshold) / (1.0 - threshold);
        return Math.min(1.0, Math.pow(raw, 1.3));
    }
}
