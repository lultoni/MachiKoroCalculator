package Logic;

public class Project {

    int id;
    String name;
    Category category;
    int cost;
    int maxOwnCount;
    int ownCount;
    Game game;

    public Project(int id, String name, Category category, int cost, int maxOwnCount, Game game) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.cost = cost;
        this.maxOwnCount = maxOwnCount;
        ownCount = 0;
        this.game = game;
    }

    void doEffect(Player player, boolean isGP2active) {
        switch (id) {
            case 4 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{1}, 1);
                    player.addDiceValueOthers(new int[]{1}, 1);
                }
            }
            case 5 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{2, 3}, (isGP2active) ? 2 : 1);
                }
            }
            case 6 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{10}, 3);
                    player.addDiceValueOthers(new int[]{10}, 3);
                }
            }
            case 7 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.FOOD) coins += 2;
                    }
                    player.addDiceValueOwn(new int[]{11, 12}, coins);
                }
            }
            case 8 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.ANIMAL) coins += 3;
                    }
                    player.addDiceValueOwn(new int[]{7}, coins);
                }
            }
            case 9 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.PRODUCTION) coins += 3;
                    }
                    player.addDiceValueOwn(new int[]{8}, coins);
                }
            }
            case 10 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{4}, (isGP2active) ? 4 : 3);
                }
            }
            case 11 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{2}, 1);
                    player.addDiceValueOthers(new int[]{2}, 1);
                }
            }
            case 12 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{5}, 1);
                    player.addDiceValueOthers(new int[]{5}, 1);
                }
            }
            case 13 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    for (Player p: game.getPlayers()) {
                        if (p.id != player.id) {
                            p.addDiceValueOwn(new int[]{9, 10}, -2);
                        } else {
                            p.addDiceValueOthers(new int[]{9, 10}, 2);
                        }
                    }
                }
            }
            case 14 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    for (Player p: game.getPlayers()) {
                        if (p.id != player.id) {
                            p.addDiceValueOwn(new int[]{3}, -1);
                        } else {
                            p.addDiceValueOthers(new int[]{3}, 1);
                        }
                    }
                }
            }
            case 15 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{9}, 5);
                    player.addDiceValueOthers(new int[]{9}, 5);
                }
            }
            case 17 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{4}, game.getPlayers().length * 2);
                }
            }
            case 18 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(new int[]{4}, 5);
                }
            }
        }
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

    public int getMaxOwnCount() {
        return maxOwnCount;
    }
}
