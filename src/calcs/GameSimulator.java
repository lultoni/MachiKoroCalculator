package calcs;

import core.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Stateless Monte Carlo game simulator for the Machi Koro base game.
 *
 * <p>Each simulated player follows a greedy buy policy: landmarks first (cheapest),
 * then the establishment with highest fast-ROI approximation. Boltzmann temperature
 * controls exploration vs. greedy behavior for establishments.
 *
 * <p>All methods are stateless. Callers must supply a per-thread {@link Random}.
 */
public class GameSimulator {

    /** Maximum total turns before declaring a timeout. */
    public static final int MAX_TURNS = 200;

    /** Market supply copies per non-landmark card. */
    static final int SUPPLY_PER_CARD = 6;

    /** Counts timeouts across all mcWinRate calls for diagnostic logging. */
    static final AtomicInteger TIMEOUT_COUNT = new AtomicInteger(0);

    /** Landmark IDs in purchase-priority order (cheapest first). */
    private static final String[] LANDMARK_ORDER =
            {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    /**
     * Geometric-series multiplier for fast ROI: γ × (1 − γ^T) / (1 − γ), γ=0.95, T=10.
     */
    private static final double ROI_GEOMETRIC_SUM;
    static {
        double gamma = RankingOptions.DEFAULT_DISCOUNT_FACTOR;
        int T = RankingOptions.DEFAULT_HORIZON;
        ROI_GEOMETRIC_SUM = gamma * (1.0 - Math.pow(gamma, T)) / (1.0 - gamma);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Simulates a complete game with greedy policy (temperature=0). State is NOT mutated. */
    public static int simulate(GameState state, Random rng) {
        return simulate(state, rng, 0.0);
    }

    /**
     * Simulates a complete game with the specified Boltzmann temperature.
     * Uses BitState internally for performance. The supplied state is NOT mutated.
     *
     * @return index of the winning player, or -1 on timeout
     */
    public static int simulate(GameState state, Random rng, double temperature) {
        BitState bs = BitState.fromGameState(state);
        return simulateBitState(bs, state.getPlayers().length, rng, temperature);
    }

    /**
     * Object-based simulation path, kept for GameStateSampler and equivalence testing.
     * The supplied state IS mutated — pass a copy if the original must be preserved.
     */
    public static int simulateObject(GameState state, Random rng, double temperature) {
        int n = state.getPlayers().length;
        Map<String, Integer> supply = buildSupply(state);

        int totalTurns = 0;
        int activePlayer = 0;

        while (totalTurns < MAX_TURNS) {
            int roll = rollDice(state, activePlayer, rng);
            applyRoll(state, activePlayer, roll);

            int winner = (temperature <= 0.0)
                    ? greedyBuy(state, activePlayer, supply)
                    : boltzmannBuy(state, activePlayer, supply, rng, temperature);
            if (winner >= 0) return winner;

            activePlayer = (activePlayer + 1) % n;
            totalTurns++;
        }

        return -1;
    }

    /**
     * Runs numSims Monte Carlo simulations in parallel and returns player's win rate.
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims) {
        return mcWinRate(state, playerIndex, numSims, 0.0);
    }

    /**
     * Runs numSims Monte Carlo simulations with specified temperature and returns win rate.
     * Converts to BitState once, then copies per simulation for performance.
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims, double temperature) {
        BitState template = BitState.fromGameState(state);
        int n = state.getPlayers().length;

        int[] outcomes = IntStream.range(0, numSims)
                .parallel()
                .map(i -> simulateBitState(template.copy(), n, ThreadLocalRandom.current(), temperature))
                .toArray();

        long wins = 0;
        int timeouts = 0;
        for (int w : outcomes) {
            if (w == playerIndex) wins++;
            else if (w == -1) timeouts++;
        }

        if (timeouts > numSims / 100) {
            System.err.println("[GameSimulator] WARNING: " + timeouts + "/" + numSims
                    + " simulations timed out (>" + MAX_TURNS
                    + " turns). State may be degenerate.");
            TIMEOUT_COUNT.addAndGet(timeouts);
        }

        return (double) wins / numSims;
    }

    // -------------------------------------------------------------------------
    // Roll helpers
    // -------------------------------------------------------------------------

    static int rollDice(GameState state, int activePlayer, Random rng) {
        Player player = state.getPlayers()[activePlayer];
        boolean hasBahnhof = player.hasProject("bahnhof");

        if (!hasBahnhof) return 1 + rng.nextInt(6);

        boolean hasHighRangeCard = hasHighRangeCard(player);
        if (!hasHighRangeCard) return 1 + rng.nextInt(6);

        int d1 = 1 + rng.nextInt(6);
        int d2 = 1 + rng.nextInt(6);
        int roll2 = d1 + d2;

        if (d1 == d2 && player.hasProject("freizeitpark")) {
            int extra = rollSecond(rng);
            applyRoll(state, activePlayer, extra);
        }

        return roll2;
    }

    private static int rollSecond(Random rng) {
        return 1 + rng.nextInt(6) + 1 + rng.nextInt(6);
    }

    // -------------------------------------------------------------------------
    // Income
    // -------------------------------------------------------------------------

    static void applyRoll(GameState state, int activePlayer, int roll) {
        Player[] players = state.getPlayers();
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }
        if (roll == 6 && players[activePlayer].hasProject("bürohaus")) {
            BürohausLogic.executeSwap(state, activePlayer);
        }
    }

    // -------------------------------------------------------------------------
    // Buy phases
    // -------------------------------------------------------------------------

    static int greedyBuy(GameState state, int activePlayer, Map<String, Integer> supply) {
        Player player = state.getPlayers()[activePlayer];

        for (String lmId : LANDMARK_ORDER) {
            if (!player.hasProject(lmId)) {
                if (lmId.equals("bahnhof") && !hasHighRangeCard(player)
                        && player.getLandmarkCount() < 3) break;
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && player.getCoins() >= lm.getCost()) {
                    purchase(player, lm, supply);
                    if (GameState.hasWon(player)) return activePlayer;
                }
                break;
            }
        }

        int n = state.getPlayers().length;
        CardIncome.PlayerStats playerStats = CardIncome.PlayerStats.of(player);
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), activePlayer);

        Project best = null;
        double bestROI = -Double.MAX_VALUE;
        for (Project p : state.getUnbuilt_projects()) {
            if (p.isIs_grossprojekt()) continue;
            if (player.getCoins() < p.getCost()) continue;
            if (supply.getOrDefault(p.getId(), 0) <= 0) continue;
            if ("lila".equals(p.getColor()) && player.hasProject(p.getId())) continue;
            double ev = CardIncome.contextualCardEvPerRound(p, playerStats, n, oppCoins);
            double roi = ev * ROI_GEOMETRIC_SUM - p.getCost();
            if (roi > bestROI) { bestROI = roi; best = p; }
        }
        if (best != null) purchase(player, best, supply);
        return -1;
    }

    static int boltzmannBuy(GameState state, int activePlayer,
                                     Map<String, Integer> supply, Random rng, double temperature) {
        Player player = state.getPlayers()[activePlayer];

        for (String lmId : LANDMARK_ORDER) {
            if (!player.hasProject(lmId)) {
                if (lmId.equals("bahnhof") && !hasHighRangeCard(player)
                        && player.getLandmarkCount() < 3) break;
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && player.getCoins() >= lm.getCost()) {
                    purchase(player, lm, supply);
                    if (GameState.hasWon(player)) return activePlayer;
                }
                break;
            }
        }

        int n = state.getPlayers().length;
        CardIncome.PlayerStats playerStats = CardIncome.PlayerStats.of(player);
        int[] oppCoins = CardIncome.buildOpponentCoins(state.getPlayers(), activePlayer);

        java.util.List<Project> cards = new java.util.ArrayList<>();
        double[] scores = new double[state.getUnbuilt_projects().size()];
        int count = 0;
        for (Project p : state.getUnbuilt_projects()) {
            if (p.isIs_grossprojekt()) continue;
            if (player.getCoins() < p.getCost()) continue;
            if (supply.getOrDefault(p.getId(), 0) <= 0) continue;
            if ("lila".equals(p.getColor()) && player.hasProject(p.getId())) continue;
            double ev = CardIncome.contextualCardEvPerRound(p, playerStats, n, oppCoins);
            double roi = ev * ROI_GEOMETRIC_SUM - p.getCost();
            cards.add(p);
            scores[count++] = roi;
        }

        if (count == 0) return -1;

        double maxScore = scores[0];
        for (int i = 1; i < count; i++) if (scores[i] > maxScore) maxScore = scores[i];

        double[] weights = new double[count];
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            weights[i] = Math.exp((scores[i] - maxScore) / temperature);
            total += weights[i];
        }

        double r = rng.nextDouble() * total;
        int chosen = count - 1;
        for (int i = 0; i < count; i++) {
            r -= weights[i];
            if (r <= 0) { chosen = i; break; }
        }

        purchase(player, cards.get(chosen), supply);
        return -1;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static void purchase(Player player, Project card, Map<String, Integer> supply) {
        player.setCoins(player.getCoins() - card.getCost());
        player.addProject(card);
        if (!card.isIs_grossprojekt()) {
            supply.merge(card.getId(), -1, Integer::sum);
        }
    }

    static boolean hasHighRangeCard(Player player) {
        for (Project p : player.getOwned_projects()) {
            if (p.isIs_grossprojekt()) continue;
            for (int activation : p.getDice_activation()) {
                if (activation >= 7) return true;
            }
        }
        return false;
    }

    /**
     * Builds the remaining market supply for all non-landmark cards.
     *
     * <p><b>Starter card invariant:</b> Weizenfeld and Bäckerei given at game start are
     * separate from the 6-copy market pool. Subtracting all owned copies then adding back
     * {@code numPlayers} starter copies ensures the market supply is correct.
     *
     * @see GameState#starterCopies(String, int)
     */
    static Map<String, Integer> buildSupply(GameState state) {
        Map<String, Integer> supply = new HashMap<>();
        for (Project p : ProjectLoader.getAllProjects()) {
            if (!p.isIs_grossprojekt()) supply.put(p.getId(), SUPPLY_PER_CARD);
        }
        for (Player player : state.getPlayers()) {
            for (Project p : player.getOwned_projects()) {
                if (!p.isIs_grossprojekt()) supply.merge(p.getId(), -1, Integer::sum);
            }
        }
        // Add back starter copies — each player's starter weizenfeld/bäckerei was given
        // outside the market pool, so those copies must not reduce market supply.
        int numPlayers = state.getPlayers().length;
        for (Project p : ProjectLoader.getAllProjects()) {
            int starters = GameState.starterCopies(p.getId(), numPlayers);
            if (starters > 0) supply.merge(p.getId(), starters, Integer::sum);
        }
        return supply;
    }

    // -------------------------------------------------------------------------
    // BitState simulation (Phase 2 hot path)
    // -------------------------------------------------------------------------

    /**
     * Simulates a complete game using BitState for all state mutation.
     * The supplied BitState is mutated.
     *
     * @return index of the winning player, or -1 on timeout
     */
    private static int simulateBitState(BitState bs, int numPlayers, Random rng, double temperature) {
        int[] supply = bs.buildSupplyArray();
        int totalTurns = 0;
        int activePlayer = 0;

        while (totalTurns < MAX_TURNS) {
            int roll = rollDiceBit(bs, activePlayer, rng);
            bs.applyRoll(activePlayer, roll);

            int winner = (temperature <= 0.0)
                    ? greedyBuyBit(bs, activePlayer, numPlayers, supply)
                    : boltzmannBuyBit(bs, activePlayer, numPlayers, supply, rng, temperature);
            if (winner >= 0) return winner;

            activePlayer = (activePlayer + 1) % numPlayers;
            totalTurns++;
        }

        return -1;
    }

    /**
     * BitState dice roll: decides 1d6 vs 2d6 based on Bahnhof + high-range cards.
     * If doubles and Freizeitpark, applies bonus turn income via {@code bs.applyRoll}.
     *
     * @return the main roll value (caller must apply income for this roll)
     */
    private static int rollDiceBit(BitState bs, int activePlayer, Random rng) {
        boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);

        if (!hasBahnhof || !bs.hasHighRangeCard(activePlayer)) {
            return 1 + rng.nextInt(6);
        }

        int d1 = 1 + rng.nextInt(6);
        int d2 = 1 + rng.nextInt(6);
        int roll2 = d1 + d2;

        if (d1 == d2 && bs.hasLandmark(activePlayer, BitStateTranslator.LM_FZP)) {
            int bonus = 1 + rng.nextInt(6) + 1 + rng.nextInt(6);
            bs.applyRoll(activePlayer, bonus);
        }

        return roll2;
    }

    /**
     * BitState greedy buy: landmarks first (cheapest), then best-ROI establishment.
     *
     * @return winning player index, or -1 if no one won
     */
    private static int greedyBuyBit(BitState bs, int activePlayer, int numPlayers, int[] supply) {
        int coins = bs.getCoins(activePlayer);

        // Landmark-first: iterate in buy order, find first unowned
        for (int li : BitStateTranslator.LANDMARK_BUY_ORDER) {
            if (!bs.hasLandmark(activePlayer, li)) {
                // Skip Bahnhof if no high-range card, unless Bahnhof is the winning purchase
                if (li == BitStateTranslator.LM_BAHNHOF && !bs.hasHighRangeCard(activePlayer)
                        && bs.getLandmarkCount(activePlayer) < 3) break;
                if (coins >= BitStateTranslator.LANDMARK_COSTS[li]) {
                    bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[li]);
                    bs.setLandmark(activePlayer, li);
                    if (bs.hasWon(activePlayer)) return activePlayer;
                    coins = bs.getCoins(activePlayer);
                }
                break; // only consider first unowned landmark
            }
        }

        // Establishment buy: best ROI, iterating in ProjectLoader order for consistency
        CardIncome.PlayerStats stats = bs.buildPlayerStats(activePlayer);
        int[] oppCoins = bs.buildOpponentCoins(activePlayer);
        coins = bs.getCoins(activePlayer);

        int bestCandIdx = -1;
        double bestROI = -Double.MAX_VALUE;

        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;

            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                double ev = CardIncome.contextualCardEvPerRound(
                        BitStateTranslator.PURPLE_CARD_PROJECTS[idx], stats, numPlayers, oppCoins);
                double roi = ev * ROI_GEOMETRIC_SUM - BitStateTranslator.PURPLE_CARD_COSTS[idx];
                if (roi > bestROI) { bestROI = roi; bestCandIdx = ci; }
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                double ev = CardIncome.contextualCardEvPerRound(
                        BitStateTranslator.NORMAL_CARD_PROJECTS[idx], stats, numPlayers, oppCoins);
                double roi = ev * ROI_GEOMETRIC_SUM - BitStateTranslator.NORMAL_CARD_COSTS[idx];
                if (roi > bestROI) { bestROI = roi; bestCandIdx = ci; }
            }
        }

        if (bestCandIdx >= 0) {
            boolean isPurple = bestCandIdx >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? bestCandIdx - BitStateTranslator.NUM_NORMAL_CARDS : bestCandIdx;
            if (isPurple) {
                bs.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[idx]);
                bs.setPurple(activePlayer, idx);
            } else {
                bs.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[idx]);
                bs.addCard(activePlayer, idx);
                supply[idx]--;
            }
        }

        return -1;
    }

    /**
     * BitState Boltzmann buy: landmarks first (greedy), then softmax-sampled establishment.
     * Iterates candidates in ProjectLoader order for equivalence with object-based path.
     *
     * @return -1 (no winner from establishment purchase)
     */
    private static int boltzmannBuyBit(BitState bs, int activePlayer, int numPlayers,
                                        int[] supply, Random rng, double temperature) {
        int coins = bs.getCoins(activePlayer);

        // Landmark-first (same greedy logic)
        for (int li : BitStateTranslator.LANDMARK_BUY_ORDER) {
            if (!bs.hasLandmark(activePlayer, li)) {
                if (li == BitStateTranslator.LM_BAHNHOF && !bs.hasHighRangeCard(activePlayer)
                        && bs.getLandmarkCount(activePlayer) < 3) break;
                if (coins >= BitStateTranslator.LANDMARK_COSTS[li]) {
                    bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[li]);
                    bs.setLandmark(activePlayer, li);
                    if (bs.hasWon(activePlayer)) return activePlayer;
                    coins = bs.getCoins(activePlayer);
                }
                break;
            }
        }

        // Collect candidates with ROI scores, iterating in ProjectLoader order
        CardIncome.PlayerStats stats = bs.buildPlayerStats(activePlayer);
        int[] oppCoins = bs.buildOpponentCoins(activePlayer);
        coins = bs.getCoins(activePlayer);

        // Max 15 candidates (12 normal + 3 purple)
        int[] candidateIdx = new int[15];
        double[] scores = new double[15];
        int count = 0;

        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;

            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                double ev = CardIncome.contextualCardEvPerRound(
                        BitStateTranslator.PURPLE_CARD_PROJECTS[idx], stats, numPlayers, oppCoins);
                candidateIdx[count] = ci;
                scores[count] = ev * ROI_GEOMETRIC_SUM - BitStateTranslator.PURPLE_CARD_COSTS[idx];
                count++;
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                double ev = CardIncome.contextualCardEvPerRound(
                        BitStateTranslator.NORMAL_CARD_PROJECTS[idx], stats, numPlayers, oppCoins);
                candidateIdx[count] = ci;
                scores[count] = ev * ROI_GEOMETRIC_SUM - BitStateTranslator.NORMAL_CARD_COSTS[idx];
                count++;
            }
        }

        if (count == 0) return -1;

        // Softmax sampling
        double maxScore = scores[0];
        for (int i = 1; i < count; i++) if (scores[i] > maxScore) maxScore = scores[i];

        double[] weights = new double[count];
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            weights[i] = Math.exp((scores[i] - maxScore) / temperature);
            total += weights[i];
        }

        double r = rng.nextDouble() * total;
        int chosen = count - 1;
        for (int i = 0; i < count; i++) {
            r -= weights[i];
            if (r <= 0) { chosen = i; break; }
        }

        int ci = candidateIdx[chosen];
        boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
        int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
        if (isPurple) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[idx]);
            bs.setPurple(activePlayer, idx);
        } else {
            bs.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[idx]);
            bs.addCard(activePlayer, idx);
            supply[idx]--;
        }

        return -1;
    }
}
