package Logic;

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

    public static int[][] values_per_r_per_p(Project[] pl_p1, Project[] pl_p2, Project[] pl_p3, Project[] pl_p4) {
        int[][] value_matrix = new int[16][12];

        // p1 to p1: 0
        // p1 to p2: 1
        // p1 to p3: 2
        // p1 to p4: 3

        // p2 to p1: 4
        // p2 to p2: 5
        // p2 to p3: 6
        // p2 to p4: 7

        // p3 to p1: 8
        // p3 to p2: 9
        // p3 to p3: 10
        // p3 to p4: 11

        // p4 to p1: 12
        // p4 to p2: 13
        // p4 to p3: 14
        // p4 to p4: 15

        // Player 1 Section
        // go through pl_p1
        // go through r 1-12
        // per project (defines in which row of val matrix the num goes) and r (defines col of val mat) call get_I
        for (int pl_index = 0; pl_index < pl_p1.length; pl_index++) {
            Project p = pl_p1[pl_index];
            int vm_row = 0;
            int p_col = get_project_color(p.getID());
            switch (p_col) {
                case 1, 2, 5 -> vm_row = 0;
                case 2 -> vm_row = 0;
                case 3 -> vm_row = 0;
                case 4 -> vm_row = 0;
            }

            for (int r = 1; r <= 12; r++) {

            }
        }

        // Player 2 Section

        // Player 3 Section

        // Player 4 Section

        return value_matrix;
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
            case 5, 7, 8, 9, 10 -> {
                return 2; // Grün
            }
            case 13, 14 -> {
                return 3; // Rot
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
