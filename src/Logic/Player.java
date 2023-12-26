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

    public double getEX(boolean single, boolean own) {
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

    public int getDiceThrow(int diceNumber, boolean isDiceThrower) {
        int blueValue;
        int[] possibleID = {4, 6, 11, 12, 15};
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        for (Player player: game.getPlayers()) {
            for (Project project: player.projectList) {
                if (contains(possibleID, project.id)) project.doEffect(player, projectList[1].ownCount == 1);
            }
        }
        blueValue = diceValuesOwn[diceNumber - 1];

        int redValue;
        possibleID = new int[]{13, 14};
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        for (Player player: game.getPlayers()) {
            for (Project project: player.projectList) {
                if (contains(possibleID, project.id)) project.doEffect(player, projectList[1].ownCount == 1);
            }
        }
        if (isDiceThrower) { // TODO fix this to immediately give other person coins
            redValue = -diceValuesOthers[diceNumber - 1];
        } else {
            redValue = diceValuesOwn[diceNumber - 1];
        }


        int greenValue;
        possibleID = new int[]{5, 7, 8, 9, 10};
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        for (Player player: game.getPlayers()) {
            if (player.id == id) for (Project project: player.projectList) {
                if (contains(possibleID, project.id)) project.doEffect(player, projectList[1].ownCount == 1);
            }
        }
        greenValue = (isDiceThrower) ? diceValuesOwn[diceNumber - 1] : 0;


        int purpleValue;
        possibleID = new int[]{16, 17, 18};
        diceValuesOwn = new int[12];
        diceValuesOthers = new int[12];
        for (Player player: game.getPlayers()) {
            if (player.id == id) for (Project project: player.projectList) {
                if (contains(possibleID, project.id)) project.doEffect(player, projectList[1].ownCount == 1);
            }
        }
        purpleValue = (isDiceThrower) ? diceValuesOwn[diceNumber - 1] : 0;


        return blueValue + redValue + greenValue + purpleValue;
    }

    private boolean contains(int[] possibleID, int id) {
        for (int i: possibleID) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }
}
