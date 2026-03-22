# CHANGELOG.md — MachiKoroCalculator

All phases of the original implementation plan are complete. This file records what was done, why, and what decisions were made.

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
