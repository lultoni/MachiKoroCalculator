# CHANGELOG.md — MachiKoroCalculator

All phases of the original implementation plan are complete. This file records what was done, why, and what decisions were made.

---

## File split: BürohausLogic and GameSessionPersistence extracted

**Goal:** Reduce the two largest mixed-concern files by extracting cohesive sub-responsibilities into dedicated package-private helpers.

### BürohausLogic (extracted from ProbabilityCalc)

`bürohausSwapEV`, `bürohausSwapNote`, and `executeBürohausSwap` shared identical logic to scan for the active player's worst-EV card and each opponent's best-EV card. All three were inlined, duplicating the nested loops. The new `BürohausLogic` class centralises this into one `findCandidates()` helper that returns a `SwapCandidates` record. The three public/package methods on `ProbabilityCalc` become thin wrappers (`BürohausLogic.swapEV`, `swapNote`, `executeSwap`). Public API and test behaviour are unchanged.

### GameSessionPersistence (extracted from GameSession)

`GameSession.save` and `GameSession.load` (140 lines, 11 Gson imports) have been moved to a new `GameSessionPersistence` class. The serialization helpers are private to that class (`serializeSnapshot`, `serializeTurns`, `parseNames`, `parseSnapshot`, `replayTurns`). `GameSession.save` / `GameSession.load` are now thin one-line wrappers that delegate. All Gson/JSON imports are isolated in `GameSessionPersistence`; `GameSession` now has only 3 standard library imports. Public API and file format are unchanged.

**Tests:** 165 passed, 0 failed.

---

## Supply model fix, SnapshotDialog multi-copy spinners, roll spinner range, roll preview

**Goal:** Fix four high-priority correctness and usability issues from the PLAN.md backlog.

### 1. Supply model — card no longer disappears after first purchase

Cards with 6 physical copies (blau/grün/rot/lila) now correctly stay purchasable until all 6 copies are owned across all players.

- `GameSession.applyTurn`: only removes a card type from `unbuilt_projects` when the total owned copies across all players reaches 6 (`GameSimulator.SUPPLY_PER_CARD`).
- `GameStateBuilder.build()`: builds a per-type owned-count map and excludes a type only when count ≥ 6. Previously used `contains(p)` with id-based equals, which excluded a type if any single player owned any copy.
- `GameState.initial()`: corrected to include weizenfeld and bäckerei in the unbuilt pool. Starter copies given to each player are separate from the 6 shared market copies — all non-landmark types belong in the pool.
- `GameSession.undoLastTurn()`: simplified to replay from `initialState.copy()` instead of manually reconstructing a hardcoded 3-coin/2-card state (which was also incorrect for snapshot-rooted sessions).
- `GameSimulator.SUPPLY_PER_CARD`: widened from `private` to package-private so `GameSession` can reference it.

### 2. SnapshotDialog — JSpinner for multi-copy cards

blau, grün, and rot cards now use `JSpinner(0–6)` instead of `JCheckBox` in the snapshot editor, allowing players who own multiple copies to record that accurately.

- lila (unique purple) and gelb (landmarks) remain `JCheckBox` — at most one copy per player.
- `loadCurrentState`: uses `Collections.frequency(ownedIds, id)` to populate spinner values.
- `onApply`: calls `builder.addProject(i, id)` N times for spinner value N.
- Field `projectChecks JCheckBox[][]` replaced by `cardControls Component[][]`; type-checked with `instanceof JSpinner` / `instanceof JCheckBox` at read time.

### 3. Roll spinner range updated on every turn change

The roll spinner now correctly reflects the active player's dice mode.

- Without Bahnhof: range 1–6, default 3. With Bahnhof: range 1–12, default 7.
- `updateRollSpinner(Player)` helper called from `refreshAll()` on every turn change (after Confirm, Undo, replaceSession).
- The label above the spinner ("Dice roll (1–N):") updates to match.
- Current spinner value is clamped to the new max if out of range; default is reset when dice mode changes.

### 4. Roll preview in center panel

Typing a roll value now immediately shows per-player coin deltas so the user can verify the math before confirming.

- `refreshRollPreview()` calls `ProbabilityCalc.computeAllDeltasForRoll` and displays "Roll N: Player X +3, Player Y −1" in a new `rollPreviewArea` (4-line `JTextArea`) in the center panel.
- `rollSpinner.addChangeListener(e -> refreshRollPreview())` wired in `buildUI()`.
- `computeAllDeltasForRoll` widened from package-private to `public` to allow access from `gui.newui`.
- Preview also refreshes on `refreshAll()` so it reflects the new active player after each confirmed turn.

**Tests:** 165 passed, 0 failed (pool-size assertion updated from 17 → 15 to match corrected `GameState.initial()` pool).

---

## Baseline win probability display in center panel

**Goal:** Show each player's current estimated win probability in the center panel so the player can gauge their overall position without having to buy a specific card first.

**What was done:**

- Added `ProbabilityCalc.computeBaselineWinProb(GameState, int)` (public) — returns the analytical softmax win probability for the active player based on current card ownership and landmark count. Backed by the same `computeScores` + `softmaxEntry` path used by `estimateWinProbDelta`.
- `MainWindow.refreshAll()` calls `computeBaselineWinProb` on every refresh (analytical, always fast) and updates a new `baselineWinProbLabel` in the center panel with "Current win prob: X.X%".
- On game over, the label shows "Current win prob: 100%".
- In a symmetric 4-player starting state each player shows ~25%.

**Tests:** 152/152 pass. Two new tests in `test_baseline_win_prob_sums_to_one`:
- Sum of baseline win probs over all 4 players = 1.0 exactly.
- Each player in symmetric state has ~0.25 win prob.

---



**Goal:** Monte Carlo simulations now correctly model bürohaus card-swaps, making win-probability estimates for bürohaus more accurate.

**What was done:**

- Added `ProbabilityCalc.executeBürohausSwap(GameState, int)` (public) — mutates `state` by removing the active player's lowest-EV non-landmark and adding the highest-EV non-landmark from the wealthiest opponent (same heuristic as `bürohausSwapEV`). No-ops if no beneficial swap exists.
- `GameSimulator.applyRoll()` now calls `executeBürohausSwap` immediately after applying coin deltas when `roll == 6` and the active player owns bürohaus.
- The `GameSimulator` Javadoc for `applyRoll` is updated to document the bürohaus behaviour.

**Tests:** 150/150 pass. Five new tests in `test_buerohaus_swap_executed_in_simulator`:
- P0 owns bürohaus + weizenfeld; P1 owns bergwerk. After `executeBürohausSwap`: P0 has bergwerk, P1 has weizenfeld, bürohaus stays with P0.

---



**Goal:** When bürohaus is the top recommended purchase, show actionable swap advice in the center panel: "Swap your [X] for [opponent]'s [Y]".

**What was done:**

- Added `bürohausSwapNote(GameState, int)` to `ProbabilityCalc` (package-visible) — finds the player's worst non-landmark card and the best non-landmark card owned by any opponent, returns a human-readable string like "Swap your Weizenfeld for P1's Bergwerk", or `null` if no beneficial swap exists.
- `rankPurchasableProjects` now populates `RankEntry.notes` for bürohaus candidates by calling `bürohausSwapNote` on a state copy with the candidate added.
- `MainWindow.buildNote()` now checks `entry.notes != null` first and shows it when present, falling back to the generic ROI message otherwise.
- Added private `capitalize(String)` helper to `ProbabilityCalc` (same logic as `UIUtils.capitalize`; kept local to avoid cross-layer dependency).

**Tests:** 145/145 pass. Three new tests added:
- `test_buerohaus_swap_note_set_in_ranking`: bürohaus appears in ranking with `notes` containing "Swap".

---

## Code Deduplication: weightedRollEV helper for dual-dice loops

**Goal:** Eliminate 4–5 near-identical `for (int d = 1; d <= 6; d++) P1[d] * fn(d)` / `for (d1, d2 = 1..6) (1/36) * fn(d1+d2)` loops scattered across `ProbabilityCalc`.

**What was done:**

- Added `weightedRollEV(boolean use2d6, IntToDoubleFunction payoutFn)` — the single canonical dice-weighted sum. When `use2d6=false` it sums over 1d6 (rolls 1–6, uniform 1/6); when `true` it sums over 2d6 (all (d1,d2) pairs, weight 1/36). The loop is written exactly once.
- Added `bestDiceEV(boolean hasBahnhof, IntToDoubleFunction payoutFn)` — computes 1d6 EV and, if `hasBahnhof`, returns `max(ev1, ev2)`, eliminating the repeated "if hasBahnhof compute both and take max" pattern.
- Refactored: `bestSecondRollEV`, `immediateEV` (1d6-only branch), `evPerRound` (own-turn and all opponent-turn loops), `computeVarianceOwnTurn` (ev1/ev2 comparison), `computeProbNoIncomeOwnTurn`, `computeProbNoIncomeRound`.
- Added `import java.util.function.IntToDoubleFunction`.

**Tests:** 142/142 pass (no behaviour change).

---

## Game-Over Detection

**Goal:** When a player buys their 4th landmark in a live session, flag the session as finished and show a win screen instead of continuing to prompt for more turns.

**What was done:**

- `GameSession` now has `private boolean finished` and `private int winnerIndex` (both reset on `undoLastTurn`).
- `applyTurn()` calls `GameSimulator.hasWon(buyer)` after any landmark purchase; if true, sets `finished = true` and `winnerIndex = pi`.
- Two new accessors: `isFinished()` and `getWinnerIndex()`.
- `MainWindow.onConfirmTurn()` checks `session.isFinished()` after applying the turn; if true, calls `showGameOver(winnerName)` instead of `refreshAll()`.
- `MainWindow.showGameOver(String)` disables the Confirm button, updates the center panel with a "Player X wins!" message and a gold color bar, clears the ranking table, and sets the status label to "Game over!". Undo remains enabled so players can verify the final state.

**Tests:** 142/142 pass. Two new tests added:
- `test_game_over_on_fourth_landmark`: P0 owns 3 landmarks; buys Funkturm → `isFinished()` true, `getWinnerIndex()` == 0.
- `test_no_game_over_before_fourth_landmark`: P0 buys 3rd landmark → `isFinished()` false.

---

## Rules Correctness: Income Order and Counter-Clockwise Payment

**Goal:** Make the EV model and live game tracking fully conform to the official rules.

**What was done:**

**Income processing order (Rot → Blau & Grün → Violett):**
- `computeNetGainForRoll` reordered: red card payments now fire against `activeCoins` (pre-roll coins) before any blue/green income is credited. Previously red fired last, causing the roller to appear richer when clamping was evaluated — making red cards look cheaper to the roller than they are.

**Counter-clockwise red card payment priority:**
- `computeNetGainForRoll` now iterates opponents counter-clockwise: `(playerIndex - step + n) % n`. Previously ascending index order was used.
- Added `computeAllDeltasForRoll(state, activePlayer, roll)` — a new single-pass method that computes all players' coin deltas for a roll in the correct order, with red card gains for opponents derived directly from sequential counter-clockwise deductions against the roller's actual coins. This replaces the old simultaneous-delta approach in `GameSession.applyTurn` and `GameSimulator.applyRoll`, which allowed multiple red card owners to each claim the same coins when the roller was short.
- `GameSession.applyTurn` and `GameSimulator.applyRoll` both updated to use `computeAllDeltasForRoll`. The two older bridge methods are marked `@Deprecated`.

**Tests:** 138/138 pass. Two new targeted tests added:
- `test_red_fires_before_green_income`: roller with 0 coins and bäckerei; opponent has café. On roll 3, correct result is +1 (red fires first against 0 coins → pays 0; then bäckerei gives +1). Old code would give 0.
- `test_red_payment_counter_clockwise_order`: 4-player game, active = P2 with 1 coin, three opponents each with café. Counter-clockwise neighbour P1 collects the coin; P0 gets nothing. Old code would have given it to P0 (ascending index).

---

## Supply / Ownership Rules Fix

**Goal:** Enforce correct per-player ownership limits for purple (lila) cards.

**What was done:**
- `GameStateBuilder.addProject()` now throws `IllegalArgumentException` if the card is purple and the target player already owns one (purple cards are unique — max 1 per player)
- `ProbabilityCalc.rankPurchasableProjects()` now skips purple cards that the active player already owns, in addition to the existing Großprojekt singleton check
- `SnapshotDialog` purple checkboxes now have an `ItemListener`: when a purple card is checked for one player, the same card is automatically unchecked for all other players
- `SnapshotDialog.onApply()` now wraps the `GameStateBuilder` calls in a try/catch; any `IllegalArgumentException` (e.g. duplicate purple) surfaces as a `JOptionPane` error dialog instead of a stack trace
- 4 new tests added to `RuntimeTester` covering all the above; total test count: 134/134

**Tests:** 134/134 pass

---

## Phase 6 — Polish & Final Integration

**Goal:** Resolve all remaining FIXMEs, complete documentation, add final benchmarks.

**What was done:**
- Implemented `bürohausSwapEV()` heuristic in `ProbabilityCalc`: approximates card-swap EV as `max(0, bestOppCardEV − worstOwnCardEV)` using `singleCardEvPerRound`; wired into `immediateEV` as `P(roll=6) × swapEV`
- Removed all FIXME/TODO comments from `src/` (zero remain)
- Added complete Javadoc to `RankEntry` (class + all 10 fields) and `RankingOptions` (class + all 4 fields)
- Added Javadoc to `Player` getters/setters, `GameState` getters, and GUI constructors
- Fixed `projects.json` bürohaus description (removed stale FIXME note)
- Added Phase 6 tests and final benchmarks to `RuntimeTester`

**Benchmarks at completion:**
- `rankPurchasableProjects`: ~0.16 ms/call (4-player starting state)
- `estimateWinProbDelta` (MC, 500 sims): ~38 ms
- `mcWinRate` (1000 sims, 4-player): ~54 ms
- `getAllProjects()` (cached): < 1 ms

**Tests:** 130/130 pass

---

## Phase 5 — Monte Carlo Deep Mode

**Goal:** Add MC game simulation for accurate win-probability deltas; expose in UI.

**What was done:**
- Created `GameSimulator` — stateless Monte Carlo simulator with greedy rollout policy (landmarks first, then highest `evPerRound/cost` establishment)
- Supply model: 6 copies per non-landmark card; tracked as `Map<String, Integer>` in `GameSimulator`
- `STATIC_EV_PER_COST` precomputed at class load from a neutral 4-player reference state
- Added `mcWinRate()` to `ProbabilityCalc` using `IntStream.parallel()` + `ThreadLocalRandom`
- MC baseline computed once in `rankPurchasableProjects` and reused across all candidates
- Updated `estimateWinProbDelta` to use MC path when `mcSimulations > 0`
- Added "Deep Analysis" toggle button to `MainWindow`; uses `SwingWorker` so EDT is never blocked
- `confirmBtn` disabled during MC computation; `statusLabel` shows progress

**Tests:** 128/128 pass

---

## Phase 4 — Remove Legacy Code

**Goal:** Delete the original `src/logic/` layer (pre-probability implementation).

**What was done:**
- Removed 14 legacy files: `Game.java`, `Player.java`, `Project.java`, `Category.java`, legacy GUI files
- `logic.Main` cleaned to single-line entry point: `SwingUtilities.invokeLater(SetupWindow::new)`
- Zero warnings after removal

**Tests:** Confirmed all Phase 1–3 tests still pass after legacy removal.

---

## Phase 3 — Game State Configuration UI

**Goal:** Swing UI for turn-by-turn tracking with snapshot mode.

**Design decision made:** Turn-by-turn tracking with mid-game snapshot editing capability (not snapshot-only entry). Player tracks each turn in the app; can also open snapshot dialog to correct state at any point.

**What was done:**
- `SetupWindow` — player count (2–4) + name entry, launches `MainWindow`
- `MainWindow` — three-column layout: left (turn input + history), center (top recommendation), right (full ranking table). Roll spinner, buy dropdown, Confirm Turn, Undo, Snapshot
- `SnapshotDialog` — modal editor with per-player coin spinner + checkbox grid of all 19 cards
- `GameStateBuilder` — fluent builder used by both setup and snapshot paths
- `GameSession` — wraps mutable `GameState` + history; `applyTurn`, `undoLastTurn`, `toSnapshot`/`fromSnapshot`
- `TurnRecord` — immutable record of one turn (playerIndex, roll, bought)

---

## Phase 2 — Core Math Engine

**Goal:** `ProbabilityCalc` correctly computes EV, ROI, variance, win probability for any game state.

**What was done:**
- Precomputed `double[] P1` and `double[] P2` probability tables (constant arrays, no switch)
- Implemented all `get_I` cases for all 19 base-game cards; audited against official rules:
  - Fixed stadion: takes 2 from *each* opponent (not richest only), no total cap
  - Fixed fernsehsender: takes up to 5 from the single richest opponent (not 2 from each)
  - Bürohaus: returns 0 (non-monetary; handled separately in Phase 6)
- `computeNetGainForRoll` / `computeOpponentTurnGainForRoll` — per-roll coin delta
- `bestSecondRollEV` — EV of optimal Freizeitpark second roll
- `immediateEV` — own-turn EV with Bahnhof/Freizeitpark/Funkturm interactions
- `evPerRound` — full-round EV (own turn + N−1 opponent turns)
- `roiOverHorizon` — geometric-series discounted ROI + variance + probNoIncome
- `estimateWinProbDelta` — softmax heuristic (analytical path)
- `rankPurchasableProjects` — sorted by ROI; win-prob delta computed on demand

**Key correctness verifications:**
- P1 and P2 sum to exactly 1.0
- Blue card EV scales linearly with player count N
- Red card EV only fires on opponent turns
- ROI is always positive for cards with positive `evPerRound`

**Tests:** 108/108 pass at Phase 2 completion (now 130/130 total).

---

## Phase 1 — Solid Data Model

**Goal:** Complete, correct, immutable data model for the probability layer.

**What was done:**
- `Project` — immutable POJO with `equals`/`hashCode` on `id`; safe to share across copies
- `Player` — validates `coins >= 0`; `copy()` shallow-copies owned list (safe because `Project` is immutable)
- `GameState` — validates 2–4 non-null players; `copy()` deep-copies; `GameState.initial(N)` factory method
- `ProjectLoader` — loads `projects.json` once at class load into static `Map<String, Project>`; `getAllProjects()` added
- `RankingOptions` — options POJO for `rankPurchasableProjects`
- Fixed `ProjectLoader` to use classpath resource loading (`getResourceAsStream`) instead of relative path

**Tests:** 31/31 pass at Phase 1 completion.

---

## Phase 0 — Deep Clean & Audit

**Goal:** Get the codebase to compile cleanly; document all known issues.

**What was done:**
- Added compile stubs (with `throw new UnsupportedOperationException`) for 6 missing method bodies in `ProbabilityCalc`
- Created `RankingOptions.java` (was missing, referenced in stub signature)
- Replaced all bare `TODO` comments with `// FIXME [Phase X]: description` in FIXME-linked format
- Marked legacy classes (`Game`, `Player`, `Project`, `Category`) with `// LEGACY` header comments
- Tagged known bugs in legacy layer with `// FIXME [Phase 4 / remove]`
- Removed dead commented-out `System.out.println` blocks from `Main.java`

---

## Resolved Design Decisions

| Question | Decision |
|----------|----------|
| Bürohaus modeling | EV heuristic: `max(0, bestOppCardEV − worstOwnCardEV)`, added to `immediateEV` at `P(roll=6)` |
| UI model | Turn-by-turn tracking with mid-game snapshot edit capability |
| Discount factor default | 0.95 per turn (configurable via `RankingOptions.discountFactor`) |
| MC simulations default | Off by default (analytical only); enabled via Deep Analysis toggle (1000 sims) |
| Card supply model | `unbuilt_projects` in `GameState` for EV ranking (presence = available); separate `Map<String,Integer>` supply counter in `GameSimulator` for simulation accuracy |
| Stadion rule | 2 coins from **each** opponent (no total cap) — fixed in Phase 2 |
| Fernsehsender rule | Up to 5 coins from the **single richest** opponent — fixed in Phase 2 |
