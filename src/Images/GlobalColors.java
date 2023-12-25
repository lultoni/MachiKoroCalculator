package Images;

import java.awt.*;

public class GlobalColors {

    public static Color backgroundBlue = new Color(62, 125, 176);
    public static Color backgroundGreen = new Color(59, 178, 42);
    public static Color backgroundRed = new Color(180, 54, 54);
    public static Color backgroundPurple = new Color(166, 72, 203);
    public static Color backgroundYellow = new Color(203, 181, 72);
    public static Color backgroundGrey = new Color(87, 87, 87);

    public static Color getCorrectBackgroundColor(int id) {
        Color back = null;
        switch (id) {
            case 0, 1, 2, 3 -> back = backgroundYellow;
            case 4, 6, 11, 12, 15 -> back = backgroundBlue;
            case 5, 7, 8, 9, 10 -> back = backgroundGreen;
            case 13, 14 -> back = backgroundRed;
            case 16, 17, 18 -> back = backgroundPurple;
            default -> back = backgroundGrey;
        }
        return back;
    }
}
