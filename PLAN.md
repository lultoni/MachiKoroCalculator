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

## Known Approximations to Improve

### 1. Bürohaus — Heuristic Model
**Priority: Low**

- [ ] `bürohausSwapEV` assumes optimal swap on every activation. A more accurate model would require a state-lookahead pass (design-level change). Acceptable approximation for now.

See `ARCHITECTURE.md §2.5` for the current approximation details.

### 2. GameSimulator — Bahnhof Always Uses 2d6
**Priority: Medium**

- [ ] In `GameSimulator.rollDice()`, compute expected income for both 1d6 and 2d6 and choose the higher-EV option (same logic as `immediateEV` uses analytically). This fixes overestimation of Bahnhof owners' income in early game. *(GameSimulator.java)*

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

- [ ] `GameSimulator.hasWon()` exists but is never called in the live game flow. After buying the 4th landmark the app still shows the ranking table. Fix: in `GameSession.applyTurn()`, check `hasWon()` after a landmark purchase and flag the session as finished. `MainWindow` should show "Player X wins!" instead of the ranking table. *(GameSession.java, MainWindow.java)*

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

- [ ] `immediateEV`, `bestSecondRollEV`, `computeNetGainForRoll`, and `computeOpponentTurnGainForRoll` all contain a loop of the form `for (int r = 0; r <= 12; r++) { double prob = hasBahnhof ? P2[r] : P1[r]; ... }`. Extract a `weightedRollSum(boolean useTwoDice, IntToDoubleFunction payoutFn)` helper (or similar) so the loop is written once.

### 2. `buildOpponentCoins` / `buildOtherCoins` — Identical Methods
**Priority: High** · *MainWindow.java (×2 or SnapshotDialog.java)*

- [ ] Two methods with different names perform the same operation: building a coins array from all players except the active one. Consolidate into one method (e.g. `getCoinsExcluding(Player[] players, int excludeIdx)`), possibly moved to a static utility or `GameState`.

### 3. Blue/Red Card Income Loops
**Priority: Medium** · *ProbabilityCalc.java*

- [ ] `computeNetGainForRoll` and `computeOpponentTurnGainForRoll` both iterate over a player's owned cards and call `get_I`. The structure is near-identical. Extract a `sumCardIncome(Player p, int roll, boolean ownTurn, ...)` helper to reduce duplication.

### 4. Initial Game State Setup — 3 Sites
**Priority: Medium** · *GameState.java, GameSession.java, GameSimulator.java*

- [ ] The standard starting state (each player: Weizenfeld + Bäckerei, 3 coins, no landmarks) is constructed in at least 3 places. Centralise into `GameState.initial(int numPlayers)` (already exists); audit all sites to ensure they use only this factory method.

### 5. Bahnhof Dice-Choice Pattern
**Priority: Medium** · *ProbabilityCalc.java*

- [ ] The pattern `double p = hasBahnhof ? P2[r] : P1[r]` (or `ev1 vs ev2` EV comparison for 1d6 vs 2d6) appears in at least 3 places. Extract `diceProb(int roll, boolean hasBahnhof)` and `chooseOptimalDice(GameState, int playerIndex)` helpers.

### 6. `PlayerStats` Computation Duplicated
**Priority: Medium** · *ProbabilityCalc.java*

- [ ] `PlayerStats.of(player)` is called in the hot loop inside `rankPurchasableProjects` but also constructed ad hoc in at least one other location. Verify it is always created via `PlayerStats.of()` and remove any inline duplicates.

### 7. `colorForCard()` — Two Versions
**Priority: Medium** · *MainWindow.java*

- [ ] `MainWindow` contains two private methods that map card color strings to Java `Color` values — one for the table cell renderer and one for the center panel. They use different palettes. Consolidate into a single `colorForCard(String color, boolean dark)` method or a shared `CardColors` utility class.

### 8. `capitalize()` — Identical 4-Line Method
**Priority: Low** · *MainWindow.java, SnapshotDialog.java*

- [ ] Both files define an identical `capitalize(String s)` helper. Move to a shared `UIUtils` class (or inline if only called once each).

### 9. Color Label Construction
**Priority: Low** · *MainWindow.java, SnapshotDialog.java*

- [ ] `colorLabel(String color)` (or equivalent logic that returns a colored `JLabel` or string for a card's color) is duplicated across files. Centralise into `UIUtils.colorLabel(String color)`.

### 10. Table Cell Renderer Setup
**Priority: Low** · *MainWindow.java*

- [ ] Two blocks in `buildRightPanel()` and `rebuildTable()` both set up the same custom `DefaultTableCellRenderer` with color-coded rows. Extract a `makeColoredRenderer(...)` factory method.

### 11. Supply Deduction Loop
**Priority: Low** · *GameSimulator.java*

- [ ] The loop that decrements supply counts after a purchase is written inline. Consider extracting `decrementSupply(Map<String,Integer> supply, String cardId)` for clarity even if it's currently only called once.

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
