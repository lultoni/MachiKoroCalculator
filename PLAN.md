# PLAN.md — MachiKoroCalculator Active Backlog

All 6 original implementation phases are complete. This file tracks **known limitations, bugs, and planned improvements**.

For historical context (what was built and why), see `CHANGELOG.md`.
For mathematical foundations and design rationales, see `ARCHITECTURE.md`.

Progress key: `[ ]` open · `[~]` in progress · `[x]` done

---

## Supply / Ownership Rules Bug

**Priority: High — correctness issue**

The current model does not correctly enforce Machi Koro's per-player ownership limits:

- **Purple (lila) cards** (Stadion, Fernsehsender, Bürohaus): each player may own **at most 1 copy**. These are unique cards.
- **Landmarks (gelb)**: each player owns their own set; not shared between players.
- **All other establishments (blau, grün, rot)**: up to **6 copies total** exist in the market, shared across all players. Multiple players *can* own the same card type, and one player can own multiple copies of the same non-purple card (up to the shared supply of 6).

**Current state:**
- `GameState.unbuilt_projects` stores one entry per card *type* (since `Project.equals` is id-based). This means the supply model treats each card type as having exactly 1 remaining copy, not 6.
- `GameSimulator` has a separate `Map<String,Integer>` supply counter (6 copies per non-landmark) which *is* correct for simulation purposes.
- `rankPurchasableProjects` uses `unbuilt_projects` to determine what cards are available — so it only shows a card as available if any copy remains, but does not account for the player already owning N copies toward the supply limit.
- The `SnapshotDialog` and `GameStateBuilder` allow adding multiple copies of the same card to a player (no uniqueness enforcement for purple cards).

**What needs fixing:**

- [x] `rankPurchasableProjects` should exclude purple cards the active player already owns (unique cards). *(ProbabilityCalc.java)*
- [x] The snapshot dialog should prevent checking a purple card for a player who already owns one. *(SnapshotDialog.java)*
- [ ] `GameState.unbuilt_projects` semantics should be clarified (or changed) to reflect supply counts, not just presence. *(GameState.java)*
- [x] `GameStateBuilder` should throw if a purple card is added twice to the same player. *(GameStateBuilder.java)*

---

## Rules Correctness Bugs

These are deviations from the official rules that affect the accuracy of EV recommendations.

### 1. Income Processing Order: Red → Blue/Green → Purple
**Priority: High — correctness issue**

RULES.md: *"Rot → Blau & Grün → Violett"* — red card payments must be resolved **first**, before the active player receives any blue or green income.

**Current state:** `computeNetGainForRoll` processes Blue → Green → Purple → Red. Because blue and green income is credited before red costs are deducted, the active player appears richer when red card clamping is evaluated. This means the model underestimates how often the active player cannot fully pay red card demands — making red cards look slightly weaker (to their owners) and slightly cheaper (to the roller) than they really are.

**What needs fixing:**
- [x] Reorder `computeNetGainForRoll` to process: Red first (against `activeCoins` before any income), then Blue, then Green, then Purple. *(ProbabilityCalc.java)*
- [x] `computeNetGainForRoll` currently tracks `remainingCoins` for sequential red deductions. After the reorder, `remainingCoins` for red should start at `activeCoins` (not `activeCoins + net`). *(ProbabilityCalc.java)*
- [x] `GameSession.applyTurn` computes all deltas simultaneously using `computeNetGainForRollPublic`. After the fix, verify that `applyTurn` produces the same result as the corrected EV model so the live game tracking stays consistent. *(GameSession.java)*
- [x] Update `ARCHITECTURE.md` to document the correct processing order. *(ARCHITECTURE.md)*

### 2. Counter-Clockwise Red Card Payment Order
**Priority: Medium — correctness issue**

RULES.md: *"Sollten sich aufgrund eines Würfelwurfs mehrere Ansprüche ergeben, werden sie **gegen den Uhrzeigersinn** abgehandelt."* — when multiple red card owners trigger on the same roll, they are paid in counter-clockwise order from the active player. This matters when the active player has fewer coins than the total demand: earlier claimants in counter-clockwise order get paid in full; later claimants get whatever remains.

**Current state:** `computeNetGainForRoll` iterates opponents in ascending index order (0, 1, 2, 3). When the active player is, say, player 2 in a 4-player game, the correct counter-clockwise order is 1, 0, 3 — not 0, 1, 3.

**What needs fixing:**
- [x] In `computeNetGainForRoll`, build the opponent iteration order as counter-clockwise from the active player: starting at `(playerIndex - 1 + n) % n`, stepping down by 1 mod n, until all opponents are covered. *(ProbabilityCalc.java)*
- [x] In `GameSession.applyTurn`, when computing red card deltas, apply the same counter-clockwise iteration order so the live tracking matches the corrected EV model. Implemented via new `computeAllDeltasForRoll` which replaced the old simultaneous-delta approach. *(GameSession.java)*
- [x] In `GameSimulator.applyRoll`, updated to use `computeAllDeltasForRoll`. *(GameSimulator.java)*

---

## Known Approximations to Improve

### 1. Bürohaus — Heuristic Model
**Priority: Low**

- [ ] `bürohausSwapEV` assumes optimal swap on every activation. A more accurate model would require a state-lookahead pass (design-level change). Acceptable approximation for now.

See `ARCHITECTURE.md §2.8` for the current approximation details.

### 2. GameSimulator — Bahnhof Always Uses 2d6
**Priority: Medium**

- [x] In `GameSimulator.rollDice()`, the dice choice is now based on whether the player owns any cards with activation in the 7–12 range (which benefit from 2d6's bell-curve). If the player only has 1–6 range cards (e.g. early game with only weizenfeld + bäckerei), 1d6 is used instead. This matches the analytical model's dice-choice behavior without expensive per-roll EV computation. *(GameSimulator.java)*

### 3. GameSimulator — Bürohaus Not Executed
**Priority: Low**

- [ ] In `applyRoll()`, detect if active player owns bürohaus and roll was 6; execute swap of lowest-EV own card for highest-EV opponent card. *(GameSimulator.java)*

### 4. `evPerRound` — Static Coin Count
**Priority: Medium**

- [ ] `evPerRound` uses the player's *current* coin count for all turns. Does not model coin accumulation between turns or red card losses changing the effective coin count mid-turn. Slightly optimistic for low-coin players.

### 5. `singleCardEvPerRound` — No Synergy in Softmax Scores
**Priority: Low**

- [ ] `computeScores()` calls `singleCardEvPerRound` with a neutral state (1 food, 1 animal, 1 production). Players with synergy-heavy builds get no credit. Makes `estimateWinProbDelta` less accurate for those builds.

---

## Missing UI Features

### 1. Game-Over Detection
**Priority: High**

- [x] `GameSimulator.hasWon()` exists but is never called in the live game flow. After buying the 4th landmark the app still shows the ranking table. Fix: in `GameSession.applyTurn()`, check `hasWon()` after a landmark purchase and flag the session as finished. `MainWindow` should show "Player X wins!" instead of the ranking table. *(GameSession.java, MainWindow.java)*

### 2. Bürohaus Buy Advice in UI
**Priority: Medium**

- [ ] When bürohaus is the top recommendation, show actionable advice: "Swap your [worst card] for [opponent]'s [best card]". Add a `notes` string to the `RankEntry` in `rankPurchasableProjects` (or a new helper) and display it in `MainWindow.populateCenter()`. *(ProbabilityCalc.java, MainWindow.java)*

### 3. Snapshot Dialog Validation Feedback
**Priority: Medium**

- [x] Invalid states (e.g. same purple card owned by two players) currently produce a Java exception. Fix: validate uniqueness constraints in `GameStateBuilder.build()` or `SnapshotDialog.onApply()` and show a `JOptionPane` error instead. *(GameStateBuilder.java, SnapshotDialog.java)*

### 4. "Current Win Probability" Summary
**Priority: Low**

- [ ] The win-prob column shows delta-per-card but no overall baseline win probability. Fix: in `refreshAll()`, call `estimateWinProbDelta` (or `mcWinRate`) with a null candidate to get the baseline; display it in the center panel header. *(MainWindow.java, ProbabilityCalc.java)*

---

## Code Deduplication & Refactoring

**Goal:** Remove all cases where equivalent logic is written twice. Each item below identifies a concrete duplication, what to extract, and where it lives.

### 1. Dual-Dice EV Loops (4–5 near-identical blocks)
**Priority: High** · *ProbabilityCalc.java*

- [x] `immediateEV`, `bestSecondRollEV`, `computeNetGainForRoll`, and `computeOpponentTurnGainForRoll` all contained a loop of the form `for (int r = 0; r <= 12; r++) { double prob = hasBahnhof ? P2[r] : P1[r]; ... }`. Extracted `weightedRollEV(boolean use2d6, IntToDoubleFunction payoutFn)` and `bestDiceEV(boolean hasBahnhof, IntToDoubleFunction payoutFn)` helpers — the loop is now written once. *(ProbabilityCalc.java)*

### 2. `buildOpponentCoins` / `buildOtherCoins` — Identical Methods
**Priority: High** · *ProbabilityCalc.java*

- [x] `buildOtherCoins(int[], int)` (legacy matrix method) and `buildOpponentCoins(Player[], int)` performed the same exclusion algorithm with different input types. Replaced with an overloaded `buildOpponentCoins(int[], int)` and deleted `buildOtherCoins`; the legacy caller now uses the overload. *(ProbabilityCalc.java)*

### 3. Blue/Red Card Income Loops
**Priority: Medium** · *ProbabilityCalc.java*

- [x] `computeNetGainForRoll` and `computeOpponentTurnGainForRoll` both iterated over a player's owned cards and called `get_I`. The blue card filter-and-sum loop was written 3 times (also in `computeAllDeltasForRoll`). Extracted `sumColorIncome(Player, String color, int roll, PlayerStats, int coins, int[] oppCoins)` helper used by all three callers. *(ProbabilityCalc.java)*

### 4. Initial Game State Setup — 3 Sites
**Priority: Medium** · *GameState.java, GameSession.java, GameSimulator.java*

- [x] The standard starting state (each player: Weizenfeld + Bäckerei, 3 coins, no landmarks) is constructed in at least 3 places. Audited: all callers use `GameState.initial()` except `undoLastTurn` which must inject custom player names — `GameState.initial()` cannot be used there directly (names default to "Player N"). The `GameStateBuilder` approach in `undoLastTurn` is the correct pattern for that site. *(no change needed)*

### 5. Bahnhof Dice-Choice Pattern
**Priority: Medium** · *ProbabilityCalc.java*

- [x] The pattern `double p = hasBahnhof ? P2[r] : P1[r]` (or `ev1 vs ev2` EV comparison for 1d6 vs 2d6) appears in at least 3 places. Replaced by `weightedRollEV(boolean use2d6, IntToDoubleFunction)` and `bestDiceEV(boolean hasBahnhof, IntToDoubleFunction)` helpers (see dedup item #1 above). *(ProbabilityCalc.java)*

### 6. `PlayerStats` Computation Duplicated
**Priority: Medium** · *ProbabilityCalc.java*

- [x] `PlayerStats.of(player)` is called in the hot loop inside `rankPurchasableProjects` but also constructed ad hoc in at least one other location. Verified: all usages are via `PlayerStats.of()` — no inline duplicates. *(ProbabilityCalc.java)*

### 7. `colorForCard()` — Two Versions
**Priority: Medium** · *MainWindow.java*

- [x] `MainWindow` contained two private methods that map card color strings to `Color` values — one for the table cell renderer (pastel, for backgrounds) and one for the center panel (saturated, for color bars). Consolidated into `colorForCard(String colorId, boolean saturated)` with a one-line wrapper `colorForCard(Project p)`. *(MainWindow.java)*

### 8. `capitalize()` — Identical 4-Line Method
**Priority: Low** · *MainWindow.java, SnapshotDialog.java*

- [x] Both files defined an identical `capitalize(String s)` helper. Moved to `UIUtils.capitalize()` in a new `gui.newui.UIUtils` class; both callers updated. *(UIUtils.java, MainWindow.java, SnapshotDialog.java)*

### 9. Color Label Construction
**Priority: Low** · *MainWindow.java, SnapshotDialog.java*

- [x] `colorLabel(String color)` exists only in `SnapshotDialog` (not duplicated in `MainWindow`). No change needed — the PLAN description was inaccurate. *(no change needed)*

### 10. Table Cell Renderer Setup
**Priority: Low** · *MainWindow.java*

- [x] The redundant renderer setup in `buildRightPanel()` (right-align + `CardNameRenderer`) was removed; `rebuildTable()` is the single site that applies all column renderers and widths. *(MainWindow.java)*

### 11. Supply Deduction Loop
**Priority: Low** · *GameSimulator.java*

- [x] `GameSimulator.purchase()` already handles supply decrement via `supply.merge(card.getId(), -1, Integer::sum)`. Only called once — no extraction needed; it's already correct and clear. *(no change needed)*

---

## Code Quality

### 1. `ProbabilityCalc` Split
**Priority: Low**

- [ ] `ProbabilityCalc.java` is ~1000 lines. Candidate split:
  - `CardIncome` — `get_I`, `P1`, `P2`, `PlayerStats`
  - `EVCalculator` — `immediateEV`, `evPerRound`, `bestSecondRollEV`
  - `RankingEngine` — `rankPurchasableProjects`, `roiOverHorizon`
  - `WinProbability` — `estimateWinProbDelta`, `mcWinRate`, `computeScores`

  This is a refactor with no behaviour change. Do the deduplication items above first — the split will be cleaner afterwards.

### 2. `MainWindow` Controller/View Separation
**Priority: Low**

- [ ] `MainWindow.java` mixes UI layout, event handling, SwingWorker lifecycle, and game logic. Extracting a thin controller would improve testability.

### 3. MC Timeout Logging
**Priority: Low**

- [ ] `GameSimulator.simulate()` returns -1 on timeout (> 200 turns). These are silently discarded. Add a counter/log line when timeouts exceed 1% of simulations to detect degenerate states. *(GameSimulator.java)*

---

## Future Features

### Expansion Support
- [ ] Add harbour/millionaire's row expansion cards. Architecture is ready: `ProjectLoader` is JSON-driven and `get_I` dispatches by card ID. Steps: (1) new entries in `projects.json`, (2) new `case "..."` blocks in `get_I`, (3) update `GameState.initial()`.

### Opponent Modeling
- [ ] All simulated players use the same greedy policy. Simulating different archetypes (aggressive landmark buyer vs. income maximizer) would produce more realistic win rates.

### Session Persistence
- [ ] Export/import `GameSession` to a file so a game can be resumed across app sessions.
