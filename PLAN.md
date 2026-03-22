# PLAN.md — MachiKoroCalculator Active Backlog

All 6 original implementation phases are complete. This file now tracks **known limitations, bugs, and planned improvements** — the items to fix next.

For historical context (what was built and why), see `CHANGELOG.md`.
For mathematical foundations and design rationales, see `ARCHITECTURE.md`.

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
1. `rankPurchasableProjects` should exclude purple cards the active player already owns (unique cards).
2. The snapshot dialog should prevent checking a purple card for a player who already owns one.
3. `GameState.unbuilt_projects` semantics should be clarified (or changed) to reflect supply counts, not just presence.
4. `GameStateBuilder` should throw if a purple card is added twice to the same player.

**Files affected:** `ProbabilityCalc.java` (`rankPurchasableProjects`), `GameStateBuilder.java`, `SnapshotDialog.java`, `GameState.java`

---

## Known Approximations to Improve

### 1. Bürohaus — Heuristic Model
**Priority: Low**

`bürohausSwapEV` assumes the player always makes the optimal swap on every activation. In reality:
- The swap is optional (player can decline)
- The value depends on game context (late game when opponents have high-EV cards is very different from early game)
- After swapping, the player's own card composition changes, affecting future EV

A more accurate model would require a state-lookahead pass, which is a design-level change.

See `ARCHITECTURE.md §2.5` for the current approximation details.

### 2. GameSimulator — Bahnhof Always Uses 2d6
**Priority: Medium**

`GameSimulator.rollDice()` always picks 2d6 when the player has Bahnhof. In early game, 1d6 can be better if the player's cards activate on rolls 1–4. This slightly overestimates Bahnhof owners' income in simulation, leading to marginally optimistic win-rate estimates for them.

**Fix:** In `rollDice()`, compute expected income for both 1d6 and 2d6 across the player's owned cards and choose the higher-EV option. This is the same logic as `immediateEV` uses for the analytical model.

**Files affected:** `GameSimulator.java` (`rollDice()`)

### 3. GameSimulator — Bürohaus Not Executed
**Priority: Low**

The greedy buy policy buys bürohaus if it has the highest `STATIC_EV_PER_COST` score, but the simulation never executes the card-swap effect when the card activates. This means bürohaus owners play suboptimally in simulation.

**Fix:** In `applyRoll()` (or as a post-roll step), detect if active player owns bürohaus and roll was 6; execute a swap of lowest-EV own card for highest-EV opponent card.

**Files affected:** `GameSimulator.java`

### 4. `evPerRound` — Static Coin Count
**Priority: Medium**

`evPerRound` evaluates all turns using the player's *current* coin count. It does not model:
- Coin accumulation between turns (player has more coins in turn 3 than turn 1)
- Red card losses changing the effective coin count for subsequent card evaluations in the same turn

This means the model is slightly optimistic for players with few coins (red card losses are capped lower than reality) and slightly off for multi-card interactions within one turn.

### 5. `singleCardEvPerRound` — No Synergy in Softmax Scores
**Priority: Low**

`computeScores()` (used for analytical win-prob softmax) calls `singleCardEvPerRound` with a neutral state (1 food, 1 animal, 1 production). A player who owns 3 food cards gets no synergy credit in their win-probability score, making `estimateWinProbDelta` less accurate for synergy-heavy builds.

---

## Missing UI Features

### 1. Game-Over Detection
**Priority: High**

`GameSimulator.hasWon()` exists but is not called anywhere in the live game flow. After a player buys their 4th landmark, `MainWindow` continues showing a ranking table as if the game is still ongoing.

**Fix:** In `GameSession.applyTurn()`, after processing a landmark purchase, check `GameSimulator.hasWon()` and flag the session as finished. `MainWindow` should detect this flag and show a "Player X wins!" message instead of the ranking table.

**Files affected:** `GameSession.java`, `MainWindow.java`

### 2. Bürohaus Buy Advice in UI
**Priority: Medium**

When bürohaus is the top recommendation, the center panel shows an EV number but gives no actionable advice. The player needs to know: "swap your [worst card] for [opponent]'s [best card]".

**Fix:** In `MainWindow.populateCenter()`, detect if the top card is bürohaus and add a `notes` string to its `RankEntry` (e.g. "Swap your Weizenfeld for Player 2's Bergwerk").

**Files affected:** `ProbabilityCalc.java` (`rankPurchasableProjects` or a new helper), `MainWindow.java`

### 3. Snapshot Dialog Validation Feedback
**Priority: Medium**

Invalid states (e.g. same purple card owned by two players) produce a Java exception rather than a friendly error message in the dialog.

**Fix:** Validate uniqueness constraints in `GameStateBuilder.build()` (or in `SnapshotDialog.onApply()`) and show a `JOptionPane` error instead of letting the exception propagate.

**Files affected:** `GameStateBuilder.java`, `SnapshotDialog.java`

### 4. "Current Win Probability" Summary
**Priority: Low**

The win-prob column shows delta-per-card but there is no overall "your current win probability is X%" displayed prominently. This would be the most immediately useful number for the player.

**Fix:** In `refreshAll()`, call `estimateWinProbDelta` (or `mcWinRate`) with a null candidate to get the baseline win probability; display it in the center panel header.

**Files affected:** `MainWindow.java`, `ProbabilityCalc.java`

---

## Code Quality

### 1. `ProbabilityCalc` Split
**Priority: Low**

`ProbabilityCalc.java` is ~1000 lines. It could be split into:
- `CardIncome` — `get_I`, `P1`, `P2`, `PlayerStats`
- `EVCalculator` — `immediateEV`, `evPerRound`, `bestSecondRollEV`
- `RankingEngine` — `rankPurchasableProjects`, `roiOverHorizon`
- `WinProbability` — `estimateWinProbDelta`, `mcWinRate`, `computeScores`

This is a refactor with no behaviour change.

### 2. `MainWindow` Controller/View Separation
**Priority: Low**

`MainWindow.java` mixes UI layout, event handling, SwingWorker lifecycle, and game logic (turn application, ranking display). Extracting a thin controller would improve testability.

### 3. MC Timeout Logging
**Priority: Low**

`GameSimulator.simulate()` returns -1 on timeout (> 200 turns). These are silently ignored in win-rate computation (`mcWinRate` just doesn't count them). A counter or log line when timeouts exceed 1% of simulations would help detect degenerate states.

---

## Future Features

### Expansion Support
The architecture is ready: `ProjectLoader` is JSON-driven, `get_I` dispatches by card ID, and `projects.json` can be extended. Adding harbour/millionaire's row expansion cards requires:
1. New entries in `projects.json`
2. New `case "..."` blocks in `get_I`
3. Updating `GameState.initial()` for the new starting state

### Opponent Modeling
Currently all simulated players use the same greedy policy. Simulating different archetypes (aggressive landmark buyer vs. income maximizer) would produce more realistic win rates.

### Session Persistence
Export/import `GameSession` to a file so a game can be resumed across app sessions.
