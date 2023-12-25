package Logic;

public class Player {

    int id;
    int coins;
    Project[] projectList;

    public Player(int id, Project[] projectList) {
        this.id = id;
        this.projectList = projectList;
        this.coins = 3;
    }

    int getD1V_Own() {
        int back = 0;
        for (Project project: projectList) {

        }
        return back;
    }

    public String getName() {
        return "Player " + id;
    }

    public int getCoins() {
        return coins;
    }

    public void setCoins(int coins) {
        this.coins = coins;
    }

    public int getID() {
        return id;
    }

    public Project[] getProjects() {
        return projectList;
    }

    public double getEX(boolean single, boolean own) {
        return 0;
    }
}
