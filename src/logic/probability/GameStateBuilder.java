package logic.probability;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Fluent builder for constructing a {@link GameState} from user-supplied inputs.
 *
 * <p>Usage:
 * <pre>
 *   GameState gs = new GameStateBuilder(3)
 *       .setPlayerName(0, "Alice")
 *       .setCoins(0, 5)
 *       .addProject(0, "weizenfeld")
 *       .addProject(0, "bäckerei")
 *       .setPlayerName(1, "Bob")
 *       .setCoins(1, 3)
 *       .addProject(1, "weizenfeld")
 *       .addProject(1, "bäckerei")
 *       ...
 *       .build();
 * </pre>
 */
public class GameStateBuilder {

    private final int numPlayers;
    private final String[] names;
    private final int[] coins;
    private final ArrayList<ArrayList<Project>> owned;

    /**
     * @param numPlayers number of players (2–4)
     */
    public GameStateBuilder(int numPlayers) {
        if (numPlayers < 2 || numPlayers > 4)
            throw new IllegalArgumentException("numPlayers must be 2–4, got: " + numPlayers);
        this.numPlayers = numPlayers;
        this.names = new String[numPlayers];
        this.coins = new int[numPlayers];
        this.owned = new ArrayList<>();
        for (int i = 0; i < numPlayers; i++) {
            names[i] = "Player " + (i + 1);
            this.owned.add(new ArrayList<>());
        }
    }

    /** Sets the display name for a player. */
    public GameStateBuilder setPlayerName(int playerIndex, String name) {
        checkIndex(playerIndex);
        this.names[playerIndex] = Objects.requireNonNull(name, "name must not be null");
        return this;
    }

    /** Sets the current coin count for a player (must be >= 0). */
    public GameStateBuilder setCoins(int playerIndex, int amount) {
        checkIndex(playerIndex);
        if (amount < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + amount);
        this.coins[playerIndex] = amount;
        return this;
    }

    /**
     * Adds a project to a player's owned list.
     *
     * @param playerIndex player index
     * @param projectId   project ID as in projects.json (e.g. "weizenfeld")
     * @throws IllegalArgumentException if projectId is not found in ProjectLoader
     */
    public GameStateBuilder addProject(int playerIndex, String projectId) {
        checkIndex(playerIndex);
        Project p = ProjectLoader.getProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project id: " + projectId));
        owned.get(playerIndex).add(p);
        return this;
    }

    /**
     * Removes a project from a player's owned list (no-op if not owned).
     */
    public GameStateBuilder removeProject(int playerIndex, String projectId) {
        checkIndex(playerIndex);
        owned.get(playerIndex).removeIf(p -> p.getId().equals(projectId));
        return this;
    }

    /**
     * Builds and returns the {@link GameState}.
     * The unbuilt_projects pool is all projects not owned by any player.
     */
    public GameState build() {
        Player[] players = new Player[numPlayers];
        for (int i = 0; i < numPlayers; i++) {
            players[i] = new Player(names[i], coins[i], new ArrayList<>(owned.get(i)));
        }

        // Unbuilt = all projects that are not owned by any player
        ArrayList<Project> allProjects = ProjectLoader.getAllProjects();
        ArrayList<Project> allOwned = new ArrayList<>();
        for (int i = 0; i < numPlayers; i++) allOwned.addAll(owned.get(i));

        ArrayList<Project> unbuilt = new ArrayList<>();
        for (Project p : allProjects) {
            if (!allOwned.contains(p)) unbuilt.add(p);
        }

        return new GameState(players, unbuilt);
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= numPlayers)
            throw new IllegalArgumentException("playerIndex out of range: " + i);
    }
}
