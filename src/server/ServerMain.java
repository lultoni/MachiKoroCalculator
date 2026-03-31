package server;

import engine.MctsBoltzmannRolloutEngine;
import engine.MctsGreedyRolloutEngine;
import engine.MctsGreedyTreeEngine;
import engine.MctsV1Engine;
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

        ApiServer server = new ApiServer(orchestrator);
        server.start();

        System.out.println("[ServerMain] MCTS v1 + Variant A (greedy) + Variant B (Boltzmann) engines registered. Server running.");
        System.out.println("[ServerMain] Press Ctrl+C to stop.");

        // Keep the main thread alive until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ServerMain] Shutting down...");
            server.stop(1);
        }));

        Thread.currentThread().join();
    }
}
