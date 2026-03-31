package util;

/**
 * Central registry for the active locale (DE/EN).
 *
 * <p>Provides the {@link #isDE()} / {@link #setLocale(Locale)} API used by
 * {@link core.Project#getLocalizedName()} and other Core classes that need to
 * return locale-aware strings. Display strings for the UI live in the UI layer.
 */
public final class Strings {

    public enum Locale { DE, EN }

    private static Locale locale = Locale.DE;

    private Strings() {}

    public static Locale getLocale() { return locale; }

    public static void setLocale(Locale l) { locale = l; }

    public static boolean isDE() { return locale == Locale.DE; }

    // ── Generic helpers ───────────────────────────────────────────────────────

    public static String s(String de, String en) {
        return locale == Locale.DE ? de : en;
    }
}
