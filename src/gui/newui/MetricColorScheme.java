package gui.newui;

import java.awt.Color;

/**
 * Defines colour-coding rules for the five ranked metrics displayed in the
 * Card Details panel and the ranking table.
 *
 * <p>Each constant carries a "good" threshold and a "neutral" threshold, both expressed as
 * raw metric values. For most metrics a value above {@code goodThreshold} is best (green).
 * For {@link #P0} and {@link #VARIANCE} a lower value is better ({@code inverted = true}),
 * so a value <em>below</em> {@code goodThreshold} gets the green tint.
 *
 * <p>Call {@link #backgroundFor(double)} to get the appropriate tint, or
 * {@link #foregroundFor(double)} for a contrasting text colour.
 * Both return {@code null} when the value is in the neutral range.
 */
enum MetricColorScheme {

    // col 1 — cost: no colour coding
    COST(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, false),

    // col 2 — EV/round: higher is better; range roughly 0 – 1.5
    //   ≥ 0.35 strong green  |  ≥ 0.10 light green  |  < 0 red
    EV(0.35, 0.10, false),

    // col 3 — ROI: higher is better
    //   ≥ 0.50 strong green  |  ≥ 0.0  light green  |  < 0 red
    ROI(0.5, 0.0, false),

    // col 4 — P(0): lower is better (inverted)
    //   ≤ 0.35 strong green  |  ≤ 0.55 light green  |  > 0.80 red
    P0(0.35, 0.55, true),

    // col 5 — Variance: lower is better (inverted)
    //   ≤ 0.80 strong green  |  ≤ 1.50 light green  |  > 3.00 red
    VARIANCE(0.8, 1.5, true),

    // col 6 — Win Prob Δ: higher is better
    //   ≥ 0.02 strong green  |  ≥ 0.00 light green  |  < −0.01 red
    WIN_PROB_DELTA(0.02, 0.0, false);

    // -------------------------------------------------------------------------

    private static final Color GREEN_STRONG = new Color(0xB8F0C0);
    private static final Color GREEN_LIGHT  = new Color(0xDDFFDD);
    private static final Color RED_LIGHT    = new Color(0xFFDDDD);
    private static final Color RED_STRONG   = new Color(0xFFBBBB);

    /**
     * For normal metrics: values ≥ this receive GREEN_STRONG.
     * For inverted metrics: values ≤ this receive GREEN_STRONG.
     */
    private final double goodThreshold;
    /**
     * For normal metrics: values ≥ this (but < goodThreshold) receive GREEN_LIGHT.
     * For inverted metrics: values ≤ this (but > goodThreshold) receive GREEN_LIGHT.
     */
    private final double neutralThreshold;
    /** When true, lower value = better (P0, Variance). */
    private final boolean inverted;

    MetricColorScheme(double goodThreshold, double neutralThreshold, boolean inverted) {
        this.goodThreshold    = goodThreshold;
        this.neutralThreshold = neutralThreshold;
        this.inverted         = inverted;
    }

    /**
     * Returns the background tint colour for {@code value}, or {@code null} for neutral.
     */
    public Color backgroundFor(double value) {
        if (!inverted) {
            // higher = better
            if (value >= goodThreshold)    return GREEN_STRONG;
            if (value >= neutralThreshold) return GREEN_LIGHT;
            if (value >= 0)               return null;
            // negative values: RED_LIGHT unless far below the neutral band
            double redStrongCutoff = -(goodThreshold - neutralThreshold);
            if (value <= redStrongCutoff)  return RED_STRONG;
            return RED_LIGHT;
        } else {
            // lower = better (inverted)
            if (value <= goodThreshold)    return GREEN_STRONG;
            if (value <= neutralThreshold) return GREEN_LIGHT;
            // above neutral: bad zone; RED_STRONG when clearly bad
            double redStrongCutoff = neutralThreshold + (neutralThreshold - goodThreshold) * 2;
            if (value >= redStrongCutoff)  return RED_STRONG;
            return RED_LIGHT;
        }
    }

    /**
     * Returns a contrasting foreground colour for strong tints, or {@code null} for default.
     */
    public Color foregroundFor(double value) {
        Color bg = backgroundFor(value);
        if (bg == GREEN_STRONG) return new Color(0x1A5C28);
        if (bg == RED_STRONG)   return new Color(0x7A1010);
        return null;
    }

    /** The five metric schemes in table-column order (columns 2–6). */
    static final MetricColorScheme[] TABLE_ORDER = { EV, ROI, P0, VARIANCE, WIN_PROB_DELTA };
}
