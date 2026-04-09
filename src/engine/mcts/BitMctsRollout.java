package engine.mcts;

import calcs.WinProbability;
import core.BitState;
import core.BitStateTranslator;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uniform-random full-game rollout for MCTS, using {@link BitState} internally.
 *
 * <p>All turn-level mutation is bitwise — zero object allocation in the hot loop.
 * Satisfies the {@link BitRolloutFn} interface directly via {@link #simulateBit}.
 *
 * <h2>Decision policy (fully uniform random)</h2>
 * <ul>
 *   <li><b>Dice count</b> — if player owns Bahnhof: 50/50 between 1d6 and 2d6.</li>
 *   <li><b>Funkturm</b> — 50/50 keep or reroll once.</li>
 *   <li><b>Bürohaus</b> — if roll 6: uniform random swap from all valid pairs + no-swap.</li>
 *   <li><b>Purchase</b> — uniform random from all affordable options + save.</li>
 *   <li><b>Freizeitpark</b> — one bonus turn on doubles (no further chaining).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Uses {@link ThreadLocalRandom} — safe for concurrent use from multiple threads.
 */
public final class BitMctsRollout {

    private BitMctsRollout() {}

    /**
     * Runs a uniform-random rollout from {@code startState} until the game ends or
     * the turn limit is reached.
     *
     * <p>Converts to BitState at entry. The supplied GameState is NOT mutated.
     * Retained for backward compatibility with external callers.
     *
     * @param startState        game state at the leaf node
     * @param startSupply       supply tracker (used only for initial conversion; supply is rebuilt from BitState)
     * @param startingPlayer    the player whose turn it is at the start of the rollout
     * @param playerPerspective the player for whom we compute the score (root's playerIndex)
     * @return score in [0, 1] from playerPerspective's point of view
     */
    public static double simulate(core.GameState startState, SupplyTracker startSupply,
                                  int startingPlayer, int playerPerspective) {
        BitState bs = BitState.fromGameState(startState);
        int[] supply = bs.buildSupplyArray();
        return simulateInternal(bs, supply, startState.getPlayers().length,
                startingPlayer, playerPerspective, MAX_TURNS);
    }

    /**
     * Creates a depth-limited rollout function.
     *
     * @param maxDepth maximum number of turns before applying the heuristic
     * @return a BitRolloutFn suitable for {@link MctsTree}
     */
    public static BitRolloutFn withMaxDepth(int maxDepth) {
        return (bs, supply, startingPlayer, playerPerspective) ->
                simulateInternal(bs.copy(), Arrays.copyOf(supply, supply.length),
                        bs.getNumPlayers(), startingPlayer, playerPerspective, Math.max(1, maxDepth));
    }

    // -------------------------------------------------------------------------
    // Core simulation
    // -------------------------------------------------------------------------

    /** Maximum number of turns before falling back to the softmax heuristic. */
    public static final int MAX_TURNS = 200;

    /**
     * BitState-native rollout entry point matching {@link BitRolloutFn}.
     *
     * <p><b>Copies state and supply at entry</b> — the caller's BitState and supply array
     * are NOT mutated. This is critical when called from MctsTree, FlatMcEngine, or
     * CreatorEngine where the same state/supply is reused across multiple rollouts.
     */
    public static double simulateBit(BitState bs, int[] supply,
                                     int startingPlayer, int playerPerspective) {
        return simulateInternal(bs.copy(), Arrays.copyOf(supply, supply.length),
                bs.getNumPlayers(), startingPlayer, playerPerspective, MAX_TURNS);
    }

    private static double simulateInternal(BitState bs, int[] supply, int n,
                                           int startingPlayer, int playerPerspective,
                                           int turnLimit) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int activePlayer = startingPlayer;
        int turnCount = 0;

        while (turnCount < turnLimit) {
            // ---- Dice count ----
            boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
            boolean twoDice = hasBahnhof && rng.nextBoolean();

            // ---- Roll ----
            int roll;
            boolean doubles = false;
            if (twoDice) {
                int d1 = rng.nextInt(1, 7);
                int d2 = rng.nextInt(1, 7);
                roll = d1 + d2;
                doubles = (d1 == d2);
            } else {
                roll = rng.nextInt(1, 7);
            }

            // ---- Funkturm: 50/50 keep or reroll ----
            if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FT)) {
                if (rng.nextBoolean()) {
                    if (twoDice) {
                        int d1 = rng.nextInt(1, 7);
                        int d2 = rng.nextInt(1, 7);
                        roll = d1 + d2;
                        doubles = (d1 == d2);
                    } else {
                        roll = rng.nextInt(1, 7);
                        doubles = false;
                    }
                }
            }

            // ---- Apply roll ----
            bs.applyRoll(activePlayer, roll);

            // ---- Bürohaus: uniform random swap ----
            if (bs.hasPurple(activePlayer, 2) && roll == 6) { // bürohaus = purple idx 2
                applyBürohausRandom(bs, activePlayer, n, rng);
            }

            // ---- Purchase: uniform random ----
            applyPurchaseRandom(bs, supply, activePlayer, n, rng);

            // ---- Win check ----
            if (bs.hasWon(activePlayer)) {
                return activePlayer == playerPerspective ? 1.0 : 0.0;
            }

            // ---- Freizeitpark bonus turn ----
            if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FZP) && doubles) {
                playBonusTurn(bs, supply, activePlayer, n, rng);
                if (bs.hasWon(activePlayer)) {
                    return activePlayer == playerPerspective ? 1.0 : 0.0;
                }
            }

            // ---- Advance ----
            activePlayer = (activePlayer + 1) % n;
            turnCount++;
        }

        // Turn/depth limit reached — use softmax heuristic
        return WinProbability.computeBaselineWinProb(bs, playerPerspective);
    }

    // -------------------------------------------------------------------------
    // Bonus turn
    // -------------------------------------------------------------------------

    private static void playBonusTurn(BitState bs, int[] supply, int activePlayer,
                                      int n, ThreadLocalRandom rng) {
        boolean hasBahnhof = bs.hasLandmark(activePlayer, BitStateTranslator.LM_BAHNHOF);
        boolean twoDice = hasBahnhof && rng.nextBoolean();

        int roll;
        if (twoDice) {
            roll = rng.nextInt(1, 7) + rng.nextInt(1, 7);
        } else {
            roll = rng.nextInt(1, 7);
        }

        // Funkturm: 50/50
        if (bs.hasLandmark(activePlayer, BitStateTranslator.LM_FT) && rng.nextBoolean()) {
            roll = twoDice ? rng.nextInt(1, 7) + rng.nextInt(1, 7) : rng.nextInt(1, 7);
        }

        bs.applyRoll(activePlayer, roll);

        if (bs.hasPurple(activePlayer, 2) && roll == 6) {
            applyBürohausRandom(bs, activePlayer, n, rng);
        }

        applyPurchaseRandom(bs, supply, activePlayer, n, rng);
    }

    // -------------------------------------------------------------------------
    // Bürohaus: uniform random swap
    // -------------------------------------------------------------------------

    /**
     * Uniform random Bürohaus swap on BitState. Count-then-index approach (no allocation).
     *
     * <p>Counts all valid (own non-purple non-landmark × opponent non-purple non-landmark)
     * pairs plus no-swap, picks uniformly.
     */
    static void applyBürohausRandom(BitState bs, int activePlayer, int n,
                                    ThreadLocalRandom rng) {
        // Count own non-purple non-landmark cards
        int ownCount = 0;
        for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
            ownCount += bs.getCardCount(activePlayer, i);
        }
        if (ownCount == 0) return;

        // Count all opponent non-purple non-landmark cards
        int oppCardCount = 0;
        for (int p = 0; p < n; p++) {
            if (p == activePlayer) continue;
            for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
                oppCardCount += bs.getCardCount(p, i);
            }
        }
        if (oppCardCount == 0) return;

        int totalSwaps = ownCount * oppCardCount;
        int totalChoices = 1 + totalSwaps; // 0 = no-swap
        int choice = rng.nextInt(totalChoices);
        if (choice == 0) return; // no-swap

        // Decode: swapIdx maps to (own card flat index, opp card flat index)
        int swapIdx = choice - 1;
        int ownTarget = swapIdx / oppCardCount;
        int oppTarget = swapIdx % oppCardCount;

        // Find own card at flat index ownTarget
        int ownCardIdx = -1;
        int oi = 0;
        for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
            int cnt = bs.getCardCount(activePlayer, i);
            if (oi + cnt > ownTarget) {
                ownCardIdx = i;
                break;
            }
            oi += cnt;
        }

        // Find opponent card at flat index oppTarget
        int oppCardIdx = -1;
        int oppPlayerIdx = -1;
        int ci = 0;
        outer:
        for (int p = 0; p < n; p++) {
            if (p == activePlayer) continue;
            for (int i = 0; i < BitStateTranslator.NUM_NORMAL_CARDS; i++) {
                int cnt = bs.getCardCount(p, i);
                if (ci + cnt > oppTarget) {
                    oppCardIdx = i;
                    oppPlayerIdx = p;
                    break outer;
                }
                ci += cnt;
            }
        }

        // Execute swap
        if (ownCardIdx >= 0 && oppCardIdx >= 0) {
            bs.removeCard(activePlayer, ownCardIdx);
            bs.removeCard(oppPlayerIdx, oppCardIdx);
            bs.addCard(activePlayer, oppCardIdx);
            bs.addCard(oppPlayerIdx, ownCardIdx);
        }
    }

    // -------------------------------------------------------------------------
    // Purchase: uniform random
    // -------------------------------------------------------------------------

    /**
     * Uniform random purchase on BitState. Count-then-index (no allocation).
     *
     * <p>Counts affordable non-landmark cards (normal + purple with supply/uniqueness checks),
     * affordable landmarks, plus save. Picks uniformly.
     */
    static void applyPurchaseRandom(BitState bs, int[] supply, int activePlayer,
                                    int n, ThreadLocalRandom rng) {
        int coins = bs.getCoins(activePlayer);

        // Instant-win check
        int winLm = bs.findInstantWinLandmark(activePlayer);
        if (winLm >= 0) {
            bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[winLm]);
            bs.setLandmark(activePlayer, winLm);
            return;
        }

        // Count options: save(1) + non-landmark cards + landmarks
        int count = 1; // save = option 0

        // Non-landmark cards (normal + purple)
        for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
            boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
            int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
            if (isPurple) {
                if (bs.hasPurple(activePlayer, idx)) continue;
                if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                count++;
            } else {
                if (supply[idx] <= 0) continue;
                if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                count++;
            }
        }

        int landmarkStart = count;
        for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
            if (!bs.hasLandmark(activePlayer, li)
                    && coins >= BitStateTranslator.LANDMARK_COSTS[li]) {
                count++;
            }
        }

        // Pick uniformly
        int choice = rng.nextInt(count);
        if (choice == 0) return; // save

        // Walk to find chosen option
        if (choice < landmarkStart) {
            // Non-landmark card
            int idx2 = 1;
            for (int ci : BitStateTranslator.CANDIDATE_ITERATION_ORDER) {
                boolean isPurple = ci >= BitStateTranslator.NUM_NORMAL_CARDS;
                int idx = isPurple ? ci - BitStateTranslator.NUM_NORMAL_CARDS : ci;
                if (isPurple) {
                    if (bs.hasPurple(activePlayer, idx)) continue;
                    if (coins < BitStateTranslator.PURPLE_CARD_COSTS[idx]) continue;
                    if (idx2 == choice) {
                        bs.setCoins(activePlayer, coins - BitStateTranslator.PURPLE_CARD_COSTS[idx]);
                        bs.setPurple(activePlayer, idx);
                        return;
                    }
                    idx2++;
                } else {
                    if (supply[idx] <= 0) continue;
                    if (coins < BitStateTranslator.NORMAL_CARD_COSTS[idx]) continue;
                    if (idx2 == choice) {
                        bs.setCoins(activePlayer, coins - BitStateTranslator.NORMAL_CARD_COSTS[idx]);
                        bs.addCard(activePlayer, idx);
                        supply[idx]--;
                        return;
                    }
                    idx2++;
                }
            }
        } else {
            // Landmark
            int idx2 = landmarkStart;
            for (int li = 0; li < BitStateTranslator.NUM_LANDMARKS; li++) {
                if (!bs.hasLandmark(activePlayer, li)
                        && coins >= BitStateTranslator.LANDMARK_COSTS[li]) {
                    if (idx2 == choice) {
                        bs.setCoins(activePlayer, coins - BitStateTranslator.LANDMARK_COSTS[li]);
                        bs.setLandmark(activePlayer, li);
                        return;
                    }
                    idx2++;
                }
            }
        }
    }
}
