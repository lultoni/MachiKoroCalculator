package calcs;

import core.*;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reusable harness for playing full games and sampling {@link GameState} snapshots
 * at turn boundaries. Designed for callback-based analysis: the caller provides a
 * {@link SamplingStrategy} (which turns to inspect) and a {@link TurnEvaluator}
 * (what to compute at each sampled turn).
 *
 * <p>Uses {@link GameSimulator}'s package-private game-play methods to avoid
 * code duplication. Games follow the same greedy/Boltzmann buy policy as
 * {@code GameSimulator.simulate()}.
 *
 * <p><b>Snapshot timing:</b> snapshots are taken <i>after</i> income resolution
 * and <i>before</i> the buy phase. This is the decision point where win-rate
 * estimates are most meaningful — the player has received income and is about to
 * choose what to buy.
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 * GameStateSampler.runGames(100, 2, 0.0, GameStateSampler.everyKTurns(5), snapshot -> {
 *     double softmax = WinProbability.computeBaselineWinProb(snapshot.state(), snapshot.activePlayer());
 *     double mc = GameSimulator.mcWinRate(snapshot.state(), snapshot.activePlayer(), 200);
 *     // accumulate error...
 * });
 * }</pre>
 */
public final class GameStateSampler {

    private GameStateSampler() {}

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of a game position at a turn boundary (after income, before buy).
     *
     * @param state        deep copy of the game state at the snapshot moment
     * @param activePlayer index of the player about to make a buy decision
     * @param turnNumber   0-based turn counter (increments once per player turn)
     * @param roll         dice total that was rolled this turn
     * @param usedTwoDice  true if the player rolled 2d6 (has Bahnhof + high-range cards)
     * @param isDoubles    true if the 2d6 roll was doubles (relevant for Freizeitpark)
     * @param gameIndex    0-based index of the current game within the batch
     */
    public record TurnSnapshot(GameState state, int activePlayer, int turnNumber,
                                int roll, boolean usedTwoDice, boolean isDoubles, int gameIndex) {}

    /**
     * Decides which turns to sample. Return {@code true} to trigger a snapshot + evaluation.
     */
    @FunctionalInterface
    public interface SamplingStrategy {
        boolean shouldSample(int turnNumber, int activePlayer, GameState state);
    }

    /**
     * Callback invoked for each sampled turn. Receives an immutable {@link TurnSnapshot}.
     */
    @FunctionalInterface
    public interface TurnEvaluator {
        void evaluate(TurnSnapshot snapshot);
    }

    // -------------------------------------------------------------------------
    // Built-in sampling strategies
    // -------------------------------------------------------------------------

    /** Sample every k-th turn (0, k, 2k, ...). */
    public static SamplingStrategy everyKTurns(int k) {
        return (turnNumber, activePlayer, state) -> turnNumber % k == 0;
    }

    /** Sample turns in [min, max] inclusive. */
    public static SamplingStrategy turnRange(int min, int max) {
        return (turnNumber, activePlayer, state) -> turnNumber >= min && turnNumber <= max;
    }

    /** Sample every turn. */
    public static SamplingStrategy allTurns() {
        return (turnNumber, activePlayer, state) -> true;
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Plays {@code numGames} complete games and invokes {@code evaluator} at each turn
     * where {@code strategy} returns {@code true}. Snapshots are taken after income
     * resolution and before the buy phase.
     *
     * <p>Games use the same greedy/Boltzmann buy policy as {@link GameSimulator#simulate}.
     * The evaluator is called synchronously within the game loop — no concurrency
     * concerns unless the evaluator itself spawns threads.
     *
     * @param numGames   number of games to play
     * @param numPlayers number of players per game (typically 2)
     * @param temperature Boltzmann temperature for buy decisions (0.0 = greedy)
     * @param strategy   decides which turns to sample
     * @param evaluator  callback invoked at each sampled turn (post-income snapshot)
     */
    public static void runGames(int numGames, int numPlayers, double temperature,
                                SamplingStrategy strategy, TurnEvaluator evaluator) {
        runGames(numGames, numPlayers, temperature, strategy, null, evaluator);
    }

    /**
     * Plays {@code numGames} complete games with two evaluator hooks:
     * <ul>
     *   <li>{@code preRollEvaluator} — called <b>before</b> dice roll and income resolution
     *       (state = end of previous turn's buy phase). Useful for luck analysis.</li>
     *   <li>{@code postIncomeEvaluator} — called <b>after</b> income resolution, before buy.
     *       Useful for WR accuracy testing.</li>
     * </ul>
     *
     * <p>Either evaluator may be {@code null} to skip that hook.
     *
     * @param numGames            number of games to play
     * @param numPlayers          number of players per game (typically 2)
     * @param temperature         Boltzmann temperature for buy decisions (0.0 = greedy)
     * @param strategy            decides which turns to sample
     * @param preRollEvaluator    callback before dice roll (may be null)
     * @param postIncomeEvaluator callback after income, before buy (may be null)
     */
    public static void runGames(int numGames, int numPlayers, double temperature,
                                SamplingStrategy strategy,
                                TurnEvaluator preRollEvaluator,
                                TurnEvaluator postIncomeEvaluator) {
        for (int g = 0; g < numGames; g++) {
            Random rng = ThreadLocalRandom.current();
            GameState state = GameState.initial(numPlayers);
            Map<String, Integer> supply = GameSimulator.buildSupply(state);

            int n = state.getPlayers().length;
            int activePlayer = 0;
            int totalTurns = 0;

            while (totalTurns < GameSimulator.MAX_TURNS) {
                Player player = state.getPlayers()[activePlayer];
                boolean hasBahnhof = player.hasProject("bahnhof");
                boolean hasHighRange = GameSimulator.hasHighRangeCard(player);
                boolean usedTwoDice = hasBahnhof && hasHighRange;

                // Roll dice
                int roll;
                boolean isDoubles = false;
                if (!hasBahnhof || !hasHighRange) {
                    roll = 1 + rng.nextInt(6);
                } else {
                    int d1 = 1 + rng.nextInt(6);
                    int d2 = 1 + rng.nextInt(6);
                    roll = d1 + d2;
                    isDoubles = (d1 == d2);
                }

                // --- Pre-roll snapshot (before income) ---
                boolean sampled = strategy.shouldSample(totalTurns, activePlayer, state);
                if (sampled && preRollEvaluator != null) {
                    TurnSnapshot preSnapshot = new TurnSnapshot(
                            state.copy(), activePlayer, totalTurns,
                            roll, usedTwoDice, isDoubles, g);
                    preRollEvaluator.evaluate(preSnapshot);
                }

                // Apply income (including Freizeitpark bonus turn handling for doubles)
                if (isDoubles && player.hasProject("freizeitpark")) {
                    int bonusRoll = 1 + rng.nextInt(6) + 1 + rng.nextInt(6);
                    GameSimulator.applyRoll(state, activePlayer, bonusRoll);
                }
                GameSimulator.applyRoll(state, activePlayer, roll);

                // --- Post-income snapshot (before buy) ---
                if (sampled && postIncomeEvaluator != null) {
                    TurnSnapshot postSnapshot = new TurnSnapshot(
                            state.copy(), activePlayer, totalTurns,
                            roll, usedTwoDice, isDoubles, g);
                    postIncomeEvaluator.evaluate(postSnapshot);
                }

                // Buy phase
                int winner;
                if (temperature <= 0.0) {
                    winner = GameSimulator.greedyBuy(state, activePlayer, supply);
                } else {
                    winner = GameSimulator.boltzmannBuy(state, activePlayer, supply, rng, temperature);
                }
                if (winner >= 0) break;

                activePlayer = (activePlayer + 1) % n;
                totalTurns++;
            }
        }
    }
}
