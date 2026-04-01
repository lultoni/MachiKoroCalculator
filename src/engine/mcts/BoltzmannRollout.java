package engine.mcts;

import calcs.Calcs;
import calcs.RankEntry;
import calcs.WinProbability;
import core.GameState;
import core.Player;
import core.Project;
import core.ProjectLoader;
import core.RollResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boltzmann (softmax) rollout policy for Variant B of the MCTS engine.
 *
 * <p>The tree phase is unchanged (full UCT). The rollout purchase policy samples
 * from a Boltzmann distribution over ROI scores with temperature T:
 * {@code P(card_i) ∝ exp(roi_i / T)}.
 *
 * <p>Landmark purchases are deterministic (always buy the cheapest affordable landmark first).
 * Dice count, Funkturm, and Bürohaus decisions use the same greedy rules as Variant A.
 *
 * <h2>Temperature semantics</h2>
 * <ul>
 *   <li>T → 0: pure greedy (argmax), equivalent to Variant A in the limit.</li>
 *   <li>T = 0.7: informed but stochastic — the default.</li>
 *   <li>T → ∞: uniform random, equivalent to Variant v1 in the limit.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} — safe for concurrent use.
 */
public final class BoltzmannRollout {

    private static final String[] LANDMARK_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int    BOLTZMANN_HORIZON  = 5;
    private static final double BOLTZMANN_DISCOUNT = 0.95;

    private BoltzmannRollout() {}

    /**
     * Creates a {@link RolloutFn} that uses the Boltzmann policy with the given temperature.
     *
     * @param temperature Boltzmann temperature T (> 0)
     * @return a RolloutFn suitable for passing to {@link MctsTree}
     */
    public static RolloutFn withTemperature(double temperature) {
        return (state, supply, startingPlayer, playerPerspective) ->
                simulate(state, supply, startingPlayer, playerPerspective, temperature);
    }

    // -------------------------------------------------------------------------
    // Core simulation
    // -------------------------------------------------------------------------

    static double simulate(GameState startState, SupplyTracker startSupply,
                           int startingPlayer, int playerPerspective, double temperature) {
        GameState state      = startState.copy();
        SupplyTracker supply = startSupply;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int n            = state.getPlayers().length;
        int activePlayer = startingPlayer;
        int turnCount    = 0;

        while (turnCount < MctsRollout.MAX_TURNS) {
            Player active = state.getPlayers()[activePlayer];

            // ---- Dice count: greedy (same as Variant A) ----
            boolean hasBahnhof = active.hasProject("bahnhof");
            boolean twoDice    = hasBahnhof && hasHigh7to12Card(active);

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7); int d2 = rng.nextInt(1, 7);
                roll = d1 + d2; doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: greedy ----
            if (active.hasProject("funkturm")) {
                double cur    = computeRollIncome(state, activePlayer, roll);
                double expEV  = computeExpectedRollIncome(state, activePlayer, twoDice);
                if (cur < expEV) {
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7); int d2 = rng.nextInt(1, 7);
                        roll = d1 + d2; doubles = (d1 == d2);
                    } else {
                        roll = rng.nextInt(1, 7); doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
            for (int i = 0; i < n; i++) {
                state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
            }

            // ---- Bürohaus: greedy ----
            if (active.hasProject("bürohaus") && roll == 6) {
                core.BürohausLogic.executeSwap(state, activePlayer);
            }

            // ---- Purchase: Boltzmann ----
            supply = applyPurchaseBoltzmann(state, supply, activePlayer, temperature, rng);

            // ---- Win check ----
            if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            boolean hasFreizeit = state.getPlayers()[activePlayer].hasProject("freizeitpark");
            if (hasFreizeit && doubles) {
                supply = playBonusTurn(state, supply, activePlayer, temperature, rng);
                if (GameState.hasWon(state.getPlayers()[activePlayer])) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        return WinProbability.computeBaselineWinProb(state, playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Boltzmann purchase
    // -------------------------------------------------------------------------

    private static SupplyTracker applyPurchaseBoltzmann(GameState state, SupplyTracker supply,
                                                         int activePlayer, double temperature,
                                                         ThreadLocalRandom rng) {
        Player active = state.getPlayers()[activePlayer];
        int coins = active.getCoins();

        // 1. Landmark priority (deterministic — same as greedy)
        int cheapestCost = Integer.MAX_VALUE;
        Project landmarkToBuy = null;
        for (String lmId : LANDMARK_IDS) {
            if (!active.hasProject(lmId)) {
                Project lm = ProjectLoader.getProject(lmId).orElse(null);
                if (lm != null && coins >= lm.getCost() && lm.getCost() < cheapestCost) {
                    cheapestCost   = lm.getCost();
                    landmarkToBuy = lm;
                }
            }
        }
        if (landmarkToBuy != null) {
            active.setCoins(coins - landmarkToBuy.getCost());
            active.getOwned_projects().add(landmarkToBuy);
            return supply;
        }

        // 2. Boltzmann sampling over non-landmark cards + save
        List<Project> candidates = new ArrayList<>();
        List<Double> scores      = new ArrayList<>();

        // Save option: ROI = 0
        candidates.add(RankEntry.WAIT_SENTINEL);
        scores.add(0.0);

        for (Project p : state.getUnbuilt_projects()) {
            if (!supply.canPurchase(p.getId())) continue;
            if (coins < p.getCost()) continue;
            // Purple cards (lila) are unique — max 1 per player per type
            if ("lila".equals(p.getColor()) && active.hasProject(p.getId())) continue;
            double ev  = Calcs.evPerRound(state, activePlayer, p);
            double roi = ev * Calcs.geometricSum(BOLTZMANN_HORIZON, BOLTZMANN_DISCOUNT) - p.getCost();
            candidates.add(p);
            scores.add(roi);
        }

        // Compute Boltzmann probabilities: P_i ∝ exp(score_i / T)
        double[] probs = new double[candidates.size()];
        double sum = 0.0;
        for (int i = 0; i < scores.size(); i++) {
            probs[i] = Math.exp(scores.get(i) / temperature);
            sum += probs[i];
        }
        if (sum < 1e-12) {
            // Degenerate: uniform
            sum = candidates.size();
            for (int i = 0; i < probs.length; i++) probs[i] = 1.0;
        }

        // Sample
        double r = rng.nextDouble() * sum;
        double cumulative = 0.0;
        int chosen = 0;
        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (r <= cumulative) { chosen = i; break; }
        }

        Project card = candidates.get(chosen);
        if (card == RankEntry.WAIT_SENTINEL) return supply; // save

        active.setCoins(coins - card.getCost());
        active.getOwned_projects().add(card);
        supply = supply.withPurchase(card.getId());
        return supply;
    }

    // -------------------------------------------------------------------------
    // Shared helpers (same as GreedyRollout)
    // -------------------------------------------------------------------------

    private static boolean hasHigh7to12Card(Player p) {
        for (Project card : p.getOwned_projects()) {
            for (int act : card.getDice_activation()) {
                if (act >= 7 && act <= 12) return true;
            }
        }
        return false;
    }

    private static double computeRollIncome(GameState state, int playerIndex, int roll) {
        int[] deltas = RollResolver.computeAllDeltasForRoll(state, playerIndex, roll);
        return deltas[playerIndex];
    }

    private static double computeExpectedRollIncome(GameState state, int playerIndex, boolean twoDice) {
        double ev = 0.0;
        if (twoDice) {
            for (int r = 2; r <= 12; r++) ev += core.CardIncome.P2[r] * computeRollIncome(state, playerIndex, r);
        } else {
            for (int r = 1; r <= 6; r++) ev += core.CardIncome.P1[r] * computeRollIncome(state, playerIndex, r);
        }
        return ev;
    }

    private static SupplyTracker playBonusTurn(GameState state, SupplyTracker supply,
                                                int activePlayer, double temperature,
                                                ThreadLocalRandom rng) {
        Player active   = state.getPlayers()[activePlayer];
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean twoDice    = hasBahnhof && hasHigh7to12Card(active);
        int n = state.getPlayers().length;

        int roll = twoDice ? rng.nextInt(1,7) + rng.nextInt(1,7) : rng.nextInt(1,7);

        if (active.hasProject("funkturm")) {
            double cur = computeRollIncome(state, activePlayer, roll);
            double exp = computeExpectedRollIncome(state, activePlayer, twoDice);
            if (cur < exp) roll = twoDice ? rng.nextInt(1,7) + rng.nextInt(1,7) : rng.nextInt(1,7);
        }

        int[] deltas = RollResolver.computeAllDeltasForRoll(state, activePlayer, roll);
        for (int i = 0; i < n; i++) {
            state.getPlayers()[i].setCoins(Math.max(0, state.getPlayers()[i].getCoins() + deltas[i]));
        }
        if (active.hasProject("bürohaus") && roll == 6) core.BürohausLogic.executeSwap(state, activePlayer);
        return applyPurchaseBoltzmann(state, supply, activePlayer, temperature, rng);
    }
}
