package engine.mcts;

import core.BitState;
import core.GameState;
import engine.EngineConfig;
import engine.EngineResult;
import engine.TurnPlan;

/**
 * Variant B engine: MCTS with a Boltzmann (softmax) rollout policy.
 *
 * <p>Identical to {@link MctsV1Engine} except the rollout purchase decisions use
 * Boltzmann sampling over ROI scores with configurable temperature T.
 * The tree phase (UCT) and all other rollout decisions (dice, Funkturm, Bürohaus) are unchanged.
 *
 * <p>Temperature T is read from {@code extra.rolloutTemperature} (default {@code "0.7"}).
 *
 * <h2>Hypothesis</h2>
 * Stochastic-but-informed rollouts offer a better exploration/accuracy trade-off than
 * either uniform random (v1) or pure greedy (Variant A).
 */
public final class MctsBoltzmannRolloutEngine extends MctsV1Engine {

    public static final String ENGINE_ID = "mcts-v1-boltzmann-rollout";

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String description() {
        return "MCTS Variant B — full UCT tree with Boltzmann rollout policy";
    }

    @Override
    protected MctsTree buildTree(BitState bs, int[] supply,
                                 int activePlayer, int playerPerspective,
                                 double explorationConstant) {
        double temperature = currentTemperature.get();
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, BitBoltzmannRollout.withTemperature(temperature));
    }

    @Override
    protected MctsTree buildFullTurnTree(BitState bs, int[] supply,
                                          int activePlayer, int playerPerspective,
                                          double explorationConstant) {
        double temperature = currentTemperature.get();
        return new MctsTree(bs, supply, activePlayer, playerPerspective,
                explorationConstant, BitBoltzmannRollout.withTemperature(temperature), false, true);
    }

    // -------------------------------------------------------------------------
    // Thread-local to pass temperature from evaluate() into buildTree()
    // -------------------------------------------------------------------------

    private static final ThreadLocal<Double> currentTemperature =
            ThreadLocal.withInitial(() -> 0.7);

    @Override
    public EngineResult evaluate(GameState state, int playerIndex, EngineConfig config) {
        double temperature = Double.parseDouble(
                config.getExtra("rolloutTemperature", "0.7"));
        currentTemperature.set(temperature);
        try {
            return super.evaluate(state, playerIndex, config);
        } finally {
            currentTemperature.remove();
        }
    }

    @Override
    public TurnPlan evaluateFullTurn(GameState state, int playerIndex, EngineConfig config) {
        double temperature = Double.parseDouble(
                config.getExtra("rolloutTemperature", "0.7"));
        currentTemperature.set(temperature);
        try {
            return super.evaluateFullTurn(state, playerIndex, config);
        } finally {
            currentTemperature.remove();
        }
    }
}
