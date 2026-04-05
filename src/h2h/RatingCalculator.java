package h2h;

import java.util.*;

/**
 * Computes Glicko-2 ratings for all engines from H2H match history.
 *
 * <p>Replays all matches chronologically, updating ratings after each match.
 * This is deterministic: given the same match history, ratings are always the same.
 *
 * <p>Engines not appearing in any match get {@link Glicko2Rating#initial()} ratings.
 */
public final class RatingCalculator {

    private RatingCalculator() {}

    /**
     * Computes current Glicko-2 ratings from all H2H match results.
     *
     * <p>Matches are sorted by date (ISO-8601 string comparison) and replayed in order.
     * For each match, both engines' ratings are updated based on the win rate.
     *
     * @param results all H2H match results (may be unsorted)
     * @return map of engine ID → current Glicko-2 rating (only engines that appeared in matches)
     */
    public static Map<String, Glicko2Rating> computeRatings(List<MatchResult> results) {
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

            // Score from A's perspective = A's win rate
            double scoreA = match.winRates[0];

            Glicko2Rating[] updated = Glicko2Rating.update(ratingA, ratingB, scoreA);
            ratings.put(idA, updated[0]);
            ratings.put(idB, updated[1]);
        }

        return ratings;
    }
}
