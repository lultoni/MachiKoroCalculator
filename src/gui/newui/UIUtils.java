package gui.newui;

/**
 * Shared UI utility helpers used by {@link MainWindow} and {@link SnapshotDialog}.
 */
class UIUtils {

    private UIUtils() {} // utility class — no instances

    /**
     * Capitalizes the first character of {@code s} and returns the result.
     * Returns {@code s} unchanged if it is null or empty.
     */
    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
