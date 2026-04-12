package engine.creator;

import core.BitState;
import core.BitStateTranslator;
import core.GameState;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.NavigationEvent;
import engine.mcts.BitRolloutFn;
import engine.mcts.SupplyTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ContinuousWorker} implementation for {@link CreatorEngine}.
 *
 * <p>Phase 1 (heuristic seeding via {@link CreatorScorer}) runs once on init.
 * Phase 2 (MC validation) accumulates samples continuously. On each iteration,
 * allocation follows Creator's strategy: top-3 candidates by heuristic score
 * receive more attention, then next-5, then the rest.
 *
 * <h2>State change handling</h2>
 * Any lock-in event causes a full reset (same as {@link FlatMcContinuousWorker}).
 * MC results from the old position are invalid for the new position.
 *
 * <h2>peekResult</h2>
 * Once MC samples exceed a threshold, returns MC win rates.
 * Before that threshold, returns heuristic-only result.
 */
public final class CreatorContinuousWorker implements ContinuousWorker {

    private static final int MC_THRESHOLD = 100; // min total samples before using MC result

    private final CreatorEngine engine = new CreatorEngine();

    private volatile List<CreatorEngine.CandidateOption> candidates;
    private BitRolloutFn rolloutFn;
    private int playerIndex;
    private int numPlayers;
    private int coins;
    private int iterCount;
    private int totalMcSamples;

    // Cycle counter for allocation strategy: track which "tier" to sample next
    private int allocationCycle;

    @Override
    public boolean supportsContinuousMode() { return true; }

    @Override
    public void init(BitState state, int[] supply, int playerIndex, EngineConfig config) {
        this.playerIndex = playerIndex;
        this.numPlayers  = state.getNumPlayers();
        this.coins       = state.getCoins(playerIndex);
        this.rolloutFn   = engine.selectRolloutFn(config);
        this.iterCount   = 0;
        this.totalMcSamples = 0;
        this.allocationCycle = 0;

        // Phase 1: heuristic seeding — requires GameState for CreatorScorer
        GameState gs = state.toGameState();
        SupplyTracker baseSupply = SupplyTracker.fromGameState(gs);
        int[] rootSupplyArr = supply;

        List<CreatorScorer.ScoredCandidate> scored = CreatorScorer.scoreAll(gs, playerIndex, baseSupply, config);

        // Build into a local list first, then assign atomically so runOneIteration()
        // never sees a partially-populated candidates reference.
        List<CreatorEngine.CandidateOption> newCandidates = new ArrayList<>();
        for (CreatorScorer.ScoredCandidate sc : scored) {
            boolean isSave = sc.card == calcs.RankEntry.WAIT_SENTINEL;
            boolean isInstantWin = sc.compositeScore == Double.MAX_VALUE;

            if (isSave) {
                CreatorEngine.CandidateOption opt = new CreatorEngine.CandidateOption(
                        sc.card, state, rootSupplyArr, false, true,
                        sc.compositeScore, sc.metrics, sc.factors, true, sc.activationGuard);
                newCandidates.add(opt);
                continue;
            }

            boolean canAfford = sc.affordable;
            BitState childBS = state.copy();
            int[] childSupply = rootSupplyArr;

            int normalIdx   = BitStateTranslator.normalCardIndex(sc.card.getId());
            int purpleIdx   = BitStateTranslator.purpleCardIndex(sc.card.getId());
            int landmarkIdx = BitStateTranslator.landmarkIndex(sc.card.getId());

            if (normalIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.addCard(playerIndex, normalIdx);
                childSupply = Arrays.copyOf(rootSupplyArr, rootSupplyArr.length);
                childSupply[normalIdx]--;
            } else if (purpleIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.setPurple(playerIndex, purpleIdx);
            } else if (landmarkIdx >= 0) {
                if (canAfford) childBS.setCoins(playerIndex, coins - sc.card.getCost());
                childBS.setLandmark(playerIndex, landmarkIdx);
            }

            if (isInstantWin) {
                CreatorEngine.CandidateOption opt = new CreatorEngine.CandidateOption(
                        sc.card, childBS, childSupply, true, false,
                        sc.compositeScore, sc.metrics, sc.factors, canAfford, sc.activationGuard);
                opt.wins = 1; opt.samples = 1;
                newCandidates.add(opt);
                continue;
            }

            CreatorEngine.CandidateOption opt = new CreatorEngine.CandidateOption(
                    sc.card, childBS, childSupply, false, false,
                    sc.compositeScore, sc.metrics, sc.factors, canAfford, sc.activationGuard);
            opt.unaffordable = !canAfford;
            newCandidates.add(opt);
        }

        // Initial survey: 20 samples per eligible MC candidate
        int nextPlayer = (playerIndex + 1) % numPlayers;
        for (CreatorEngine.CandidateOption c : newCandidates) {
            if (!c.unaffordable && !c.isInstantWin && !c.isSave && c.activationGuard > 0.0) {
                engine.runSamples(c, 20, nextPlayer, playerIndex, rolloutFn);
                totalMcSamples += 20;
            }
        }

        // Single atomic write — worker thread sees either the old complete list or the new
        // complete list, never a partially-constructed one.
        this.candidates = newCandidates;
    }

    @Override
    public void runOneIteration() {
        // Snapshot the reference once — if init() replaces candidates concurrently,
        // we keep iterating over the old (complete) list safely.
        List<CreatorEngine.CandidateOption> snap = candidates;
        if (snap == null || snap.isEmpty()) return;

        // Build MC-eligible candidates sorted by heuristic score descending
        List<CreatorEngine.CandidateOption> eligible = new ArrayList<>();
        for (CreatorEngine.CandidateOption c : snap) {
            if (!c.unaffordable && !c.isInstantWin && !c.isSave && c.activationGuard > 0.0) {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) return;

        // Sort by heuristic score descending (stable sort — reflects Creator's intent)
        eligible.sort((a, b) -> Double.compare(b.heuristicScore, a.heuristicScore));

        // Allocation strategy (mirrors CreatorEngine.allocateAndRun proportions):
        //   top-3 → 50%, next-5 → 30%, rest → 20% of attention
        // We implement this as a simple cycle: pick from the appropriate tier
        CreatorEngine.CandidateOption target = allocateTarget(eligible);

        int nextPlayer = (playerIndex + 1) % numPlayers;
        engine.runSamples(target, 1, nextPlayer, playerIndex, rolloutFn);
        totalMcSamples++;
        iterCount++;
        allocationCycle++;
    }

    private CreatorEngine.CandidateOption allocateTarget(List<CreatorEngine.CandidateOption> eligible) {
        int cycle = allocationCycle % 10;
        int topSize  = Math.min(3, eligible.size());
        int midStart = topSize;
        int midSize  = Math.min(5, eligible.size() - topSize);
        int restStart = topSize + midSize;
        int restSize  = eligible.size() - restStart;

        // top-3: cycles 0-4 (50%), mid-5: cycles 5-7 (30%), rest: cycles 8-9 (20%)
        if (cycle < 5 && topSize > 0) {
            return eligible.get(cycle % topSize);
        } else if (cycle < 8 && midSize > 0) {
            return eligible.get(midStart + (cycle - 5) % midSize);
        } else if (restSize > 0) {
            return eligible.get(restStart + (cycle - 8) % restSize);
        }
        // Fallback: least sampled
        CreatorEngine.CandidateOption min = eligible.get(0);
        for (CreatorEngine.CandidateOption c : eligible) {
            if (c.samples < min.samples) min = c;
        }
        return min;
    }

    @Override
    public EngineResult peekResult(GameState state, int playerIdx, EngineConfig cfg) {
        List<CreatorEngine.CandidateOption> snap = candidates;
        if (snap == null || snap.isEmpty()) return null;
        boolean usedMC = totalMcSamples >= MC_THRESHOLD;
        return engine.buildResult(snap, state.getPlayers()[playerIdx].getCoins(),
                iterCount, 0L, usedMC, 0L);
    }

    @Override
    public boolean navigate(NavigationEvent event) {
        // Always reset: MC results from old position are invalid for new state
        return false;
    }

    @Override
    public int iterations() { return iterCount; }
}
