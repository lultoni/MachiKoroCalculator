package core;

import java.util.ArrayList;
import java.util.Objects;

public class Player {

    private final String name;
    private int coins;
    private final ArrayList<Project> owned_projects;

    // Landmark fast-path: bitfield + count, maintained by addProject/removeProject/recomputeFlags.
    private int landmarkFlags = 0;
    private int landmarkCount = 0;
    // Bit assignments:
    private static final int BIT_BAHNHOF         = 1;
    private static final int BIT_EINKAUFSZENTRUM = 2;
    private static final int BIT_FREIZEITPARK    = 4;
    private static final int BIT_FUNKTURM        = 8;

    /**
     * @param name            player display name, must not be null
     * @param coins           current coin count, must be >= 0
     * @param owned_projects  list of owned projects, must not be null
     */
    public Player(String name, int coins, ArrayList<Project> owned_projects) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
        this.owned_projects = Objects.requireNonNull(owned_projects, "owned_projects must not be null");
        recomputeLandmarkFlags();
    }

    /**
     * Package-private copy constructor: transfers pre-computed landmark flags directly,
     * avoiding the O(k) {@link #recomputeLandmarkFlags()} scan on every copy.
     *
     * <p><b>Caller must guarantee</b> that {@code landmarkFlags} and {@code landmarkCount}
     * are consistent with the projects in {@code owned_projects}. Only used by {@link #copy()}.
     */
    Player(String name, int coins, ArrayList<Project> owned_projects,
           int landmarkFlags, int landmarkCount) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
        this.owned_projects = Objects.requireNonNull(owned_projects, "owned_projects must not be null");
        this.landmarkFlags = landmarkFlags;
        this.landmarkCount = landmarkCount;
    }

    /**
     * Returns the player's display name.
     */
    public String getName() {
        return name;
    }

    /** Returns the player's current coin count (always ≥ 0). */
    public int getCoins() {
        return coins;
    }

    /**
     * Sets the player's coin count.
     *
     * @param coins new coin count, must be ≥ 0
     * @throws IllegalArgumentException if coins &lt; 0
     */
    public void setCoins(int coins) {
        if (coins < 0) throw new IllegalArgumentException("coins must be >= 0, got: " + coins);
        this.coins = coins;
    }

    /**
     * Returns the mutable list of projects owned by this player.
     * Callers may add or remove entries directly; {@link Project} references are safe to share
     * because {@link Project} is immutable.
     */
    public ArrayList<Project> getOwned_projects() {
        return owned_projects;
    }

    /**
     * Returns {@code true} if this player owns at least one project with the given ID.
     * O(1) for landmarks (bitfield), O(n) for non-landmarks.
     *
     * @param project_id the project ID to search for (case-sensitive, German spelling)
     */
    public boolean hasProject(String project_id) {
        int bit = landmarkBit(project_id);
        if (bit != 0) return (landmarkFlags & bit) != 0;
        for (Project project : owned_projects) if (project.getId().equals(project_id)) return true;
        return false;
    }

    /** Returns the number of landmarks this player owns. */
    public int getLandmarkCount() {
        return landmarkCount;
    }

    /**
     * Adds a project to this player's portfolio and updates the landmark bitfield.
     * Prefer this over {@code getOwned_projects().add()} in hot paths.
     */
    public void addProject(Project p) {
        owned_projects.add(p);
        int bit = landmarkBit(p.getId());
        if (bit != 0) {
            landmarkFlags |= bit;
            landmarkCount++;
        }
    }

    /**
     * Removes a project from this player's portfolio and updates the landmark bitfield.
     * Returns true if the project was found and removed.
     * Prefer this over {@code getOwned_projects().remove()} in hot paths.
     */
    public boolean removeProject(Project p) {
        boolean removed = owned_projects.remove(p);
        if (removed) {
            int bit = landmarkBit(p.getId());
            if (bit != 0) {
                // Re-check: could still own another copy (shouldn't for landmarks, but safe)
                landmarkFlags = 0;
                landmarkCount = 0;
                for (Project proj : owned_projects) {
                    int b = landmarkBit(proj.getId());
                    if (b != 0) { landmarkFlags |= b; landmarkCount++; }
                }
            }
        }
        return removed;
    }

    /**
     * Returns a new Player with the same name, coins, and landmark state, and a new ArrayList
     * containing the same Project references. Safe because Project is immutable.
     * Uses the package-private constructor to skip landmark recomputation.
     */
    public Player copy() {
        return new Player(name, coins, new ArrayList<>(owned_projects), landmarkFlags, landmarkCount);
    }

    // -------------------------------------------------------------------------
    // Landmark bitfield internals
    // -------------------------------------------------------------------------

    private static int landmarkBit(String id) {
        return switch (id) {
            case "bahnhof"         -> BIT_BAHNHOF;
            case "einkaufszentrum" -> BIT_EINKAUFSZENTRUM;
            case "freizeitpark"    -> BIT_FREIZEITPARK;
            case "funkturm"        -> BIT_FUNKTURM;
            default                -> 0;
        };
    }

    private void recomputeLandmarkFlags() {
        landmarkFlags = 0;
        landmarkCount = 0;
        for (Project p : owned_projects) {
            int bit = landmarkBit(p.getId());
            if (bit != 0) { landmarkFlags |= bit; landmarkCount++; }
        }
    }
}
