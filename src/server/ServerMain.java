package server;

import engine.expectimax.ExpectimaxEngine;
import engine.flat.FlatMcEngine;
import engine.heuristic.HeuristicEvEngine;
import engine.mcts.MctsAdaptiveEngine;
import engine.mcts.MctsBoltzmannRolloutEngine;
import engine.mcts.MctsDepthLimitedEngine;
import engine.mcts.MctsGreedyRolloutEngine;
import engine.mcts.MctsGreedyTreeEngine;
import engine.mcts.MctsV1Engine;
import iface.EngineOrchestrator;

/**
 * Entry point for the MachiKoro Calculator API server.
 *
 * <p>Registers the MCTS v1 engine with the orchestrator and starts the HTTP API server
 * on {@link ApiServer#DEFAULT_PORT} (8080). The server accepts connections on
 * {@code localhost} only.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp "out:src:gson-2.11.0.jar" server.ServerMain
 * </pre>
 *
 * <p>The server runs until the process is killed. Press Ctrl+C to stop.
 */
public final class ServerMain {

    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        EngineOrchestrator orchestrator = new EngineOrchestrator();
        orchestrator.register(new MctsV1Engine());
        orchestrator.register(new MctsGreedyRolloutEngine());
        orchestrator.register(new MctsBoltzmannRolloutEngine());
        orchestrator.register(new MctsGreedyTreeEngine());
        orchestrator.register(new MctsDepthLimitedEngine());
        orchestrator.register(new MctsAdaptiveEngine());
        orchestrator.register(new FlatMcEngine());
        orchestrator.register(new HeuristicEvEngine());
        orchestrator.register(new ExpectimaxEngine());

        ApiServer server = new ApiServer(orchestrator);
        server.start();

        System.out.println("[ServerMain] MCTS v1 + Variants A-E + Flat MC + Heuristic EV + Expectimax registered. Server running.");
        System.out.println("[ServerMain] Press Ctrl+C to stop.");

        // Keep the main thread alive until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ServerMain] Shutting down...");
            server.stop(1);
        }));

        Thread.currentThread().join();
    }
}
