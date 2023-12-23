package Logic;

public class Project {

    int id;
    String name;
    Category category;
    int cost;
    int maxOwnCount;
    int ownCountP1;
    int ownCountP2;
    int ownCountP3;
    int ownCountP4;

    public Project(int id, String name, Category category, int cost, int maxOwnCount) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.cost = cost;
        this.maxOwnCount = maxOwnCount;
        ownCountP1 = 0;
        ownCountP2 = 0;
        ownCountP3 = 0;
        ownCountP4 = 0;
    }

    void doEffect() {

    }

    int getOwnCount (int player) {
        int back = 0;
        switch (player) {
            case 1 -> back = ownCountP1;
            case 2 -> back = ownCountP2;
            case 3 -> back = ownCountP3;
            case 4 -> back = ownCountP4;
        }
        return back;
    }

}
