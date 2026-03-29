package logic.probability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateless Monte Carlo game simulator for the Machi Koro base game.
 *
 * <h2>Rollout policy (greedy)</h2>
 * Each simulated player follows this priority at the buy phase:
 * <ol>
 *   <li>If the player can afford the next unbuilt landmark (cheapest first),
 *       buy it — landmark progression is always strictly optimal.</li>
 *   <li>Otherwise buy the affordable establishment card with the highest
 *       contextual {@code evPerRound / cost} ratio, evaluated in the buying
 *       player's actual synergy context (real Einkaufszentrum status and
 *       food/animal/production counts).</li>
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
    static final int SUPPLY_PER_CARD = 6;

    /** Counts timeouts across all mcWinRate calls for diagnostic logging. */
    static final AtomicInteger TIMEOUT_COUNT = new AtomicInteger(0);

    /** Landmark IDs in purchase-priority order (cheapest first). */
    private static final String[] LANDMARK_ORDER =
            {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

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

    /**
     * Returns the total number of simulation timeouts observed since class load.
     * Each timeout means a game exceeded {@link #MAX_TURNS} without a winner.
     * Useful for detecting degenerate game states.
     */
    public static int getTimeoutCount() {
        return TIMEOUT_COUNT.get();
    }

    // -------------------------------------------------------------------------
    // Roll helpers
    // -------------------------------------------------------------------------

    /**
     * Rolls dice for the active player.
     * If the player has Bahnhof, chooses between 1d6 and 2d6 based on which dice range
     * covers more of the player's owned card activations: if the player has cards with
     * activation in 7–12 range, 2d6 is preferred; if all activations are in 1–6, 1d6 is used.
     * This is consistent with the optimal dice choice logic in the analytical model.
     */
    private static int rollDice(GameState state, int activePlayer, Random rng) {
        Player player = state.getPlayers()[activePlayer];
        boolean hasBahnhof = player.hasProject("bahnhof");

        if (!hasBahnhof) {
            return 1 + rng.nextInt(6);
        }

        // Choose dice based on whether the player has any cards activating on 7–12.
        // This fast heuristic matches the behavior of the analytical dice-choice model
        // without calling computeNetGainForRoll 42 times per turn.
        boolean hasHighRangeCard = false;
        for (Project p : player.getOwned_projects()) {
            for (int activation : p.getDice_activation()) {
                if (activation >= 7) { hasHighRangeCard = true; break; }
            }
            if (hasHighRangeCard) break;
        }

        boolean use2d6 = hasHighRangeCard;

        if (!use2d6) {
            return 1 + rng.nextInt(6);
        }

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
     * Applies the coin effects of {@code roll} to all players, then executes any
     * bürohaus card-swap if the active player owns bürohaus and rolled exactly 6.
     * Uses {@link ProbabilityCalc#computeAllDeltasForRoll} to resolve all deltas
     * in the correct order: red card payments counter-clockwise first, then
     * blue/green/purple income.
     */
    private static void applyRoll(GameState state, int activePlayer, int roll) {
        Player[] players = state.getPlayers();
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }
        // Bürohaus: on roll 6, active player swaps their worst card for the best opponent card.
        if (roll == 6 && players[activePlayer].hasProject("bürohaus")) {
            ProbabilityCalc.executeBürohausSwap(state, activePlayer);
        }
    }

    // -------------------------------------------------------------------------
    // Greedy buy phase
    // -------------------------------------------------------------------------

    /**
     * Executes the greedy buy decision for the active player.
     * Card scores are computed in the player's actual synergy context (real
     * Einkaufszentrum, food/animal/production counts) using
     * {@link CardIncome#contextualCardEvPerRound}, so synergy multipliers
     * like Markthalle and Molkerei are correctly reflected in card rankings.
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

        // 2. Try to buy the best-value establishment card using contextual EV.
        // Build the player's stats and opponent coins once for this buy phase.
        int n = state.getPlayers().length;
        CardIncome.PlayerStats playerStats = CardIncome.PlayerStats.of(player);
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), activePlayer);

        Project best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Project p : state.getUnbuilt_projects()) {
            if (p.isIs_grossprojekt()) continue;
            if (player.getCoins() < p.getCost()) continue;
            int remaining = supply.getOrDefault(p.getId(), 0);
            if (remaining <= 0) continue;
            // Evaluate in active player's real context (synergy-aware)
            double ev = CardIncome.contextualCardEvPerRound(p, playerStats, n, oppCoins);
            double score = p.getCost() > 0 ? ev / p.getCost() : 0.0;
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
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) {
                supply.put(p.getId(), SUPPLY_PER_CARD);
            }
        }
        for (Player player : state.getPlayers()) {
            for (Project p : player.getOwned_projects()) {
                if (!p.isIs_grossprojekt()) {
                    supply.merge(p.getId(), -1, Integer::sum);
                }
            }
        }
        return supply;
    }
}