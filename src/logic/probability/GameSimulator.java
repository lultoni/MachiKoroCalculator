package logic.probability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Stateless Monte Carlo game simulator for the Machi Koro base game.
 *
 * <h2>Rollout policy (greedy)</h2>
 * Each simulated player follows this priority at the buy phase:
 * <ol>
 *   <li>If the player can afford the next unbuilt landmark (cheapest first),
 *       buy it — landmark progression is always strictly optimal.</li>
 *   <li>Otherwise buy the affordable establishment card with the highest
 *       {@code evPerRound / cost} ratio (simple ROI proxy), if any.</li>
 *   <li>If nothing is affordable, save (skip buy phase).</li>
 * </ol>
 *
 * <h2>Win condition</h2>
 * A player wins immediately upon buying their fourth landmark (all four
 * Großprojekte: Bahnhof, Einkaufszentrum, Freizeitpark, Funkturm).
 *
 * <h2>Supply model</h2>
 * Non-landmark establishments have a limited market supply of 6 copies each
 * in the base game. The simulator tracks remaining supply in a
 * {@code Map&lt;String,Integer&gt;}. Players may not buy a card if supply is
 * exhausted. Landmarks are always available (one per player, unlimited supply
 * for simulation purposes).
 *
 * <h2>Termination guarantee</h2>
 * If no winner is found within {@link #MAX_TURNS} total turns, the game is
 * considered a timeout and the method returns {@code -1} (no winner). This
 * cannot happen with a correct policy but prevents infinite loops.
 *
 * <h2>Thread safety</h2>
 * All methods are stateless static methods. Callers must supply a per-thread
 * {@link Random} (use {@link java.util.concurrent.ThreadLocalRandom#current()}
 * in parallel contexts). Each call receives its own deep-copied {@link GameState}.
 */
public class GameSimulator {

    /** Maximum total turns before declaring a timeout. */
    public static final int MAX_TURNS = 200;

    /** Market supply copies per non-landmark card in the base game. */
    private static final int SUPPLY_PER_CARD = 6;

    /** Landmark IDs in purchase-priority order (cheapest first). */
    private static final String[] LANDMARK_ORDER =
            {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    // Pre-computed static EV-per-turn/cost ratios for a neutral mid-game state
    // (1 food, 1 animal, 1 production card, no landmarks, 4 opponents at 5 coins each).
    // Used in greedy buy policy to avoid calling ProbabilityCalc.evPerRound in the inner loop.
    private static final Map<String, Double> STATIC_EV_PER_COST = buildStaticEvPerCost();

    /**
     * Simulates a complete game from the given state using a greedy rollout policy.
     *
     * <p>The supplied {@code state} is mutated during simulation — callers must
     * pass a deep copy (via {@link GameState#copy()}) if the original must be preserved.
     *
     * @param state initial game state (will be mutated)
     * @param rng   random number source (use {@code ThreadLocalRandom.current()} in
     *              parallel contexts)
     * @return index (0-based) of the winning player, or {@code -1} on timeout
     */
    public static int simulate(GameState state, Random rng) {
        int n = state.getPlayers().length;
        Map<String, Integer> supply = buildSupply(state);

        int totalTurns = 0;
        int activePlayer = 0;

        while (totalTurns < MAX_TURNS) {
            Player player = state.getPlayers()[activePlayer];

            // --- Roll phase ---
            int roll = rollDice(state, activePlayer, rng);

            // --- Income phase: apply roll effects to all players ---
            applyRoll(state, activePlayer, roll);

            // --- Buy phase: greedy purchase ---
            int winner = greedyBuy(state, activePlayer, supply);
            if (winner >= 0) return winner;

            activePlayer = (activePlayer + 1) % n;
            totalTurns++;
        }

        return -1; // timeout — should not happen in practice
    }

    // -------------------------------------------------------------------------
    // Roll helpers
    // -------------------------------------------------------------------------

    /**
     * Rolls dice for the active player. Uses 2d6 if Bahnhof is owned and 2d6 is
     * better (higher EV for owned cards), otherwise 1d6.
     */
    private static int rollDice(GameState state, int activePlayer, Random rng) {
        Player player = state.getPlayers()[activePlayer];
        boolean hasBahnhof = player.hasProject("bahnhof");

        if (!hasBahnhof) {
            return 1 + rng.nextInt(6);
        }

        // Approximate choice: always use 2d6 when Bahnhof is available
        // (valid heuristic for mid-to-late game where 2d6 range covers more owned cards)
        int d1 = 1 + rng.nextInt(6);
        int d2 = 1 + rng.nextInt(6);
        int roll2 = d1 + d2;

        // Freizeitpark: if doubles, roll again (no chain on second roll)
        if (d1 == d2 && player.hasProject("freizeitpark")) {
            int extra = rollSecond(state, activePlayer, rng, 2);
            applyRoll(state, activePlayer, extra);
        }

        return roll2;
    }

    /** Rolls the second dice set for Freizeitpark. No doubles chaining. */
    private static int rollSecond(GameState state, int activePlayer, Random rng, int diceCount) {
        if (diceCount == 1) {
            return 1 + rng.nextInt(6);
        }
        int d1 = 1 + rng.nextInt(6);
        int d2 = 1 + rng.nextInt(6);
        // Funkturm not modelled here: a Funkturm re-roll on dislike is a complex strategic
        // decision; for simulation speed we always accept the second roll.
        return d1 + d2;
    }

    // -------------------------------------------------------------------------
    // Coin income application
    // -------------------------------------------------------------------------

    /**
     * Applies the coin effects of {@code roll} to all players.
     * Uses the package-visible bridges in {@link ProbabilityCalc}.
     */
    private static void applyRoll(GameState state, int activePlayer, int roll) {
        Player[] players = state.getPlayers();
        int n = players.length;

        // Compute deltas first (based on coins before this roll),
        // then apply simultaneously to avoid order-dependency.
        int[] deltas = new int[n];
        for (int i = 0; i < n; i++) {
            deltas[i] = (i == activePlayer)
                    ? ProbabilityCalc.computeNetGainForRollPublic(state, i, roll)
                    : ProbabilityCalc.computeOpponentTurnGainForRollPublic(state, i, activePlayer, roll);
        }
        for (int i = 0; i < n; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }
    }

    // -------------------------------------------------------------------------
    // Greedy buy phase
    // -------------------------------------------------------------------------

    /**
     * Executes the greedy buy decision for the active player.
     *
     * @return the winner's player index if this purchase completes the game,
     *         or {@code -1} if the game continues
     */
    private static int greedyBuy(GameState state, int activePlayer,
                                   Map<String, Integer> supply) {
        Player player = state.getPlayers()[activePlayer];

        // 1. Try to buy the next unbuilt landmark (priority)
        for (String lmId : LANDMARK_ORDER) {
            if (!player.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && player.getCoins() >= lm.getCost()) {
                    purchase(player, lm, supply);
                    if (hasWon(player)) return activePlayer;
                }
                // Only try to buy the cheapest missing landmark (don't skip ahead)
                break;
            }
        }

        // 2. Try to buy the best-value establishment card
        Project best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Project p : state.getUnbuilt_projects()) {
            if (p.isIs_grossprojekt()) continue;
            if (player.getCoins() < p.getCost()) continue;
            int remaining = supply.getOrDefault(p.getId(), 0);
            if (remaining <= 0) continue;
            double score = STATIC_EV_PER_COST.getOrDefault(p.getId(), 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best != null) {
            purchase(player, best, supply);
            // Establishment purchase cannot win the game
        }

        return -1; // game continues
    }

    /** Applies the coin cost and adds the card to the player's owned list. */
    private static void purchase(Player player, Project card, Map<String, Integer> supply) {
        player.setCoins(player.getCoins() - card.getCost());
        player.getOwned_projects().add(card);
        if (!card.isIs_grossprojekt()) {
            supply.merge(card.getId(), -1, Integer::sum);
        }
    }

    /** Returns true if the player owns all 4 landmarks. */
    public static boolean hasWon(Player player) {
        int landmarkCount = 0;
        for (Project p : player.getOwned_projects()) {
            if (p.isIs_grossprojekt()) landmarkCount++;
        }
        return landmarkCount >= 4;
    }

    // -------------------------------------------------------------------------
    // Supply initialisation
    // -------------------------------------------------------------------------

    /**
     * Builds the initial market supply map for the given game state.
     * <p>
     * Each non-landmark card starts with {@link #SUPPLY_PER_CARD} copies minus
     * however many are already owned by players.
     */
    private static Map<String, Integer> buildSupply(GameState state) {
        Map<String, Integer> supply = new HashMap<>();
        // Start with full supply for every non-landmark card
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) {
                supply.put(p.getId(), SUPPLY_PER_CARD);
            }
        }
        // Subtract cards already owned by players
        for (Player player : state.getPlayers()) {
            for (Project p : player.getOwned_projects()) {
                if (!p.isIs_grossprojekt()) {
                    supply.merge(p.getId(), -1, Integer::sum);
                }
            }
        }
        return supply;
    }

    // -------------------------------------------------------------------------
    // Static EV-per-cost table (precomputed for greedy policy)
    // -------------------------------------------------------------------------

    /**
     * Precomputes {@code evPerRound / cost} ratios for every establishment card
     * using a neutral mid-game reference state: 4 players, each with 1 food/animal/
     * production card, no landmarks, opponents at 5 coins each.
     *
     * <p>This table is used by the greedy buy policy to rank cards without calling
     * {@link ProbabilityCalc#evPerRound} in the hot simulation loop.
     */
    private static Map<String, Double> buildStaticEvPerCost() {
        Map<String, Double> table = new HashMap<>();
        // Build a 4-player reference state with minimal owned cards
        try {
            GameStateBuilder builder = new GameStateBuilder(4);
            for (int i = 0; i < 4; i++) {
                builder.setPlayerName(i, "Sim" + i)
                       .setCoins(i, 5)
                       .addProject(i, "weizenfeld")
                       .addProject(i, "bäckerei");
            }
            GameState refState = builder.build();

            for (Project p : ProjectLoader.getAllProjects()) {
                if (p.isIs_grossprojekt()) continue;
                if (p.getCost() == 0) continue;
                double ev = ProbabilityCalc.evPerRound(refState, 0, p);
                table.put(p.getId(), ev / p.getCost());
            }
        } catch (Exception e) {
            // If initialisation fails (e.g., during static class loading order issues),
            // fall back to uniform score of 0 — greedy policy will buy any affordable card.
        }
        return table;
    }
}
