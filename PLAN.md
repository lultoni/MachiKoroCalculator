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

`evPerRound` projects each player's coins forward by `estimateUncappedOwnTurnEV` before evaluating red card clamping. This is a single-step projection (not a full multi-turn model). Accepted approximation. See `ARCHITECTURE.md §2.4b`.

---

## Code Quality

### 1. File Split — Priority 1 (complete)

| File | Concern extracted | Status |
|------|-------------------|--------|
| `ProbabilityCalc.java` | Bürohaus helpers → `BürohausLogic` | `[x]` |
| `GameSession.java` | JSON persistence → `GameSessionPersistence` | `[x]` |

### 2. File Split — Priority 2 (deferred — needs UI test layer first)

- [ ] Extract `UIDataModel` from `MainWindow` (~50 lines): holds `session`, `rankOpts`, `lastRanking`, `showWinProb`.
- [ ] Extract `RankingUIRenderer` from `MainWindow` (~100 lines): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`.
- [ ] Extract thin `GameController` from `MainWindow`: turn application, undo, snapshot, save/load event dispatch.

### 3. File Split — No action needed

`RuntimeTester.java` is a single self-contained test runner. Despite its size it has no mixed concerns.
`CardIncome.java`, `WinProbabilityCalc.java`, `GameSimulator.java` — well-factored, no split warranted.

---

## UI Polish Batch 2 — Open Items

Items from the second UI review (2026-03-29). Organized by panel and priority.

### Left panel — Current Turn Tracker

#### [x] Rename "Current Turn" → "Current Turn Tracker"
Done.

#### [x] BoundedSpinner — arrow buttons disable at boundaries
Done. `BoundedSpinner` wraps `JSpinner` and disables increment/decrement at model bounds.

#### [x] Doubles tracking (Freizeitpark/Bahnhof)
Done. "Doubles?" checkbox shown when player owns both. Bonus turn logic in `GameSession`.

#### [ ] Coin display — visual overhaul (coin icon + denominations)
Current state: bold label "N → M coins (after roll)".
Requested: Use `COIN.png` with number overlaid; optionally show bronze/silver/gold denominations
(1/5/10 coin variants). Below the current coins, show a second row for coins-after-roll (delta only — e.g. if player has 3 and earns 1, only show a single bronze coin in the "after roll" delta section).
**Decision needed:** Simple icon+number label vs. full denomination rendering — ask user before implementing.

#### [ ] Roll input — slider instead of spinner
Low priority. Slider would require a separate label to show the current value and is less precise for keyboard input. Deferred unless user explicitly prefers it.

#### [x] History panel — show coin deltas (paid/received amounts per player per turn)
Done. `TurnRecord` now stores `int[] coinDeltas` (computed by `applyTurn`). `refreshHistory` shows a per-player delta line below each roll row, green for gains, red for losses. Backward-compatible: old saves without the field display history without the delta line.

#### [x] Left panel resize — history should get free space, not labels
Done. `buildLeftPanel` now uses `BorderLayout`; the controls sub-panel is in `NORTH` (fixed) and the history `JScrollPane` is in `CENTER` (fills all remaining vertical space).

### Center panel — Card Details

#### [x] Rename "Best Purchase" → "Card Details"
Done.

#### [x] Metric explanations (tooltips on each metric label)
Done. Each label has a tooltip explaining EV/rnd, ROI, P(0), Variance, Win Prob Δ.

#### [x] Win Prob Δ row hidden by default; toggle via "Show Win Prob Δ" button
Done.

#### [x] Win Prob row visibility should follow the global toggle (not always shown)
Done. `populateCenter` now calls `setWinProbRowVisible(showWinProb)` after setting values, keeping it in sync with the toggle regardless of how the center panel was repopulated.

#### [x] Sort order preserved in ranking table after table rebuild
Done. `rebuildTable` saves `sorter.getSortKeys()` before column rebuild and restores them after, with column-index clamping for the case where the Win Δ column is added/removed.

#### [x] Deep Analysis toggle should NOT auto-show win prob column
Done. `onToggleDeepAnalysis` no longer sets `showWinProb = true`; the "Show Win Prob Δ" button is the sole gate for column visibility.

### Right panel — All Affordable Cards

#### [x] Sortable columns
Done. `TableRowSorter` with numeric comparators per column.

#### [x] Color-coded cell values
Done. `NumericCellRenderer` green/red/neutral based on value.

#### [x] Großprojekte (GPs) included in ranking
Done. `rankPurchasableProjects` now includes unowned GPs.

#### [x] Configurable MC sim count + independent reload button
Done. `BoundedSpinner` 100–10 000 + "⟳" button; independent of win-prob toggle.

#### [ ] Win Prob Δ explanation in table header / column
Column header "Win Δ" is not self-explanatory. Add tooltip to the column header. A JTable header tooltip requires a custom `JTableHeader.getToolTipText(MouseEvent)` override. Low priority; current table-header tooltip covers all columns collectively.

#### [ ] Table sort indicator in column header
`TableRowSorter` already renders a sort arrow in the column header — this is the default Swing behavior. No additional work needed unless a custom look is desired.

---

## Future Features

### Expansion Support
- [ ] Add harbour/millionaire's row expansion cards. Architecture is ready: `ProjectLoader` is JSON-driven and `get_I` dispatches by card ID.

### Opponent Modeling
- [ ] Simulated players use the same greedy policy. Simulating different archetypes would produce more realistic win rates.

### Localisation
- [ ] Add a language switcher (DE/EN) in the setup screen. All displayed strings should be centralised in a `Strings` class with a static locale selection. Low priority.
