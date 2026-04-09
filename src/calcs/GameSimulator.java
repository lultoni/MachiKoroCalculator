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

    /** Simulates a complete game with greedy policy (temperature=0). State is mutated. */
    public static int simulate(GameState state, Random rng) {
        return simulate(state, rng, 0.0);
    }

    /**
     * Simulates a complete game with the specified Boltzmann temperature.
     * The supplied state is mutated — pass a copy if the original must be preserved.
     *
     * @return index of the winning player, or -1 on timeout
     */
    public static int simulate(GameState state, Random rng, double temperature) {
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
     */
    public static double mcWinRate(GameState state, int playerIndex, int numSims, double temperature) {
        int[] outcomes = IntStream.range(0, numSims)
                .parallel()
                .map(i -> simulate(state.copy(), ThreadLocalRandom.current(), temperature))
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
                if (lmId.equals("bahnhof") && !hasHighRangeCard(player)) break;
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
                if (lmId.equals("bahnhof") && !hasHighRangeCard(player)) break;
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
        return supply;
    }
}
