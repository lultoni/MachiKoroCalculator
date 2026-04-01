package h2h;

import calcs.RankEntry;
import calcs.WinProbability;
import core.*;
import engine.EngineConfig;
import engine.SimulationEngine;
import engine.TurnPlan;
import engine.mcts.SupplyTracker;
import iface.EngineOrchestrator;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a head-to-head match: multiple complete games between engine-controlled players.
 *
 * <p>Each player's strategic decisions (dice count, Funkturm reroll, Bürohaus swap,
 * purchase) come from the engine's real MCTS tree search via
 * {@link SimulationEngine#evaluateFullTurn}. The only random element is the actual
 * dice roll.
 *
 * <p>Games are independent and run in parallel using a ForkJoinPool.
 *
 * <h2>Game loop</h2>
 * <ol>
 *   <li>Engine evaluates full turn → TurnPlan with diceCount</li>
 *   <li>Roll actual dice</li>
 *   <li>Navigate tree: TurnPlan.navigateRoll(roll)</li>
 *   <li>If Funkturm decision = reroll: roll again, navigateReroll(newRoll)</li>
 *   <li>Apply final roll income to state</li>
 *   <li>If Bürohaus swap: apply to state</li>
 *   <li>Apply purchase to state</li>
 *   <li>Win check; if turn limit → softmax for winner</li>
 *   <li>Freizeitpark bonus turn handling</li>
 *   <li>Advance to next player</li>
 * </ol>
 */
public final class MatchRunner {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    private final EngineOrchestrator orchestrator;

    public MatchRunner(EngineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Callback for match progress reporting.
     */
    public interface ProgressListener {
        void onGameCompleted(int gameIndex, GameLog log);
    }

    /**
     * Runs the full match: {@code config.gameCount()} games in parallel.
     *
     * @param config   match configuration
     * @param listener optional progress listener (may be null)
     * @return match result with all game logs
     */
    public MatchResult runMatch(MatchConfig config, ProgressListener listener) {
        long startMs = System.currentTimeMillis();

        // Resolve engines from registry
        SimulationEngine[] engines = resolveEngines(config);
        EngineConfig evalConfig = config.toEngineConfig();

        // Run games in parallel
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        List<Future<GameLog>> futures = new ArrayList<>();

        AtomicInteger completed = new AtomicInteger(0);

        for (int g = 0; g < config.gameCount(); g++) {
            final int gameIdx = g;
            futures.add(pool.submit(() -> {
                GameLog log = playGame(gameIdx, config, engines, evalConfig);
                int done = completed.incrementAndGet();
                if (listener != null) {
                    listener.onGameCompleted(gameIdx, log);
                }
                return log;
            }));
        }

        List<GameLog> logs = new ArrayList<>();
        for (Future<GameLog> f : futures) {
            try {
                logs.add(f.get());
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Game execution failed", e);
            }
        }
        pool.shutdown();

        // Sort logs by game index for deterministic ordering
        logs.sort((a, b) -> Integer.compare(a.gameIndex, b.gameIndex));

        long totalMs = System.currentTimeMillis() - startMs;
        return new MatchResult(config, logs, totalMs);
    }

    // -------------------------------------------------------------------------
    // Engine resolution
    // -------------------------------------------------------------------------

    private SimulationEngine[] resolveEngines(MatchConfig config) {
        SimulationEngine[] engines = new SimulationEngine[config.playerCount()];
        for (int i = 0; i < config.playerCount(); i++) {
            String registryId = config.engineIds()[i];
            EngineRegistryEntry entry = EngineRegistry.findById(registryId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown engine registry id: " + registryId));
            SimulationEngine engine = orchestrator.getEngine(entry.engineClass());
            if (engine == null) {
                throw new IllegalStateException(
                        "Engine class not registered: " + entry.engineClass()
                        + " (from registry id: " + registryId + ")");
            }
            engines[i] = engine;
        }
        return engines;
    }

    // -------------------------------------------------------------------------
    // Single game
    // -------------------------------------------------------------------------

    private GameLog playGame(int gameIndex, MatchConfig config,
                             SimulationEngine[] engines, EngineConfig evalConfig) {
        GameLog log = new GameLog(gameIndex);
        GameState state = GameState.initial(config.playerCount());
        int n = config.playerCount();
        int activePlayer = 0;
        int turnCount = 0;

        while (turnCount < config.maxTurnsPerGame()) {
            TurnLog turnLog = playTurn(state, activePlayer, engines[activePlayer], evalConfig);
            log.turns.add(turnLog);
            turnCount++;

            // Win check
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                log.winnerIndex = activePlayer;
                break;
            }

            // Freizeitpark bonus turn
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && turnLog.isDoubles) {
                TurnLog bonusTurnLog = playTurn(state, activePlayer, engines[activePlayer], evalConfig);
                log.turns.add(bonusTurnLog);
                turnCount++;

                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    log.winnerIndex = activePlayer;
                    break;
                }
            }

            activePlayer = (activePlayer + 1) % n;
        }

        // Turn limit reached — softmax winner
        if (log.winnerIndex < 0) {
            log.timeoutWin = true;
            log.winnerIndex = softmaxWinner(state);
        }

        log.totalTurns = turnCount;
        log.finalCoins = new int[n];
        log.landmarkCounts = new int[n];
        for (int i = 0; i < n; i++) {
            log.finalCoins[i] = state.getPlayers()[i].getCoins();
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (p.isIs_grossprojekt()) log.landmarkCounts[i]++;
            }
        }

        return log;
    }

    // -------------------------------------------------------------------------
    // Single turn
    // -------------------------------------------------------------------------

    private TurnLog playTurn(GameState state, int activePlayer,
                             SimulationEngine engine, EngineConfig evalConfig) {
        // 1. Engine evaluates full turn
        TurnPlan plan = engine.evaluateFullTurn(state, activePlayer, evalConfig);

        // 2. Roll dice
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int diceCount = plan.diceCount;
        int d1, d2, roll;
        boolean doubles = false;
        if (diceCount == 2) {
            d1 = rng.nextInt(1, 7);
            d2 = rng.nextInt(1, 7);
            roll = d1 + d2;
            doubles = (d1 == d2);
        } else {
            roll = rng.nextInt(1, 7);
            d1 = roll;
            d2 = 0;
        }

        // 3. Navigate tree with actual roll
        plan.navigateRoll(roll);

        // 4. Funkturm reroll decision
        boolean funkturmRerolled = false;
        if (plan.hasFunkturmChoice && !plan.funkturmKeep) {
            funkturmRerolled = true;
            // Reroll
            if (diceCount == 2) {
                d1 = rng.nextInt(1, 7);
                d2 = rng.nextInt(1, 7);
                roll = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
                d1 = roll;
                d2 = 0;
                doubles = false;
            }
            plan.navigateReroll(roll);
        }

        // 5. Apply roll income
        int n = state.getPlayers().length;
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < n; i++) {
            int newCoins = state.getPlayers()[i].getCoins() + deltas[i];
            state.getPlayers()[i].setCoins(Math.max(0, newCoins));
        }

        // 6. Bürohaus swap
        String bürohausSwap = null;
        if (plan.hasBürohausChoice && plan.bürohausOwnCard != null
                && plan.bürohausOppPlayer >= 0 && plan.bürohausOppCard != null) {
            try {
                BürohausLogic.executeSwap(state, activePlayer,
                        plan.bürohausOwnCard, plan.bürohausOppPlayer, plan.bürohausOppCard);
                bürohausSwap = plan.bürohausOwnCard.getId() + "→" + plan.bürohausOppCard.getId();
            } catch (IllegalArgumentException e) {
                // Swap not valid in current state (tree divergence) — skip
            }
        }

        // 7. Apply purchase
        String purchasedCardId = null;
        Project purchase = plan.purchase;
        if (purchase != null && purchase != RankEntry.WAIT_SENTINEL) {
            int cost = purchase.getCost();
            Player active = state.getPlayers()[activePlayer];
            if (active.getCoins() >= cost) {
                active.setCoins(active.getCoins() - cost);
                active.getOwned_projects().add(purchase);
                purchasedCardId = purchase.getId();

                // Update unbuilt_projects if supply exhausted
                if (!purchase.isIs_grossprojekt()) {
                    updateSupply(state, purchase, n);
                }
            }
        }

        int coinsAfterPurchase = state.getPlayers()[activePlayer].getCoins();

        return new TurnLog(
                activePlayer, diceCount, roll, doubles,
                deltas, purchasedCardId, plan.purchaseWinRate,
                coinsAfterPurchase, bürohausSwap, funkturmRerolled,
                plan.computeTimeMs
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Updates the unbuilt_projects list when a non-landmark card is purchased.
     * If all SUPPLY_PER_CARD copies are now owned, removes the card type from the list.
     */
    private void updateSupply(GameState state, Project purchased, int playerCount) {
        int ownedCount = 0;
        for (int i = 0; i < playerCount; i++) {
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (p.getId().equals(purchased.getId())) ownedCount++;
            }
        }
        if (ownedCount >= GameState.SUPPLY_PER_CARD) {
            state.getUnbuilt_projects().removeIf(p -> p.getId().equals(purchased.getId()));
        }
    }

    /**
     * Determines the winner using softmax win probabilities when the turn limit is reached.
     */
    private int softmaxWinner(GameState state) {
        int n = state.getPlayers().length;
        double bestProb = -1;
        int bestIdx = 0;
        for (int i = 0; i < n; i++) {
            double prob = WinProbability.computeBaselineWinProb(state, i);
            if (prob > bestProb) {
                bestProb = prob;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
