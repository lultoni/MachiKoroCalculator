package Logic;

public class Player {

    int id;
    Project[] projectList;

    public Player(int id, Project[] projectList) {
        this.id = id;
        this.projectList = projectList;
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
}
