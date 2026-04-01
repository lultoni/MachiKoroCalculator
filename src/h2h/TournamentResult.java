package h2h;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a completed round-robin tournament.
 *
 * <p>Aggregates individual {@link MatchResult} data into a leaderboard
 * (ranked by overall win rate) and a head-to-head matrix.
 */
public final class TournamentResult {

    public final String id;
    public final String date;
    public final List<String> engineIds;
    public final List<LeaderboardEntry> leaderboard;
    /** {@code h2hMatrix[i][j]} = win rate of engine i against engine j (seat-neutral). */
    public final double[][] h2hMatrix;
    public final List<String> matchResultIds;
    public long totalTimeMs;

    /**
     * Builds the tournament result from individual match results.
     *
     * @param engineIds      ordered list of participating engine IDs
     * @param matchResults   all completed match results (one per unordered pair)
     * @param totalTimeMs    wall-clock time for the entire tournament
     */
    public TournamentResult(List<String> engineIds, List<MatchResult> matchResults,
                            long totalTimeMs) {
        this.id = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.date = java.time.Instant.now().toString();
        this.engineIds = new ArrayList<>(engineIds);
        this.totalTimeMs = totalTimeMs;
        this.matchResultIds = matchResults.stream().map(r -> r.id).toList();

        int n = engineIds.size();
        this.h2hMatrix = new double[n][n];
        int[] totalWins = new int[n];
        int[] totalGames = new int[n];

        for (MatchResult result : matchResults) {
            // Find engine indices
            String idA = result.config.engineIds()[0];
            String idB = result.config.engineIds()[1];
            int a = engineIds.indexOf(idA);
            int b = engineIds.indexOf(idB);
            if (a < 0 || b < 0) continue;

            int gamesPlayed = result.gameLogs.size();

            // wins[0] = wins for engineIds[0] in this match (with seat swap already handled)
            h2hMatrix[a][b] = gamesPlayed > 0 ? (double) result.wins[0] / gamesPlayed : 0.5;
            h2hMatrix[b][a] = gamesPlayed > 0 ? (double) result.wins[1] / gamesPlayed : 0.5;

            totalWins[a] += result.wins[0];
            totalWins[b] += result.wins[1];
            totalGames[a] += gamesPlayed;
            totalGames[b] += gamesPlayed;
        }

        // Build leaderboard sorted by win rate descending
        List<LeaderboardEntry> lb = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int losses = totalGames[i] - totalWins[i];
            double winRate = totalGames[i] > 0 ? (double) totalWins[i] / totalGames[i] : 0.0;
            lb.add(new LeaderboardEntry(engineIds.get(i), totalWins[i], losses, totalGames[i], winRate));
        }
        lb.sort((a, b) -> Double.compare(b.winRate, a.winRate));
        this.leaderboard = lb;
    }

    /**
     * A single entry in the tournament leaderboard.
     */
    public static final class LeaderboardEntry {
        public final String engineId;
        public final int totalWins;
        public final int totalLosses;
        public final int totalGames;
        public final double winRate;

        public LeaderboardEntry(String engineId, int totalWins, int totalLosses,
                                int totalGames, double winRate) {
            this.engineId = engineId;
            this.totalWins = totalWins;
            this.totalLosses = totalLosses;
            this.totalGames = totalGames;
            this.winRate = winRate;
        }
    }
}
