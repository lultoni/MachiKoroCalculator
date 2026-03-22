# PLAN.md — MachiKoroCalculator Active Backlog

All 6 original implementation phases are complete. This file tracks **known limitations, bugs, and planned improvements** — open items only.

For historical context (what was built and why), see `CHANGELOG.md`.
For mathematical foundations and design rationales, see `ARCHITECTURE.md`.

Progress key: `[ ]` open · `[~]` in progress · `[x]` done

---

## Supply Model Bugs

These are correctness issues in the live game path that affect what cards appear as purchasable.

### 1. Card Disappears From Market After First Purchase
**Priority: High — correctness issue**

Non-purple, non-landmark cards have **6 physical copies** shared across all players. Buying one copy should reduce supply by 1, leaving 5 copies still available. Instead, the card vanishes from the market entirely after the first purchase by any player.

**Root cause — `GameSession.applyTurn` (GameSession.java ~line 94):**
```java
boolean inPool = state.getUnbuilt_projects().remove(card);
```
`unbuilt_projects` stores one entry per card *type* (because `Project.equals` is id-based). Calling `remove(card)` on the first purchase removes the type entry — silently making the card unavailable for the remaining 5 copies.

**Root cause — `GameStateBuilder.build()` (GameStateBuilder.java ~line 110):**
```java
for (Project p : allProjects) {
    if (!allOwned.contains(p)) unbuilt.add(p);  // id-based equals
}
```
`allOwned` collects every player's owned cards; `contains(p)` uses id-based equals — so if *any* player owns a card type, it is excluded from `unbuilt`, even if only 1 of 6 copies is taken.

**What needs fixing:**
- [ ] `GameSession.applyTurn`: Only remove a card type from `unbuilt_projects` when the total number of copies owned across all players reaches 6 (not on the first purchase). *(GameSession.java)*
- [ ] `GameStateBuilder.build()`: Compute per-type owned counts and exclude a card type only when owned count ≥ 6. *(GameStateBuilder.java)*
- [ ] `GameState.unbuilt_projects` semantics: Consider whether to keep the one-entry-per-type model with explicit supply-count tracking, or store 6 entries per non-landmark card type. The one-per-type + count approach avoids bloating the list. *(GameState.java, GameStateBuilder.java)*
- [ ] Update `rankPurchasableProjects` and `MainWindow.rebuildBuyCombo` if the semantics change — both currently source from `unbuilt_projects` to determine what is purchasable. *(ProbabilityCalc.java, MainWindow.java)*

**Note on starter cards:** Weizenfeld and Bäckerei are given to each player at game start — they never enter the shared buyable pool. Their 6 physical copies exist in the pool independently of what players start with.

### 2. SnapshotDialog Shows Binary Ownership for Multi-Copy Cards
**Priority: High — correctness issue**

The snapshot dialog uses `JCheckBox[player][card]` — strictly 0 or 1 per card per player. For blau, grün, and rot cards, a player can legitimately own multiple copies (e.g. 3× Mini-Markt). The binary model prevents this from being accurately entered.

**Root cause — `SnapshotDialog.java` (loadCurrentState ~line 185, onApply ~line 205):**
```java
// loadCurrentState — binary: hasProject returns true/false
projectChecks[i][j].setSelected(
        players[i].hasProject(allProjects.get(j).getId()));

// onApply — adds exactly one copy per checked box
if (projectChecks[i][j] != null && projectChecks[i][j].isSelected()) {
    builder.addProject(i, allProjects.get(j).getId());
}
```
A player owning 3× Mini-Markt would display as "1×" and round-trip to 1 copy on apply.

**What needs fixing:**
- [ ] Replace `JCheckBox` with `JSpinner(0..6)` for blau/grün/rot cards. Purple (lila) cards remain binary checkboxes (max 1 per player). Landmarks (gelb) remain binary checkboxes (owned or not, per player, independent). *(SnapshotDialog.java)*
- [ ] `loadCurrentState`: Count owned copies with something like `Collections.frequency(ownedIds, cardId)` instead of `hasProject`. *(SnapshotDialog.java)*
- [ ] `onApply`: Call `builder.addProject(i, id)` N times where N is the spinner value. *(SnapshotDialog.java)*
- [ ] Spinner upper bound: For a given card type, the total across all players cannot exceed 6. Optionally enforce this per-spinner or in `GameStateBuilder.build()` validation. *(SnapshotDialog.java or GameStateBuilder.java)*

---

## Known Approximations (Accepted)

These are documented deviations from optimal accuracy that have been reviewed and accepted.

### 1. Bürohaus — Optimal-Swap Assumption
**Priority: Low**

`bürohausSwapEV` assumes the player always makes the optimal swap on every activation. In reality the swap is optional. Acceptable heuristic. See `ARCHITECTURE.md §2.8`.

### 2. GameSimulator — Static EV/Cost Table Ignores Synergy
**Priority: Low**

`STATIC_EV_PER_COST` is precomputed from a neutral reference state; synergy-heavy builds (e.g. many food cards with Markthalle) make suboptimal buy decisions during simulation. Acceptable for win-rate estimation. See `ARCHITECTURE.md §4.2`.

### 3. `evPerRound` — Projected Coin Correction is Approximate
**Priority: Low**

`evPerRound` projects each player's coins forward by `estimateUncappedOwnTurnEV` before evaluating red card clamping. This is a single-step projection (one turn of blue+green income), not a full multi-turn model. Accepted approximation. See `ARCHITECTURE.md §2.4b`.

---

## Missing UI Features

### 1. Roll Outcome Display in Turn Tracker
**Priority: Medium**

After a roll is entered in the turn input, the center panel should show which coins each player will gain or lose from that roll — so the user can verify the calculation matches what happened on the physical table. Currently the panel only updates after `Confirm Turn` is pressed.

**What needs fixing:**
- [ ] When the roll spinner changes (or on a "Preview" button click), call `ProbabilityCalc.computeAllDeltasForRoll(state, activePlayer, roll)` and display per-player deltas in the center panel. *(MainWindow.java)*
- [ ] Show: "Roll [N]: Player X +3, Player Y −1 (café)" so the user can immediately see and verify the outcome before confirming.

---

## Code Quality

### 1. File Split Analysis
**Priority: High**

Several source files have grown large enough that they likely warrant splitting. Before doing any split, conduct a deep analysis pass across the whole `src/` tree to identify candidates — looking at line count, number of distinct responsibilities per file, and test coverage boundaries.

Known candidates from prior work:
- `src/Tests/RuntimeTester.java` — a single ~1800-line class containing all tests, benchmarks, and helpers. Should be split by domain (data-model tests, EV/probability tests, simulation tests, UI/session tests, benchmarks) into separate test classes, ideally under a proper test source root.
- `src/gui/newui/MainWindow.java` — mixes UI layout, event handling, SwingWorker lifecycle, and game-state read calls. A controller/presenter separation would make event logic independently testable.

**What needs doing:**
- [ ] Audit every file in `src/` for line count and responsibility count. Produce a prioritised list of split candidates with proposed target structure. *(analysis only — no code changes)*
- [ ] Split `RuntimeTester.java` into per-domain test classes. *(Tests/)*
- [ ] Extract a thin `GameController` from `MainWindow` handling turn application, undo, snapshot, and session save/load. *(gui/newui/)*

### 2. `MainWindow` Controller/View Separation
**Priority: Low**

Covered under File Split Analysis above — tracked separately so the controller extraction can be done independently of the broader audit.

---

## Future Features

### Expansion Support
- [ ] Add harbour/millionaire's row expansion cards. Architecture is ready: `ProjectLoader` is JSON-driven and `get_I` dispatches by card ID. Steps: (1) new entries in `projects.json`, (2) new `case "..."` blocks in `get_I`, (3) update `GameState.initial()`.

### Opponent Modeling
- [ ] All simulated players use the same greedy policy. Simulating different archetypes (aggressive landmark buyer vs. income maximizer) would produce more realistic win rates.
