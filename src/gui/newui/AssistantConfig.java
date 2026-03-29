package gui.newui;

/**
 * Central configuration for the Game Assistant's phase detection and profile weighting.
 *
 * <p>All thresholds and weight arrays are defined as named constants here so they can be
 * quickly adjusted without hunting through {@code MainWindow.rebuildAssistantPanel}.
 * This class is the target output of the future N4d {@code PhaseFitter} — once enough
 * labels are collected, the fitted values replace the constants here.
 *
 * <h2>Phase detection</h2>
 * Phase is determined by economics (coin flows and EV), not just GP counts:
 * <ul>
 *   <li><b>Early game</b>: average portfolio EV is low and Einkaufszentrum is not in reach
 *       within the next {@link #EARLY_SAVE_ROUNDS} rounds.</li>
 *   <li><b>Late game</b>: any player (own or opponent) has ≥ {@link #LATE_GP_THRESHOLD} GPs.</li>
 *   <li><b>Mid game</b>: everything else.</li>
 * </ul>
 *
 * <h2>Profile weights</h2>
 * Eight profiles: [ROI, EV, Safe, LowVar, Cheap, WinProb, Aggro, GPRush].
 * Stored as three arrays (early / mid / late). Opponent-pressure modifiers are additive.
 */
final class AssistantConfig {

    private AssistantConfig() {}

    // -------------------------------------------------------------------------
    // Phase detection thresholds
    // -------------------------------------------------------------------------

    /**
     * Average portfolio EV/round across all players below which the game is considered
     * economically undeveloped (one component of early-game detection).
     */
    static final double EARLY_AVG_EV_THRESHOLD = 1.2;

    /**
     * Number of future rounds to project when checking whether Einkaufszentrum (cost 10)
     * is realistically reachable. Early game if: coins + EARLY_SAVE_ROUNDS × own_ev < EKZ_cost.
     */
    static final int EARLY_SAVE_ROUNDS = 2;

    /** Cost of Einkaufszentrum — used as the early/mid transition marker. */
    static final int EKZ_COST = 10;

    /**
     * GP threshold: if max(own GPs, any opponent GPs) reaches this value, the game is late.
     */
    static final int LATE_GP_THRESHOLD = 3;

    // -------------------------------------------------------------------------
    // Rückstand (opponent-pressure) modifier thresholds
    // -------------------------------------------------------------------------

    /**
     * If the leading opponent can win within this many turns, apply the "emergency" modifier.
     * Turns-to-win = (4th GP cost − opponent coins) / opponent evPerRound.
     */
    static final double PRESSURE_EMERGENCY_TURNS = 3.0;

    /** If within this many turns (but more than EMERGENCY), apply the "pressure" modifier. */
    static final double PRESSURE_WARNING_TURNS   = 6.0;

    /** GPRush and Aggro weight additions in emergency mode. */
    static final double PRESSURE_EMERGENCY_GPRUSH = 0.5;
    static final double PRESSURE_EMERGENCY_AGGRO  = 0.3;

    /** GPRush and Aggro weight additions in warning (pressure) mode. */
    static final double PRESSURE_WARNING_GPRUSH = 0.2;
    static final double PRESSURE_WARNING_AGGRO  = 0.1;

    // -------------------------------------------------------------------------
    // Profile weight arrays [ROI, EV, Safe, LowVar, Cheap, WinProb, Aggro, GPRush]
    // -------------------------------------------------------------------------

    /** Profile weight indices — use these constants when indexing into weight arrays. */
    static final int W_ROI    = 0;
    static final int W_EV     = 1;
    static final int W_SAFE   = 2;
    static final int W_LOWVAR = 3;
    static final int W_CHEAP  = 4;
    static final int W_WIN    = 5;
    static final int W_AGGRO  = 6;
    static final int W_GPRUSH = 7;

    static final double[] WEIGHTS_EARLY = {0.8, 0.6, 0.4, 0.3, 0.9, 0.2, 0.2, 0.7};
    static final double[] WEIGHTS_MID   = {1.0, 0.8, 0.4, 0.4, 0.3, 0.6, 0.5, 0.8};
    static final double[] WEIGHTS_LATE  = {0.6, 0.5, 0.2, 0.2, 0.2, 1.0, 0.8, 1.0};

    /**
     * Returns a mutable copy of the weight array for the given phase label.
     * Callers may apply modifiers and must NOT modify the original arrays.
     */
    static double[] weightsForPhase(String phase) {
        double[] src = phase.equals(Strings.assistantPhaseLate())  ? WEIGHTS_LATE
                     : phase.equals(Strings.assistantPhaseEarly()) ? WEIGHTS_EARLY
                     : WEIGHTS_MID;
        return src.clone();
    }
}
