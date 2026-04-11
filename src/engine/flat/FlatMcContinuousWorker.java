package engine.flat;

import core.BitState;
import core.GameState;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.NavigationEvent;

import java.util.Arrays;
import java.util.List;

/**
 * {@link ContinuousWorker} implementation for {@link FlatMcEngine}.
 *
 * <p>Maintains a list of {@link FlatMcEngine.CandidateOption} with accumulated samples.
 * On each iteration, the candidate with the fewest samples receives one rollout.
 *
 * <h2>State change handling</h2>
 * Any lock-in event causes a full reset: the candidate list is rebuilt from the new
 * game state and accumulators are zeroed. This is safe because FlatMC accumulates
 * ~25K samples/second — a few hundred ms of lost work is trivial compared to the
 * 30-60s of continuous accumulation during a human turn.
 *
 * <h2>peekResult</h2>
 * Builds an {@link EngineResult} by reading current win rates from candidate accumulators.
 * Read-only access to {@code samples} and {@code wins} is thread-safe because
 * both are primitive fields and {@code int} reads are atomic per JLS §17.7.
 */
public final class FlatMcContinuousWorker implements ContinuousWorker {

    private final FlatMcEngine engine = new FlatMcEngine();

    /** Enumerated candidates, with accumulated rollout results. */
    private List<FlatMcEngine.CandidateOption> candidates;

    /** Bit state at current position (snapshot taken at init/navigate). */
    private BitState currentState;

    private int[] currentSupply;
    private int playerIndex;
    private int numPlayers;
    private EngineConfig config;
    private int iterCount;

    @Override
    public boolean supportsContinuousMode() { return true; }

    @Override
    public void init(BitState state, int[] supply, int playerIndex, EngineConfig config) {
        this.currentState  = state;
        this.currentSupply = supply;
        this.playerIndex   = playerIndex;
        this.numPlayers    = state.getNumPlayers();
        this.config        = config;

        int coins = state.getCoins(playerIndex);
        this.candidates = engine.enumerateOptions(state, supply, playerIndex, coins);

        // Initial survey: 20 samples per candidate
        int nextPlayer = (playerIndex + 1) % numPlayers;
        for (FlatMcEngine.CandidateOption c : candidates) {
            if (!c.unaffordable && !c.isInstantWin) {
                engine.runSamples(c, 20, nextPlayer, playerIndex);
            }
        }
        this.iterCount = 0;
    }

    @Override
    public void runOneIteration() {
        if (candidates == null || candidates.isEmpty()) return;

        // Pick the candidate with the fewest samples (round-robin least-sampled)
        FlatMcEngine.CandidateOption target = null;
        int minSamples = Integer.MAX_VALUE;
        for (FlatMcEngine.CandidateOption c : candidates) {
            if (c.unaffordable || c.isInstantWin) continue;
            if (c.samples < minSamples) {
                minSamples = c.samples;
                target = c;
            }
        }

        if (target == null) return;

        int nextPlayer = (playerIndex + 1) % numPlayers;
        engine.runSamples(target, 1, nextPlayer, playerIndex);
        iterCount++;
    }

    @Override
    public EngineResult peekResult(GameState state, int playerIdx, EngineConfig cfg) {
        if (candidates == null || iterCount == 0) return null;
        int coins = currentState.getCoins(playerIdx);
        return engine.buildResult(candidates, coins, numPlayers, iterCount, 0L);
    }

    @Override
    public boolean navigate(NavigationEvent event) {
        // FlatMC always resets: rollout results from old state are not valid for new state
        return false;
    }

    @Override
    public int iterations() { return iterCount; }
}
