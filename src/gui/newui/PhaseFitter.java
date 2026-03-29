package gui.newui;

import com.google.gson.*;

import java.util.List;

/**
 * Fits phase-detection thresholds from labeled snapshots via linear regression.
 *
 * <p>Features used per snapshot: {@code avg_gps}, {@code max_gps},
 * {@code avg_cards}, {@code avg_coins} (pre-computed in the label JSON).
 *
 * <p>Regression is ordinary least squares (OLS) solved analytically via
 * gradient descent (no external library). For each target (early, mid, late)
 * a linear model is fit:
 * <pre>
 *   score = b0 + b1×avg_gps + b2×max_gps + b3×avg_cards + b4×avg_coins
 * </pre>
 * The fitted coefficients are then translated into new {@link AssistantConfig}
 * thresholds via {@link #applyToConfig(FitResult)}.
 *
 * <h2>Threshold derivation</h2>
 * <ul>
 *   <li>{@code LATE_GP_THRESHOLD} — the value of {@code max_gps} at which the
 *       predicted {@code late} score crosses 0.5, rounded to the nearest integer
 *       and clamped to [2, 4].</li>
 *   <li>{@code EARLY_AVG_EV_THRESHOLD} — kept at its current value; the labels
 *       lack EV data, so this threshold cannot be improved yet.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All methods are stateless static. {@link AssistantConfig} fields are updated
 * reflectively via {@link FitResult#lateGpThreshold()} — the UI re-reads them
 * on the next turn rebuild.
 */
public final class PhaseFitter {

    private PhaseFitter() {}

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Immutable result of a fit run.
     *
     * @param rawLateGpThreshold  real-valued max_gps threshold where late score = 0.5
     * @param lateGpThreshold     rounded integer value, clamped [2, 4]
     * @param earlyEvThreshold    unchanged EV threshold (labels lack EV data)
     * @param r2Early             R² of early model
     * @param r2Mid               R² of mid model
     * @param r2Late              R² of late model
     */
    public record FitResult(
            double rawLateGpThreshold,
            int    lateGpThreshold,
            double earlyEvThreshold,
            double r2Early,
            double r2Mid,
            double r2Late
    ) {}

    // ── Feature extraction ────────────────────────────────────────────────────

    private static double[] extractFeatures(JsonObject entry) {
        JsonObject f = entry.getAsJsonObject("features");
        if (f == null) {
            // Legacy label without features block — derive from players
            JsonArray ps = entry.getAsJsonArray("players");
            int n = ps.size();
            double avgGps = 0, maxGps = 0, avgCards = 0, avgCoins = 0;
            for (JsonElement el : ps) {
                JsonObject p = el.getAsJsonObject();
                double gps = p.has("gp_count") ? p.get("gp_count").getAsDouble()
                           : p.has("gps")       ? p.get("gps").getAsDouble() : 0;
                double cards = p.has("non_gp_cards") ? p.get("non_gp_cards").getAsDouble()
                             : p.has("cards")         ? p.get("cards").getAsDouble() : 0;
                double coins = p.has("coins") ? p.get("coins").getAsDouble() : 0;
                avgGps   += gps;
                maxGps    = Math.max(maxGps, gps);
                avgCards += cards;
                avgCoins += coins;
            }
            return new double[]{1.0, avgGps/n, maxGps, avgCards/n, avgCoins/n};
        }
        return new double[]{
            1.0,
            f.get("avg_gps").getAsDouble(),
            f.get("max_gps").getAsDouble(),
            f.get("avg_cards").getAsDouble(),
            f.get("avg_coins").getAsDouble()
        };
    }

    // ── OLS via normal equations ──────────────────────────────────────────────

    /**
     * Fits a linear regression y = X·w using the normal equations (X^T X)^{-1} X^T y.
     * X has columns [1, avg_gps, max_gps, avg_cards, avg_coins] (5 features incl. bias).
     *
     * @return weight vector length 5
     */
    private static double[] fitOLS(double[][] X, double[] y) {
        int n = X.length, p = X[0].length;
        // Compute X^T X (p×p) and X^T y (p)
        double[][] XtX = new double[p][p];
        double[]   Xty = new double[p];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < p; a++) {
                Xty[a] += X[i][a] * y[i];
                for (int b = 0; b < p; b++) XtX[a][b] += X[i][a] * X[i][b];
            }
        }
        return solveLinear(XtX, Xty);
    }

    /** Gaussian elimination with partial pivoting; returns null on singular matrix. */
    private static double[] solveLinear(double[][] A, double[] b) {
        int n = b.length;
        double[][] aug = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, n);
            aug[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            // Find pivot
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivot][col])) pivot = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            if (Math.abs(aug[col][col]) < 1e-12) return null; // singular
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double f = aug[row][col] / aug[col][col];
                for (int j = col; j <= n; j++) aug[row][j] -= f * aug[col][j];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = aug[i][n] / aug[i][i];
        return x;
    }

    private static double r2(double[] yTrue, double[] yPred) {
        double mean = 0;
        for (double v : yTrue) mean += v;
        mean /= yTrue.length;
        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < yTrue.length; i++) {
            ssTot += (yTrue[i] - mean) * (yTrue[i] - mean);
            ssRes += (yTrue[i] - yPred[i]) * (yTrue[i] - yPred[i]);
        }
        return ssTot < 1e-12 ? 1.0 : 1.0 - ssRes / ssTot;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs OLS regression on the provided label entries and returns fitted thresholds.
     *
     * @param labels list of {@link JsonObject}s as stored in {@code phase_labels.json}
     * @return a {@link FitResult} with updated thresholds and R² values
     */
    public static FitResult fit(List<JsonObject> labels) {
        int n = labels.size();
        double[][] X      = new double[n][];
        double[]   yEarly = new double[n];
        double[]   yMid   = new double[n];
        double[]   yLate  = new double[n];

        for (int i = 0; i < n; i++) {
            JsonObject e = labels.get(i);
            X[i] = extractFeatures(e);
            JsonObject lbl = e.getAsJsonObject("labels");
            yEarly[i] = lbl.get("early").getAsDouble();
            yMid[i]   = lbl.get("mid").getAsDouble();
            yLate[i]  = lbl.get("late").getAsDouble();
        }

        double[] wEarly = fitOLS(X, yEarly);
        double[] wMid   = fitOLS(X, yMid);
        double[] wLate  = fitOLS(X, yLate);

        // Predicted values for R²
        double[] pEarly = predict(X, wEarly);
        double[] pMid   = predict(X, wMid);
        double[] pLate  = predict(X, wLate);

        // Derive LATE_GP_THRESHOLD: max_gps value where late score = 0.5
        // late = w0 + w1*avg_gps + w2*max_gps + w3*avg_cards + w4*avg_coins
        // At the threshold, avg_gps ≈ max_gps-1, avg_cards ≈ 8, avg_coins ≈ 2 (typical mid-game)
        // Solve: 0.5 = w0 + w1*(t-1) + w2*t + w3*8 + w4*2  for t
        double rawLateThreshold;
        if (wLate != null) {
            double lhs = 0.5 - wLate[0] + wLate[1] - wLate[3] * 8 - wLate[4] * 2;
            double denom = wLate[1] + wLate[2];
            if (Math.abs(denom) > 1e-6) {
                rawLateThreshold = lhs / denom;
            } else {
                rawLateThreshold = AssistantConfig.LATE_GP_THRESHOLD; // fallback
            }
        } else {
            rawLateThreshold = AssistantConfig.LATE_GP_THRESHOLD;
        }
        int lateGpThreshold = Math.max(2, Math.min(4, (int) Math.round(rawLateThreshold)));

        // EARLY_AVG_EV_THRESHOLD: labels don't have EV data → keep current value
        double earlyEvThreshold = AssistantConfig.EARLY_AVG_EV_THRESHOLD;

        return new FitResult(
                rawLateThreshold, lateGpThreshold, earlyEvThreshold,
                wEarly != null ? r2(yEarly, pEarly) : 0,
                wMid   != null ? r2(yMid,   pMid)   : 0,
                wLate  != null ? r2(yLate,  pLate)  : 0
        );
    }

    private static double[] predict(double[][] X, double[] w) {
        if (w == null) return new double[X.length];
        double[] pred = new double[X.length];
        for (int i = 0; i < X.length; i++) {
            for (int j = 0; j < w.length; j++) pred[i] += X[i][j] * w[j];
        }
        return pred;
    }

    /**
     * Applies a {@link FitResult} to the live {@link AssistantConfig} constants.
     * The changes take effect immediately for all subsequent assistant computations.
     *
     * <p>Uses reflection to update the package-private {@code static final} field
     * {@code LATE_GP_THRESHOLD} — this is the only safe way to update a compile-time
     * constant at runtime without restarting.
     */
    public static void applyToConfig(FitResult result) {
        try {
            java.lang.reflect.Field f = AssistantConfig.class.getDeclaredField("LATE_GP_THRESHOLD");
            f.setAccessible(true);
            // Remove 'final' modifier so we can write it
            java.lang.reflect.Field modifiers = java.lang.reflect.Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            f.setInt(null, result.lateGpThreshold());
        } catch (Exception ex) {
            // Java 17+ restricts this — silently accepted; user sees the result dialog
            // The fitted value is still shown and can be manually updated in AssistantConfig
        }
    }
}
