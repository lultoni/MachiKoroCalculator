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

### B05: Coin buy preview causes vertical jitter
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Hovering over projects causes elements below to shift. Both hovered/unhovered states render `h-4` divs, but React reconciliation between ternary branches may cause layout shifts. Also `truncate` on project names could cause text layout changes.
**Fix options:** (1) Always render placeholder div with fixed height, (2) add delay before removal, (3) always show recommended project as fallback.
**Files:** `web/src/components/CoinFlowDisplay.tsx:44-62`

### B06: Recommendation box doesn't show coin preview on hover
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Hovering inside AssistantPanel doesn't update coin flow preview. Only RankedList rows and PurchaseArea manual buy buttons trigger hover events.
**Fix:** Add hover handlers to the recommendation card area in AssistantPanel.
**Files:** `web/src/components/AssistantPanel.tsx:66-115`

### B07: No opponent coin flow display on user turn
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** When user rolls dice, only their personal coin delta is shown. `perRollDeltas` contains all players' deltas but CoinFlowDisplay only shows the active player's.
**Depends on:** B05 (fix jitter before adding more coin display UI)
**Files:** `web/src/components/CoinFlowDisplay.tsx`, `web/src/components/GameScreen.tsx:61-63`

### B08: Explanation factor bars and labels have inconsistent widths
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Category labels size based on text length, weight bars are fixed 2.5rem. Different category names cause misalignment.
**Fix:** Give category badges a fixed min-width, add percentage label to weight bar.
**Files:** `web/src/components/ExplanationFactors.tsx:47-61`

### B09: Duplicate "Win" category in MCTS v1 explanation factors
**Status:** OPEN | **Layer:** BE | **Priority:** P2
**Problem:** "winRate" category appears twice in detail view. Engine may produce duplicate factors.
**Fix:** Deduplicate factors by category before returning, or fix generation logic.
**Files:** `src/engine/MctsV1Engine.java` (factor generation)

### B11: Ranked list columns not explained
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Column headers have browser-native `title` tooltips but user may want more visible explanations.
**Fix:** Add toggle-able legend or more visible tooltip styling.
**Files:** `web/src/utils/columns.ts`, `web/src/components/RankedList.tsx`

### B12: "See all options" label is misleading
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** The list shows all engine-evaluated options (affordable and unaffordable, dimmed), but the label doesn't clarify this.
**Fix:** Clarify label text.
**Files:** `web/src/components/AssistantPanel.tsx`

### B13: ETW, win-rate, and cost columns lack color highlighting
**Status:** OPEN | **Layer:** FE+BE | **Priority:** P2
**Problem:** Columns define `colorGradient: true` and `rangeKey`, but `metricRanges` from backend doesn't include keys for standalone fields like `score`, `cost`. The `metricBgStyle` function only runs when rangeKey exists in metricRanges.
**Fix:** Add score/cost to metricRanges computation, or compute ranges client-side.
**Files:** `web/src/utils/columns.ts`, `src/server/EvaluateHandler.java:239-269`

### B14: Manual buy tab not sorted by engine ranking
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Manual buy buttons use project list order instead of engine ranking.
**Fix:** Sort manual buy options by engine ranking (match position in `options` array).
**Files:** `web/src/components/PurchaseArea.tsx:28-31`

### B15: Opponent view — ETW unclear, wrong sort order
**Status:** OPEN | **Layer:** FE+BE | **Priority:** P2
**Problem:** ETW calculation basis not explained in UI; ETW bars show furthest-from-winning at top; tempo/portfolio EV numbers not explained.
**Depends on:** B16 (insights must update first)
**Files:** `web/src/components/InsightsPanel.tsx`, `src/server/SessionInsightsHandler.java`

### ~~B16: Insights data doesn't update across rounds~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Fix:** Already resolved — `useInsights` hook includes `turnCount` (= `effectiveTurnCount`) as a dependency, which changes every turn and triggers refetch.

### ~~B17: Opponent "what they can buy" uses pre-roll coins~~ ✅ FIXED
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Fix:** Already resolved — `OpponentTurnEntry` computes `opponentCoinsAfterRoll` from `coinDeltas` and uses that for affordability filtering.

### B18: No round counter, only turn counter
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** Turn indicator shows turn count but not round count.
**Fix:** Add `Math.ceil(effectiveTurnCount / numPlayers)`.
**Files:** `web/src/components/TurnIndicator.tsx`

### B19: "New Game" button looks like two words/options
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** Header buttons all look the same, "New Game" could be mistaken for two separate links.
**Fix:** Use "New-Game" or add visual separators.
**Files:** `web/src/components/GameScreen.tsx:157-183`

### ~~B19b: Ranked list doesn't show purples and yellows~~ ✅ FIXED
**Status:** FIXED | **Layer:** BE | **Priority:** P1
**Fix:** MCTS `buildOptionsFromFullTurnTree` and legacy `buildOptions` now enrich results with unaffordable cards (non-landmarks + landmarks) not explored by the tree, using `WinProbability.computeBaselineWinProb` as the heuristic score. All 20 base-game cards now appear in the ranked list.

### B20: Clicking factor category shows no useful extra info
**Status:** OPEN | **Layer:** FE+BE | **Priority:** P2
**Problem:** Expanding a factor shows `detail` text identical to summary — no additional context.
**Fix:** Enrich detail with actual numbers, comparisons, percentile rankings.
**Files:** `web/src/components/ExplanationFactors.tsx:70-73`, engine factor generation

### B21: Undo button doesn't say what it will undo
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** Button just says "Undo" with no context about last action.
**Fix:** Add tooltip or inline text showing last turn info.
**Files:** `web/src/components/GameScreen.tsx:158-163`

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
7. **B05** → **B07** — Jitter fix, then all-player coin deltas
8. **B06** — Recommendation hover preview
9. **B13** — Color gradients for score/cost/ETW
10. **B09** — Duplicate Win category
11. **B08** — Factor bar alignment
12. **B14** — Manual buy sort order
13. **B15** — ETW sort + explanations (after B16)
14. **B11** — Column legend
15. **B20** — Factor detail enrichment

### Wave 4 — P3 Cosmetic
16. **B12** — Label clarification
17. **B18** — Round counter
18. **B19** — New Game button styling
19. **B21** — Undo context
20. **B24** — H2H per-engine iterations
21. **B25** — Engine sorting
