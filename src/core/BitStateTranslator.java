package core;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the bitwise game-state encoding layout.
 *
 * <p>Maps between bit positions and card IDs/indices. All constants for the
 * 51-bit-per-player encoding are defined here — nothing is hardcoded elsewhere.
 *
 * <h3>Encoding (base game, 51 bits per player, fits in one {@code long}):</h3>
 * <pre>
 * Bits  0-7:   coins (8 bits, 0-255)
 * Bits  8-11:  landmarks (4 bits: bahnhof|ekz|fzp|funkturm)
 * Bits 12-47:  12 normal card counts × 3 bits each (0-7)
 * Bits 48-50:  3 purple cards × 1 bit each
 * --- 51 bits used, 13 spare in a long ---
 * </pre>
 */
public final class BitStateTranslator {

    private BitStateTranslator() {}

    // -------------------------------------------------------------------------
    // Dimensions
    // -------------------------------------------------------------------------

    public static final int NUM_NORMAL_CARDS = 12;
    public static final int NUM_PURPLE_CARDS = 3;
    public static final int NUM_LANDMARKS = 4;

    // -------------------------------------------------------------------------
    // Bit offsets within a player long
    // -------------------------------------------------------------------------

    public static final int COINS_OFFSET = 0;
    public static final int COINS_BITS = 8;
    public static final int COINS_MASK = 0xFF;

    public static final int LANDMARKS_OFFSET = 8;
    public static final int LANDMARKS_BITS = 4;
    public static final int LANDMARKS_MASK = 0xF;

    public static final int NORMAL_CARDS_OFFSET = 12;  // 12 cards × 3 bits each = 36 bits
    public static final int PURPLE_CARDS_OFFSET = 48;   // 12 + 12*3 = 48

    public static final int BITS_PER_PLAYER = 51;       // 8 + 4 + 36 + 3

    // -------------------------------------------------------------------------
    // Landmark bit positions within the 4-bit landmark field
    // -------------------------------------------------------------------------

    public static final int LM_BAHNHOF = 0;
    public static final int LM_EKZ = 1;
    public static final int LM_FZP = 2;
    public static final int LM_FT = 3;

    // -------------------------------------------------------------------------
    // Card ID arrays (index = bit-position index)
    // -------------------------------------------------------------------------

    /**
     * Normal (non-landmark, non-purple) card IDs, indexed 0-11.
     * Each occupies 3 bits at offset {@code NORMAL_CARDS_OFFSET + index*3}.
     */
    public static final String[] NORMAL_CARD_IDS = {
        "weizenfeld",           // 0  - blue,  food,       r=1,  +1
        "bäckerei",             // 1  - green, store,      r=2,3, +1/+2(EKZ)
        "bauernhof",            // 2  - blue,  animal,     r=2,  +1
        "wald",                 // 3  - blue,  production, r=5,  +1
        "mini-markt",           // 4  - green, store,      r=4,  +3/+4(EKZ)
        "café",                 // 5  - red,   cafe,       r=3,  -1/-2(EKZ)
        "molkerei",             // 6  - green, factory,    r=7,  +3×animal
        "möbelfabrik",          // 7  - green, factory,    r=8,  +3×production
        "bergwerk",             // 8  - blue,  production, r=9,  +5
        "apfelplantage",        // 9  - blue,  food,       r=10, +3
        "familienrestaurant",   // 10 - red,   cafe,       r=9,10, -2/-3(EKZ)
        "markthalle"            // 11 - green, market,     r=11,12, +2×food
    };

    /** Purple card IDs, indexed 0-2. Each occupies 1 bit at offset {@code PURPLE_CARDS_OFFSET + index}. */
    public static final String[] PURPLE_CARD_IDS = {
        "stadion",              // 0 - r=6, +2 from each opponent
        "fernsehsender",        // 1 - r=6, +5 from richest opponent
        "bürohaus"              // 2 - r=6, card swap (no coin delta)
    };

    /** Landmark IDs, indexed 0-3. Each occupies 1 bit at offset {@code LANDMARKS_OFFSET + index}. */
    public static final String[] LANDMARK_IDS = {
        "bahnhof",              // 0 - dice choice
        "einkaufszentrum",      // 1 - +1 to store/cafe
        "freizeitpark",         // 2 - doubles bonus turn
        "funkturm"              // 3 - reroll once
    };

    // -------------------------------------------------------------------------
    // Category index arrays (for synergy counting)
    // -------------------------------------------------------------------------

    /** Normal card indices with category "food": weizenfeld(0), apfelplantage(9). */
    public static final int[] FOOD_CARD_INDICES = {0, 9};

    /** Normal card indices with category "animal": bauernhof(2). */
    public static final int[] ANIMAL_CARD_INDICES = {2};

    /** Normal card indices with category "production": wald(3), bergwerk(8). */
    public static final int[] PRODUCTION_CARD_INDICES = {3, 8};

    // -------------------------------------------------------------------------
    // EKZ bonus flags — which normal card indices get the +1 EKZ bonus
    // -------------------------------------------------------------------------

    /** True if the card at this normal index benefits from Einkaufszentrum (+1 bonus). */
    public static final boolean[] IS_STORE_OR_CAFE = new boolean[NUM_NORMAL_CARDS];
    static {
        IS_STORE_OR_CAFE[1] = true;   // bäckerei (store)
        IS_STORE_OR_CAFE[4] = true;   // mini-markt (store)
        IS_STORE_OR_CAFE[5] = true;   // café (cafe)
        IS_STORE_OR_CAFE[10] = true;  // familienrestaurant (cafe)
    }

    /** True if the card at this normal index is a starter card (outside the 6-copy market supply). */
    public static final boolean[] IS_STARTER_CARD = new boolean[NUM_NORMAL_CARDS];
    static {
        IS_STARTER_CARD[0] = true;    // weizenfeld
        IS_STARTER_CARD[1] = true;    // bäckerei
    }

    // -------------------------------------------------------------------------
    // Cost arrays (from ProjectLoader, for buy-phase computation)
    // -------------------------------------------------------------------------

    /** Cost of each normal card, indexed 0-11. */
    public static final int[] NORMAL_CARD_COSTS = new int[NUM_NORMAL_CARDS];

    /** Cost of each purple card, indexed 0-2. */
    public static final int[] PURPLE_CARD_COSTS = new int[NUM_PURPLE_CARDS];

    /** Cost of each landmark, indexed 0-3. */
    public static final int[] LANDMARK_COSTS = new int[NUM_LANDMARKS];

    /** Project references for EV computation. Loaded from ProjectLoader at class-init time. */
    public static final Project[] NORMAL_CARD_PROJECTS = new Project[NUM_NORMAL_CARDS];
    public static final Project[] PURPLE_CARD_PROJECTS = new Project[NUM_PURPLE_CARDS];

    /** True if any dice_activation value >= 7 (needs 2d6 / Bahnhof to reach). */
    public static final boolean[] IS_HIGH_RANGE = new boolean[NUM_NORMAL_CARDS];

    /** Landmark indices in purchase-priority order (cheapest first). */
    public static final int[] LANDMARK_BUY_ORDER = {LM_BAHNHOF, LM_EKZ, LM_FZP, LM_FT};

    /**
     * Unified candidate iteration order matching {@code ProjectLoader.getAllProjects()}.
     * Each entry is an index into the normal (0-11) or purple (offset by NUM_NORMAL_CARDS) arrays.
     * Purple entries have index >= NUM_NORMAL_CARDS.
     * This order must match the object-based iteration for Boltzmann sampling equivalence.
     */
    public static final int[] CANDIDATE_ITERATION_ORDER;

    // -------------------------------------------------------------------------
    // Reverse lookups: card ID → index
    // (Declared before the main static block so they can be populated first)
    // -------------------------------------------------------------------------

    private static final Map<String, Integer> NORMAL_INDEX_MAP = new HashMap<>();
    private static final Map<String, Integer> PURPLE_INDEX_MAP = new HashMap<>();
    private static final Map<String, Integer> LANDMARK_INDEX_MAP = new HashMap<>();

    static {
        // Initialize reverse lookup maps FIRST (needed by iteration order building below)
        for (int i = 0; i < NORMAL_CARD_IDS.length; i++) NORMAL_INDEX_MAP.put(NORMAL_CARD_IDS[i], i);
        for (int i = 0; i < PURPLE_CARD_IDS.length; i++) PURPLE_INDEX_MAP.put(PURPLE_CARD_IDS[i], i);
        for (int i = 0; i < LANDMARK_IDS.length; i++) LANDMARK_INDEX_MAP.put(LANDMARK_IDS[i], i);

        // Populate cost, project, and high-range arrays from ProjectLoader
        for (int i = 0; i < NUM_NORMAL_CARDS; i++) {
            Project p = ProjectLoader.getProject(NORMAL_CARD_IDS[i]).orElseThrow();
            NORMAL_CARD_COSTS[i] = p.getCost();
            NORMAL_CARD_PROJECTS[i] = p;
            for (int da : p.getDice_activation()) {
                if (da >= 7) { IS_HIGH_RANGE[i] = true; break; }
            }
        }
        for (int i = 0; i < NUM_PURPLE_CARDS; i++) {
            Project p = ProjectLoader.getProject(PURPLE_CARD_IDS[i]).orElseThrow();
            PURPLE_CARD_COSTS[i] = p.getCost();
            PURPLE_CARD_PROJECTS[i] = p;
        }
        for (int i = 0; i < NUM_LANDMARKS; i++) {
            LANDMARK_COSTS[i] = ProjectLoader.getProject(LANDMARK_IDS[i]).orElseThrow().getCost();
        }

        // Build iteration order from ProjectLoader's order (matches unbuilt_projects iteration)
        java.util.ArrayList<Integer> order = new java.util.ArrayList<>();
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt()) continue; // skip landmarks
            int nIdx = normalCardIndex(p.getId());
            if (nIdx >= 0) {
                order.add(nIdx);
                continue;
            }
            int pIdx = purpleCardIndex(p.getId());
            if (pIdx >= 0) {
                order.add(NUM_NORMAL_CARDS + pIdx);
            }
        }
        CANDIDATE_ITERATION_ORDER = order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns the normal card index (0-11) for the given card ID, or -1 if not a normal card. */
    public static int normalCardIndex(String cardId) {
        Integer idx = NORMAL_INDEX_MAP.get(cardId);
        return idx != null ? idx : -1;
    }

    /** Returns the purple card index (0-2) for the given card ID, or -1 if not a purple card. */
    public static int purpleCardIndex(String cardId) {
        Integer idx = PURPLE_INDEX_MAP.get(cardId);
        return idx != null ? idx : -1;
    }

    /** Returns the landmark index (0-3) for the given landmark ID, or -1 if not a landmark. */
    public static int landmarkIndex(String landmarkId) {
        Integer idx = LANDMARK_INDEX_MAP.get(landmarkId);
        return idx != null ? idx : -1;
    }
}
