package logic.probability;

import java.util.ArrayList;

public class Player {

    private final String name;
    private int coins;
    private final ArrayList<Project> owned_projects;

    public Player(String name, int coins, ArrayList<Project> owned_projects) {
        this.name = name;
        this.coins = coins;
        this.owned_projects = owned_projects;
    }

    public String getName() {
        return name;
    }

    public int getCoins() {
        return coins;
    }

    public void setCoins(int coins) {
        this.coins = coins;
    }

    public ArrayList<Project> getOwned_projects() {
        return owned_projects;
    }

    public boolean hasProject(String project_id) {
        for (Project project: getOwned_projects()) if (project.getId().equals(project_id)) return true;
        return false;
    }
}
