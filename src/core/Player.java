package core;

import java.util.ArrayList;
import java.util.Objects;

public class Player {

    private final String name;
    private int coins;
    private final ArrayList<Project> owned_projects;

    /**
     * @param name            player display name, must not be null
     * @param coins           current coin count, must be >= 0
     * @param owned_projects  list of owned projects, must not be null
     */
    public Player(String name, int coins, ArrayList<Project> owned_projects) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
        this.owned_projects = Objects.requireNonNull(owned_projects, "owned_projects must not be null");
    }

    /**
     * Returns the player's display name.
     */
    public String getName() {
        return name;
    }

    /** Returns the player's current coin count (always ≥ 0). */
    public int getCoins() {
        return coins;
    }

    /**
     * Sets the player's coin count.
     *
     * @param coins new coin count, must be ≥ 0
     * @throws IllegalArgumentException if coins &lt; 0
     */
    public void setCoins(int coins) {
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
    }

    /**
     * Returns the mutable list of projects owned by this player.
     * Callers may add or remove entries directly; {@link Project} references are safe to share
     * because {@link Project} is immutable.
     */
    public ArrayList<Project> getOwned_projects() {
        return owned_projects;
    }

    /**
     * Returns {@code true} if this player owns at least one project with the given ID.
     *
     * @param project_id the project ID to search for (case-sensitive, German spelling)
     */
    public boolean hasProject(String project_id) {
        for (Project project : owned_projects) if (project.getId().equals(project_id)) return true;
        return false;
    }

    /**
     * Returns a new Player with the same name and coins, and a new ArrayList containing
     * the same Project references. This is a safe defensive copy because Project is immutable.
     */
    public Player copy() {
        return new Player(name, coins, new ArrayList<>(owned_projects));
    }
}
