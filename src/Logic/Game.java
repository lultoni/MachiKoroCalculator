package Logic;

import java.util.ArrayList;

public class Game {

    Player[] players;

    public Game(int playerAmount) {

        players = new Player[playerAmount];
        for (int i = 0; i < playerAmount; i++) {
            players[i] = new Player(i, getProjectList(), this);
            players[i].projectList[4].ownCount++;
            players[i].projectList[5].ownCount++;
        }

    }

    public Player[] getPlayers() {
        return players;
    }

    private Project[] getProjectList() {
        Project[] back = new Project[19];
        // GP
        back[0] = new Project(0, "Bahnhof", Category.OFFICE, 4, 1, this);
        back[1] = new Project(1, "Einkaufzentrum", Category.OFFICE, 10, 1, this);
        back[2] = new Project(2, "Freizeitpark", Category.OFFICE, 16, 1, this);
        back[3] = new Project(3, "Funkturm", Category.OFFICE, 22, 1, this);
        // Starter
        back[4] = new Project(4, "Weizenfeld", Category.FOOD, 1, 7, this);
        back[5] = new Project(5, "Bäckerei", Category.STORE, 1, 7, this);
        // Blue
        back[6] = new Project(6, "Apfelplantage", Category.FOOD, 3, 6, this);
        back[11] = new Project(11, "Bauernhof", Category.ANIMAL, 1, 6, this);
        back[12] = new Project(12, "Wald", Category.PRODUCTION, 3, 6, this);
        back[15] = new Project(15, "Bergwerk", Category.CAFE, 6, 6, this);
        // Green
        back[7] = new Project(7, "Markthalle", Category.MARKET, 2, 6, this);
        back[8] = new Project(8, "Molkerei", Category.FACTORY, 5, 6, this);
        back[9] = new Project(9, "Möbelfabrik", Category.FACTORY, 3, 6, this);
        back[10] = new Project(10, "Mini-Markt", Category.STORE, 2, 6, this);
        // Red
        back[13] = new Project(13, "Familienrestaurant", Category.CAFE, 3, 6, this);
        back[14] = new Project(14, "Café", Category.CAFE, 2, 6, this);
        // Purple
        back[16] = new Project(16, "Bürohaus", Category.OFFICE, 8, 1, this);
        back[17] = new Project(17, "Stadion", Category.OFFICE, 6, 1, this);
        back[18] = new Project(18, "Fernsehsender", Category.OFFICE, 7, 1, this);
        return back;
    }

    public int getPlayerRank(int id) {
        return id; // TODO fix this mess (use calculateScore function and sort I guess (think about 2 people having the same score))
    }

    public Project[] getBestProjects(int amount, Player player) {
        ArrayList<Project> backList = new ArrayList<>();

        double[] beforeScores = calculateScores(player);

        ArrayList<double[]> values = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();

        for (Project project : player.projectList) {
            if (project.getOwnCount() < project.getMaxOwnCount() && project.cost <= player.coins) {
                project.setOwnCount(project.getOwnCount() + 1);
                values.add(calculateScores(player));
                ids.add(project.id);
                project.setOwnCount(project.getOwnCount() - 1);
            }
        }

        for (int i = 0; i < values.size(); i++) {
            double[] scoreDiff = calculateScoreDifference(beforeScores, values.get(i));
            double totalDiff = calculateTotalDifference(scoreDiff);

            totalDiff /= player.projectList[ids.get(i)].cost;

            if (backList.size() < amount) {
                backList.add(player.projectList[ids.get(i)]);
            } else {
                for (int j = 0; j < amount; j++) {
                    double[] existingDiff = calculateScoreDifference(beforeScores, calculateScores(player));

                    existingDiff[0] /= backList.get(j).cost;

                    if (calculateTotalDifference(existingDiff) < totalDiff) {
                        backList.set(j, player.projectList[ids.get(i)]);
                        break;
                    }
                }
            }
        }

        return backList.toArray(new Project[0]);
    }


    private double[] calculateScoreDifference(double[] before, double[] after) {
        double[] diff = new double[before.length];
        for (int i = 0; i < before.length; i++) {
            diff[i] = after[i] - before[i];
        }
        return diff;
    }

    private double calculateTotalDifference(double[] diff) {
        double total = 0;
        for (double d : diff) {
            total += d;
        }
        return total;
    }

    private double[] calculateScores(Player player) {
        Player o1 = (player.id == 0) ? players[1] : (player.id == 1) ? players[2] : (player.id == 2) ? players[3] : players[0];
        Player o2 = (player.id == 0) ? players[2] : (player.id == 1) ? players[3] : (player.id == 2) ? players[0] : players[1];
        Player o3 = (player.id == 0) ? players[3] : (player.id == 1) ? players[0] : (player.id == 2) ? players[1] : players[2];
        double pScore = player.getEX(true, true) + player.getEX(true, false) + player.getEX(false, true) + player.getEX(false, false);
        double o1Score = o1.getEX(true, true) + o1.getEX(true, false) + o1.getEX(false, true) + o1.getEX(false, false);
        double o2Score = o2.getEX(true, true) + o2.getEX(true, false) + o2.getEX(false, true) + o2.getEX(false, false);
        double o3Score = o3.getEX(true, true) + o3.getEX(true, false) + o3.getEX(false, true) + o3.getEX(false, false);
        return new double[]{pScore, o1Score, o2Score, o3Score};
    }

    public double[] getChangeArr(Project[] projects, Player player) {
        double[] back = new double[projects.length];

        double[] originalScores = calculateScores(player);

        for (int i = 0; i < projects.length; i++) {
            Project project = projects[i];

            project.setOwnCount(project.getOwnCount() + 1);

            double[] newScores = calculateScores(player);
            double newPlayerScore = newScores[0];
            double originalPlayerScore = originalScores[0];

            back[i] = Math.round((newPlayerScore - originalPlayerScore) * 1000.0) / 1000.0;

            project.setOwnCount(project.getOwnCount() - 1);
        }

        return back;
    }

}
