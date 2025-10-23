package logic;

public class Project {

    int id;
    String name;
    Category category;
    int cost;
    int maxOwnCount;
    int ownCount;
    Game game;
    int[] diceActivation;

    public Project(int id, String name, Category category, int cost, int maxOwnCount, Game game, int[] diceActivation) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.cost = cost;
        this.maxOwnCount = maxOwnCount;
        ownCount = 0;
        this.game = game;
        this.diceActivation = diceActivation;
    }

    void doEffect(Player player, boolean isGP2active) {
        switch (id) {
            case 4, 11, 12 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, 1);
                    player.addDiceValueOthers(diceActivation, 1);
                }
            }
            case 5 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, (isGP2active) ? 2 : 1);
                }
            }
            case 6 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, 3);
                    player.addDiceValueOthers(diceActivation, 3);
                }
            }
            case 7 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.FOOD) coins += 2 * project.getOwnCount();
                    }
                    player.addDiceValueOwn(diceActivation, coins);
                }
            }
            case 8 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.ANIMAL ) coins += 3 * project.getOwnCount();
                    }
                    player.addDiceValueOwn(diceActivation, coins);
                }
            }
            case 9 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Project project: player.getProjects()) {
                        if (project.category == Category.PRODUCTION) coins += 3 * project.getOwnCount();
                    }
                    player.addDiceValueOwn(diceActivation, coins);
                }
            }
            case 10 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, (isGP2active) ? 4 : 3);
                }
            }
            case 13 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    for (Player p: game.getPlayers()) { // TODO unable to pay (look at case 17)
                        if (p.id != player.id) {
                            p.addDiceValueOwn(diceActivation, -2);
                        } else {
                            p.addDiceValueOthers(diceActivation, 2);
                        }
                    }
                }
            }
            case 14 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    for (Player p: game.getPlayers()) { // TODO unable to pay (look at case 17)
                        if (p.id != player.id) {
                            p.addDiceValueOwn(diceActivation, -1);
                        } else {
                            p.addDiceValueOthers(diceActivation, 1);
                        }
                    }
                }
            }
            case 15 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, 5);
                    player.addDiceValueOthers(diceActivation, 5);
                }
            }
            case 17 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    int coins = 0;
                    for (Player p1: game.getPlayers()) {
                        if (p1.id != player.id) {
                            if (p1.coins >= 2) {
                                coins += 2;
                            } else if (p1.coins == 1) {
                                coins += 1;
                            }
                        }
                    }
                    player.addDiceValueOwn(diceActivation, coins);
                }
            }
            case 18 -> {
                for (int i = 0; i < player.projectList[id].ownCount; i++) {
                    player.addDiceValueOwn(diceActivation, 5);
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

    public int getMaxOwnCount(Player player) {
        int back = maxOwnCount;
        if (category != Category.OFFICE) for (Player player2: game.getPlayers()) {
            if (player.id != player2.id) back -= player2.projectList[id].ownCount;
        }
        return back;
    }

    public int getCost() {
        return cost;
    }

    public String getActivationString() {
        String back = "";
        for (int i: diceActivation) {
            back += i;
            if (i != diceActivation[diceActivation.length - 1]) {
                back += ",";
            }
        }
        return back;
    }
}
