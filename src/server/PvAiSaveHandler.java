package server;

import calcs.LuckAnalyzer;
import calcs.WinProbability;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.CardIncome;
import core.BürohausLogic;
import core.GameState;
import core.Player;
import core.Project;
import core.RollResolver;
import core.TurnRecord;
import h2h.GameLog;
import h2h.TurnLog;

import java.io.IOException;
import java.util.List;

/**
 * POST /api/session/pvai/save — saves the completed PvAI game to {@code data/pvai-games.json}.
 *
 * <p>Replays the full turn history from the initial state, computing per-turn luck (MC mode,
 * 200 sims) and win probability (heuristic) for every turn. Builds a {@link GameLog} that
 * mirrors the H2H format so the game can be replayed with the {@code H2hGameReplay} component.
 *
 * <h2>Request body</h2>
 * <pre>
 * {
 *   "humanName":     "Alice",  // display name for the human player
 *   "aiPlayerIndex": 1,        // seat index of the AI (0 or 1)
 *   "engineId":      "mcts-v1" // engine class id
 * }
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * { "id": "a1b2c3d4", "date": "2026-04-11T..." }
 * </pre>
 */
final class PvAiSaveHandler implements HttpHandler {

    /** MC simulations per roll outcome for luck computation. */
    private static final int MC_SIMS = 200;

    private final SessionManager sessionManager;
    private final PvAiGameStore store;

    PvAiSaveHandler(SessionManager sessionManager, PvAiGameStore store) {
        this.sessionManager = sessionManager;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "POST");
            return;
        }

        core.GameSession session = sessionManager.getSession();
        if (session == null) {
            ApiUtils.sendError(exchange, 404, "No active session");
            return;
        }

        PlayerVsAiController pvai = sessionManager.getPvaiController();

        JsonObject body;
        try {
            body = ApiUtils.parseBody(exchange);
        } catch (Exception e) {
            ApiUtils.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        try {
            String humanName = body.has("humanName") ? body.get("humanName").getAsString() : "Human";

            int aiPlayerIndex;
            if (body.has("aiPlayerIndex") && !body.get("aiPlayerIndex").isJsonNull()) {
                aiPlayerIndex = body.get("aiPlayerIndex").getAsInt();
            } else {
                aiPlayerIndex = pvai.getAiPlayerIndex();
                if (aiPlayerIndex < 0) aiPlayerIndex = 1; // default: AI is player 1
            }

            String engineId;
            if (body.has("engineId") && !body.get("engineId").isJsonNull()) {
                engineId = body.get("engineId").getAsString();
            } else {
                String cid = pvai.getEngineClassId();
                engineId = (cid != null) ? cid : "unknown";
            }

            boolean luckUseMc = !body.has("luckUseMc") || body.get("luckUseMc").isJsonNull()
                    || body.get("luckUseMc").getAsBoolean();

            int humanPlayerIndex = 1 - aiPlayerIndex;
            int numPlayers = session.getState().getPlayers().length;

            GameLog gameLog = buildGameLog(session.getHistory(), numPlayers, luckUseMc);

            PvAiGameRecord record = new PvAiGameRecord(humanName, engineId, humanPlayerIndex, gameLog);
            store.save(record);

            JsonObject resp = new JsonObject();
            resp.addProperty("id", record.id);
            resp.addProperty("date", record.date);
            ApiUtils.sendJson(exchange, 200, resp);

        } catch (Exception e) {
            System.err.println("[PvAiSaveHandler] Error: " + e.getMessage());
            e.printStackTrace(System.err);
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Replays turn history from the initial game state, computing luck and win-rate
     * for each turn, and returns a populated {@link GameLog}.
     *
     * @param luckUseMc true = Monte Carlo luck evaluation (accurate, slow ~200 sims/roll);
     *                  false = heuristic evaluation (instant, ~0.25 MAE)
     */
    private static GameLog buildGameLog(List<TurnRecord> history, int numPlayers, boolean luckUseMc) {
        GameState replayState = GameState.initial(numPlayers);
        GameLog log = new GameLog(0);

        for (TurnRecord record : history) {
            int pi = record.playerIndex;
            boolean usedTwoDice = record.diceCount == 2;

            // Capture pre-roll state for luck computation (must be pre-income)
            GameState preRollState = replayState.copy();

            // Compute luck + baseline WR
            LuckAnalyzer.RollLuck rollLuck = LuckAnalyzer.computeRollLuck(
                    preRollState, pi, record.roll, usedTwoDice, MC_SIMS, luckUseMc);

            // Per-card income attribution (computed from pre-roll state for chart display)
            java.util.Map<String, int[]> cardIncome = RollResolver.attributeIncomePerCard(
                    preRollState, pi, record.roll);

            // Apply income — use stored coinDeltas from the TurnRecord (exact actual values)
            // rather than recomputing, since recomputation from an approximated replay state
            // may diverge (e.g., coins clamped at 0 accumulate drift).
            int[] deltas = record.coinDeltas != null
                    ? record.coinDeltas
                    : RollResolver.computeAllDeltasForRoll(replayState, pi, record.roll);
            Player[] players = replayState.getPlayers();
            for (int i = 0; i < players.length; i++) {
                players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
            }

            // Apply bürohaus swap if recorded
            String bürohausSwapStr = null;
            boolean bürohausActivated = false;
            if (record.swappedAway != null && record.swappedIn != null) {
                bürohausSwapStr = record.swappedAway.getId() + "→" + record.swappedIn.getId();
                bürohausActivated = true;
                if (record.swapOppPlayerIndex >= 0) {
                    BürohausLogic.executeSwap(replayState, pi,
                            record.swappedAway, record.swapOppPlayerIndex, record.swappedIn);
                } else {
                    BürohausLogic.executeSwap(replayState, pi);
                }
            } else if (record.roll == 6 && players[pi].hasProject("bürohaus")) {
                bürohausActivated = true; // triggered but no swap executed
            }

            // Apply purchase
            String purchasedCardId = null;
            Double purchasedCardExpectedEv = null;
            if (record.bought != null) {
                Project card = record.bought;
                purchasedCardId = card.getId();
                Player buyer = players[pi];
                if (!card.isIs_grossprojekt()) {
                    // Remove from pool when all market copies are owned (mirrors GameSession logic)
                    int totalOwned = 0;
                    for (Player p : players) {
                        for (Project owned : p.getOwned_projects()) {
                            if (owned.getId().equals(card.getId())) totalOwned++;
                        }
                    }
                    int starters = GameState.starterCopies(card.getId(), numPlayers);
                    if ((totalOwned - starters) >= GameState.SUPPLY_PER_CARD) {
                        replayState.getUnbuilt_projects().remove(card);
                    }
                }
                buyer.addProject(card);
                buyer.setCoins(Math.max(0, buyer.getCoins() - card.getCost()));

                // Expected per-round EV for the purchased card (post-purchase player stats)
                if (!card.isIs_grossprojekt()) {
                    CardIncome.PlayerStats stats = CardIncome.PlayerStats.of(buyer);
                    int[] oppCoins = CardIncome.buildOpponentCoins(players, pi);
                    purchasedCardExpectedEv = CardIncome.contextualCardEvPerRound(
                            card, stats, numPlayers, oppCoins);
                }
            }

            int coinsAfterPurchase = players[pi].getCoins();
            double wrAfterPurchase = WinProbability.computeBaselineWinProb(replayState, pi);

            TurnLog turnLog = new TurnLog(
                    pi, record.diceCount, record.roll, record.isDoubles,
                    deltas, purchasedCardId,
                    wrAfterPurchase, true, /* scoreIsWinRate */
                    coinsAfterPurchase,
                    bürohausSwapStr, bürohausActivated,
                    false, 0L, null, /* funkturmRerolled, evaluateTimeMs, decisionDetail */
                    rollLuck.luck(), rollLuck.expectedWr(), rollLuck.wrAfterActual(), rollLuck.wrPerRoll(),
                    cardIncome.isEmpty() ? null : cardIncome, purchasedCardExpectedEv
            );
            log.turns.add(turnLog);
        }

        // Final stats
        Player[] finalPlayers = replayState.getPlayers();
        log.finalCoins = new int[numPlayers];
        log.landmarkCounts = new int[numPlayers];
        for (int i = 0; i < numPlayers; i++) {
            log.finalCoins[i] = finalPlayers[i].getCoins();
            log.landmarkCounts[i] = countLandmarks(finalPlayers[i]);
        }

        // Winner: player with all 4 landmarks, or most landmarks if game was cut short
        int winner = -1;
        for (int i = 0; i < numPlayers; i++) {
            if (GameState.hasWon(finalPlayers[i])) { winner = i; break; }
        }
        if (winner < 0) {
            int max = -1;
            for (int i = 0; i < numPlayers; i++) {
                if (log.landmarkCounts[i] > max) { max = log.landmarkCounts[i]; winner = i; }
            }
        }
        log.winnerIndex = winner;
        log.totalTurns = history.size();

        return log;
    }

    private static int countLandmarks(Player player) {
        int count = 0;
        for (Project p : player.getOwned_projects()) {
            if ("gelb".equals(p.getColor())) count++;
        }
        return count;
    }
}
