package h2h;

import iface.EngineOrchestrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a round-robin tournament: every engine plays every other engine once,
 * with mid-match seat swapping for fairness.
 *
 * <p>Generates N×(N-1)/2 unordered pairs. Each pair runs as a single match with
 * {@link MatchConfig#seatSwap()} enabled, so the first half of games use one seat
 * ordering and the second half swap P1/P2.
 *
 * <p>Matches run sequentially (each match already uses ForkJoinPool for game-level
 * parallelism). Individual match results are saved to {@link H2hResultStore}.
 */
public final class TournamentRunner {

    private final EngineOrchestrator orchestrator;

    public TournamentRunner(EngineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Callback for tournament progress reporting.
     */
    public interface ProgressListener {
        void onMatchStarted(int matchIndex, int totalMatches, String engineA, String engineB);
        void onMatchCompleted(int matchIndex, int totalMatches, MatchResult result);
    }

    /**
     * Runs the full round-robin tournament.
     *
     * @param engineIds          engine registry IDs to participate
     * @param gamesPerMatchup    games per match (split across seat swap)
     * @param maxTurnsPerGame    turn limit per game
     * @param iterationsOverride MCTS iterations override (0 = use registry default)
     * @param seatSwap           whether to swap seats mid-match
     * @param listener           optional progress listener
     * @return aggregated tournament result
     */
    public TournamentResult runTournament(List<String> engineIds, int gamesPerMatchup,
                                          int maxTurnsPerGame, int iterationsOverride,
                                          boolean seatSwap, ProgressListener listener) {
        long startMs = System.currentTimeMillis();

        // Generate all unordered pairs
        List<int[]> pairs = generatePairs(engineIds.size());
        int totalMatches = pairs.size();

        MatchRunner runner = new MatchRunner(orchestrator);
        H2hResultStore store = new H2hResultStore();
        List<MatchResult> results = new ArrayList<>();

        for (int m = 0; m < totalMatches; m++) {
            int[] pair = pairs.get(m);
            String idA = engineIds.get(pair[0]);
            String idB = engineIds.get(pair[1]);

            if (listener != null) {
                listener.onMatchStarted(m, totalMatches, idA, idB);
            }

            MatchConfig config = new MatchConfig(
                    new String[]{idA, idB}, gamesPerMatchup, maxTurnsPerGame,
                    iterationsOverride, seatSwap);

            MatchResult result = runner.runMatch(config, null);
            store.save(result);
            results.add(result);

            if (listener != null) {
                listener.onMatchCompleted(m, totalMatches, result);
            }
        }

        long totalMs = System.currentTimeMillis() - startMs;
        return new TournamentResult(engineIds, results, totalMs);
    }

    /**
     * Generates all N×(N-1)/2 unordered pairs from N engines.
     */
    public static List<int[]> generatePairs(int n) {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairs.add(new int[]{i, j});
            }
        }
        return pairs;
    }
}
