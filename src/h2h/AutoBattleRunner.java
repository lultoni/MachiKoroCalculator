package h2h;

import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated engine battle system that prioritizes matchups based on rating uncertainty.
 *
 * <p>Selects engine pairs with the highest combined Glicko-2 RD (rating deviation),
 * avoiding recently played pairs and self-play. Runs matches sequentially, saving
 * each result to the store. Continues until stopped or max rounds reached.
 *
 * <p>Supports endless mode ({@code maxRounds <= 0}): runs indefinitely until
 * {@link #requestStop()} is called. Stop is effective mid-match — the current match
 * is cancelled and partial results (completed games) are saved.
 *
 * <h2>Pair selection algorithm</h2>
 * <ol>
 *   <li>Compute current Glicko-2 ratings from all match history</li>
 *   <li>Generate all unique engine pairs from the configured engine set</li>
 *   <li>Score each pair: sum of both engines' RD (higher = more uncertain)</li>
 *   <li>Penalty: subtract 50 for each time this pair has been played in this session</li>
 *   <li>Pick the pair with the highest score</li>
 * </ol>
 */
public final class AutoBattleRunner {

    private final EngineOrchestrator orchestrator;
    private final H2hResultStore store;

    // Config
    private final int gamesPerMatch;
    private final int maxTurnsPerGame;
    private final int effectiveMaxRounds;
    private final List<String> engineIds;

    // State
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile int roundsCompleted;
    private volatile int gamesCompletedInMatch;
    private volatile int totalGamesPlayed;
    private volatile long startTimeMs;
    private volatile String currentMatchup;
    private volatile String error;
    private final Map<String, Integer> pairPlayCount = new HashMap<>();

    /**
     * @param maxRounds maximum number of rounds; {@code <= 0} for endless mode
     */
    public AutoBattleRunner(EngineOrchestrator orchestrator, H2hResultStore store,
                            int gamesPerMatch, int maxTurnsPerGame, int maxRounds,
                            List<String> engineIds) {
        this.orchestrator = orchestrator;
        this.store = store;
        this.gamesPerMatch = gamesPerMatch;
        this.maxTurnsPerGame = maxTurnsPerGame;
        this.effectiveMaxRounds = maxRounds <= 0 ? Integer.MAX_VALUE : maxRounds;
        this.engineIds = engineIds;
    }

    /** Start the auto battle loop. Call from a background thread. */
    public void run() {
        running = true;
        stopRequested = false;
        roundsCompleted = 0;
        gamesCompletedInMatch = 0;
        totalGamesPlayed = 0;
        startTimeMs = System.currentTimeMillis();
        error = null;
        currentMatchup = null;
        pairPlayCount.clear();

        try {
            MatchRunner runner = new MatchRunner(orchestrator);

            while (!stopRequested && roundsCompleted < effectiveMaxRounds) {
                // 1. Compute current ratings
                List<MatchResult> allResults = store.loadAll();
                Map<String, Glicko2Rating> ratings = RatingCalculator.computeRatings(allResults);

                // 2. Select best pair
                String[] pair = selectPair(ratings);
                if (pair == null) {
                    error = "No valid pair found";
                    break;
                }

                currentMatchup = pair[0] + " vs " + pair[1];
                String pairKey = pairKey(pair[0], pair[1]);
                pairPlayCount.merge(pairKey, 1, Integer::sum);

                // 3. Run match (with mid-match cancellation support)
                gamesCompletedInMatch = 0;
                AtomicInteger matchGamesCounter = new AtomicInteger(0);
                MatchConfig config = new MatchConfig(
                        pair, gamesPerMatch, maxTurnsPerGame, 0, true);
                MatchResult result = runner.runMatch(config, (gameIdx, log) -> {
                    gamesCompletedInMatch = matchGamesCounter.incrementAndGet();
                }, () -> stopRequested);

                // Save result if at least one game completed
                if (!result.gameLogs.isEmpty()) {
                    store.save(result);
                    totalGamesPlayed += result.gameLogs.size();
                }

                roundsCompleted++;
            }
        } catch (Exception e) {
            error = e.getMessage();
        } finally {
            running = false;
            currentMatchup = null;
        }
    }

    /** Request a stop. Effective mid-match — cancels remaining games in the current match. */
    public void requestStop() {
        stopRequested = true;
    }

    // --- Accessors ---

    public boolean isRunning() { return running; }
    public int getRoundsCompleted() { return roundsCompleted; }
    public int getMaxRounds() { return effectiveMaxRounds; }
    public boolean isEndless() { return effectiveMaxRounds == Integer.MAX_VALUE; }
    public int getGamesPerMatch() { return gamesPerMatch; }
    public int getGamesCompletedInMatch() { return gamesCompletedInMatch; }
    public int getTotalGamesPlayed() { return totalGamesPlayed; }
    public long getStartTimeMs() { return startTimeMs; }
    public String getCurrentMatchup() { return currentMatchup; }
    public String getError() { return error; }

    // --- Pair selection ---

    private String[] selectPair(Map<String, Glicko2Rating> ratings) {
        String[] bestPair = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < engineIds.size(); i++) {
            for (int j = i + 1; j < engineIds.size(); j++) {
                String idA = engineIds.get(i);
                String idB = engineIds.get(j);

                Glicko2Rating rA = ratings.getOrDefault(idA, Glicko2Rating.initial());
                Glicko2Rating rB = ratings.getOrDefault(idB, Glicko2Rating.initial());

                // Score = combined uncertainty
                double score = rA.rd + rB.rd;

                // Penalty for recently played pairs
                String key = pairKey(idA, idB);
                int playCount = pairPlayCount.getOrDefault(key, 0);
                score -= playCount * 50;

                if (score > bestScore) {
                    bestScore = score;
                    bestPair = new String[]{idA, idB};
                }
            }
        }

        return bestPair;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Create an AutoBattleRunner with all registered engines. */
    public static AutoBattleRunner createWithAllEngines(
            EngineOrchestrator orchestrator, H2hResultStore store,
            int gamesPerMatch, int maxTurnsPerGame, int maxRounds) {
        List<String> ids = new ArrayList<>();
        for (EngineRegistryEntry e : EngineRegistry.getAll()) {
            ids.add(e.id());
        }
        return new AutoBattleRunner(orchestrator, store,
                gamesPerMatch, maxTurnsPerGame, maxRounds, ids);
    }

    /** Create an AutoBattleRunner with engines from a specific tier. */
    public static AutoBattleRunner createWithTier(
            EngineOrchestrator orchestrator, H2hResultStore store,
            String tier, int gamesPerMatch, int maxTurnsPerGame, int maxRounds) {
        List<String> ids = new ArrayList<>();
        for (EngineRegistryEntry e : EngineRegistry.getByTier(tier)) {
            ids.add(e.id());
        }
        return new AutoBattleRunner(orchestrator, store,
                gamesPerMatch, maxTurnsPerGame, maxRounds, ids);
    }
}
