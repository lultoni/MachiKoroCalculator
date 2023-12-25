package Logic;

public class Player {

    int id;
    int coins;
    Project[] projectList;
    int[] diceValuesOwn;
    int[] diceValuesOthers;
    Game game;

    public Player(int id, Project[] projectList, Game game) {
        this.id = id;
        this.projectList = projectList;
        this.coins = 3;
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        this.game = game;
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

    public double getEX(boolean single, boolean own) { // TODO own/not
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        for (Player player: game.getPlayers()) {
            for (Project project: player.projectList) {
                project.doEffect(player, projectList[1].ownCount == 1);
            }
        }
        double back = 0;
        if (own) {
            if (single) {
                for (int i = 0; i < 6; i++) {
                    back += (double) diceValuesOwn[i] / 6;
                }
            } else {
                for (int i = 0; i < 12; i++) {
                    back += (double) diceValuesOwn[i] * getProb(i + 1);
                }
            }
        } else {
            if (single) {
                for (int i = 0; i < 6; i++) {
                    back += (double) diceValuesOthers[i] / 6;
                }
            } else {
                for (int i = 0; i < 12; i++) {
                    back += (double) diceValuesOthers[i] * getProb(i + 1);
                }
            }
        }
        return Math.round(back * 1000.0) / 1000.0;
    }

    private double getProb(int i) {
        double back = 0;
        switch (i) {
            case 2, 12 -> back = (double) 1 / 36;
            case 3, 11 -> back = (double) 2 / 36;
            case 4, 10 -> back = (double) 3 / 36;
            case 5, 9 -> back = (double) 4 / 36;
            case 6, 8 -> back = (double) 5 / 36;
            case 7 -> back = (double) 6 / 36;
        }
        return back;
    }

    public void addDiceValueOwn(int[] dVs, int coins) {
        for (int i: dVs) {
            diceValuesOwn[i - 1] += coins;
        }
    }

    public void addDiceValueOthers(int[] dVs, int coins) {
        for (int i: dVs) {
            diceValuesOthers[i - 1] += coins;
        }
    }
}
