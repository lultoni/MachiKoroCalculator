package Logic;

public class Project {

    int id;
    String name;
    Category category;
    int cost;
    int maxOwnCount;
    int ownCount;

    public Project(int id, String name, Category category, int cost, int maxOwnCount) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.cost = cost;
        this.maxOwnCount = maxOwnCount;
        ownCount = 0;
    }

    void doEffect() {

    }

    public String getName() {
        return name;
    }

    public int getOwnCount() {
        return ownCount;
    }

    public void setOwnCount(int i) {
        ownCount = i;
    }

    public Category getCategory() {
        return category;
    }

    public int getID() {
        return id;
    }
}
