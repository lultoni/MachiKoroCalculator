package h2h;

import java.util.Arrays;
import java.util.Random;

/**
 * Tree-structured Parzen Estimator (TPE) for hyperparameter optimization.
 *
 * <p>TPE is a sequential model-based optimization algorithm that models the objective
 * function indirectly by building two density estimates: {@code l(x)} for parameter
 * vectors that produced good results, and {@code g(x)} for those that produced bad results.
 * New candidates are sampled from {@code l(x)} and scored by {@code l(x)/g(x)}, which
 * is proportional to Expected Improvement.
 *
 * <p>Each dimension is modeled independently with a 1D Gaussian kernel density estimate.
 * This scales well to high dimensions (20+ parameters) unlike GP-based approaches.
 *
 * <p>Uses Latin Hypercube Sampling for initial startup trials before TPE kicks in.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Bergstra et al., "Algorithms for Hyper-Parameter Optimization" (2011)</li>
 *   <li>Optuna: A Next-generation Hyperparameter Optimization Framework (2019)</li>
 * </ul>
 *
 * @see SweepMain
 */
public final class TpeSampler {

    private final Random rng;
    private final double gamma;
    private final int nCandidates;

    /**
     * @param rng         random source
     * @param gamma       fraction of observations considered "good" (0.0-1.0, typically 0.25)
     * @param nCandidates number of candidates sampled from l(x) per suggestion
     */
    public TpeSampler(Random rng, double gamma, int nCandidates) {
        this.rng = rng;
        this.gamma = gamma;
        this.nCandidates = nCandidates;
    }

    /**
     * Parameter space definition: one bounded continuous dimension.
     */
    public record ParamDef(String key, double low, double high, double defaultVal) {
        double range() { return high - low; }
        double clamp(double v) { return Math.max(low, Math.min(high, v)); }
        /** Normalize to [0,1]. */
        double normalize(double v) { return (v - low) / range(); }
        /** Denormalize from [0,1]. */
        double denormalize(double n) { return low + n * range(); }
    }

    /**
     * Generates Latin Hypercube Sampling points for the startup phase.
     *
     * <p>Divides each dimension into {@code n} equal strata and randomly assigns
     * one sample per stratum per dimension, ensuring uniform coverage of the space.
     *
     * @param params parameter definitions
     * @param n      number of samples
     * @return       n × params.length array of sampled parameter values
     */
    public double[][] latinHypercube(ParamDef[] params, int n) {
        int d = params.length;
        double[][] result = new double[n][d];

        for (int dim = 0; dim < d; dim++) {
            // Create permuted stratum indices
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) perm[i] = i;
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
            }

            ParamDef p = params[dim];
            for (int i = 0; i < n; i++) {
                double low = (double) perm[i] / n;
                double high = (double) (perm[i] + 1) / n;
                double u = low + rng.nextDouble() * (high - low);
                result[i][dim] = p.denormalize(u);
            }
        }
        return result;
    }

    /**
     * Suggests the next parameter vector to evaluate using TPE.
     *
     * <p>Splits observations into good/bad by objective value, fits 1D KDEs
     * to each group per dimension, samples candidates from l(x), and returns
     * the candidate maximizing l(x)/g(x).
     *
     * @param params     parameter definitions
     * @param values     observed parameter vectors (nTrials × nParams)
     * @param objectives observed objective values (higher = better)
     * @return           next parameter vector to evaluate
     */
    public double[] suggest(ParamDef[] params, double[][] values, double[] objectives) {
        int n = objectives.length;
        int d = params.length;

        // Split into good/bad
        int nGood = Math.max(1, (int) (gamma * n));
        int[] sortedIdx = sortedIndices(objectives);
        // Top nGood by objective = good
        boolean[] isGood = new boolean[n];
        for (int i = n - nGood; i < n; i++) {
            isGood[sortedIdx[i]] = true;
        }

        // Extract good/bad values per dimension
        double[][] goodPerDim = new double[d][];
        double[][] badPerDim = new double[d][];
        double[] bandwidthGood = new double[d];
        double[] bandwidthBad = new double[d];

        for (int dim = 0; dim < d; dim++) {
            double[] gVals = new double[nGood];
            double[] bVals = new double[n - nGood];
            int gi = 0, bi = 0;
            for (int i = 0; i < n; i++) {
                double normalized = params[dim].normalize(values[i][dim]);
                if (isGood[i]) gVals[gi++] = normalized;
                else bVals[bi++] = normalized;
            }
            goodPerDim[dim] = gVals;
            badPerDim[dim] = bVals;
            bandwidthGood[dim] = silvermanBandwidth(gVals);
            bandwidthBad[dim] = silvermanBandwidth(bVals);
        }

        // Sample nCandidates from l(x) and score by l(x)/g(x)
        double bestScore = Double.NEGATIVE_INFINITY;
        double[] bestCandidate = null;

        for (int c = 0; c < nCandidates; c++) {
            double[] candidate = new double[d];
            double logLx = 0.0, logGx = 0.0;

            for (int dim = 0; dim < d; dim++) {
                // Sample from good KDE
                double sample = sampleFromKde(goodPerDim[dim], bandwidthGood[dim]);
                sample = Math.max(0.0, Math.min(1.0, sample)); // clamp to [0,1]
                candidate[dim] = sample;

                // Compute log densities
                logLx += Math.log(Math.max(1e-300,
                        kde(goodPerDim[dim], bandwidthGood[dim], sample)));
                logGx += Math.log(Math.max(1e-300,
                        kde(badPerDim[dim], bandwidthBad[dim], sample)));
            }

            double score = logLx - logGx;
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        // Denormalize back to parameter space
        double[] result = new double[d];
        for (int dim = 0; dim < d; dim++) {
            result[dim] = params[dim].denormalize(bestCandidate[dim]);
        }
        return result;
    }

    // =====================================================================
    // 1D Kernel Density Estimation
    // =====================================================================

    /**
     * Evaluates a 1D Gaussian KDE at a query point.
     * Uses a mixture of Gaussians centered at each observation.
     */
    private static double kde(double[] samples, double bandwidth, double query) {
        if (samples.length == 0) return 1.0; // uniform prior for empty groups
        double sum = 0.0;
        double invBw = 1.0 / bandwidth;
        for (double s : samples) {
            double z = (query - s) * invBw;
            sum += Math.exp(-0.5 * z * z);
        }
        // Normalization: 1/(n*bw*sqrt(2pi))
        return sum * invBw / (samples.length * SQRT_2PI);
    }

    /**
     * Samples a point from a 1D Gaussian KDE.
     * Picks a random observation as the kernel center, then adds Gaussian noise.
     */
    private double sampleFromKde(double[] samples, double bandwidth) {
        if (samples.length == 0) return rng.nextDouble(); // uniform fallback
        int idx = rng.nextInt(samples.length);
        return samples[idx] + rng.nextGaussian() * bandwidth;
    }

    /**
     * Silverman's rule of thumb for bandwidth selection.
     * {@code bw = 0.9 * min(std, IQR/1.34) * n^(-1/5)}, clamped to [0.01, 0.5].
     */
    private static double silvermanBandwidth(double[] samples) {
        int n = samples.length;
        if (n <= 1) return 0.2; // wide default for tiny samples

        double[] sorted = samples.clone();
        Arrays.sort(sorted);

        // Standard deviation
        double mean = 0;
        for (double s : sorted) mean += s;
        mean /= n;
        double var = 0;
        for (double s : sorted) var += (s - mean) * (s - mean);
        double std = Math.sqrt(var / n);

        // IQR
        double q1 = sorted[(int) (0.25 * (n - 1))];
        double q3 = sorted[(int) (0.75 * (n - 1))];
        double iqr = q3 - q1;

        double spread = Math.min(std, iqr / 1.34);
        if (spread < 1e-10) spread = std > 1e-10 ? std : 0.1;

        double bw = 0.9 * spread * Math.pow(n, -0.2);
        return Math.max(0.01, Math.min(0.5, bw));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    /** Returns indices sorted by ascending value. */
    private static int[] sortedIndices(double[] values) {
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));
        int[] result = new int[idx.length];
        for (int i = 0; i < idx.length; i++) result[i] = idx[i];
        return result;
    }
}
