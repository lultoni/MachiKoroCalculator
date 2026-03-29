package logic.probability;

/**
 * Result POJO holding all ranking metrics for a single candidate project.
 *
 * <p>Instances are produced by
 * {@link ProbabilityCalc#rankPurchasableProjects(GameState, int, RankingOptions)} and
 * {@link ProbabilityCalc#roiOverHorizon(GameState, int, Project, int, double)}.
 * Fields are populated in that order; {@link #winProbDelta} is only set when
 * {@link RankingOptions#includeWinProbDelta} is true.
 *
 * <p>The special {@link #WAIT_SENTINEL} project is used for the synthetic "Wait/Save" entry
 * that {@link ProbabilityCalc#rankAllProjects} may insert to represent the option of saving
 * coins for a future purchase. Callers should test {@link #isWaitEntry()} before accessing
 * project-specific fields.
 */
public class RankEntry {

    /**
     * Sentinel project used to mark a "Wait/Save" synthetic RankEntry.
     * Never appears in {@code projects.json}; use {@link #isWaitEntry()} to detect it.
     */
    public static final Project WAIT_SENTINEL = new Project(
            "_wait_", "wait", false, 0, new int[0], "none",
            "Save coins for a better card", "Save coins for a better card", "Save coins for a better card");

    /** Returns true if this entry represents the synthetic "Wait/Save" option. */
    public boolean isWaitEntry() {
        return project == WAIT_SENTINEL;
    }

    /** The candidate project this entry describes. */
    public Project project;

    /**
     * Expected coin gain on the player's own next turn, assuming this card was just bought.
     * Accounts for Bahnhof (1d6 vs 2d6 choice), Freizeitpark second-roll, and Funkturm re-roll.
     */
    public double immediateEV;

    /**
     * {@link #immediateEV} minus the card's purchase cost.
     * Negative values indicate the card does not recoup its cost within one turn.
     */
    public double immediateEV_afterCost;

    /**
     * Expected coin gain per full round (player's own turn + N−1 opponent turns).
     * Blue cards contribute on every turn; green/purple only on own turn;
     * red cards provide income on opponent turns.
     */
    public double evPerRound;

    /**
     * Discounted return on investment over the ranking horizon, minus the card's cost.
     * Computed as: {@code evPerRound × γ × (1 − γ^T) / (1 − γ) − cost}
     * where {@code γ = discountFactor} and {@code T = horizonTurns}.
     * Used as the primary sort key in {@link ProbabilityCalc#rankPurchasableProjects}.
     */
    public double roiOverHorizon;

    /**
     * Probability of earning ≤ 0 coins on the player's own turn with this card owned.
     * Risk metric: higher values indicate the card provides income on few own-turn rolls.
     */
    public double probNoIncomeOwnTurn;

    /**
     * Probability of earning ≤ 0 coins over a full round (own turn + opponent turns).
     * Accounts for red-card income on opponent turns and blue-card income every turn.
     */
    public double probNoIncomeRound;

    /**
     * Variance of the per-turn net coin gain distribution after buying this card.
     * High variance indicates a "swingy" card (e.g. stadion, bergwerk).
     * Computed as: {@code Σ_r P(r) × gain(r)² − EV²}.
     */
    public double variance;

    /**
     * Win-probability delta: P(player wins | buys this card) − P(player wins | baseline).
     * Set to {@code 0.0} if {@link RankingOptions#includeWinProbDelta} was false.
     * May be computed analytically (softmax heuristic) or via Monte Carlo depending on
     * {@link RankingOptions#mcSimulations}.
     */
    public double winProbDelta;

    /**
     * Portfolio marginal EV per round: the increase in the player's total per-round income
     * that results from adding this card to their current portfolio.
     *
     * <p>Formula: {@code playerEvPerRound(portfolio + card) − playerEvPerRound(portfolio)}
     *
     * <p>Unlike {@link #evPerRound} (which measures only this card's own income contribution),
     * this captures cross-card synergies:
     * <ul>
     *   <li>Buying Bauernhof raises this value for Molkerei because the animal-card count
     *       increases, boosting Molkerei's payout on roll 7.</li>
     *   <li>Buying a food card raises this value for Markthalle.</li>
     *   <li>Buying Bahnhof raises this value for all 7–12 cards because the player will
     *       use 2d6 more often.</li>
     * </ul>
     * Use this as the primary signal for synergy-aware ranking.
     */
    public double portfolioDeltaEV;

    /**
     * Optional human-readable annotation displayed alongside this entry in the UI.
     * {@code null} if no annotation was set.
     */
    public String notes;

    /**
     * True if the player can currently afford this card (coins ≥ cost).
     * Set by {@link ProbabilityCalc#rankAllProjects}; always true for entries from
     * {@link ProbabilityCalc#rankPurchasableProjects}.
     */
    public boolean affordable = true;

}
