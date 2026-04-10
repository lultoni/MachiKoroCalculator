# Continuous Thinking — Design Specification

**Status:** Design document for discussion. Not yet a plan.
**Scope:** Phase 2 of TODO #17 (time budget mode). Covers continuous engine evaluation for Player-vs-AI mode.
**Date:** 2026-04-11

---

## 1. Vision

In Player-vs-AI mode, the AI opponent should think **continuously** — not just during its own turn. While the human player rolls dice, distributes coins, and deliberates on purchases (30-60 seconds per turn), the engine accumulates iterations on its upcoming position. When the AI's turn arrives, it has already thought deeply and can act with far superior quality.

The engine is **always running**. External events (dice rolls, purchases, turn boundaries) steer it by redirecting where it focuses, not by stopping and restarting it.

### Key Metaphor: "Letter on the Desk"

The engine is a worker running laps around a track. It never stops unless told to. Communication happens via letters left on a desk that the worker checks each time it passes by:

- **"New root"** — redirect search to a different position in the tree
- **"Stop and report"** — pause, hand over current best result, wait for next instruction
- **"Shutdown"** — terminate

The engine is never interrupted mid-iteration. It finishes its current lap, checks for mail, and acts accordingly.

---

## 2. Game Flow in Player-vs-AI Mode

### 2.1 Current Flow (No Continuous Thinking)

```
Human's turn:
  1. Human selects dice (1d6/2d6 toggle) — UI state only, not final
  2. Human clicks die face(s) — UI state only, not final
  3. Human sees income preview (from cached evaluate response)
  4. Human selects Bürohaus swap (if applicable) — UI state only
  5. Human clicks purchase button → handleBuy() locks in the turn
  6. Server applies turn, returns new state

AI's turn:
  7. Server calls engine.evaluateFullTurn() — starts from scratch, ~100-500ms
  8. Engine returns TurnPlan with all decisions
  9. Server applies AI's turn
  10. Frontend shows AI's actions (dice, income, purchase) with dramatic pauses

Human's next turn:
  11. Engine evaluation triggered (useEffect) — starts from scratch
  12. Human deliberates...
```

**Problem:** The AI thinks for ~200ms (step 7) while the human thought for ~30-60s (steps 1-5). The AI gets 5,000 iterations. With continuous thinking during the human's 30-60s turn, it could get 750,000-1,500,000 iterations.

### 2.2 Proposed Flow (Continuous Thinking)

```
Human's turn:
  1. Engine is ALREADY RUNNING, iterating on a tree that includes:
     - Human's upcoming dice/purchase decisions (upper layers)
     - AI's upcoming turn (deeper layers)
  2. Human selects dice faces → NOT a navigation event (not final)
  3. Human clicks "Buy" → LOCK-IN EVENT
     a. Server applies human's turn
     b. ContinuousEvaluator receives navigation: "human chose X dice, rolled Y, bought Z"
     c. Tree navigates to the matching subtree (or creates fresh if unexplored)
     d. Engine continues iterating from new root (AI's upcoming DiceChoiceNode)

AI's turn:
  4. Timekeeper sends "stop and report" after minThinkTimeMs (e.g., 200ms for dramatic effect)
     — but engine has been running for 30-60s already!
  5. Engine returns deeply-analyzed TurnPlan
  6. Frontend reveals AI's actions with 200ms dramatic pauses between steps

AI's turn locks in:
  7. ContinuousEvaluator receives navigation: "AI chose X dice, rolled Y, bought Z"
  8. Tree navigates to human's next turn position
  9. Engine continues iterating (background, while frontend shows dramatic reveal)

Human's next turn:
  10. Engine has been running since step 8 — already accumulating iterations
  11. Human deliberates for 30-60s — engine keeps running
  12. Cycle repeats from step 2
```

### 2.3 Lock-In Events vs. Preview Events

**Lock-in events** (trigger tree navigation / state reset):
- Human clicks "Buy" (commits dice + Funkturm + Bürohaus + purchase in one atomic action)
- AI's turn completes (all AI decisions applied at once)
- Undo (loses current subtree progress, fresh start from restored state)

**Preview events** (NO tree navigation, UI-only):
- Human clicks die face (just selects UI state, can change freely)
- Human toggles 1d6/2d6 (just selects UI state)
- Human hovers over purchase option (preview only)
- Human selects Bürohaus swap candidate (pending until Buy click)

**Critical rule:** The engine does NOT react to preview events. Only lock-in events cause tree navigation. This prevents the "restart every time user clicks" problem.

---

## 3. Engine Persistence Models

Each engine family has a different internal structure. The continuous thinking wrapper must accommodate all of them.

### 3.1 MCTS (MctsV1 + Variants A-E) — Tree Navigation

**Current state:** MctsTree holds an explicit tree of MctsNode objects. Nodes have visitCount, totalScore, children, state (BitState), supply. Tree is built fresh each evaluate() call and discarded after.

**Continuous model:**
- Tree persists across turns
- On lock-in event: navigate to matching child node, sever parent link (GC collects ancestors + sibling subtrees)
- Engine continues MCTS iterations from new root
- If matching child doesn't exist (unexplored path): create fresh node, engine starts expanding from there

**Tree spans multiple turns:** The MCTS full-turn tree already models opponent responses. With continuous thinking, the tree naturally grows several turns deep. When we navigate past the human's turn to the AI's DiceChoiceNode, the subtree below may already have thousands of visits from UCT exploration.

**Node structure (from MctsNode.java):**
| Field | Type | Size |
|-------|------|------|
| state | BitState (long[]) | ~8-24 bytes (2-6 players) |
| supply | int[] (12 elements) | 48 bytes (shared when unchanged) |
| parent | MctsNode reference | 8 bytes |
| children | ArrayList<MctsNode> | 24 bytes + entries |
| visitCount | int | 4 bytes |
| totalScore | double | 8 bytes |
| expanded | boolean | 1 byte |
| _cachedGameState | GameState | 8 bytes (lazy) |
| **Per node total** | | **~100-120 bytes** |

**Memory estimate:** 1M iterations ≈ 50K-200K nodes (many iterations revisit existing nodes) ≈ 5-24 MB. Acceptable.

**Navigation path on human lock-in (Buy click):**
```
Current root: DiceChoiceNode (human's turn)
  → child[twoDice] : ChanceNode
    → child[roll=N, isDoubles=D] : post-roll node
      → FunkturmNode? → child[keep] (always keep in this context)
        → BürohausNode? → child[swap_or_no_swap]
          → BuyDecisionNode → child[purchased_card]
            → DiceChoiceNode (AI's turn) ← NEW ROOT
```

Navigation requires matching each level. The `handleBuy` call provides all info: `diceCount`, `die1`, `die2` (→ roll total + isDoubles), `bürohausSwap`, `purchasedCard`.

**Navigation on AI turn complete:**
```
Current root: DiceChoiceNode (AI's turn)
  → AI's chosen dice count → ChanceNode
    → AI's roll → FunkturmNode? → BürohausNode? → BuyDecisionNode
      → AI's purchase → DiceChoiceNode (human's next turn) ← NEW ROOT
```

The AI's decisions are all known (engine just decided them). Navigation is deterministic.

### 3.2 Expectimax — Transposition Table

**Current state:** ExpectimaxEngine uses recursive minimax with alpha-beta pruning. No persistent tree — all state is on the recursion stack. Iterative deepening evaluates depth 1, then depth 2, etc.

**Continuous model:**
- Introduce a **transposition table**: `Map<Long, TranspositionEntry>` keyed by BitState hash
- Each entry: `{ double value, int depth, int flag (EXACT/LOWER/UPPER) }`
- On lock-in event: keep the table (entries for future states are still valid), clear entries at depths that are now unreachable
- Engine continues deepening from current completed depth
- Table entries from previous turns that match future states save re-computation

**Intermediate result extraction:** After each completed depth, the engine has a full set of scored options. `peekResult()` returns the deepest completed depth's result.

**Memory estimate:** Transposition table with 100K entries × ~32 bytes = ~3.2 MB. Bounded by table size cap.

**Flag insertion point:** After each `evaluateAtDepth()` completes (line ~118 in ExpectimaxEngine.java), check stop flag before starting next depth.

### 3.3 FlatMC / CreatorEngine — Candidate Accumulators

**Current state:** Both engines enumerate candidate options (save + all affordable cards), then run rollouts. Each candidate accumulates `samples` (int) and `wins` (double). CreatorEngine adds a heuristic seeding phase before MC.

**Continuous model:**
- Candidate list + accumulators persist as long as game state is unchanged
- Engine runs one rollout at a time, updating the appropriate candidate's samples/wins
- On lock-in event (state change): **reset candidates** — rebuild candidate list for new state, zero accumulators
- Between lock-in events: keep accumulating samples

**Why full reset on state change (not warm-start):**
- State changes (income from dice, purchases) alter the game position
- Rollout results from old state are biased for new state
- FlatMC accumulates 25K+ samples/sec — a few hundred ms of lost work is trivial compared to 30-60s of continuous accumulation
- Warm-start (decay factor) adds complexity for marginal benefit

**CandidateOption fields (from FlatMcEngine.java):**
| Field | Type | Notes |
|-------|------|-------|
| card | Project | Purchase option |
| postState | BitState | State after purchase |
| postSupply | int[] | Supply after purchase |
| isInstantWin | boolean | Win check |
| samples | int | Rollout count (accumulates) |
| wins | double | Win count (accumulates) |

**Memory estimate:** ~20 candidates × ~200 bytes = ~4 KB. Negligible.

**Flag insertion point:** FlatMC time-budget loop (line ~158) already checks deadline. Replace with flag check. CreatorEngine MC loop (line ~186) same pattern.

**Intermediate result extraction:** `winRate() = wins / samples` is valid at any point after survey phase (≥20 samples per candidate).

### 3.4 HeuristicEv — No Continuous Mode

**Current state:** Single-pass formula evaluation. <5ms. No iteration loop.

**Continuous model:** None needed. Computes once, returns result, idles. When stop-and-report arrives, hands over the already-computed result immediately.

---

## 4. Architecture

### 4.1 ContinuousEvaluator (New Class)

**Package:** `engine/`

**Responsibility:** Wraps any engine for continuous evaluation. Manages the background worker thread, mailbox, and result extraction.

```java
public class ContinuousEvaluator {

    // --- Mailbox (checked by worker between iterations) ---
    private final AtomicReference<NavigationEvent> pendingNavigation;
    private final AtomicBoolean stopAndReport;
    private final AtomicBoolean shutdown;

    // --- Result buffer (written by worker, read by caller) ---
    private volatile EngineResult latestResult;
    private volatile int accumulatedIterations;
    private volatile long thinkingStartMs;

    // --- Engine-specific worker ---
    private final ContinuousWorker worker;
    private final Thread workerThread;

    // --- Public API ---
    /** Start thinking about the given position. */
    void init(GameState state, int playerIndex, EngineConfig config);

    /** Navigate to a new position (lock-in event). */
    void navigate(NavigationEvent event);

    /** Request the engine to stop and return its current best result. */
    EngineResult stopAndGetResult();

    /** Peek at current result without stopping. */
    EngineResult peekResult();

    /** How long has the engine been thinking on this position? */
    long thinkingTimeMs();

    /** How many iterations accumulated on this position? */
    int iterations();

    /** Shut down the background thread permanently. */
    void shutdown();
}
```

### 4.2 ContinuousWorker (Interface)

**Package:** `engine/`

Each engine family implements this to expose its iteration loop:

```java
public interface ContinuousWorker {

    /** Can this engine run continuously? */
    boolean supportsContinuousMode();

    /** Initialize for a new game position. Builds internal state (tree, candidates, etc). */
    void init(BitState state, int[] supply, int playerIndex, EngineConfig config);

    /** Run one iteration (one MCTS iteration, one FlatMC rollout, one Expectimax depth, etc). */
    void runOneIteration();

    /** Extract current best result without disrupting internal state. */
    EngineResult peekResult(GameState state, int playerIndex, EngineConfig config);

    /** Handle a navigation event. Returns true if navigation succeeded (reused state),
     *  false if a fresh init is needed. */
    boolean navigate(NavigationEvent event);

    /** How many iterations have been performed since last init/navigate. */
    int iterations();
}
```

**Implementations:**
| Class | Engine | Notes |
|-------|--------|-------|
| `MctsContinuousWorker` | MctsV1 + variants | Tree persistence, node navigation |
| `FlatMcContinuousWorker` | FlatMcEngine | Candidate accumulators, reset on navigate |
| `CreatorContinuousWorker` | CreatorEngine | Heuristic seed + MC accumulators, reset on navigate |
| `ExpectimaxContinuousWorker` | ExpectimaxEngine | Transposition table, iterative deepening |
| `HeuristicContinuousWorker` | HeuristicEvEngine | Compute once, then idle |

### 4.3 NavigationEvent

```java
public record NavigationEvent(
    GameState newState,       // Full game state after the lock-in
    int playerIndex,          // Whose perspective to evaluate from

    // For MCTS tree navigation (optional — null for non-MCTS or fresh start):
    Integer diceCount,        // 1 or 2 (null = unknown)
    Integer rollTotal,        // Dice sum (null = unknown)
    Boolean isDoubles,        // Doubles flag (null = unknown)
    Boolean funkturmKeep,     // true=keep, false=reroll, null=no Funkturm
    Integer rerollTotal,      // Reroll result (null = no reroll)
    Boolean rerollIsDoubles,  // Reroll doubles (null = no reroll)
    String bürohausOwnCardId, // Card swapped away (null = no swap)
    String bürohausOppCardId, // Card received (null = no swap)
    Integer bürohausOppPlayer,// Opponent index (null = no swap)
    String purchasedCardId,   // Card bought (null = save)

    boolean forceReset        // true = don't attempt navigation, start fresh (e.g., undo)
)
```

The MCTS worker uses these fields to navigate its tree level by level. Non-MCTS workers ignore the navigation fields and just use `newState` + `playerIndex` to reset.

### 4.4 TreeNavigator (MCTS-specific)

**Package:** `engine/mcts/`

Encapsulates the logic of walking an MCTS tree to find the child matching a NavigationEvent.

```java
public class TreeNavigator {

    /**
     * Navigate the tree from currentRoot to the position described by event.
     * Returns the new root node, or null if navigation failed (unexplored path).
     *
     * Navigation steps:
     * 1. DiceChoiceNode → child matching diceCount
     * 2. ChanceNode → child matching (rollTotal, isDoubles)
     * 3. FunkturmNode → child matching funkturmKeep
     *    - If reroll: navigate reroll ChanceNode → child matching (rerollTotal, rerollIsDoubles)
     *    - Then navigate next FunkturmNode → force keep (once-per-turn rule)
     * 4. BürohausNode → child matching swap (or no-swap)
     * 5. BuyDecisionNode → child matching purchasedCardId
     * 6. Return: next player's DiceChoiceNode or ChanceNode
     */
    static MctsNode navigate(MctsNode currentRoot, NavigationEvent event);

    /**
     * Sever parent link and let GC collect ancestors.
     */
    static void pruneAbove(MctsNode newRoot);
}
```

When navigation fails at any step (child not yet expanded, or the specific outcome was never explored), the method returns null. The ContinuousEvaluator then falls back to a fresh `init()` with the new game state.

### 4.5 Timekeeper

**Package:** `server/` (or `engine/`)

Manages the timing contract between the UI and the engine.

```java
public class Timekeeper {

    private final ContinuousEvaluator evaluator;
    private int minThinkTimeMs;      // From Settings (global minimum)
    private int engineTimeBudgetMs;  // From EngineConfig (per-engine minimum)

    /**
     * Called when the UI requests results (e.g., AI's turn starts).
     * If the engine hasn't thought for at least effectiveMin, schedules
     * a delayed stop. Otherwise stops immediately.
     *
     * Returns a CompletableFuture<EngineResult> that completes when
     * the minimum time has elapsed and the engine has stopped.
     */
    CompletableFuture<EngineResult> requestResult();

    /**
     * Effective minimum think time = max(minThinkTimeMs, engineTimeBudgetMs).
     */
    int effectiveMinMs();

    /** Update settings (called when user changes minThinkTimeMs). */
    void setMinThinkTimeMs(int ms);
}
```

**Behavior:**
- If `thinkingTimeMs >= effectiveMinMs`: stop engine immediately, return result
- If `thinkingTimeMs < effectiveMinMs`: schedule stop at `effectiveMinMs`, return future
- If no explicit stop requested: engine runs indefinitely (during human's turn)

### 4.6 Integration with Server

**New endpoint (or extended existing):**

The server needs to coordinate the ContinuousEvaluator with game session events. Two approaches:

**Option A: Server-managed ContinuousEvaluator**
- `SessionManager` holds a `ContinuousEvaluator` alongside the `GameSession`
- `SessionTurnHandler` sends NavigationEvent after applying turn
- New endpoint `GET /api/session/ai-turn` triggers stop-and-report + applies AI's decisions

**Option B: Dedicated AI controller**
- New `PlayerVsAiController` class manages the session + evaluator + timekeeper
- Activated when session is created in Player-vs-AI mode
- Handles the full AI turn flow (stop engine → extract decisions → dramatic pause → apply)

Option B is cleaner — separates Player-vs-AI concerns from the general session flow.

### 4.7 AI Turn Dramatic Reveal

When the AI's turn arrives:

```
1. Timekeeper.requestResult() → waits for minThinkTimeMs if needed
2. EngineResult → TurnPlan extraction (dice, Funkturm, Bürohaus, purchase)
3. Frontend receives AI turn data as a sequence of steps:
   [
     { type: "dice", diceCount: 2, roll: 8, delay: 200 },
     { type: "income", coinDeltas: [3, -2, ...], delay: 200 },
     { type: "funkturm", keep: true, delay: 200 },           // if applicable
     { type: "purchase", cardId: "käsefabrik", delay: 200 },
     { type: "turnEnd" }
   ]
4. Frontend animates each step with 200ms pauses between them
5. After last step: NavigationEvent sent to ContinuousEvaluator
```

This could be a single API response with all steps, or a streaming/polling approach. Single response is simpler.

---

## 5. Thread Model

```
┌─────────────────────────────────┐
│ Main Thread (HTTP Server)       │
│  - Handles API requests         │
│  - Applies turns to GameSession │
│  - Sends NavigationEvents       │
│  - Reads results via peek/stop  │
└──────────┬──────────────────────┘
           │ (atomic flags + volatile result)
           │
┌──────────▼──────────────────────┐
│ Worker Thread (ContinuousEval)  │
│  - Runs engine iterations       │
│  - Checks mailbox each lap      │
│  - Writes result atomically     │
│  - Never blocks, never waits    │
│    (except when paused after    │
│     stop-and-report)            │
└─────────────────────────────────┘
```

**Thread safety requirements:**
- `NavigationEvent` delivery: `AtomicReference` swap (lock-free)
- `stopAndReport` flag: `AtomicBoolean` (lock-free)
- `latestResult` read: `volatile` field (no lock needed)
- MCTS tree access: single-writer (worker thread only). Reads by `peekResult` must either:
  - (a) Copy the result snapshot under a brief lock, or
  - (b) Only read immutable aggregates (visitCount, totalScore) which are safely readable as ints/doubles

**Option (b) is preferred.** MctsTree.bestChild() reads visitCount and totalScore which are primitives. Even with a concurrent write (backpropagation incrementing visitCount), a torn read of an int is impossible on the JVM (int writes are atomic per JLS §17.7). Double writes are NOT atomic on all JVMs, but totalScore is only used for win-rate computation where a slightly stale value is acceptable for a peek.

For `stopAndGetResult()` (authoritative result), the worker thread pauses first, ensuring no concurrent writes.

---

## 6. Memory Management

### 6.1 MCTS Tree Growth

With 750K-1.5M iterations during a 30-60s human turn:
- Estimated node count: 50K-200K (UCT concentrates on promising paths)
- Per node: ~100-120 bytes
- **Total: 5-24 MB**

After navigation to new root, ancestor nodes + sibling subtrees become GC-eligible. The tree naturally stays bounded as old branches are pruned.

### 6.2 Emergency Pruning (Future, If Needed)

If memory becomes an issue (unlikely at these scales):
- Prune least-visited subtrees (visitCount < threshold)
- Cap total node count, evict LRU branches
- Monitor via `Runtime.getRuntime().freeMemory()`

**Not implementing this now.** Noting it as a safety valve.

### 6.3 Expectimax Transposition Table

- Fixed-size table with LRU or depth-preferred replacement
- Cap at ~100K entries (~3.2 MB)
- On navigation: optionally clear entries deeper than current state

### 6.4 FlatMC/Creator Candidates

- ~4 KB total. No memory concern.

---

## 7. Engine-Specific Implementation Details

### 7.1 MctsContinuousWorker

**init():**
1. Convert GameState → BitState + supply
2. Build MctsTree with fullTurn=true (DiceChoiceNode or ChanceNode as root)
3. Store tree reference

**runOneIteration():**
1. Call `tree.runOneIteration()` (new method — extract from existing runIterations loop)
2. Increment iteration counter

**navigate(event):**
1. Call `TreeNavigator.navigate(tree.fullTurnRoot, event)`
2. If successful (non-null result):
   - Call `TreeNavigator.pruneAbove(newRoot)`
   - Reconstruct MctsTree with newRoot as fullTurnRoot (or just update root reference)
   - Return true
3. If failed (null — unexplored path):
   - Return false (caller will do fresh init)

**peekResult():**
1. Walk tree.fullTurnRoot to find BuyDecisionNode (following bestChild at each level)
2. Collect buy options with visit counts and win rates
3. Build EngineResult from aggregated statistics
4. Does NOT modify tree state

**Required changes to MctsTree:**
- Extract `runOneIteration()` as public method (currently private)
- Allow external root replacement (new constructor or setter)
- `MctsTree.navigateToRoll()` is already static — reusable by TreeNavigator

**Required changes to MctsV1Engine:**
- No changes to existing evaluate/evaluateFullTurn — those stay as one-shot API
- MctsContinuousWorker uses MctsTree directly, bypassing MctsV1Engine

### 7.2 FlatMcContinuousWorker

**init():**
1. Convert GameState → BitState + supply
2. Enumerate candidates (save + affordable cards) — same logic as FlatMcEngine.evaluate()
3. Run survey phase (20 samples per candidate) — same as existing
4. Store candidate list

**runOneIteration():**
1. Pick candidate with fewest samples (round-robin or least-sampled)
2. Run one rollout on that candidate
3. Update candidate.samples++, candidate.wins += result

Alternative: pick from top-K (UCB1-like exploration-exploitation on flat candidates). Simpler: round-robin among top-K by current win rate, re-sorted every N iterations.

**navigate(event):**
1. Always return false (triggers fresh init from new state)
2. Could optimize: if only coin count changed and candidate list is identical, keep accumulators with decay. **Defer this optimization.**

**peekResult():**
1. Sort candidates by winRate() descending
2. Build EngineResult with ranked options
3. Read-only access to samples/wins (safe: int reads are atomic)

### 7.3 CreatorContinuousWorker

**init():**
1. Run CreatorScorer.scoreAll() (heuristic phase, ~2ms)
2. Convert top candidates to CandidateOption with postState/postSupply
3. Run initial MC survey (50 samples across top candidates)
4. Store candidate list with heuristic scores + MC accumulators

**runOneIteration():**
1. Same as FlatMcContinuousWorker but with Creator's allocation strategy:
   - Top-3 by heuristic get 50% of attention
   - Next-5 get 30%
   - Rest get 20%
2. Alternatively: flat round-robin among all MC-eligible candidates

**navigate(event):**
1. Always return false (fresh init with new state)

**peekResult():**
1. If MC samples > threshold (e.g., 100): use MC win rates
2. If MC samples < threshold: blend heuristic scores with MC estimates
3. Build EngineResult

### 7.4 ExpectimaxContinuousWorker

**init():**
1. Convert GameState → BitState + supply
2. Create empty transposition table (or keep existing if navigate returned true)
3. Set currentDepth = 0, bestResult = null

**runOneIteration():**
1. Increment targetDepth (currentDepth + 1)
2. Run evaluateAtDepth(targetDepth) — full deterministic search
3. If completed before next flag check:
   - Store result as bestResult
   - currentDepth = targetDepth
4. If depth takes too long (>10s?), this "iteration" spans many flag checks
   - **Problem:** evaluateAtDepth is recursive, can't check flags mid-recursion
   - **Solution:** Pass deadline into recursion, bail out at minimax nodes if exceeded
   - Or: accept that depth N+1 might run for a while. Flag is checked between depths.

**navigate(event):**
1. Keep transposition table (future states may be cached)
2. Clear currentDepth (restart deepening from depth 1, but with warm cache)
3. Return true (table reuse counts as successful navigation)

**peekResult():**
1. Return bestResult (last completed depth)
2. If no completed depth yet (still computing depth 1): return null (ContinuousEvaluator falls back to heuristic)

### 7.5 HeuristicContinuousWorker

**init():**
1. Run HeuristicEvEngine.evaluate() synchronously (~2ms)
2. Store result

**runOneIteration():**
1. No-op. Thread parks/waits until navigation event or stop.

**navigate(event):**
1. Run evaluate() on new state, store result
2. Return true

**peekResult():**
1. Return stored result

---

## 8. UI Integration

### 8.1 Player-vs-AI Mode Activation

The current UI supports a "user player index" setting (Settings.userPlayerIndex). When continuous thinking is enabled, the system knows:
- **User's turn:** Engine runs in background, accumulating iterations for AI's upcoming turn
- **AI's turn:** Engine has been pre-thinking; stop-and-report → dramatic reveal

A new setting or mode flag distinguishes "Player-vs-AI" from "Player-vs-Player with assistant."

**Important clarification from discussion:** In Player-vs-AI mode, **the player does NOT have an engine assistant.** The engine only plays as the AI opponent. The player relies on their own skill. This mode serves dual purposes:
1. Pure skill test for the player
2. R&D tool to observe engine behavior

### 8.2 AI Turn Animation

Frontend receives all AI decisions at once and reveals them sequentially:

```typescript
interface AiTurnStep {
  type: 'dice' | 'income' | 'funkturm' | 'bürohaus' | 'purchase' | 'turnEnd';
  // dice
  diceCount?: 1 | 2;
  rollTotal?: number;
  isDoubles?: boolean;
  // income
  coinDeltas?: number[];
  // funkturm
  keep?: boolean;
  rerollTotal?: number;
  // bürohaus
  swapOwn?: string;
  swapOpp?: string;
  swapOppPlayer?: number;
  // purchase
  cardId?: string | null;
  // timing
  delayMs: number;  // 200ms default between steps
}
```

Frontend processes steps sequentially with delays. User sees the AI "thinking" (brief minThinkTimeMs pause) then acting step by step.

### 8.3 Settings Integration

- `Settings.minThinkTimeMs`: Already in UI (default 1000ms). Wire to Timekeeper.
- New toggle: "Player vs AI mode" (enables continuous thinking, hides assistant panel)
- Optional: "AI speed" slider controlling dramatic pause duration (100-500ms per step)

---

## 9. Backward Compatibility

### 9.1 H2H Matches: Unchanged

H2H matches use `SimulationEngine.evaluate()` / `evaluateFullTurn()` which remain one-shot stateless calls. ContinuousEvaluator is only used in Player-vs-AI sessions. No changes to MatchRunner, TournamentRunner, or SweepMain.

### 9.2 Game Session (Player-vs-Player): Unchanged

The existing game session with manual opponent entry and engine assistant continues to work as-is. ContinuousEvaluator is only activated in Player-vs-AI mode.

### 9.3 Engine Classes: Minimal Changes

Engine classes (MctsV1Engine, FlatMcEngine, etc.) keep their existing evaluate() API. The ContinuousWorker implementations use the engines' internal components (MctsTree, CandidateOption, etc.) but don't modify the engine classes themselves.

**Exception:** MctsTree needs `runOneIteration()` exposed as public. This is a minor visibility change.

---

## 10. Testing Strategy

### 10.1 Unit Tests (RuntimeTester Sections)

**New section: "Continuous Evaluation"**

| Test | What it verifies |
|------|------------------|
| MCTS continuous init + iterate + peek | Worker starts, accumulates iterations, peekResult returns valid result |
| MCTS tree navigation (happy path) | Navigate through DiceChoice → Chance → Buy, reach expected subtree |
| MCTS tree navigation (unexplored path) | Navigation returns null, triggers fresh init |
| MCTS tree navigation with Funkturm | Reroll path navigates correctly, once-per-turn enforced |
| MCTS tree navigation with Bürohaus | Swap + no-swap paths navigate correctly |
| MCTS tree pruning | After navigation, old nodes are GC-eligible (WeakReference test) |
| FlatMC continuous accumulation | Samples accumulate, winRate converges over time |
| FlatMC state reset on navigate | Candidates rebuilt, old accumulators discarded |
| Creator continuous accumulation | Heuristic scores preserved, MC samples accumulate |
| Expectimax deepening | Depth increases over time, result quality improves |
| Expectimax transposition reuse | Table entries reused after navigation |
| Heuristic instant result | Returns immediately, runOneIteration is no-op |
| Stop-and-report timing | Engine pauses within one iteration of flag being set |
| Navigation event serialization | NavigationEvent correctly constructed from turn data |

### 10.2 Integration Tests

| Test | What it verifies |
|------|------------------|
| Full AI turn cycle | init → accumulate → navigate (human turn) → accumulate → stop → valid AI TurnPlan |
| Engine switch during thinking | Change engine mid-session → old worker shuts down, new worker starts |
| Undo during thinking | Undo → fresh init with restored state |
| Concurrent peek + iterate | peekResult returns consistent result while worker iterates |

### 10.3 Engine Compliance Tests

Extend existing "Engine Compliance" section:

| Test | What it verifies |
|------|------------------|
| ContinuousWorker.supportsContinuousMode() | All expected engines return true |
| init + 100 iterations + peekResult | Valid EngineResult with >0 options |
| navigate + 100 iterations + peekResult | Valid result after state change |
| Result quality increases with iterations | WR spread between top-2 options increases over time (confidence grows) |

---

## 11. Open Questions

### Q1: Should the engine evaluate from its OWN perspective or the HUMAN's perspective?

In Player-vs-AI, the engine IS the AI opponent. It should evaluate from its own perspective (maximizing its own win rate). When the human's turn is in progress, the engine thinks about what it (the AI) will do on its upcoming turn.

**Answer:** AI perspective. The engine evaluates as playerIndex = AI's seat index.

### Q2: What tree does the engine build during the human's turn?

The engine builds a tree rooted at the **human's** DiceChoiceNode (since the human hasn't acted yet). The tree explores human decisions (dice, purchase) and then AI decisions deeper in the tree. When the human locks in their turn, the engine navigates past the human's choices to reach the AI's DiceChoiceNode — where it continues evaluating.

This way, the engine simultaneously:
- Pre-computes responses to all possible human moves (upper tree)
- Deeply evaluates its own best moves (lower tree, concentrated by UCT)

### Q3: How does the engine handle the tree during the AI's dramatic reveal?

During the 200ms-per-step reveal animation, the engine has already decided its moves. The reveal is purely cosmetic. After the last step (purchase applied), the engine immediately receives a NavigationEvent for the human's next turn and starts thinking.

The engine could even start thinking during the reveal animation (since the AI's decisions are already committed). This gives ~1s of extra think time for free.

### Q4: What if the game ends during continuous thinking?

If the human's purchase wins the game (4th landmark), or the AI's purchase wins: ContinuousEvaluator receives a shutdown event. No further thinking needed.

### Q5: Should we support multiple AI opponents (3/4 player)?

Not in Phase 2. The architecture supports it (multiple ContinuousEvaluators, one per AI seat), but 3/4 player support is TODO #19 and blocked on other work. Phase 2 targets 2-player only.

---

## 12. Implementation Priority

| Step | Component | Depends On | Effort |
|------|-----------|------------|--------|
| 1 | `ContinuousWorker` interface | — | Small |
| 2 | `MctsContinuousWorker` | Step 1, MctsTree.runOneIteration() | Medium |
| 3 | `TreeNavigator` | MctsTree internals | Medium |
| 4 | `FlatMcContinuousWorker` | Step 1 | Small |
| 5 | `CreatorContinuousWorker` | Step 1, Step 4 | Small |
| 6 | `ExpectimaxContinuousWorker` | Step 1 | Medium |
| 7 | `HeuristicContinuousWorker` | Step 1 | Trivial |
| 8 | `ContinuousEvaluator` (wrapper) | Steps 1-7 | Medium |
| 9 | `Timekeeper` | Step 8 | Small |
| 10 | `NavigationEvent` record | — | Small |
| 11 | Server integration (`PlayerVsAiController`) | Steps 8-10 | Medium |
| 12 | AI turn endpoint + dramatic reveal | Step 11 | Medium |
| 13 | Frontend: Player-vs-AI mode | Step 12 | Medium |
| 14 | Settings wiring (minThinkTimeMs) | Step 9, Step 13 | Small |
| 15 | Tests | Steps 1-14 | Medium |

**Critical path:** Steps 1 → 2 → 3 → 8 → 11 → 12 → 13

---

## 13. Relationship to Other TODOs

- **#14 (Player-vs-AI: engine auto-play backend):** This spec IS the backend for Player-vs-AI. TODO #14 describes the need; this spec describes the solution. Continuous thinking is the differentiator vs. a simple "engine.evaluate() on AI's turn."
- **#15 (Player-vs-AI: immersive board-game UI):** The AI dramatic reveal (Section 8.2) feeds into the UI redesign. Frontend needs to animate AI actions.
- **#16 (Player-vs-AI: per-turn notes):** Independent of continuous thinking. Notes can be added alongside.
- **#17b (Phase 3: expand Player-vs-AI TODOs):** This spec supersedes the brief notes that #17b planned to add.

---

## 14. Non-Goals (Explicitly Out of Scope)

- **MCTS tree reuse in H2H matches:** Not applicable (both players are engines, no idle time).
- **Opening book / pre-computed positions:** Interesting but separate from continuous thinking.
- **GPU acceleration:** TODO #21, separate infrastructure.
- **Multi-player continuous thinking:** Deferred to TODO #19.
- **Soft navigation (preview without committing):** Future feature. For now, only lock-in events cause navigation.
- **Warm-starting FlatMC/Creator across state changes:** Complexity not justified by benefit at current sample rates.
