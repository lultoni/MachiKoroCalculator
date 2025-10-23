package logic;

import java.util.ArrayList;
import java.util.Arrays;

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
        back[0] = new Project(0, "Bahnhof", Category.OFFICE, 4, 1, this, new int[]{0});
        back[1] = new Project(1, "Einkaufzentrum", Category.OFFICE, 10, 1, this, new int[]{0});
        back[2] = new Project(2, "Freizeitpark", Category.OFFICE, 16, 1, this, new int[]{0});
        back[3] = new Project(3, "Funkturm", Category.OFFICE, 22, 1, this, new int[]{0});
        // Starter
        back[4] = new Project(4, "Weizenfeld", Category.FOOD, 1, 6 + getPlayers().length, this, new int[]{1});
        back[5] = new Project(5, "Bäckerei", Category.STORE, 1, 6 + getPlayers().length, this, new int[]{2, 3});
        // Blue
        back[6] = new Project(6, "Apfelplantage", Category.FOOD, 3, 6, this, new int[]{10});
        back[11] = new Project(11, "Bauernhof", Category.ANIMAL, 1, 6, this, new int[]{2});
        back[12] = new Project(12, "Wald", Category.PRODUCTION, 3, 6, this, new int[]{5});
        back[15] = new Project(15, "Bergwerk", Category.PRODUCTION, 6, 6, this, new int[]{9});
        // Green
        back[7] = new Project(7, "Markthalle", Category.MARKET, 2, 6, this, new int[]{11, 12});
        back[8] = new Project(8, "Molkerei", Category.FACTORY, 5, 6, this, new int[]{7});
        back[9] = new Project(9, "Möbelfabrik", Category.FACTORY, 3, 6, this, new int[]{8});
        back[10] = new Project(10, "Mini-Markt", Category.STORE, 2, 6, this, new int[]{4});
        // Red
        back[13] = new Project(13, "Familienrestaurant", Category.CAFE, 3, 6, this, new int[]{9, 10});
        back[14] = new Project(14, "Café", Category.CAFE, 2, 6, this, new int[]{3});
        // Purple
        back[16] = new Project(16, "Bürohaus", Category.OFFICE, 8, 1, this, new int[]{6});
        back[17] = new Project(17, "Stadion", Category.OFFICE, 6, 1, this, new int[]{6});
        back[18] = new Project(18, "Fernsehsender", Category.OFFICE, 7, 1, this, new int[]{6});
        return back;
    }

    public int getPlayerRank(Player player) {
        return getPlayerRanks()[player.id];
    }

    public int[] getPlayerRanks() {
        int playerNumber = players.length;
        int[] ranks = new int[playerNumber];
        double[] scores = calculateScores(players[0]);
        double[] sortedScores = Arrays.copyOf(scores, 4);
        Arrays.sort(sortedScores);
        sortedScores = reverse(sortedScores);

        for (int i = 0; i < playerNumber; i++) {
            if (scores[i] == sortedScores[0]) {
                ranks[i] = 1;
            } else if (scores[i] == sortedScores[1]) {
                ranks[i] = 2;
            } else if (playerNumber >= 3 && scores[i] == sortedScores[2]) {
                ranks[i] = 3;
            } else if (playerNumber >= 4 && scores[i] == sortedScores[3]) {
                ranks[i] = 4;
            }
        }

        return ranks;
    }

    public Project[] getBestProjects(int amount, Player player) {
        ArrayList<Project> backList = new ArrayList<>();

        double[] beforeScores = calculateScores(player);

        ArrayList<double[]> values = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();

        for (Project project : player.projectList) {
            if (project.getOwnCount() < project.getMaxOwnCount(player) && project.cost <= player.coins) {
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
        int numPlayers = players.length;

        int o1Index = (player.id + 1) % numPlayers;
        int o2Index = (player.id + 2) % numPlayers;
        int o3Index = (player.id + 3) % numPlayers;

        Player o1 = players[o1Index];
        Player o2 = players[o2Index];
        Player o3 = players[o3Index];

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

    private double[] reverse(double[] arr) {
        double[] back = new double[arr.length];
        for (int i = 0; i < back.length / 2; i++) {
            double temp = arr[i];
            back[i] = arr[back.length - i - 1];
            back[back.length - i - 1] = temp;
        }
        if ((back.length % 2) != 0) {
            int index = (back.length / 2);
            back[index] = arr[index];
        }
        return back;
    }

}
