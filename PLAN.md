# PLAN.md — MachiKoroCalculator Active Backlog

All 6 original implementation phases are complete. This file tracks **known limitations, bugs, and planned improvements** — open items only.

For historical context (what was built and why), see `CHANGELOG.md`.
For mathematical foundations and design rationales, see `ARCHITECTURE.md`.

Progress key: `[ ]` open · `[~]` in progress · `[x]` done

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

## Code Quality

### 1. File Split — Priority 1 (complete)

Audit conducted 2026-03-23. All files assessed by line count and responsibility. Files ≥ 200 lines
with mixed concerns and a clear split boundary:

| File | Lines | Concern to extract | Status |
|------|-------|--------------------|--------|
| `ProbabilityCalc.java` | 870 | Bürohaus helpers (3 methods, ~130 lines) → `BürohausLogic` | `[x]` |
| `GameSession.java` | 336 | JSON persistence (save + load, ~140 lines) → `GameSessionPersistence` | `[x]` |

### 2. File Split — Priority 2 (deferred — needs UI test layer first)

- [ ] Extract `UIDataModel` from `MainWindow` (~50 lines): holds `session`, `rankOpts`, `lastRanking`, `showWinProb`.
- [ ] Extract `RankingUIRenderer` from `MainWindow` (~100 lines): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`.
- [ ] Extract thin `GameController` from `MainWindow`: turn application, undo, snapshot, save/load event dispatch.

### 3. File Split — No action needed

`RuntimeTester.java` (928 lines) is a single self-contained test runner with 47 test methods and 7
benchmark sections. Despite its size it has no mixed concerns — splitting into per-domain classes
would add a multi-class test runner harness without meaningful benefit given the current single-file
compile-and-run workflow.

`CardIncome.java`, `WinProbabilityCalc.java`, `GameSimulator.java` — well-factored, no split warranted.

### 4. `MainWindow` Controller/View Separation
**Priority: Low**

Covered under File Split Priority 2 above — deferred until a Swing UI testing layer (JUnit + WindowTester or similar) is in place.

---

## Future Features

### Expansion Support
- [ ] Add harbour/millionaire's row expansion cards. Architecture is ready: `ProjectLoader` is JSON-driven and `get_I` dispatches by card ID. Steps: (1) new entries in `projects.json`, (2) new `case "..."` blocks in `get_I`, (3) update `GameState.initial()`.

### Opponent Modeling
- [ ] All simulated players use the same greedy policy. Simulating different archetypes (aggressive landmark buyer vs. income maximizer) would produce more realistic win rates.
