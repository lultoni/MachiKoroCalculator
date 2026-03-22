package logic.probability;

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

    public String getName() {
        return name;
    }

    public int getCoins() {
        return coins;
    }

    public void setCoins(int coins) {
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
    }

    public ArrayList<Project> getOwned_projects() {
        return owned_projects;
    }

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
