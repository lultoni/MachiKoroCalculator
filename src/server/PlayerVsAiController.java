package server;

import core.BitState;
import core.GameSession;
import core.GameState;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;
import core.TurnRecord;
import engine.ContinuousEvaluator;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.TurnPlan;
import engine.Timekeeper;
import engine.creator.CreatorContinuousWorker;
import engine.expectimax.ExpectimaxContinuousWorker;
import engine.flat.FlatMcContinuousWorker;
import engine.heuristic.HeuristicContinuousWorker;
import engine.mcts.MctsContinuousWorker;

import java.util.concurrent.ExecutionException;
import java.util.Random;

/**
 * Coordinates the Player-vs-AI session: continuous engine thinking, human lock-in events,
 * and AI turn execution.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #start} — called after session creation; picks a ContinuousWorker for the engine,
 *       creates ContinuousEvaluator + Timekeeper, kicks off thinking on the initial position.</li>
 *   <li>{@link #onHumanTurnComplete} — called after the human's Buy is applied to GameSession.
 *       Re-initialises the engine on the AI's upcoming position so thinking begins
 *       immediately on the right state.</li>
 *   <li>{@link #executeAiTurn} — called when the frontend wants the AI's decision.
 *       Blocks for the remaining minThinkTimeMs, then retrieves the TurnPlan, rolls dice,
 *       navigates Funkturm/Bürohaus, applies the turn to the session, and returns the result.</li>
 *   <li>{@link #stop} — shuts down the evaluator and timekeeper.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * All public methods are called from the HTTP thread. Internal synchronization is handled
 * by ContinuousEvaluator.
 */
public final class PlayerVsAiController {

    private final SessionManager sessionManager;
    private ContinuousEvaluator evaluator;
    private Timekeeper timekeeper;
    private int aiPlayerIndex;
    private String engineClassId;
    private EngineConfig engineConfig;
    private final Random rng = new Random();

    /** True while PvAI mode is active. */
    private volatile boolean active = false;

    public PlayerVsAiController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Activates Player-vs-AI mode for the current session.
     *
     * @param aiPlayerIndex  seat index occupied by the AI (0 or 1)
     * @param engineClassId  engine identifier (e.g. "mcts-v1", "flat-mc", "creator", etc.)
     * @param config         engine configuration including timeBudgetMs
     * @param minThinkTimeMs minimum think time from user Settings
     * @throws IllegalStateException if no session is active
     */
    public synchronized void start(int aiPlayerIndex, String engineClassId,
                                   EngineConfig config, int minThinkTimeMs) {
        GameSession session = sessionManager.getSession();
        if (session == null) throw new IllegalStateException("No active session");

        if (this.active) stop(); // clean up any prior instance

        this.aiPlayerIndex  = aiPlayerIndex;
        this.engineClassId  = engineClassId;
        this.engineConfig   = config;

        ContinuousWorker worker = createWorker(engineClassId, config);
        this.evaluator  = new ContinuousEvaluator(worker);
        this.timekeeper = new Timekeeper(evaluator);
        timekeeper.setMinThinkTimeMs(minThinkTimeMs);
        timekeeper.setEngineTimeBudgetMs(config.timeBudgetMs);
        this.active = true;

        if (session.nextPlayerIndex() == aiPlayerIndex) {
            // AI goes first — start thinking immediately on this position
            evaluator.init(session.getState(), aiPlayerIndex, config);
            timekeeper.start(System.currentTimeMillis());
        }
        // If human goes first, evaluator stays idle until onHumanTurnComplete()
    }

    /**
     * Called after the human player's turn has been applied to the session.
     * Initialises the engine on the AI's upcoming turn position and starts the
     * think-time clock.
     *
     * <p>We always do a fresh {@code init()} here rather than {@code navigate()}.
     * The engine was thinking on the initial position (AI's first turn) — not on the
     * human's turn that just completed — so tree navigation would look for the human's
     * roll/buy path inside the AI's turn tree, which is structurally wrong.
     * After the AI's turn completes, the engine is re-started on the human's predicted
     * next turn via the post-AI-turn {@code init()} at the bottom of {@link #executeAiTurn}.
     *
     * @param humanTurnRecord the TurnRecord just applied (contains roll, diceCount, etc.)
     */
    public synchronized void onHumanTurnComplete(TurnRecord humanTurnRecord) {
        if (!active) return;
        GameSession session = sessionManager.getSession();
        if (session == null) return;

        GameState newState = session.getState();
        evaluator.init(newState, aiPlayerIndex, engineConfig);
        timekeeper.start(System.currentTimeMillis());
    }

    /**
     * Executes the AI's pre-computed turn. Blocks for the remaining minThinkTimeMs,
     * retrieves the best EngineResult, extracts the TurnPlan, rolls dice, and applies
     * the turn to the session.
     *
     * @return the AI's turn result (dice, income, Bürohaus, purchase), or null on failure
     */
    public synchronized AiTurnResult executeAiTurn() {
        if (!active) return null;
        GameSession session = sessionManager.getSession();
        if (session == null) return null;

        long thinkStart = System.currentTimeMillis();

        // Block until minThinkTimeMs has elapsed, then get result
        EngineResult result;
        try {
            result = timekeeper.requestResult().get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            result = evaluator.peekResult();
        }

        long thinkTimeMs = System.currentTimeMillis() - thinkStart;
        int iterationsUsed = evaluator.iterations();

        // Build TurnPlan from result (or fall back to save)
        GameState state = session.getState();
        TurnPlan plan = buildPlan(result, state);

        // Roll dice
        int diceCount = plan.diceCount;
        int roll = rollDice(diceCount);
        boolean isDoubles = (diceCount == 2) && (rng.nextInt(6) + 1 == rng.nextInt(6) + 1);
        // Re-roll properly: two independent dice
        int die1 = rng.nextInt(6) + 1;
        int die2 = (diceCount == 2) ? (rng.nextInt(6) + 1) : 0;
        roll     = (diceCount == 2) ? (die1 + die2) : die1;
        isDoubles = (diceCount == 2) && (die1 == die2);

        // Navigate the plan's tree with the actual roll
        plan.navigateRoll(roll, isDoubles);

        // Funkturm handling
        Boolean funkturmKeep = null;
        Integer rerollTotal  = null;
        Boolean rerollIsDoubles = null;
        if (plan.hasFunkturmChoice) {
            funkturmKeep = plan.funkturmKeep;
            if (!plan.funkturmKeep) {
                int rd1 = rng.nextInt(6) + 1;
                int rd2 = (diceCount == 2) ? (rng.nextInt(6) + 1) : 0;
                int reroll = (diceCount == 2) ? (rd1 + rd2) : rd1;
                boolean rerollDoubles = (diceCount == 2) && (rd1 == rd2);
                rerollTotal     = reroll;
                rerollIsDoubles = rerollDoubles;
                plan.navigateReroll(reroll, rerollDoubles);
                // Use reroll for applying income
                roll      = reroll;
                isDoubles = rerollDoubles;
            }
        }

        // Compute coin deltas (income resolution)
        int finalRoll    = roll;
        boolean finalDbl = isDoubles;
        int[] coinDeltas = RollResolver.computeAllDeltasForRoll(state, aiPlayerIndex, finalRoll);

        // Bürohaus
        String bürohausOwnCardId = null;
        String bürohausOppCardId = null;
        Integer bürohausOppPlayer = null;
        if (plan.hasBürohausChoice && plan.bürohausOwnCard != null
                && plan.bürohausOppPlayer >= 0 && plan.bürohausOppCard != null) {
            bürohausOwnCardId = plan.bürohausOwnCard.getId();
            bürohausOppCardId = plan.bürohausOppCard.getId();
            bürohausOppPlayer = plan.bürohausOppPlayer;
        }

        // Determine purchase — null out WAIT_SENTINEL so TurnRecord treats it as save
        String purchasedCardId = null;
        Project purchase = plan.purchase;
        if (purchase != null && !calcs.RankEntry.WAIT_SENTINEL.getId().equals(purchase.getId())) {
            purchasedCardId = purchase.getId();
        } else {
            purchase = null; // treat as save for session.applyTurn
        }

        // Validate purchase affordability AFTER income is applied — engine evaluated pre-roll coins;
        // red-card income can reduce the AI's coins, making the planned purchase unaffordable.
        if (purchase != null) {
            int coinsAfterIncome = Math.max(0, state.getPlayers()[aiPlayerIndex].getCoins()
                    + coinDeltas[aiPlayerIndex]);
            if (coinsAfterIncome < purchase.getCost()) {
                purchase = null;
                purchasedCardId = null;
            }
        }

        // Apply turn to session
        TurnRecord record = new TurnRecord(
                aiPlayerIndex, finalRoll, purchase, finalDbl,
                coinDeltas,
                bürohausOwnCardId != null ? plan.bürohausOwnCard : null,
                bürohausOppCardId != null ? plan.bürohausOppCard : null,
                bürohausOppPlayer != null ? bürohausOppPlayer : -1,
                diceCount);
        session.applyTurn(record);
        sessionManager.addEngineSnapshot(null); // AI turns don't store engine snapshots in replay

        // Now start thinking on the human's upcoming turn
        GameState newState = session.getState();
        int humanPlayerIndex = (aiPlayerIndex == 0) ? 1 : 0;
        evaluator.init(newState, humanPlayerIndex, engineConfig);
        // Note: human triggers navigate() via their own actions; engine previews from initial position
        timekeeper.start(System.currentTimeMillis());

        return new AiTurnResult(
                diceCount, finalRoll, finalDbl,
                coinDeltas,
                funkturmKeep, rerollTotal, rerollIsDoubles,
                bürohausOwnCardId, bürohausOppCardId, bürohausOppPlayer,
                purchasedCardId,
                iterationsUsed, thinkTimeMs);
    }

    /**
     * Shuts down the continuous evaluator and timekeeper. Safe to call multiple times.
     */
    public synchronized void stop() {
        active = false;
        if (evaluator != null) {
            evaluator.shutdown();
            evaluator = null;
        }
        if (timekeeper != null) {
            timekeeper.shutdown();
            timekeeper = null;
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /** Returns true if PvAI mode is currently active. */
    public boolean isActive() { return active; }

    /** Returns the AI's player seat index, or -1 if not active. */
    public int getAiPlayerIndex() { return active ? aiPlayerIndex : -1; }

    /** Returns the engine class ID used by the AI, or null if not active. */
    public String getEngineClassId() { return active ? engineClassId : null; }

    /**
     * Peeks at the current best result without stopping the worker.
     * Returns null if no result is available yet.
     */
    public EngineResult peekResult() {
        return (active && evaluator != null) ? evaluator.peekResult() : null;
    }

    /** Returns total iterations accumulated on the current position. */
    public int iterations() {
        return (active && evaluator != null) ? evaluator.iterations() : 0;
    }

    /** Returns how long the engine has been thinking on the current position (ms). */
    public long thinkingTimeMs() {
        return (active && evaluator != null) ? evaluator.thinkingTimeMs() : 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ContinuousWorker createWorker(String engineClassId, EngineConfig config) {
        return switch (engineClassId) {
            case "flat-mc"   -> new FlatMcContinuousWorker();
            case "creator"   -> new CreatorContinuousWorker();
            case "expectimax"-> new ExpectimaxContinuousWorker();
            case "heuristic-ev" -> new HeuristicContinuousWorker();
            default          -> new MctsContinuousWorker(); // covers mcts-v1 + variants
        };
    }

    private TurnPlan buildPlan(EngineResult result, GameState state) {
        if (result == null) {
            // Fallback: save (dice count = Bahnhof check)
            boolean hasBahnhof = state.getPlayers()[aiPlayerIndex].hasProject("bahnhof");
            return TurnPlan.staticPlan(hasBahnhof ? 2 : 1, calcs.RankEntry.WAIT_SENTINEL,
                    0.0, 0, 0L, null);
        }
        boolean hasBahnhof = state.getPlayers()[aiPlayerIndex].hasProject("bahnhof");
        int diceCount = hasBahnhof ? 2 : 1;
        return engine.SimulationEngine.staticPlanWithInstantWinPriority(
                diceCount, result, state, aiPlayerIndex, 0L);
    }

    private int rollDice(int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) sum += rng.nextInt(6) + 1;
        return sum;
    }
}
