package h2h;

import java.util.*;

/**
 * Computes Glicko-2 ratings for all engines from H2H match history.
 *
 * <p>Replays all matches chronologically, updating ratings after each match.
 * This is deterministic: given the same match history, ratings are always the same.
 *
 * <p>Matches with more games produce more rating update rounds (one round per
 * {@value #BASE_GAMES_PER_ROUND} games, minimum 1). This gives larger matches
 * proportionally more influence on ratings and tightens RD faster.
 *
 * <p>Engines not appearing in any match get {@link Glicko2Rating#initial()} ratings.
 */
public final class RatingCalculator {

    /**
     * Number of games that constitute one Glicko-2 rating period.
     * Matches with fewer games still count as 1 period; larger matches
     * produce {@code max(1, gameCount / BASE_GAMES_PER_ROUND)} periods.
     */
    static final int BASE_GAMES_PER_ROUND = 50;

    private RatingCalculator() {}

    /**
     * Rating change for both engines in a single match.
     */
    public record RatingDelta(double deltaA, double deltaB) {}

    /**
     * Computes current Glicko-2 ratings from all H2H match results.
     *
     * <p>Matches are sorted by date (ISO-8601 string comparison) and replayed in order.
     * For each match, both engines' ratings are updated based on the win rate.
     * Matches with more games produce proportionally more update rounds.
     *
     * @param results all H2H match results (may be unsorted)
     * @return map of engine ID → current Glicko-2 rating (only engines that appeared in matches)
     */
    public static Map<String, Glicko2Rating> computeRatings(List<MatchResult> results) {
        return computeRatingsWithDeltas(results, null, false);
    }

    /**
     * Computes Glicko-2 ratings using luck-adjusted win rates where available.
     * Falls back to raw win rates for matches without luck data.
     */
    public static Map<String, Glicko2Rating> computeRatingsLuckAdjusted(List<MatchResult> results) {
        return computeRatingsWithDeltas(results, null, true);
    }

    /**
     * Computes Glicko-2 ratings and optionally records per-match rating deltas.
     *
     * @param results all H2H match results (may be unsorted)
     * @param deltas  if non-null, populated with match ID → rating delta for each processed match
     * @return map of engine ID → current Glicko-2 rating
     */
    public static Map<String, Glicko2Rating> computeRatingsWithDeltas(
            List<MatchResult> results, Map<String, RatingDelta> deltas) {
        return computeRatingsWithDeltas(results, deltas, false);
    }

    /**
     * Core implementation: computes Glicko-2 ratings with optional luck adjustment.
     *
     * @param results           all H2H match results (may be unsorted)
     * @param deltas            if non-null, populated with match ID → rating delta
     * @param useLuckAdjusted   if true, uses {@code luckAdjustedWinRates} where available
     */
    public static Map<String, Glicko2Rating> computeRatingsWithDeltas(
            List<MatchResult> results, Map<String, RatingDelta> deltas,
            boolean useLuckAdjusted) {
        Map<String, Glicko2Rating> ratings = new HashMap<>();

        // Sort by date for chronological replay
        List<MatchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(r -> r.date));

        for (MatchResult match : sorted) {
            if (match.config.engineIds().length != 2) continue;

            String idA = match.config.engineIds()[0];
            String idB = match.config.engineIds()[1];

            // Skip self-play (no meaningful rating update)
            if (idA.equals(idB)) continue;

            Glicko2Rating ratingA = ratings.getOrDefault(idA, Glicko2Rating.initial());
            Glicko2Rating ratingB = ratings.getOrDefault(idB, Glicko2Rating.initial());
            double beforeA = ratingA.rating;
            double beforeB = ratingB.rating;

            // Score from A's perspective = A's win rate (luck-adjusted if requested and available)
            double scoreA;
            if (useLuckAdjusted && match.luckAdjustedWinRates != null
                    && match.luckAdjustedWinRates.length > 0
                    && match.luckAdjustedWinRates[0] != match.winRates[0]) {
                scoreA = match.luckAdjustedWinRates[0];
            } else {
                scoreA = match.winRates[0];
            }

            // More games → more rating periods → stronger signal, tighter RD
            int actualGameCount = 0;
            for (int w : match.wins) actualGameCount += w;
            int rounds = Math.max(1, actualGameCount / BASE_GAMES_PER_ROUND);

            for (int r = 0; r < rounds; r++) {
                Glicko2Rating[] updated = Glicko2Rating.update(ratingA, ratingB, scoreA);
                ratingA = updated[0];
                ratingB = updated[1];
            }
            ratings.put(idA, ratingA);
            ratings.put(idB, ratingB);

            if (deltas != null) {
                deltas.put(match.id, new RatingDelta(
                        ratingA.rating - beforeA, ratingB.rating - beforeB));
            }
        }

        return ratings;
    }
}
