package engine.creator;

import calcs.Calcs;
import calcs.WinProbability;
import core.BürohausLogic;
import core.CardIncome;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;
import engine.mcts.MctsRollout;
import engine.mcts.RolloutFn;
import engine.mcts.SupplyTracker;

import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creator's own rollout policy for Monte Carlo simulations.
 *
 * <p>Uses a lightweight heuristic for purchase decisions during rollouts — NOT the
 * full {@link CreatorScorer} (which is too expensive for rollout-speed requirements).
 *
 * <p>v3 (7.46): Added coverage bonus and save-toward-landmark logic. Coverage bonus
 * rewards cards that activate on roll values the player doesn't currently cover
 * (portfolio diversification). Save-toward-landmark prevents buying marginal cards
 * when close to affording the next landmark. H2H benchmarks showed +4% vs MCTS v1,
 * +1% vs heuristic-ev compared to plain GreedyRollout. Now the default rollout policy.
 *
 * <p>v2 (7.45): Removed v1 landmark-skip logic (hurt performance) and reverted to
 * cheapest-landmark-first ordering matching GreedyRollout.
 *
 * <h2>Decision policy</h2>
 * <ul>
 *   <li><b>Dice</b>: 2d6 if Bahnhof + useful 7-12 card; else 1d6.</li>
 *   <li><b>Funkturm</b>: keep if income ≥ expected reroll EV.</li>
 *   <li><b>Bürohaus</b>: greedy best swap via {@link BürohausLogic#executeSwap}.</li>
 *   <li><b>Purchase (instant-win)</b>: always buy winning landmark.</li>
 *   <li><b>Purchase (landmark)</b>: buy cheapest affordable landmark.</li>
 *   <li><b>Purchase (card)</b>: highest {@code contextualCardEvPerRound × geometricSum − cost + coverageBonus}.</li>
 *   <li><b>Save-toward-landmark</b>: save if within 4 coins of next landmark and best card is marginal.</li>
 *   <li><b>Save</b>: if no card has positive net value.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom}. All state is local to the simulate call.
 */
public final class CreatorRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int    HORIZON  = 5;
    private static final double DISCOUNT = 0.95;
    private static final int    EV_CACHE_REFRESH = 40;
    /** Bonus multiplier per new roll coverage: net += newCoverage * COVERAGE_BONUS * ev. */
    private static final double COVERAGE_BONUS = 0.15;
    /** If card net EV is below this fraction of next-landmark cost, prefer saving. */
    private static final double SAVE_THRESHOLD_RATIO = 0.3;

    private CreatorRollout() {}

    /**
     * Returns a {@link RolloutFn} using the Creator policy.
     */
    public static RolloutFn asRolloutFn() {
        return CreatorRollout::simulate;
    }

    /**
     * Simulates one full game from the given leaf state using the Creator policy.
     */
    public static double simulate(GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
        GameState state = startState.copy();
        SupplyTracker.MutableSupplyTracker supply = startSupply.toMutable();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount = 0;
        int[] deltas = new int[n];
        EvCache evCache = new EvCache(state, startingPlayer, EV_CACHE_REFRESH);

        while (turnCount < MctsRollout.MAX_TURNS) {
            Player active = state.getPlayers()[activePlayer];
            evCache.tickTurn();

            // ---- Dice count ----
            boolean hasBahnhof = active.hasProject("bahnhof");
            boolean twoDice = hasBahnhof && hasHigh7to12Card(active);

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7);
                int d2 = rng.nextInt(1, 7);
                roll = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: keep if income >= expected reroll income ----
            if (active.hasProject("funkturm")) {
                double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
                double rerollEV = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
                if (currentIncome < rerollEV) {
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7);
                        int d2 = rng.nextInt(1, 7);
                        roll = d1 + d2;
                        doubles = (d1 == d2);
                    } else {
                        roll = rng.nextInt(1, 7);
                        doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus ----
            if (active.hasProject("bürohaus") && roll == 6) {
                BürohausLogic.executeSwap(state, activePlayer);
            }

            // ---- Purchase ----
            applyPurchase(state, supply, activePlayer, evCache);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (state.getPlayers()[activePlayer].hasProject("freizeitpark") && doubles) {
                playBonusTurn(state, supply, activePlayer, rng, deltas, evCache);
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }

    // =====================================================================
    // Purchase logic — the Creator difference
    // =====================================================================

    /**
     * Creator purchase policy: instant-win → cheapest affordable landmark →
     * best net-EV card (with save-toward-landmark check) → save.
     *
     * <p>v2: Always buys cheapest landmark (no skip logic). H2H benchmarks (7.45) showed
     * that skipping Bahnhof/Freizeitpark based on current portfolio state hurt performance
     * because rollouts need to explore the natural synergy path where early Bahnhof
     * acquisition leads to buying 7-12 cards that synergize with it.
     *
     * <p>v3: Added coverage bonus and save-toward-landmark. If the best card's net EV
     * is marginal relative to the next landmark's cost, the player saves to reach the
     * landmark faster. This prevents wasteful purchases that delay landmark progression.
     */
    private static void applyPurchase(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                      int activePlayer, EvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // 1. Instant-win check
        Project winLandmark = GameState.findInstantWinLandmark(active);
        if (winLandmark != null) {
            active.setCoins(coins - winLandmark.getCost());
            active.addProject(winLandmark);
            return;
        }

        // 2. Cheapest affordable landmark
        Project bestLandmark = null;
        int bestCost = Integer.MAX_VALUE;
        int nextLandmarkCost = Integer.MAX_VALUE; // cheapest unowned landmark (even unaffordable)
        for (String lmId : LANDMARK_IDS) {
            if (active.hasProject(lmId)) continue;
            Project lm = ProjectLoader.getProject(lmId).orElse(null);
            if (lm == null) continue;
            if (lm.getCost() < nextLandmarkCost) {
                nextLandmarkCost = lm.getCost();
            }
            if (coins < lm.getCost()) continue;
            if (lm.getCost() < bestCost) {
                bestCost = lm.getCost();
                bestLandmark = lm;
            }
        }

        if (bestLandmark != null) {
            active.setCoins(coins - bestLandmark.getCost());
            active.addProject(bestLandmark);
            return;
        }

        // 3. Best non-landmark card by net EV + coverage bonus
        // The coverage bonus rewards cards that activate on rolls the player
        // doesn't currently cover, promoting portfolio diversification.
        Project bestCard = null;
        double bestScore = 0.0;
        boolean hasBahnhof = active.hasProject("bahnhof");
        long coveredRolls = computeCoveredRolls(active);

        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            double ev = evCache.getOrRefresh(state, activePlayer, p.getId());
            double net = ev * Calcs.geometricSum(HORIZON, DISCOUNT) - p.getCost();

            // Coverage bonus: count how many NEW roll values this card covers
            int newCoverage = 0;
            for (int act : p.getDice_activation()) {
                // Only count rolls the player can actually reach
                if (!hasBahnhof && act > 6) continue;
                if ((coveredRolls & (1L << act)) == 0) newCoverage++;
            }
            if (newCoverage > 0) {
                net += newCoverage * COVERAGE_BONUS * ev;
            }

            if (net > bestScore) {
                bestScore = net;
                bestCard = p;
            }
        }

        // 4. Save-toward-landmark check: if best card's net value is marginal
        // compared to the next landmark cost, save to reach the landmark faster.
        // This prevents buying a 1-coin card with net=0.3 when saving 2 more turns
        // would reach a 10-coin landmark that unlocks much more value.
        if (bestCard != null && nextLandmarkCost < Integer.MAX_VALUE) {
            int gap = nextLandmarkCost - coins;
            if (gap > 0 && gap <= 4 && bestScore < nextLandmarkCost * SAVE_THRESHOLD_RATIO) {
                // Save: gap is small and best card is marginal
                return;
            }
        }

        if (bestCard != null) {
            active.setCoins(coins - bestCard.getCost());
            active.addProject(bestCard);
            supply.purchase(bestCard.getId());
        }
        // else: save
    }

    // =====================================================================
    // Bonus turn
    // =====================================================================

    private static void playBonusTurn(GameState state, SupplyTracker.MutableSupplyTracker supply,
                                      int activePlayer, ThreadLocalRandom rng,
                                      int[] deltas, EvCache evCache) {
        Player active = state.getPlayers()[activePlayer];
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean twoDice = hasBahnhof && hasHigh7to12Card(active);
        int n = state.getPlayers().length;

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        if (active.hasProject("funkturm")) {
            double currentIncome = computeRollIncome(state, activePlayer, roll, deltas);
            double rerollEV = computeExpectedRollIncome(state, activePlayer, twoDice, deltas);
            if (currentIncome < rerollEV) {
                roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
            }
        }

        RollResolver.computeAllDeltasForRoll(state, activePlayer, roll, deltas);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }

        if (active.hasProject("bürohaus") && roll == 6) {
            BürohausLogic.executeSwap(state, activePlayer);
        }

        applyPurchase(state, supply, activePlayer, evCache);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Returns a bitmask of roll values (1-12) that produce income for this player.
     * Bit i is set if the player has at least one non-landmark card activating on roll i.
     * Excludes red cards from coverage since they only activate on opponent turns.
     */
    private static long computeCoveredRolls(Player player) {
        long mask = 0;
        for (Project card : player.getOwned_projects()) {
            if ("gelb".equals(card.getColor())) continue; // landmarks
            if ("rot".equals(card.getColor())) continue;  // red = opponent turns only
            for (int act : card.getDice_activation()) {
                mask |= (1L << act);
            }
        }
        return mask;
    }

    private static boolean hasHigh7to12Card(Player p) {
        for (Project card : p.getOwned_projects()) {
            for (int act : card.getDice_activation()) {
                if (act >= 7 && act <= 12) return true;
            }
        }
        return false;
    }

    private static double computeRollIncome(GameState state, int playerIndex, int roll, int[] deltas) {
        RollResolver.computeAllDeltasForRoll(state, playerIndex, roll, deltas);
        return deltas[playerIndex];
    }

    private static double computeExpectedRollIncome(GameState state, int playerIndex,
                                                     boolean twoDice, int[] deltas) {
        double ev = 0.0;
        if (twoDice) {
            for (int r = 2; r <= 12; r++) {
                ev += CardIncome.P2[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        } else {
            for (int r = 1; r <= 6; r++) {
                ev += CardIncome.P1[r] * computeRollIncome(state, playerIndex, r, deltas);
            }
        }
        return ev;
    }

    // =====================================================================
    // Inline EV cache (same pattern as RolloutEvCache, but in this package)
    // =====================================================================

    /**
     * Lightweight per-card EV cache for rollout purchase decisions.
     * Uses {@link CardIncome#contextualCardEvPerRound} with periodic refresh.
     */
    static final class EvCache {
        private final HashMap<String, Double> scores = new HashMap<>();
        private final int refreshInterval;
        private int turnsUntilRefresh;

        EvCache(GameState state, int activePlayer, int refreshInterval) {
            this.refreshInterval = refreshInterval;
            this.turnsUntilRefresh = refreshInterval;
            rebuild(state, activePlayer);
        }

        void tickTurn() { turnsUntilRefresh--; }

        double getOrRefresh(GameState state, int activePlayer, String cardId) {
            if (turnsUntilRefresh <= 0) {
                rebuild(state, activePlayer);
                turnsUntilRefresh = refreshInterval;
            }
            return scores.getOrDefault(cardId, 0.0);
        }

        private void rebuild(GameState state, int activePlayer) {
            scores.clear();
            Player player = state.getPlayers()[activePlayer];
            CardIncome.PlayerStats baseStats = CardIncome.PlayerStats.of(player);
            int numPlayers = state.getPlayers().length;
            int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), activePlayer);

            for (Project p : state.getUnbuilt_projects()) {
                if (!scores.containsKey(p.getId())) {
                    CardIncome.PlayerStats withCandidate = baseStats.withExtra(p);
                    scores.put(p.getId(),
                            CardIncome.contextualCardEvPerRound(p, withCandidate, numPlayers, oppCoins));
                }
            }
        }
    }
}
