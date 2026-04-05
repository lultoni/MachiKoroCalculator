package h2h;

/**
 * Glicko-2 rating for an engine, computed from H2H match history.
 *
 * <p>Based on Mark Glickman's Glicko-2 system (2001). Each engine has:
 * <ul>
 *   <li>{@link #rating} — skill estimate (starts at 1500)</li>
 *   <li>{@link #rd} — rating deviation / uncertainty (starts at 350, decreases with more games)</li>
 *   <li>{@link #volatility} — rate of expected change (starts at 0.06)</li>
 *   <li>{@link #matchCount} — number of matches played</li>
 * </ul>
 *
 * <p>All math uses Glickman's original paper formulas. The Glicko-2 scale (μ, φ) is used
 * internally; public values are on the Glicko-1 scale (rating, RD) for readability.
 *
 * @see <a href="http://www.glicko.net/glicko/glicko2.pdf">Glicko-2 paper</a>
 */
public final class Glicko2Rating {

    // --- Glicko-1 scale constants ---
    private static final double INITIAL_RATING = 1500.0;
    private static final double INITIAL_RD = 350.0;
    private static final double INITIAL_VOLATILITY = 0.06;

    // --- Glicko-2 scale conversion ---
    private static final double SCALE = 173.7178;  // 400 / ln(10)

    // --- System constant τ (constrains volatility change) ---
    private static final double TAU = 0.5;

    // --- Convergence tolerance for Illinois method ---
    private static final double EPSILON = 1e-6;

    public final double rating;
    public final double rd;
    public final double volatility;
    public final int matchCount;

    public Glicko2Rating(double rating, double rd, double volatility, int matchCount) {
        this.rating = rating;
        this.rd = rd;
        this.volatility = volatility;
        this.matchCount = matchCount;
    }

    /** Initial rating for an engine with no match history. */
    public static Glicko2Rating initial() {
        return new Glicko2Rating(INITIAL_RATING, INITIAL_RD, INITIAL_VOLATILITY, 0);
    }

    /**
     * Updates both player and opponent ratings after a match.
     *
     * @param player   the player's current rating
     * @param opponent the opponent's current rating
     * @param score    match result from player's perspective (0.0 = loss, 0.5 = draw, 1.0 = win;
     *                 fractional values represent win rate over multiple games)
     * @return array of two updated ratings: [updatedPlayer, updatedOpponent]
     */
    public static Glicko2Rating[] update(Glicko2Rating player, Glicko2Rating opponent, double score) {
        Glicko2Rating updatedPlayer = updateSingle(player, opponent, score);
        Glicko2Rating updatedOpponent = updateSingle(opponent, player, 1.0 - score);
        return new Glicko2Rating[]{updatedPlayer, updatedOpponent};
    }

    /**
     * Computes the updated rating for a single player after facing one opponent.
     */
    private static Glicko2Rating updateSingle(Glicko2Rating player, Glicko2Rating opponent, double score) {
        // Step 2: Convert to Glicko-2 scale
        double mu = (player.rating - INITIAL_RATING) / SCALE;
        double phi = player.rd / SCALE;
        double sigma = player.volatility;

        double muJ = (opponent.rating - INITIAL_RATING) / SCALE;
        double phiJ = opponent.rd / SCALE;

        // Step 3: Compute v (estimated variance of rating based on game outcomes)
        double gPhiJ = g(phiJ);
        double eVal = E(mu, muJ, gPhiJ);
        double v = 1.0 / (gPhiJ * gPhiJ * eVal * (1.0 - eVal));

        // Step 4: Compute delta (estimated improvement)
        double delta = v * gPhiJ * (score - eVal);

        // Step 5: Compute new volatility σ' via Illinois method
        double sigmaPrime = computeNewVolatility(sigma, phi, v, delta);

        // Step 6: Update RD to pre-rating period value
        double phiStar = Math.sqrt(phi * phi + sigmaPrime * sigmaPrime);

        // Step 7: Update rating and RD
        double phiPrime = 1.0 / Math.sqrt(1.0 / (phiStar * phiStar) + 1.0 / v);
        double muPrime = mu + phiPrime * phiPrime * gPhiJ * (score - eVal);

        // Convert back to Glicko-1 scale
        double newRating = muPrime * SCALE + INITIAL_RATING;
        double newRd = phiPrime * SCALE;

        return new Glicko2Rating(newRating, newRd, sigmaPrime, player.matchCount + 1);
    }

    /**
     * g(φ) = 1 / sqrt(1 + 3φ²/π²)
     */
    private static double g(double phi) {
        return 1.0 / Math.sqrt(1.0 + 3.0 * phi * phi / (Math.PI * Math.PI));
    }

    /**
     * E(μ, μ_j, g(φ_j)) = 1 / (1 + exp(-g(φ_j)(μ - μ_j)))
     */
    private static double E(double mu, double muJ, double gPhiJ) {
        return 1.0 / (1.0 + Math.exp(-gPhiJ * (mu - muJ)));
    }

    /**
     * Computes new volatility σ' using the Illinois variant of the regula falsi method.
     * This solves for σ' such that f(σ') = 0, where f is defined in Step 5 of the Glicko-2 paper.
     */
    private static double computeNewVolatility(double sigma, double phi, double v, double delta) {
        double a = Math.log(sigma * sigma);
        double deltaSq = delta * delta;
        double phiSq = phi * phi;

        // f(x) as defined in the paper
        java.util.function.DoubleUnaryOperator f = x -> {
            double ex = Math.exp(x);
            double d = phiSq + v + ex;
            double part1 = ex * (deltaSq - phiSq - v - ex) / (2.0 * d * d);
            double part2 = (x - a) / (TAU * TAU);
            return part1 - part2;
        };

        // Set initial bounds
        double A = a;
        double B;

        if (deltaSq > phiSq + v) {
            B = Math.log(deltaSq - phiSq - v);
        } else {
            // Find B such that f(B) < 0
            int k = 1;
            B = a - k * TAU;
            while (f.applyAsDouble(B) > 0) {
                k++;
                B = a - k * TAU;
            }
        }

        // Illinois method to find root
        double fA = f.applyAsDouble(A);
        double fB = f.applyAsDouble(B);

        while (Math.abs(B - A) > EPSILON) {
            double C = A + (A - B) * fA / (fB - fA);
            double fC = f.applyAsDouble(C);

            if (fC * fB <= 0) {
                A = B;
                fA = fB;
            } else {
                fA /= 2.0;
            }
            B = C;
            fB = fC;
        }

        return Math.exp(A / 2.0);
    }

    @Override
    public String toString() {
        return String.format("%.0f ±%.0f (σ=%.4f, %d matches)", rating, rd, volatility, matchCount);
    }
}
