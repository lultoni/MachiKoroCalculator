package engine.mcts;

import core.BitState;
import core.BitStateTranslator;
import core.GameState;
import engine.ContinuousWorker;
import engine.EngineConfig;
import engine.EngineResult;
import engine.NavigationEvent;

/**
 * {@link ContinuousWorker} implementation for MCTS engines (MctsV1 and variants A-E).
 *
 * <p>Maintains a persistent {@link MctsTree} across turns. On a lock-in event,
 * {@link TreeNavigator} walks the existing tree to the new root position, severing
 * the parent link so GC can collect ancestor nodes and sibling subtrees.
 *
 * <h2>Iteration loop</h2>
 * Each call to {@link #runOneIteration()} performs one MCTS iteration via
 * {@link MctsTree#runOneIteration()}. The caller ({@link engine.ContinuousEvaluator})
 * drives the loop and checks the stop flag between calls.
 *
 * <h2>peekResult</h2>
 * Delegates to {@link MctsV1Engine#buildResult} (protected method, same package),
 * passing the current tree. Does NOT modify tree state.
 *
 * <h2>Memory management</h2>
 * After successful navigation, {@link TreeNavigator#pruneAbove} sets the new root's
 * parent to null. The former root and all sibling subtrees become GC-eligible.
 * With 750K-1.5M iterations over a 30-60s human turn, the tree is estimated at
 * 50K-200K nodes ≈ 5-24 MB. This is well within acceptable bounds.
 */
public final class MctsContinuousWorker implements ContinuousWorker {

    /** Engine used for tree construction and result extraction. */
    private final MctsV1Engine engine;

    /** The active MCTS tree. Replaced when init() is called; navigated on lock-in events. */
    private MctsTree tree;

    /** Accumulated iterations since last init() or successful navigate(). */
    private int iterCount;

    /** Last-seen player index (for tree rebuild on navigation failure). */
    private int playerIndex;

    /** Last-seen config (for tree rebuild on navigation failure). */
    private EngineConfig config;

    /**
     * Creates a worker backed by a standard MctsV1Engine (uniform rollout policy).
     * Other MCTS variants (A-E) should supply their own engine instance.
     */
    public MctsContinuousWorker() {
        this(new MctsV1Engine());
    }

    /**
     * Creates a worker backed by the given MCTS engine variant.
     *
     * @param engine MCTS engine to use for tree construction and result extraction
     */
    public MctsContinuousWorker(MctsV1Engine engine) {
        this.engine = engine;
    }

    @Override
    public boolean supportsContinuousMode() {
        return true;
    }

    @Override
    public void init(BitState state, int[] supply, int playerIndex, EngineConfig config) {
        this.playerIndex = playerIndex;
        this.config      = config;

        double C = parseExplorationConstant(config);
        BitRolloutFn rolloutFn = engine.buildRolloutFn(config);
        boolean greedyBuy = parseGreedyBuy(config);

        this.tree = new MctsTree(state, supply, playerIndex, playerIndex,
                C, rolloutFn, greedyBuy, /* fullTurn= */ true);
        this.iterCount = 0;
    }

    @Override
    public void runOneIteration() {
        if (tree == null) return;
        tree.runOneIteration();
        iterCount++;
    }

    @Override
    public EngineResult peekResult(GameState state, int playerIdx, EngineConfig cfg) {
        if (tree == null || iterCount == 0) return null;
        // buildResult is protected in MctsV1Engine — accessible here (same package)
        return engine.buildResult(state, playerIdx, tree, iterCount, 0L, cfg);
    }

    @Override
    public boolean navigate(NavigationEvent event) {
        if (tree == null) return false;
        MctsNode newRoot = TreeNavigator.navigate(tree.fullTurnRoot, event);
        if (newRoot == null) return false;

        TreeNavigator.pruneAbove(newRoot);

        // Rebuild tree with new root. MctsTree's full-turn constructor builds from scratch,
        // but we need to reuse the existing subtree. We reconstruct a MctsTree whose
        // fullTurnRoot is the navigated node by using the package-private factory.
        double C = parseExplorationConstant(this.config);
        BitRolloutFn rolloutFn = engine.buildRolloutFn(this.config);
        boolean greedyBuy = parseGreedyBuy(this.config);

        this.tree = MctsTree.withExistingRoot(newRoot, event.playerIndex(), C, rolloutFn, greedyBuy);
        this.iterCount = 0;
        this.playerIndex = event.playerIndex();
        return true;
    }

    @Override
    public int iterations() {
        return iterCount;
    }

    // -------------------------------------------------------------------------
    // Config parsing helpers
    // -------------------------------------------------------------------------

    private static double parseExplorationConstant(EngineConfig config) {
        String cStr = config.getExtra("explorationConstant", "1.4142");
        try { return Double.parseDouble(cStr); } catch (NumberFormatException e) { return 1.4142; }
    }

    private static boolean parseGreedyBuy(EngineConfig config) {
        return "true".equalsIgnoreCase(config.getExtra("greedyBuySelection", "false"));
    }
}
