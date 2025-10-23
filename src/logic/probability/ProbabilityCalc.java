package logic.probability;

import logic.Project;

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
    public static int get_I(int r, int p_id, boolean oop, boolean eb, int f, int a, int p, int c, int[] co) {
        switch (p_id) {
            case 4 -> { // Weizenfeld
                if (r != 1) return 0;
                return 1;
            }
            case 5 -> { // Bäckerei
                if (r != 2 && r != 3) return 0;
                if (!oop) return 0;
                return eb ? 2 : 1;
            }
            case 6 -> { // Apfelplantage
                if (r != 10) return 0;
                return 3;
            }
            case 7 -> { // Markthalle
                if (r != 11 && r != 12) return 0;
                if (!oop) return 0;
                return f * 2;
            }
            case 8 -> { // Molkerei
                if (r != 7) return 0;
                if (!oop) return 0;
                return a * 3;
            }
            case 9 -> { // Möbelfabrik
                if (r != 8) return 0;
                if (!oop) return 0;
                return p * 3;
            }
            case 10 -> { // Mini-Markt
                if (r != 4) return 0;
                if (!oop) return 0;
                return eb ? 4 : 3;
            }
            case 11 -> { // Bauernhof
                if (r != 2) return 0;
                return 1;
            }
            case 12 -> { // Wald
                if (r != 5) return 0;
                return 1;
            }
            case 13 -> { // Familienrestaurant
                if (r != 9 && r != 10) return 0;
                if (oop) return 0;
                int cost = eb ? -3 : -2;
                if (Math.abs(cost) > c) return c;
                return cost;
            }
            case 14 -> { // Café
                if (r != 3) return 0;
                if (oop) return 0;
                int cost = eb ? -2 : -1;
                if (Math.abs(cost) > c) return c;
                return cost;
            }
            case 15 -> { // Bergwerk
                if (r != 9) return 0;
                return 5;
            }
            case 17 -> { // Stadion
                if (r != 6) return 0;
                if (!oop) return 0;
                int gain = 5;
                int mco = 0;
                for (int i: co) mco = Math.max(i, mco);
                return Math.min(gain, mco);
            }
            case 18 -> { // Fernsehsender
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

    // TODO per round perspective: also bei 4 spielern können blaue 4 mal getriggert werden
    //      -> wenn selber eine blaue gekauft wird, dann kann sie basierend auf spieleranzahl in einer runde
    //         mehrfach aktiviert werden, was dir, bis du wieder dran bist, mehr einkommen generieren kann
    //         -> hier kann man also gucken wie der EV einer karte für eine ganze runde aussieht (jeder war 1 mal dran)
    //            im vergleich zu wenn man selber nur einmal würfelt

    // TODO erwartete werte für eine runde müssen die großprojekte (gp's) mit in betracht gezogen werden

    // TODO einen tree search von allen möglichkeiten, wo jede aktion von spielern nach einem würfelwurf geguckt wird
    //      und mit der wahrscheinlichkeit des wurfes kombiniert wird und dann nach dem optimalen pfad jedes spieler
    //      geschaut werden kann
    //      -> hier müsste man auch auf die würfelwahl achten (1 oder 2 d6), da es pro spieler bessere ergebnisse geben
    //         könnte (man darf erst mit 2d6 würfeln, wenn man das großprojekt Bahnhof gebaut hat)

    // TODO über einen zeitverlauf schauen, was dann am meisten return gibt

    /**
     * Gibt eine Erwartungswert-Matrix zurück, welche für jeden Spieler und jede Projekt-Farbe
     * eine Zeile mit allen Würfelzahlen enthält.
     * <p>
     * Es wird nicht darauf geachtet, was dann tatsächlich an die Spieler gezahlt wird
     * (wie sich die Coin-Anzahl real verändert, z.B. bei roten Projekten relevant).
     *
     * @param playerProjects Eine Liste der Projektlisten aller Spieler (2–4 Spieler).
     * @param playerCoins    Münzanzahlen der Spieler.
     * @return Eine 16x12-Matrix:<p>
     *         Zeilen 1–4: blaue Projekte,<p>
     *         Zeilen 5–8: rote Projekte,<p>
     *         Zeilen 9–12: grüne Projekte,<p>
     *         Zeilen 13–16: lila Projekte.
     */
    public static int[][] values_per_r_per_p(ArrayList<Project[]> playerProjects, int[] playerCoins) {
        if (playerProjects == null || playerProjects.size() < 2 || playerProjects.size() > 4) {
            throw new IllegalArgumentException("Player count must be between 2 and 4.");
        }

        final int PROJECT_COLORS = 4;   // blue, red, green, purple
        final int ROWS_PER_COLOR = 4;   // max players
        final int ROLL_COUNT = 12;      // dice values

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
                int ownCount = project.getOwnCount();
                if (ownCount == 0) continue;

                int projectColorIndex = get_project_color(project.getID()) - 1;
                int vmRow = projectColorIndex * ROWS_PER_COLOR + playerIndex;

                if (vmRow >= valueMatrix.length) {
                    System.out.printf("vm_row out of bounds for player %d, project=%s%n", playerIndex + 1, project.getName());
                    continue;
                }

                for (int roll = 1; roll <= ROLL_COUNT; roll++) {
                    int increment = get_I(
                            roll,
                            project.getID(),
                            true,
                            stats.has_einkaufszentrum_built,
                            stats.foodCount,
                            stats.animalCount,
                            stats.productionCount,
                            ownCoins,
                            otherCoins
                    ) * ownCount;

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
            if (project.getID() == 1) stats.has_einkaufszentrum_built = true;
            switch (project.getCategory()) {
                case FOOD -> stats.foodCount++;
                case ANIMAL -> stats.animalCount++;
                case PRODUCTION -> stats.productionCount++;
            }
        }

        return stats;
    }

    /**
     * (Hilfsmethode) Gibt die Münzanzahlen der anderen Spieler zurück.
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
     * @param project_id ID von dem Projekt
     * @return 1 für Blau, 2 für Grün, 3 für Rot, 4 für Lila, 5 für GP, -1 als Default
     */
    private static int get_project_color(int project_id) {
        switch (project_id) {
            case 4, 6, 11, 12, 15 -> {
                return 1; // Blau
            }
            case 13, 14 -> {
                return 2; // Rot
            }
            case 5, 7, 8, 9, 10 -> {
                return 3; // Grün
            }
            case 16, 17, 18 -> {
                return 4; // Lila
            }
            case 0, 1, 2, 3 -> {
                return 5; // GP
            }
        }
        return -1;
    }

}
