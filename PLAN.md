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
