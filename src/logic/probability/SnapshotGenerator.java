package logic.probability;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

/**
 * Generates realistic mid-game {@link GameState} snapshots for use in labeling/training.
 *
 * <h2>Random generation</h2>
 * {@link #generate(int, int, int)} simulates a fresh game step-by-step until it reaches
 * a turn in the given range {@code [minTurn, maxTurn]}, then returns the state at that point.
 * The returned state is a deep copy — the generator does not retain a reference to it.
 *
 * <h2>File-based generation</h2>
 * {@link #generateFromFile(Path)} loads a {@code .mkoro} save file and returns the final
 * game state from that file. This produces snapshots from real human play.
 *
 * <p>Both methods return {@code null} if no state could be produced (e.g. the file is
 * empty or the game ended before the target turn range).
 */
public class SnapshotGenerator {

    private SnapshotGenerator() {}

    /**
     * Generates a {@link GameState} by simulating a fresh game and stopping at a random
     * turn in {@code [minTurn, maxTurn]}.
     *
     * @param numPlayers number of players (2–4)
     * @param minTurn    minimum effective turn count to stop at (inclusive)
     * @param maxTurn    maximum effective turn count to stop at (inclusive)
     * @return a deep-copied game state at the chosen turn, or {@code null} on timeout
     */
    public static GameState generate(int numPlayers, int minTurn, int maxTurn) {
        if (numPlayers < 2 || numPlayers > 4) throw new IllegalArgumentException("numPlayers must be 2–4");
        if (minTurn < 0 || maxTurn < minTurn) throw new IllegalArgumentException("invalid turn range");

        Random rng = new Random();
        int targetTurn = minTurn + (maxTurn > minTurn ? rng.nextInt(maxTurn - minTurn + 1) : 0);

        GameState state = GameState.initial(numPlayers);
        Map<String, Integer> supply = GameSimulator.buildSupply(state);

        int totalTurns = 0;
        int activePlayer = 0;
        int n = numPlayers;

        while (totalTurns < GameSimulator.MAX_TURNS) {
            if (totalTurns == targetTurn) return state.copy();

            // Roll and apply income
            int roll = rollDice(state, activePlayer, rng);
            GameSimulator.applyRoll(state, activePlayer, roll);

            // Greedy buy — check for game-over
            int winner = GameSimulator.greedyBuy(state, activePlayer, supply);
            if (winner >= 0) {
                return (totalTurns >= minTurn) ? state.copy() : null;
            }

            activePlayer = (activePlayer + 1) % n;
            totalTurns++;
        }

        return null; // timeout
    }

    /**
     * Loads a {@code .mkoro} save file and returns the game state at the end of the
     * recorded history (all turns replayed).
     *
     * @param path path to the {@code .mkoro} file
     * @return deep copy of the game state after all recorded turns, or {@code null} if empty
     * @throws IOException if the file cannot be read or is malformed
     */
    public static GameState generateFromFile(Path path) throws IOException {
        GameSession session = GameSession.load(path);
        return session.getState().copy();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int rollDice(GameState state, int pi, Random rng) {
        Player p = state.getPlayers()[pi];
        if (!p.hasProject("bahnhof")) return 1 + rng.nextInt(6);
        for (Project proj : p.getOwned_projects()) {
            for (int a : proj.getDice_activation()) {
                if (a >= 7) return 1 + rng.nextInt(6) + 1 + rng.nextInt(6);
            }
        }
        return 1 + rng.nextInt(6);
    }
}
