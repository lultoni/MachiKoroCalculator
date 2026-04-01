package h2h;

import iface.EngineOrchestrator;

import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p>Completed results are accessible at any time via {@link #getCompletedResults()}
 * for partial reporting on interruption.
 */
public final class TournamentRunner {

    private final EngineOrchestrator orchestrator;
    private volatile List<String> currentEngineIds = List.of();
    private final List<MatchResult> completedResults =
            Collections.synchronizedList(new ArrayList<>());

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
     * Returns results collected so far (thread-safe).
     * Useful for building partial results on Ctrl+C interruption.
     */
    public List<MatchResult> getCompletedResults() {
        synchronized (completedResults) {
            return new ArrayList<>(completedResults);
        }
    }

    /**
     * Returns the engine IDs for the current tournament (set at start).
     */
    public List<String> getCurrentEngineIds() {
        return currentEngineIds;
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
        this.currentEngineIds = new ArrayList<>(engineIds);
        completedResults.clear();

        // Generate all unordered pairs
        List<int[]> pairs = generatePairs(engineIds.size());
        int totalMatches = pairs.size();

        MatchRunner runner = new MatchRunner(orchestrator);
        H2hResultStore store = new H2hResultStore();

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
            completedResults.add(result);

            if (listener != null) {
                listener.onMatchCompleted(m, totalMatches, result);
            }
        }

        long totalMs = System.currentTimeMillis() - startMs;
        return new TournamentResult(engineIds, getCompletedResults(), totalMs);
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
