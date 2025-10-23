package logic.probability;

import java.util.ArrayList;
import java.util.stream.IntStream;

public class ProbabilityCalc {

    /**
     * @param r Gewürfelte Zahl
     * @return Wahrscheinlichkeit, dass die Zahl gewürfelt wird bei 1d6.
     */
    public static double get_P1(int r) {
        double back = 0;
        switch (r) {
            case 1, 2, 3, 4, 5, 6 -> back = (double) 1 / 6;
        }
        return back;
    }

    /**
     * @param r Gewürfelte Zahl
     * @return Wahrscheinlichkeit, dass die Zahl gewürfelt wird bei 2d6.
     */
    public static double get_P2(int r) {
        double back = 0;
        switch (r) {
            case 2, 12 -> back = (double) 1 / 36;
            case 3, 11 -> back = (double) 2 / 36;
            case 4, 10 -> back = (double) 3 / 36;
            case 5, 9 -> back = (double) 4 / 36;
            case 6, 8 -> back = (double) 5 / 36;
            case 7 -> back = (double) 6 / 36;
        }
        return back;
    }

    /**
     * @param r Gewürfelte Zahl
     * @param p_id Die ID von dem Projekt
     * @param oop Ist der Nutzer der Besitzer des Projekts
     * @param eb Ist das Einkaufszentrum für den Besitzer gebaut
     * @param f Projekte des Nutzers mit der Kategorie FOOD
     * @param a Projekte des Nutzers mit der Kategorie ANIMAL
     * @param p Projekte des Nutzers mit der Kategorie PRODUCTION
     * @param c Münzen die der Nutzer besitzt
     * @param co Münzen der Gegner des Nutzers
     * @return Auszahlung (positiv) oder Einzahlung (negativ) von einem Projekt für den Nutzer bei den Parametern bei
     * einer bestimmten Würfelzahl.
     */
    public static int get_I(int r, String p_id, boolean oop, boolean eb, int f, int a, int p, int c, int[] co) {
        switch (p_id) {
            case "weizenfeld" -> {
                if (r != 1) return 0;
                return 1;
            }
            case "bäckerei" -> {
                if (r != 2 && r != 3) return 0;
                if (!oop) return 0;
                return eb ? 2 : 1;
            }
            case "apfelplantage" -> {
                if (r != 10) return 0;
                return 3;
            }
            case "markthalle" -> {
                if (r != 11 && r != 12) return 0;
                if (!oop) return 0;
                return f * 2;
            }
            case "molkerei" -> {
                if (r != 7) return 0;
                if (!oop) return 0;
                return a * 3;
            }
            case "möbelfabrik" -> {
                if (r != 8) return 0;
                if (!oop) return 0;
                return p * 3;
            }
            case "mini-markt" -> {
                if (r != 4) return 0;
                if (!oop) return 0;
                return eb ? 4 : 3;
            }
            case "bauernhof" -> {
                if (r != 2) return 0;
                return 1;
            }
            case "wald" -> {
                if (r != 5) return 0;
                return 1;
            }
            case "familienrestaurant" -> {
                if (r != 9 && r != 10) return 0;
                if (oop) return 0;
                int cost = eb ? -3 : -2;
                if (Math.abs(cost) > c) return c;
                return cost;
            }
            case "café" -> {
                if (r != 3) return 0;
                if (oop) return 0;
                int cost = eb ? -2 : -1;
                if (Math.abs(cost) > c) return c;
                return cost;
            }
            case "bergwerk" -> {
                if (r != 9) return 0;
                return 5;
            }
            case "stadion" -> {
                if (r != 6) return 0;
                if (!oop) return 0;
                int gain = 5;
                int mco = 0;
                for (int i: co) mco = Math.max(i, mco);
                return Math.min(gain, mco);
            }
            case "fernsehsender" -> {
                if (r != 6) return 0;
                if (!oop) return 0;
                int max_gain = 5;
                int real_gain = 0;
                for (int i: co) real_gain += Math.min(i, 2);
                return Math.min(max_gain, real_gain);
            }
        }
        return 0;
    }

    /**
     * Gibt eine Erwartungswert-Matrix zurück, welche für jeden Spieler und jede Projekt-Farbe
     * eine Zeile mit allen Würfelzahlen enthält.
     * <p>
     * Es wird nicht darauf geachtet, was dann tatsächlich an die Spieler gezahlt wird
     * (wie sich die Coin-Anzahl real verändert, z.B. bei roten Projekten relevant).
     *
     * @param playerProjects Eine Liste der Projektlisten aller Spieler (2–4 Spieler).
     * @param playerCoins    Münzanzahlen der Spieler.
     * @return Eine (8/12/16)x12-Matrix:<p>
     *         - Zeilen 1–4: blaue Projekte,<p>
     *         - Zeilen 5–8: rote Projekte,<p>
     *         - Zeilen 9–12: grüne Projekte,<p>
     *         - Zeilen 13–16: lila Projekte.
     */
    public static int[][] values_per_r_per_p(ArrayList<Project[]> playerProjects, int[] playerCoins) {
        if (playerProjects == null || playerProjects.size() < 2 || playerProjects.size() > 4) {
            throw new IllegalArgumentException("Player count must be between 2 and 4.");
        }

        final int PROJECT_COLORS = 4;                       // blue, red, green, purple
        final int ROWS_PER_COLOR = playerProjects.size();   // player amount
        final int ROLL_COUNT = 12;                          // dice values

        int[][] valueMatrix = new int[PROJECT_COLORS * ROWS_PER_COLOR][ROLL_COUNT];

        ArrayList<PlayerStats> playerStats = new ArrayList<>();
        for (Project[] projects : playerProjects) {
            playerStats.add(calculatePlayerStats(projects));
        }

        for (int playerIndex = 0; playerIndex < playerProjects.size(); playerIndex++) {
            Project[] projects = playerProjects.get(playerIndex);
            PlayerStats stats = playerStats.get(playerIndex);
            int ownCoins = playerCoins[playerIndex];
            int[] otherCoins = getOtherPlayerCoins(playerCoins, playerIndex);

            for (Project project : projects) {
                int projectColorIndex = get_project_color_index(project.getColor());
                int vmRow = projectColorIndex * ROWS_PER_COLOR + playerIndex;

                if (vmRow >= valueMatrix.length) {
                    System.out.printf("vm_row out of bounds for player %d, project=%s%n", playerIndex + 1, project.getId());
                    continue;
                }

                for (int roll = 1; roll <= ROLL_COUNT; roll++) {
                    int increment = get_I(
                            roll,
                            project.getId(),
                            true,
                            stats.has_einkaufszentrum_built,
                            stats.foodCount,
                            stats.animalCount,
                            stats.productionCount,
                            ownCoins,
                            otherCoins
                    );

                    valueMatrix[vmRow][roll - 1] += increment;
                }
            }
        }

        return valueMatrix;
    }

    /**
     * Hilfsfunktion zur Berechnung der Spielerstatistiken.
     */
    private static PlayerStats calculatePlayerStats(Project[] projects) {
        PlayerStats stats = new PlayerStats();

        for (Project project : projects) {
            if (project.getId().equals("einkaufszentrum")) stats.has_einkaufszentrum_built = true;
            switch (project.getCategory()) {
                case "food" -> stats.foodCount++;
                case "animal" -> stats.animalCount++;
                case "production" -> stats.productionCount++;
            }
        }

        return stats;
    }

    /**
     * (Hilfsfunktion) Gibt die Münzanzahlen der anderen Spieler zurück.
     */
    private static int[] getOtherPlayerCoins(int[] playerCoins, int excludeIndex) {
        return IntStream.range(0, playerCoins.length)
                .filter(i -> i != excludeIndex)
                .map(i -> playerCoins[i])
                .toArray();
    }

    /**
     * (Hilfsklasse) Container für Spieler-bezogene Zählwerte.
     */
    private static class PlayerStats {
        boolean has_einkaufszentrum_built = false;
        int foodCount = 0;
        int animalCount = 0;
        int productionCount = 0;
    }

    /**
     * (Hilfsfunktion) Gibt eine Zahl für jede Projektfarbe zurück.
     * @param project_color Color von dem Projekt
     * @return 0 für Blau, 1 für Rot, 2 für Grün, 3 für Lila, 4 für GP, -1 als Default
     */
    private static int get_project_color_index(String project_color) {
        switch (project_color) {
            case "blau" -> {
                return 0; // Blau
            }
            case "rot" -> {
                return 1; // Rot
            }
            case "grün" -> {
                return 2; // Grün
            }
            case "lila" -> {
                return 3; // Lila
            }
            case "gelb" -> {
                return 4; // GP
            }
        }
        return -1;
    }

    // TODO write function docs
    // Erwarteter Wert (Münzen) *sofort* für den Käufer, wenn er das Project kauft und danach seinen aktuellen Zug beendet.
    // - berücksichtigt: 1d6/2d6-Option (wenn Bahnhof vorhanden wählbar), Einkaufszentrum-Effekt (über get_I),
    // - berechnet erwarteten Ertrag **bis zum Ende dieses Zuges** (inkl. falls Freizeitpark -> Pasch Zweitwurf).
    // TODO write function body
    public static double immediateEV(GameState gs, int playerId, Project candidate, boolean forceUse2d6IfAvailable);

    // TODO write function docs
    // Erwarteter Netto-Ertrag des Käufers, bis alle anderen einmal dran waren (also bis zum eigenen nächsten Zug).
    // - zählt, dass blaue Karten von anderen Spielern pro Runde mehrfach triggen (Anzahl Spieler relevant).
    // - berücksichtigt GP-Effekte (Einkaufszentrum, Bahnhof etc.) und dass Gegner in der Simulation sinnvoll handeln.
    // TODO write function body
    public static double evPerRound(GameState gs, int playerId, Project candidate);

    // TODO write function docs
    // Return on Investment für Kauf von `candidate` über horizonTurns (z. B. N = 5) mit Discount factor.
    // Gibt z.B. EV_total - cost und evPerTurn etc. zurück (als POJO/RankEntry).
    // TODO write function body
    public static RankEntry roiOverHorizon(GameState gs, int playerId, Project candidate, int horizonTurns, double discountFactor);

    // TODO write function docs
    // Schätzt Gewinnwahrscheinlichkeit bzw. relative Nutzendifferenz (z. B. Siegchance innerhalb M Zügen) wenn candidate gekauft wird.
    // - kann Expectimax (optimal opponents) bis searchDepth nutzen oder Monte-Carlo (nSim simulations).
    // TODO write function body
    public static double estimateWinProbDelta(GameState gs, int playerId, Project candidate, int searchDepth, int mcSimulations);

    // TODO write function docs
    // Gibt sortiertes Ranking aller legalen Kaufoptionen zurück.
    // RankEntry enthält: Project, immediateEV, evPerRound, roiOverHorizon (N), winProbDelta, variance, notes
    // TODO write function body
    public static ArrayList<RankEntry> rankPurchasableProjects(GameState gs, int playerId, RankingOptions opts);

}
