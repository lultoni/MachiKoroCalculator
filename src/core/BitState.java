package core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Packed bitwise representation of a Machi Koro game state.
 *
 * <p>Each player's entire state fits in a single {@code long} (51 bits for the base game).
 * Copy is {@code Arrays.copyOf} — zero object allocation. All accessors are shift+mask
 * arithmetic, no string comparisons or object lookups.
 *
 * <p>Encoding layout is defined by {@link BitStateTranslator}.
 *
 * <p><b>Thread safety:</b> Not thread-safe. Callers must synchronize externally or use
 * separate copies for concurrent access.
 *
 * @see BitStateTranslator
 */
public final class BitState {

    private final long[] players;
    private final int numPlayers;

    /** Creates an empty state with all zeros for the given number of players. */
    public BitState(int numPlayers) {
        this.numPlayers = numPlayers;
        this.players = new long[numPlayers];
    }

    /** Creates a state from raw player longs. The array is NOT copied — caller must not retain. */
    BitState(long[] players) {
        this.numPlayers = players.length;
        this.players = players;
    }

    // -------------------------------------------------------------------------
    // Copy — the key performance win
    // -------------------------------------------------------------------------

    /** Returns a deep copy. Copy cost = one array allocation + memcpy of numPlayers longs. */
    public BitState copy() {
        return new BitState(Arrays.copyOf(players, numPlayers));
    }

    // -------------------------------------------------------------------------
    // Coins (bits 0-7)
    // -------------------------------------------------------------------------

    public int getCoins(int player) {
        return (int) (players[player] & BitStateTranslator.COINS_MASK);
    }

    public void setCoins(int player, int coins) {
        players[player] = (players[player] & ~(long) BitStateTranslator.COINS_MASK) | (coins & BitStateTranslator.COINS_MASK);
    }

    // -------------------------------------------------------------------------
    // Landmarks (bits 8-11)
    // -------------------------------------------------------------------------

    public boolean hasLandmark(int player, int landmarkIndex) {
        return ((players[player] >> (BitStateTranslator.LANDMARKS_OFFSET + landmarkIndex)) & 1) != 0;
    }

    public void setLandmark(int player, int landmarkIndex) {
        players[player] |= (1L << (BitStateTranslator.LANDMARKS_OFFSET + landmarkIndex));
    }

    public int getLandmarkCount(int player) {
        int field = (int) ((players[player] >> BitStateTranslator.LANDMARKS_OFFSET) & BitStateTranslator.LANDMARKS_MASK);
        return Integer.bitCount(field);
    }

    public boolean hasWon(int player) {
        int field = (int) ((players[player] >> BitStateTranslator.LANDMARKS_OFFSET) & BitStateTranslator.LANDMARKS_MASK);
        return field == BitStateTranslator.LANDMARKS_MASK;
    }

    // -------------------------------------------------------------------------
    // Normal cards (bits 12-47, 3 bits each)
    // -------------------------------------------------------------------------

    public int getCardCount(int player, int normalCardIndex) {
        int shift = BitStateTranslator.NORMAL_CARDS_OFFSET + normalCardIndex * 3;
        return (int) ((players[player] >> shift) & 0x7);
    }

    public void addCard(int player, int normalCardIndex) {
        int shift = BitStateTranslator.NORMAL_CARDS_OFFSET + normalCardIndex * 3;
        players[player] += (1L << shift);
    }

    public void removeCard(int player, int normalCardIndex) {
        int shift = BitStateTranslator.NORMAL_CARDS_OFFSET + normalCardIndex * 3;
        players[player] -= (1L << shift);
    }

    // -------------------------------------------------------------------------
    // Purple cards (bits 48-50, 1 bit each)
    // -------------------------------------------------------------------------

    public boolean hasPurple(int player, int purpleIndex) {
        return ((players[player] >> (BitStateTranslator.PURPLE_CARDS_OFFSET + purpleIndex)) & 1) != 0;
    }

    public void setPurple(int player, int purpleIndex) {
        players[player] |= (1L << (BitStateTranslator.PURPLE_CARDS_OFFSET + purpleIndex));
    }

    // -------------------------------------------------------------------------
    // Category counting (for synergy multipliers)
    // -------------------------------------------------------------------------

    public int foodCount(int player) {
        int count = 0;
        for (int idx : BitStateTranslator.FOOD_CARD_INDICES) count += getCardCount(player, idx);
        return count;
    }

    public int animalCount(int player) {
        int count = 0;
        for (int idx : BitStateTranslator.ANIMAL_CARD_INDICES) count += getCardCount(player, idx);
        return count;
    }

    public int productionCount(int player) {
        int count = 0;
        for (int idx : BitStateTranslator.PRODUCTION_CARD_INDICES) count += getCardCount(player, idx);
        return count;
    }

    // -------------------------------------------------------------------------
    // Supply (derived from all players)
    // -------------------------------------------------------------------------

    /**
     * Returns remaining market supply for the given normal card index (0-6).
     *
     * <p>Starter cards (weizenfeld, bäckerei) are accounted for: each player receives
     * one copy at game start <em>outside</em> the 6-copy market pool. The formula
     * subtracts only market-purchased copies: {@code 6 - max(0, totalOwned - numPlayers)}
     * for starter cards, and {@code 6 - totalOwned} for all others.
     */
    public int supplyRemaining(int normalCardIndex) {
        int total = 0;
        for (int p = 0; p < numPlayers; p++) total += getCardCount(p, normalCardIndex);
        if (BitStateTranslator.IS_STARTER_CARD[normalCardIndex]) {
            int marketPurchased = Math.max(0, total - numPlayers);
            return GameState.SUPPLY_PER_CARD - marketPurchased;
        }
        return GameState.SUPPLY_PER_CARD - total;
    }

    // -------------------------------------------------------------------------
    // Conversion: GameState ↔ BitState
    // -------------------------------------------------------------------------

    /** Encodes a GameState into a BitState. */
    public static BitState fromGameState(GameState gs) {
        Player[] gsPlayers = gs.getPlayers();
        long[] bits = new long[gsPlayers.length];
        for (int p = 0; p < gsPlayers.length; p++) {
            bits[p] = encodePlayer(gsPlayers[p]);
        }
        return new BitState(bits);
    }

    private static long encodePlayer(Player player) {
        long val = 0L;

        // Coins
        val |= (player.getCoins() & BitStateTranslator.COINS_MASK);

        // Count cards by index
        int[] normalCounts = new int[BitStateTranslator.NUM_NORMAL_CARDS];
        boolean[] purples = new boolean[BitStateTranslator.NUM_PURPLE_CARDS];

        for (Project proj : player.getOwned_projects()) {
            String id = proj.getId();

            // Landmark?
            int lmIdx = BitStateTranslator.landmarkIndex(id);
            if (lmIdx >= 0) {
                val |= (1L << (BitStateTranslator.LANDMARKS_OFFSET + lmIdx));
                continue;
            }

            // Normal card?
            int nIdx = BitStateTranslator.normalCardIndex(id);
            if (nIdx >= 0) {
                normalCounts[nIdx]++;
                continue;
            }

            // Purple card?
            int pIdx = BitStateTranslator.purpleCardIndex(id);
            if (pIdx >= 0) {
                purples[pIdx] = true;
            }
        }

        // Encode normal card counts
        for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
            if (normalCounts[i] > 0) {
                val |= ((long) normalCounts[i] << (BitStateTranslator.NORMAL_CARDS_OFFSET + i * 3));
            }
        }

        // Encode purple flags
        for (int i = 0; i < BitStateTranslator.NUM_PURPLE_CARDS; i++) {
            if (purples[i]) {
                val |= (1L << (BitStateTranslator.PURPLE_CARDS_OFFSET + i));
            }
        }

        return val;
    }

    /** Decodes this BitState back into a GameState. */
    public GameState toGameState() {
        Player[] gsPlayers = new Player[numPlayers];
        for (int p = 0; p < numPlayers; p++) {
            ArrayList<Project> owned = new ArrayList<>();

            // Decode normal cards
            for (int ci = 0; ci < BitStateTranslator.NUM_NORMAL_CARDS; ci++) {
                int count = getCardCount(p, ci);
                if (count > 0) {
                    Project card = ProjectLoader.getProject(BitStateTranslator.NORMAL_CARD_IDS[ci]).orElse(null);
                    if (card != null) {
                        for (int j = 0; j < count; j++) owned.add(card);
                    }
                }
            }

            // Decode purple cards
            for (int ci = 0; ci < BitStateTranslator.NUM_PURPLE_CARDS; ci++) {
                if (hasPurple(p, ci)) {
                    Project card = ProjectLoader.getProject(BitStateTranslator.PURPLE_CARD_IDS[ci]).orElse(null);
                    if (card != null) owned.add(card);
                }
            }

            // Decode landmarks
            for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
                if (hasLandmark(p, li)) {
                    Project lm = ProjectLoader.getProject(BitStateTranslator.LANDMARK_IDS[li]).orElse(null);
                    if (lm != null) owned.add(lm);
                }
            }

            gsPlayers[p] = new Player("Player " + (p + 1), getCoins(p), owned);
        }

        // Reconstruct unbuilt_projects: all non-landmark card types with supply > 0
        ArrayList<Project> unbuilt = new ArrayList<>();
        for (Project proj : ProjectLoader.getAllProjects()) {
            if (!proj.isIs_grossprojekt()) {
                unbuilt.add(proj);
            }
        }

        return new GameState(gsPlayers, unbuilt);
    }

    // -------------------------------------------------------------------------
    // Income resolution (replaces RollResolver for simulation hot path)
    // -------------------------------------------------------------------------

    /**
     * Applies a dice roll to this state, following the exact same resolution order as
     * {@link RollResolver#computeAllDeltasForRoll}: Red → Blue/Green → Purple.
     *
     * <p>After computing coin deltas, coins are clamped to ≥ 0 for each player.
     * If roll == 6 and the active player has bürohaus, {@link #executeGreedySwap} is called.
     *
     * @param activePlayer the rolling player
     * @param roll         dice total (1-12)
     */
    public void applyRoll(int activePlayer, int roll) {
        int[] deltas = new int[numPlayers];

        boolean activeHasEKZ = hasLandmark(activePlayer, BitStateTranslator.LM_EKZ);

        // Step 1: Red card payments (counter-clockwise, sequential)
        int rollerCoins = getCoins(activePlayer);
        for (int step = 1; step < numPlayers; step++) {
            int oppIdx = (activePlayer - step + numPlayers) % numPlayers;
            boolean oppHasEKZ = hasLandmark(oppIdx, BitStateTranslator.LM_EKZ);
            int oppRedIncome = computeRedIncome(oppIdx, oppHasEKZ, roll, rollerCoins);
            if (oppRedIncome > 0) {
                deltas[activePlayer] -= oppRedIncome;
                deltas[oppIdx] += oppRedIncome;
                rollerCoins -= oppRedIncome;
                if (rollerCoins < 0) rollerCoins = 0;
            }
        }

        // Step 2: Blue card income for every player
        for (int p = 0; p < numPlayers; p++) {
            deltas[p] += computeBlueIncome(p, roll);
        }

        // Step 3: Green income for active player only
        deltas[activePlayer] += computeGreenIncome(activePlayer, activeHasEKZ, roll);

        // Step 4: Purple income for active player
        // NOTE: RollResolver only adds to the active player's delta for purple cards —
        // it does NOT subtract from opponents. This is a known simplification in the
        // object-based model (purple cards treated as "from bank" in simulations).
        // BitState must match this behavior for equivalence.
        // CRITICAL: RollResolver uses freshOpponentCoins = buildOpponentCoins(players, activePlayer)
        // which reads players[i].getCoins() — the BASE pre-delta coins, NOT base+delta.
        // So opponent coins here must be getCoins(p), not getCoins(p)+deltas[p].
        if (roll == 6) {
            // Stadion
            if (hasPurple(activePlayer, 0)) { // stadion = purple idx 0
                int total = 0;
                for (int p = 0; p < numPlayers; p++) {
                    if (p == activePlayer) continue;
                    total += Math.min(2, getCoins(p));
                }
                deltas[activePlayer] += total;
            }
            // Fernsehsender
            if (hasPurple(activePlayer, 1)) { // fernsehsender = purple idx 1
                int richest = 0;
                for (int p = 0; p < numPlayers; p++) {
                    if (p == activePlayer) continue;
                    int oppCoins = getCoins(p);
                    if (oppCoins > richest) richest = oppCoins;
                }
                deltas[activePlayer] += Math.min(5, richest);
            }
        }

        // Apply deltas with clamping
        for (int p = 0; p < numPlayers; p++) {
            setCoins(p, Math.max(0, getCoins(p) + deltas[p]));
        }

        // Bürohaus swap
        if (roll == 6 && hasPurple(activePlayer, 2)) { // bürohaus = purple idx 2
            executeGreedySwap(activePlayer);
        }
    }

    /** Red card income for one opponent. Returns amount the roller must pay this opponent. */
    private int computeRedIncome(int oppIdx, boolean oppHasEKZ, int roll, int rollerCoins) {
        int totalGain = 0;

        // café (idx 5): activates on roll 3
        if (roll == 3) {
            int count = getCardCount(oppIdx, 5);
            if (count > 0) {
                int perCopy = oppHasEKZ ? 2 : 1;
                int demand = count * perCopy;
                int actual = Math.min(demand, rollerCoins);
                totalGain += actual;
                rollerCoins -= actual;
            }
        }

        // familienrestaurant (idx 10): activates on roll 9, 10
        if (roll == 9 || roll == 10) {
            int count = getCardCount(oppIdx, 10);
            if (count > 0) {
                int perCopy = oppHasEKZ ? 3 : 2;
                int demand = count * perCopy;
                int actual = Math.min(demand, rollerCoins);
                totalGain += actual;
            }
        }

        return totalGain;
    }

    /** Blue card income for one player on this roll. Always positive (from bank). */
    private int computeBlueIncome(int player, int roll) {
        int income = 0;
        // weizenfeld (idx 0): r=1, +1
        if (roll == 1) income += getCardCount(player, 0);
        // bauernhof (idx 2): r=2, +1
        if (roll == 2) income += getCardCount(player, 2);
        // wald (idx 3): r=5, +1
        if (roll == 5) income += getCardCount(player, 3);
        // bergwerk (idx 8): r=9, +5
        if (roll == 9) income += getCardCount(player, 8) * 5;
        // apfelplantage (idx 9): r=10, +3
        if (roll == 10) income += getCardCount(player, 9) * 3;
        return income;
    }

    /** Green card income for the active player on this roll. */
    private int computeGreenIncome(int player, boolean hasEKZ, int roll) {
        int income = 0;
        // bäckerei (idx 1): r=2,3, +1/+2(EKZ)
        if (roll == 2 || roll == 3) {
            income += getCardCount(player, 1) * (hasEKZ ? 2 : 1);
        }
        // mini-markt (idx 4): r=4, +3/+4(EKZ)
        if (roll == 4) {
            income += getCardCount(player, 4) * (hasEKZ ? 4 : 3);
        }
        // molkerei (idx 6): r=7, +3×animal
        if (roll == 7) {
            int count = getCardCount(player, 6);
            if (count > 0) income += count * 3 * animalCount(player);
        }
        // möbelfabrik (idx 7): r=8, +3×production
        if (roll == 8) {
            int count = getCardCount(player, 7);
            if (count > 0) income += count * 3 * productionCount(player);
        }
        // markthalle (idx 11): r=11,12, +2×food
        if (roll == 11 || roll == 12) {
            int count = getCardCount(player, 11);
            if (count > 0) income += count * 2 * foodCount(player);
        }
        return income;
    }

    // -------------------------------------------------------------------------
    // Bürohaus greedy swap
    // -------------------------------------------------------------------------

    /**
     * Executes the greedy Bürohaus swap, mirroring {@link BürohausLogic#executeSwap}.
     *
     * <p>Phase 1 pragmatism: converts to temporary Player objects and delegates EV
     * computation to {@link CardIncome#contextualCardEvPerRound}. The swap itself
     * is performed via bitwise operations.
     */
    public void executeGreedySwap(int activePlayer) {
        // Build temporary GameState for BürohausLogic
        GameState tempGs = toGameState();
        BürohausLogic.SwapCandidates candidates = BürohausLogic.findCandidates(tempGs, activePlayer);
        if (!candidates.isBeneficial()) return;

        // Find the card indices to swap
        String worstOwnId = candidates.worstOwn().getId();
        String bestOppId = candidates.bestOpp().getId();
        int oppPlayer = candidates.bestOppPlayer();

        int worstIdx = BitStateTranslator.normalCardIndex(worstOwnId);
        int bestIdx = BitStateTranslator.normalCardIndex(bestOppId);

        // Both must be normal cards (purple/landmarks are excluded by findCandidates)
        if (worstIdx < 0 || bestIdx < 0) return;

        // Swap via bit operations
        removeCard(activePlayer, worstIdx);
        removeCard(oppPlayer, bestIdx);
        addCard(activePlayer, bestIdx);
        addCard(oppPlayer, worstIdx);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Exposes the raw long for player at the given index. For debugging/testing. */
    public long raw(int player) {
        return players[player];
    }

    public int getNumPlayers() {
        return numPlayers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BitState other)) return false;
        return Arrays.equals(players, other.players);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(players);
    }
}
