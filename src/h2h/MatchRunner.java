package h2h;

import calcs.RankEntry;
import calcs.WinProbability;
import core.*;
import engine.EngineConfig;
import engine.EngineResult;
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
import java.util.function.BooleanSupplier;

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
 *   <li>Navigate tree: TurnPlan.navigateRoll(roll, doubles)</li>
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
     * Resolved engine instances and their per-seat configs.
     */
    private record ResolvedMatch(SimulationEngine[] engines, EngineConfig[] configs) {}

    /**
     * Runs the full match: {@code config.gameCount()} games in parallel.
     *
     * @param config   match configuration
     * @param listener optional progress listener (may be null)
     * @return match result with all game logs
     */
    public MatchResult runMatch(MatchConfig config, ProgressListener listener) {
        return runMatch(config, listener, null);
    }

    /**
     * Runs a match with optional mid-match cancellation support.
     *
     * <p>Games run in parallel via ForkJoinPool. After each game completes (in submission
     * order), the {@code shouldStop} predicate is checked. When it returns {@code true},
     * remaining futures are cancelled and the result is built from completed games only.
     *
     * <p>Stop latency is approximately one game duration (running games are not interrupted).
     *
     * @param config     match configuration
     * @param listener   optional progress listener (may be null)
     * @param shouldStop optional stop predicate checked between games (may be null)
     * @return match result (may contain fewer games than configured if stopped early)
     */
    public MatchResult runMatch(MatchConfig config, ProgressListener listener,
                                BooleanSupplier shouldStop) {
        long startMs = System.currentTimeMillis();

        // Resolve engines and per-seat configs from registry
        ResolvedMatch resolved = resolveMatch(config);

        // Run games in parallel
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        List<Future<GameLog>> futures = new ArrayList<>();

        AtomicInteger completed = new AtomicInteger(0);

        for (int g = 0; g < config.gameCount(); g++) {
            final int gameIdx = g;
            futures.add(pool.submit(() -> {
                GameLog log = playGame(gameIdx, config, resolved.engines, resolved.configs);
                int done = completed.incrementAndGet();
                if (listener != null) {
                    listener.onGameCompleted(gameIdx, log);
                }
                return log;
            }));
        }

        List<GameLog> logs = new ArrayList<>();
        boolean stopped = false;
        for (Future<GameLog> f : futures) {
            if (stopped) {
                f.cancel(false);
                continue;
            }
            try {
                logs.add(f.get());
                if (shouldStop != null && shouldStop.getAsBoolean()) {
                    stopped = true;
                }
            } catch (CancellationException e) {
                // Game was cancelled before starting, skip
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

    private ResolvedMatch resolveMatch(MatchConfig config) {
        SimulationEngine[] engines = new SimulationEngine[config.playerCount()];
        EngineConfig[] configs = new EngineConfig[config.playerCount()];
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
            configs[i] = config.buildSeatConfig(entry.config(), i);
        }
        return new ResolvedMatch(engines, configs);
    }

    // -------------------------------------------------------------------------
    // Single game
    // -------------------------------------------------------------------------

    private GameLog playGame(int gameIndex, MatchConfig config,
                             SimulationEngine[] engines, EngineConfig[] evalConfigs) {
        GameLog log = new GameLog(gameIndex);
        GameState state = GameState.initial(config.playerCount());
        int n = config.playerCount();
        int activePlayer = 0;
        int turnCount = 0;

        // Seat swap: after half the games, swap P1/P2 positions
        boolean swapped = config.seatSwap() && n == 2 && gameIndex >= config.gameCount() / 2;
        SimulationEngine[] gameEngines = swapped
                ? new SimulationEngine[]{engines[1], engines[0]} : engines;
        EngineConfig[] gameConfigs = swapped
                ? new EngineConfig[]{evalConfigs[1], evalConfigs[0]} : evalConfigs;

        while (turnCount < config.maxTurnsPerGame()) {
            TurnLog turnLog = playTurn(state, activePlayer,
                    gameEngines[activePlayer], gameConfigs[activePlayer]);
            log.turns.add(turnLog);
            turnCount++;

            // Win check
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                // Map winner back to original seat index
                log.winnerIndex = swapped ? (1 - activePlayer) : activePlayer;
                break;
            }

            // Freizeitpark bonus turn
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && turnLog.isDoubles) {
                TurnLog bonusTurnLog = playTurn(state, activePlayer,
                        gameEngines[activePlayer], gameConfigs[activePlayer]);
                log.turns.add(bonusTurnLog);
                turnCount++;

                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    log.winnerIndex = swapped ? (1 - activePlayer) : activePlayer;
                    break;
                }
            }

            activePlayer = (activePlayer + 1) % n;
        }

        // Turn limit reached — softmax winner
        if (log.winnerIndex < 0) {
            log.timeoutWin = true;
            int rawWinner = softmaxWinner(state);
            log.winnerIndex = swapped ? (1 - rawWinner) : rawWinner;
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
        plan.navigateRoll(roll, doubles);

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
            plan.navigateReroll(roll, doubles);
        }

        // 5. Apply roll income
        int n = state.getPlayers().length;
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < n; i++) {
            int newCoins = state.getPlayers()[i].getCoins() + deltas[i];
            state.getPlayers()[i].setCoins(Math.max(0, newCoins));
        }

        // 6. Bürohaus swap
        // MCTS engines populate plan.hasBürohausChoice + swap details via tree navigation.
        // Non-MCTS engines (FlatMc, Creator) use staticPlan which leaves these empty.
        // Fallback: if the active player owns Bürohaus and rolled 6, apply greedy swap.
        String bürohausSwap = null;
        boolean bürohausActivated = plan.hasBürohausChoice;
        if (plan.hasBürohausChoice && plan.bürohausOwnCard != null
                && plan.bürohausOppPlayer >= 0 && plan.bürohausOppCard != null) {
            // MCTS-provided swap decision
            try {
                BürohausLogic.executeSwap(state, activePlayer,
                        plan.bürohausOwnCard, plan.bürohausOppPlayer, plan.bürohausOppCard);
                bürohausSwap = plan.bürohausOwnCard.getId() + "→" + plan.bürohausOppCard.getId();
            } catch (IllegalArgumentException e) {
                // Swap not valid in current state (tree divergence) — skip
            }
        } else if (!plan.hasBürohausChoice
                && state.getPlayers()[activePlayer].hasProject("bürohaus") && roll == 6) {
            // Greedy fallback for non-MCTS engines
            bürohausActivated = true;
            BürohausLogic.SwapCandidates candidates = BürohausLogic.findCandidates(state, activePlayer);
            if (candidates.isBeneficial()) {
                BürohausLogic.executeSwap(state, activePlayer);
                bürohausSwap = candidates.worstOwn().getId() + "→" + candidates.bestOpp().getId();
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
                active.addProject(purchase);
                purchasedCardId = purchase.getId();

                // Update unbuilt_projects if supply exhausted
                if (!purchase.isIs_grossprojekt()) {
                    updateSupply(state, purchase, n);
                }
            }
        }

        int coinsAfterPurchase = state.getPlayers()[activePlayer].getCoins();

        // 8. Build decision detail from engine evaluation
        TurnLog.DecisionDetail detail = buildDecisionDetail(plan, purchasedCardId);

        return new TurnLog(
                activePlayer, diceCount, roll, doubles,
                deltas, purchasedCardId, plan.purchaseWinRate,
                coinsAfterPurchase, bürohausSwap, bürohausActivated, funkturmRerolled,
                plan.computeTimeMs, detail
        );
    }

    /**
     * Builds a compact DecisionDetail from the engine's evaluation.
     * For non-MCTS engines, uses the stored EngineResult. For MCTS engines,
     * extracts buy alternatives from the BuyDecisionNode tree.
     */
    private TurnLog.DecisionDetail buildDecisionDetail(TurnPlan plan, String chosenCardId) {
        // Non-MCTS engines carry the full EngineResult
        if (plan.engineResult != null) {
            return buildDetailFromEngineResult(plan.engineResult, chosenCardId);
        }
        // MCTS engines: extract from tree
        java.util.List<TurnPlan.BuyAlternative> alts = plan.getMctsBuyAlternatives(5);
        if (alts != null && !alts.isEmpty()) {
            return buildDetailFromMctsAlternatives(alts, chosenCardId, plan.iterationsUsed);
        }
        return null;
    }

    private TurnLog.DecisionDetail buildDetailFromEngineResult(EngineResult result, String chosenCardId) {
        java.util.List<TurnLog.DecisionOption> options = new java.util.ArrayList<>();
        String chosenKey = chosenCardId != null ? chosenCardId : "_wait_";
        boolean chosenIncluded = false;
        for (EngineResult.Option opt : result.rankedOptions) {
            // Only include affordable options (save is always "affordable")
            if (!opt.affordable) continue;
            double score = sanitizeDouble(opt.score);
            String cardId = opt.project.getId();
            boolean chosen = cardId.equals(chosenKey);
            if (chosen) chosenIncluded = true;
            options.add(new TurnLog.DecisionOption(cardId, score, chosen));
            if (options.size() >= 5 && chosenIncluded) break;
        }
        // Ensure chosen option is always present (may have been outside top 5)
        if (!chosenIncluded) {
            for (EngineResult.Option opt : result.rankedOptions) {
                if (!opt.affordable) continue;
                if (opt.project.getId().equals(chosenKey)) {
                    options.add(new TurnLog.DecisionOption(
                            opt.project.getId(), sanitizeDouble(opt.score), true));
                    break;
                }
            }
        }
        // Trim to 5 + chosen
        if (options.size() > 6) {
            options = new java.util.ArrayList<>(options.subList(0, 6));
        }
        double conf = sanitizeDouble(result.confidence);
        return new TurnLog.DecisionDetail(options, result.iterationsUsed, conf);
    }

    private TurnLog.DecisionDetail buildDetailFromMctsAlternatives(
            java.util.List<TurnPlan.BuyAlternative> alts, String chosenCardId, int iterations) {
        java.util.List<TurnLog.DecisionOption> options = new java.util.ArrayList<>();
        String chosenKey = chosenCardId != null ? chosenCardId : "_wait_";
        for (TurnPlan.BuyAlternative alt : alts) {
            boolean chosen = alt.cardId().equals(chosenKey);
            options.add(new TurnLog.DecisionOption(alt.cardId(), sanitizeDouble(alt.winRate()), chosen));
        }
        return new TurnLog.DecisionDetail(options, iterations, -1.0);
    }

    /** Replaces NaN, Infinity, -Infinity with -1 for JSON serialization safety. */
    private static double sanitizeDouble(double v) {
        return Double.isFinite(v) ? v : -1.0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Updates the unbuilt_projects list when a non-landmark card is purchased.
     * If all SUPPLY_PER_CARD market copies are now owned, removes the card type from the list.
     * Starter copies (weizenfeld, bäckerei) are outside the market pool and not counted.
     */
    private void updateSupply(GameState state, Project purchased, int playerCount) {
        int ownedCount = 0;
        for (int i = 0; i < playerCount; i++) {
            for (Project p : state.getPlayers()[i].getOwned_projects()) {
                if (p.getId().equals(purchased.getId())) ownedCount++;
            }
        }
        int starters = GameState.starterCopies(purchased.getId(), playerCount);
        int purchasedCopies = ownedCount - starters;
        if (purchasedCopies >= GameState.SUPPLY_PER_CARD) {
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
