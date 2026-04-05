# UI Bug Tracker

## Legend
- **Status**: `OPEN` | `CONFIRMED` | `DESIGN_QUESTION`
- **Layer**: `FE` | `BE` | `FE+BE` | `DESIGN`
- **Priority**: `P0` (blocks other fixes) | `P1` (high impact) | `P2` (medium) | `P3` (low/cosmetic)

---

## Open Bugs

### ~~B01: Supply panel shows wrong counts for Weizenfeld/Backerei~~ CANNOT REPRODUCE
**Status:** CLOSED | **Layer:** FE | **Priority:** P1
**Investigation:** Both backend (`GameStateBuilder.build()`, `GameState.starterCopies()`) and frontend (`GameScreen.tsx:348-353`) correctly subtract starter copies from the total owned count. Formula: `remaining = 6 - (totalOwned - starterCopies)`. In a 2-player game at start, this yields 6 for both weizenfeld and bäckerei. Unable to reproduce the reported "4" value.

### ~~B05: Coin buy preview causes vertical jitter~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** Already resolved — all columns use `h-4` placeholder divs for fixed height. Both hovered/unhovered states render same-size elements.

### ~~B06: Recommendation box doesn't show coin preview on hover~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** Already resolved — recommendation card wrapper and RankedList rows both have `onMouseEnter`/`onMouseLeave` handlers.
**Files:** `web/src/components/AssistantPanel.tsx:66-115`

### ~~B07: No opponent coin flow display on user turn~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** Added opponent coin delta grid below CoinFlowDisplay on user's turn when a roll is selected. Shows each opponent's coin delta from the roll.

### ~~B08: Explanation factor bars and labels have inconsistent widths~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** Added `min-w-16 text-center` to category badges for consistent width. Added percentage label next to weight bar.

### ~~B09: Duplicate "Win" category in MCTS v1 explanation factors~~ ✅ FIXED
**Status:** FIXED | **Layer:** BE | **Priority:** P2
**Fix:** Factor #8 (winProbDelta) was using category `"winRate"`, duplicating factor #1. Folded winProbDelta into factor #1's detail text instead.

### ~~B11: Ranked list columns not explained~~ LOW PRIORITY
**Status:** DEFERRED | **Layer:** FE | **Priority:** P2
**Note:** Column headers already have `title` tooltips with descriptions. A custom tooltip component would be a nice-to-have but not blocking.

### ~~B12: "See all options" label is misleading~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P3
**Fix:** Now accurate — the ranked list shows all options including unaffordable cards (dimmed with `opacity-40`).

### ~~B13: ETW, win-rate, and cost columns lack color highlighting~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** `RankedList.extendedRanges` now computes client-side ranges for ALL numeric metrics from `options[0].metrics`, not just `winRate` and `cost`. All `colorGradient: true` columns now get gradient backgrounds.

### ~~B14: Manual buy tab not sorted by engine ranking~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** `PurchaseArea` affordable list now sorted by position in engine `options` array.

### ~~B15: Opponent view — ETW unclear, wrong sort order~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P2
**Fix:** ETW bars now sorted ascending (closest to winning first). Added tooltips explaining ETW, tempo, and portfolio EV calculations.

### ~~B16: Insights data doesn't update across rounds~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Fix:** Already resolved — `useInsights` hook includes `turnCount` (= `effectiveTurnCount`) as a dependency, which changes every turn and triggers refetch.

### ~~B17: Opponent "what they can buy" uses pre-roll coins~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Fix:** Already resolved — `OpponentTurnEntry` computes `opponentCoinsAfterRoll` from `coinDeltas` and uses that for affordability filtering.

### ~~B18: No round counter, only turn counter~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P3
**Fix:** Added round counter next to turn count in TurnIndicator: `Math.ceil(effectiveTurnCount / numPlayers)`.

### ~~B19: "New Game" button looks like two words/options~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P3
**Fix:** Added vertical divider (`w-px h-4 bg-machi-border`) before New Game button.

### ~~B19b: Ranked list doesn't show purples and yellows~~ ✅ FIXED
**Status:** FIXED | **Layer:** BE | **Priority:** P1
**Fix:** MCTS `buildOptionsFromFullTurnTree` and legacy `buildOptions` now enrich results with unaffordable cards (non-landmarks + landmarks) not explored by the tree, using `WinProbability.computeBaselineWinProb` as the heuristic score. All 20 base-game cards now appear in the ranked list.

### ~~B20: Clicking factor category shows no useful extra info~~ LOW PRIORITY
**Status:** DEFERRED | **Layer:** FE+BE | **Priority:** P2
**Note:** Factor detail text now includes numeric breakdowns (rollout count, EV, variance, P(no income), tempo, etc.) after B09 fix. Further enrichment with percentile rankings deferred.

### ~~B21: Undo button doesn't say what it will undo~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P3
**Fix:** Added tooltip showing last turn info: "{player}: {roll} → {card bought or saved}".

### ~~B23: Saved games not visible / auto-save not working~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Fix:** Autosave was unimplemented — the settings toggle existed but no code triggered saves. Added `useEffect` in `GameScreen` that fires `session.save()` when `effectiveTurnCount` changes and `settings.autosave` is enabled. Errors silently suppressed.

### B24: H2H only has one iterations field for two engines
**Status:** OPEN | **Layer:** DESIGN | **Priority:** P3
**Problem:** Single "iterations/eval" field applies to both engines.
**Files:** `web/src/components/H2hOverview.tsx`

### B25: Engine selection in settings not sorted by performance
**Status:** DESIGN_QUESTION | **Layer:** FE | **Priority:** P3
**Files:** `web/src/components/SettingsScreen.tsx`


---

## Dependency Graph

```
B05 (jitter) → B07 (more coin display)
B16 (insights update) → B15 (ETW display)
```

## Priority Waves

### Wave 1 — P1 Backend Correctness
1. ~~**B19b** — Purples/yellows missing from ranked list~~ ✅ FIXED

### Wave 2 — P1 Frontend
3. ~~**B16** — Insights not updating~~ ✅ FIXED
4. ~~**B17** — Opponent buy uses pre-roll coins~~ ✅ FIXED
5. ~~**B01** — Supply panel counts~~ CANNOT REPRODUCE
6. ~~**B23** — Save/load not working~~ ✅ FIXED

### Wave 3 — P2 Polish
7. ~~**B05** → **B07** — Jitter fix, then all-player coin deltas~~ ✅ FIXED
8. ~~**B06** — Recommendation hover preview~~ ✅ FIXED
9. ~~**B13** — Color gradients for score/cost/ETW~~ ✅ FIXED
10. ~~**B09** — Duplicate Win category~~ ✅ FIXED
11. ~~**B08** — Factor bar alignment~~ ✅ FIXED
12. ~~**B14** — Manual buy sort order~~ ✅ FIXED
13. **B15** — ETW sort + explanations (after B16)
14. **B11** — Column legend
15. **B20** — Factor detail enrichment

### Wave 4 — P3 Cosmetic
16. ~~**B12** — Label clarification~~ ✅ FIXED
17. ~~**B18** — Round counter~~ ✅ FIXED
18. ~~**B19** — New Game button styling~~ ✅ FIXED
19. ~~**B21** — Undo context~~ ✅ FIXED
20. **B24** — H2H per-engine iterations
21. **B25** — Engine sorting
