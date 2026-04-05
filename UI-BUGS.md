# UI Bug Tracker

This document tracks all UI issues, their root causes, fix status, and dependencies.

## Issue Legend
- **Status**: `OPEN` | `IN_PROGRESS` | `FIXED` | `WONTFIX` | `DESIGN_QUESTION`
- **Layer**: `FE` (frontend only) | `BE` (backend only) | `FE+BE` (both) | `DESIGN` (UI/UX choice)
- **Priority**: `P0` (blocks other fixes) | `P1` (high impact) | `P2` (medium) | `P3` (low/cosmetic)

---

## Bug List

### B01: Supply panel shows wrong counts for Weizenfeld/Backerei
**Status:** OPEN | **Layer:** FE | **Priority:** P1
**Problem:** In a 2-player game, Weizenfeld and Backerei show 4 copies remaining at game start. The starter cards (1 per player) are being counted in `totalOwned` but then subtracted, yielding `6 - (2 - 2) = 6`. Wait — actually re-reading the code at `GameScreen.tsx:330-336`: `totalOwned` counts ALL owned copies (including starters). For a fresh 2-player game: totalOwned=2 (one per player), starterCopies=2, remaining = 6 - (2 - 2) = 6. That's correct (6 market copies remain).
**Re-analysis:** The user reports seeing "4" at game start. Let me re-check. If each player starts with 1 weizenfeld, totalOwned=2, starterCopies=2, remaining = 6-(2-2)=6. The user says they see 4. Possible issue: `ownedIds` may not list starter cards, meaning totalOwned=0, starterCopies=2, remaining=6-(0-2)=8. OR the backend serializes ownedIds WITHOUT starters? Need to check `GameStateSerializer` and `GameSession.create()` to see if starters are in ownedIds.
**Root Cause:** NEEDS INVESTIGATION — check what `ownedIds` contains after session creation. If starters ARE in ownedIds, remaining=6 (correct). If starters are NOT in ownedIds, remaining = 6 - (0 - 2) = 8 (wrong — too high, not 4). If the user sees 4, maybe supply is 6 and it shows `remaining/cost` format and they're reading "6 / 1c" but thinking the 4 is the count? OR the display format `{remaining} / {cost}c` is confusing (e.g., "6 / 1c" for weizenfeld). USER REPORTED "4 copies left" — need to verify exact display.
**Files:** `web/src/components/GameScreen.tsx:327-354`, `src/server/GameStateSerializer.java`
**Fix:** Verify backend serialization of ownedIds. If starters are missing, the frontend math is wrong.

### B02: Landmarks and purples show "6 available" despite uniqueness constraint
**Status:** FIXED | **Layer:** FE | **Priority:** P1
**Problem:** The supply panel filters with `!p.is_grossprojekt` (line 328), so landmarks (gelb) are already excluded from the supply. But purple cards (lila) are NOT grossprojekte — they're regular cards with a uniqueness constraint. The supply panel shows them with 6 copies, but each player can only own 1 copy of each purple card.
**Root Cause:** The supply panel doesn't distinguish purple cards from regular cards. Purple cards show up in the supply list with remaining=6, which is technically correct (there are 6 copies in the market pool) but misleading since each player can only buy one.
**Desired Behavior:** Purple and yellow cards should show ownership indicators per player (like landmark badges), not a generic supply count.
**Dependencies:** None
**Files:** `web/src/components/GameScreen.tsx:327-354`

### B03: English locale shows German project names
**Status:** OPEN | **Layer:** BE | **Priority:** P1
**Problem:** When English is selected, card names still appear in German throughout the UI.
**Root Cause:** The backend `ProjectsHandler.java:34` generates `name_de` by capitalizing the card ID (German): `Character.toUpperCase(p.getId().charAt(0)) + p.getId().substring(1)`. The `name_en` field correctly uses `p.getNameEn()` which comes from `projects.json`. However, `projects.json` HAS `name_en` for all cards. The frontend reads `proj[name_${language}]` which should work. BUT: the `ProjectsHandler` maps `id` directly as the response `id` field, and also the `name_de` is just the capitalized `id`. The `name_en` is properly set.
**Re-analysis:** The frontend accesses `proj.name_de` and `proj.name_en`. The backend sends both. The frontend pattern `proj[\`name_${settings.language}\`]` should return the English name when language='en'. This should work. BUT — need to check: does the `ProjectDef` type in types.ts match what the backend actually sends? The backend sends `name_de` and `name_en`. The type declares both. This SHOULD work... unless the backend key is wrong or the values are wrong.
**Additional check needed:** The backend maps `is_grossprojekt` → `isGrossprojekt` in the JSON but the frontend type uses `is_grossprojekt`. ALSO: backend sends `activationRolls` but frontend expects `dice_activation`. This mapping mismatch could mean `name_en` isn't populated correctly.
**FILES:** `src/server/ProjectsHandler.java:30-46`, `web/src/api/types.ts:43-55`, `web/src/hooks/useProjects.ts`
**Fix:** Check exact JSON keys sent by backend vs what frontend expects. Likely a field name mismatch on `is_grossprojekt`/`isGrossprojekt` and `dice_activation`/`activationRolls`.

### B04: Engine change doesn't update recommendation
**Status:** OPEN | **Layer:** FE | **Priority:** P1
**Problem:** When switching engine in settings, the recommendation still shows the old engine's results.
**Root Cause:** The `useEffect` at `GameScreen.tsx:72-80` watches `[s.nextPlayerIndex, s.effectiveTurnCount, settings.engineId]`. It DOES include `settings.engineId`, so changing the engine should trigger a re-evaluation. Possible issue: `settings.engineId` might not be a stable reference, or the useEffect dependencies might not catch it properly. OR the evaluate function might be returning a cached result from the precompute cache (which was run with the old engine).
**More likely:** The precompute cache on the backend (`PrecomputeCache`) is keyed on `(structuralHash, playerIndex, engineId)`, so a different engine should miss the cache. The frontend AbortController cancels in-flight requests. This should work... unless `settings.engineId` is undefined when first switching (the `SettingsScreen` calls `update({ engineId: e.id })`).
**Files:** `web/src/components/GameScreen.tsx:72-80`, `web/src/hooks/useEngine.ts`, `web/src/hooks/useSettings.ts`
**Fix:** Need to verify the useEffect actually fires on engineId change. May need to add `settings.engineId` to more deps.

### B05: Coin buy preview causes vertical jitter
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** When hovering over projects, the coin-buy-preview project name appears/disappears, causing elements below to shift. This is especially visible when hovering over the supply list.
**Root Cause:** In `CoinFlowDisplay.tsx:52-53`, when hovered, a `<div className="text-xs ... h-4">` with the project name is shown. When NOT hovered (lines 57-59), an empty `<div className="h-4" />` is rendered. Both have `h-4` so height should be stable. BUT: when `coinsAfterBuy` transitions from non-null to null (leaving a hover), the entire third column switches between the two branches of the ternary. The `h-4` div should maintain height... unless the buy column text content above it changes height.
**Better analysis:** The issue is likely that when coinsAfterRoll is null (no dice selected) AND hovered is null, the buy column shows `—` with `h-4` spacer. But when hovered becomes non-null, `coinsAfterBuy` is still null because coinsAfterRoll is null (no dice). So the preview only works AFTER dice are selected. The jitter might come from `truncate` causing text layout shifts, or from the React reconciliation between the two ternary branches.
**Fix options (user suggested 3):**
1. Always render a placeholder div with fixed height
2. Add a delay before removal
3. Always show the recommended project as fallback
**Files:** `web/src/components/CoinFlowDisplay.tsx:44-62`

### B06: Recommendation box doesn't show coin preview on hover
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Hovering inside the recommendation box (AssistantPanel) doesn't update the coin flow preview.
**Root Cause:** The `AssistantPanel` component doesn't trigger `onHover` for its top recommendation card. Only the RankedList rows (line 108-109 in RankedList.tsx) and the PurchaseArea manual buy buttons trigger hover events. The top recommendation card in AssistantPanel has no `onMouseEnter`/`onMouseLeave`.
**Files:** `web/src/components/AssistantPanel.tsx:66-115`
**Fix:** Add hover handlers to the recommendation card area in AssistantPanel.

### B07: No opponent coin flow display on user turn
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** When the user rolls dice, only their personal coin delta is shown. There's no way to see how the roll affects opponents.
**Root Cause:** `CoinFlowDisplay.tsx` only shows 3 values: Now (current coins), Roll (user's coins after roll), Buy (coins after purchase). The `perRollDeltas` data from the evaluate response DOES contain per-player deltas for all players, but the CoinFlowDisplay only uses the active player's delta.
**User note:** The opponent turn view already has a grid showing all players' coin deltas (OpponentTurnEntry.tsx:83-98). The user wants something similar for the user's own turn.
**Files:** `web/src/components/CoinFlowDisplay.tsx`, `web/src/components/GameScreen.tsx:61-63`
**Dependencies:** B05 (jitter) should be fixed first since this adds more UI to the coin area

### B08: Explanation factor bars and labels have inconsistent widths
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** In the recommendation detail and explanation tabs, category labels and weight bars don't have the same width, making it hard to compare across categories.
**Root Cause:** In `ExplanationFactors.tsx:48`, the category badge uses `shrink-0 px-1.5 py-0.5` which sizes based on text length. The weight bar uses `shrink-0 w-10` (fixed 2.5rem). Different category names have different text lengths, causing misalignment.
**Also:** No "top X%" indicator on bars.
**Files:** `web/src/components/ExplanationFactors.tsx:47-61`
**Fix:** Give category badges a fixed min-width, and add a percentage label to the weight bar.

### B09: Duplicate "Win" category in MCTS v1 explanation factors
**Status:** OPEN | **Layer:** BE | **Priority:** P2
**Problem:** The MCTS v1 engine shows the "winRate" category twice in the detail view, but not in the recommendation tab.
**Root Cause:** The backend generates structured factors in `EngineResult`, and the MCTS v1 engine may produce duplicate `winRate` factors if both the raw win rate metric and a win-rate-derived factor are included. Need to check the factor generation logic in the engine.
**Files:** `src/engine/MctsV1Engine.java` (factor generation)
**Fix:** Deduplicate factors by category before returning, or fix the generation logic.

### B10: What are the 9 factor categories and why these?
**Status:** DESIGN_QUESTION | **Layer:** N/A | **Priority:** P3
**Problem:** User asks why winRate, income, synergy, risk, tempo, landmark, cost, coverage, scarcity were chosen.
**Answer:** These categories map to the metrics in `calcs.Calcs`: winRate (MCTS score), income (evPerRound), synergy (portfolioDeltaEV), risk (variance/Sharpe), tempo (tempoAdvantage/ETW), landmark (win proximity), cost (ROI), coverage (dice roll coverage/entropy), scarcity (supply pressure/HHI). They represent the key dimensions of a purchase decision.
**No code change needed** — but better tooltips in the UI would help.

### B11: Ranked list columns not explained
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Column headers in the ranked list table don't explain what they mean.
**Root Cause:** The `columns.ts` already has `tooltip` fields for all columns. `RankedList.tsx:82` sets `title={col.tooltip}` on `<th>` elements. So browser-native tooltips ARE present. The user may not have noticed them, or wants more visible explanations.
**Fix:** The tooltips exist. Could add a toggle-able legend or more visible tooltip styling.

### B12: Ranked list only shows affordable options but has "affordable" column
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** The "see all options" list only shows affordable cards, making the "affordable" column redundant.
**Root Cause:** Actually, looking at the code: `RankedList` receives `options` directly from the engine result, which includes ALL ranked options (affordable and unaffordable). The `affordable` field is per-option. The list DOES show all options — but marks unaffordable ones with `opacity-50`. The "see all options" text doesn't clarify this.
**Re-analysis:** The engine result includes `_wait_` and all evaluated cards. Unaffordable ones have `affordable: false` and are dimmed. The user may be confused because landmarks and purples seem missing (see B19).
**Fix:** Clarify the "see all options" label text.

### B13: ETW, win-rate, and cost columns lack color highlighting
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** In the ranked list, some columns (ETW, win-rate, cost) don't have color gradient backgrounds.
**Root Cause:** Looking at `columns.ts`: `score` (win rate) has `colorGradient: true, rangeKey: 'winRate'`. `cost` has `colorGradient: true, invertColor: true`. `turnsToWin` (ETW) has `colorGradient: true, invertColor: true`. These all have gradients defined. The issue is likely that `metricRanges` from the backend doesn't include keys for `winRate`, `cost`, or `turnsToWin`. The `metricRanges` is computed from `opt.metrics` which may not include those keys. Score/cost are separate fields, not in `metrics`.
**Root cause confirmed:** `score` uses `rangeKey: 'winRate'` but `winRate` may not be in `metricRanges` if the engine doesn't put it in `metrics`. `cost` has no `rangeKey` so it defaults to `'cost'` which is also not in metrics. The `metricBgStyle` function only runs when the rangeKey exists in metricRanges.
**Files:** `web/src/utils/columns.ts`, `src/server/EvaluateHandler.java:239-269`
**Fix:** Either add score/cost to metricRanges computation, or compute ranges client-side for these built-in fields.

### B14: Manual buy tab not sorted by engine ranking
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Manual buy buttons are sorted by project list order, not by engine recommendation.
**Root Cause:** `PurchaseArea.tsx:29-31` filters `projects.projects` by affordability. This uses the original project list order, not the engine's ranked order.
**Files:** `web/src/components/PurchaseArea.tsx:28-31`
**Fix:** Sort manual buy options by engine ranking (match position in `options` array).

### B15: Opponent view — ETW unclear, wrong sort order
**Status:** OPEN | **Layer:** FE+BE | **Priority:** P2
**Problem:** Multiple issues with the opponent/insights view:
1. ETW calculation basis not clear in UI
2. ETW bars show furthest-from-winning at top (should be closest-to-winning first)
3. Tempo advantage and portfolio EV numbers not explained
**Root Cause:**
- ETW is computed as `(landmarkCostRemaining - currentCoins) / evPerRound` (backend `SessionInsightsHandler.java:152-168`). This isn't explained in the UI.
- The ETW bars are rendered in player index order (`playerInsights.map`), not sorted by ETW value.
- Tempo and portfolio EV have labels but no tooltips or explanations.
**Files:** `web/src/components/InsightsPanel.tsx`, `src/server/SessionInsightsHandler.java`

### B16: Insights data doesn't update across rounds
**Status:** OPEN | **Layer:** FE | **Priority:** P1
**Problem:** The opponent view numbers stay the same through multiple rounds.
**Root Cause:** `useInsights.ts:15` caches data and only refetches when `playerIndex` changes: `if (lastPlayerRef.current === playerIndex && data != null) return;`. In a 2-player game, opponent turns always have the same playerIndex, so insights are fetched once and never updated.
**Files:** `web/src/hooks/useInsights.ts:12-24`
**Fix:** Add `effectiveTurnCount` or a similar changing dependency to force refetch.

### B17: Opponent "what they can buy" uses pre-roll coins
**Status:** OPEN | **Layer:** FE | **Priority:** P1
**Problem:** The opponent purchase dropdown shows cards affordable with their CURRENT coins, not coins AFTER the roll.
**Root Cause:** `OpponentTurnEntry.tsx:65` filters affordable cards using `coinsAvailable` prop, which is `activePlayer.coins` (passed from GameScreen.tsx:299). The component does have `coinDeltas` state from the roll preview API, but doesn't use it to adjust affordability.
**Files:** `web/src/components/OpponentTurnEntry.tsx:65`, `web/src/components/GameScreen.tsx:299`
**Fix:** Use `coinsAvailable + coinDeltas[activePlayerIndex]` for filtering affordable cards.

### B18: No round counter, only turn counter
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** The turn indicator shows turn count but not round count.
**Root Cause:** `TurnIndicator.tsx:47` shows `effectiveTurnCount`. A "round" would be `Math.floor(effectiveTurnCount / numPlayers) + 1`.
**Files:** `web/src/components/TurnIndicator.tsx`
**Fix:** Add round counter: `Math.ceil(effectiveTurnCount / numPlayers)`.

### B19: "New Game" button looks like two words/options
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** The header buttons (Undo, Save, Settings, New Game) all look the same, and "New Game" could look like two separate links.
**Root Cause:** All buttons use the same `text-sm text-machi-text-dim` styling with no visual separators.
**Files:** `web/src/components/GameScreen.tsx:157-183`
**Fix:** Use "New-Game" or add visual separators/grouping between buttons.

### B20: Clicking factor category in recommendation shows no useful info
**Status:** OPEN | **Layer:** FE | **Priority:** P2
**Problem:** Expanding a factor category in the recommendation tab shows only the `detail` text, which is the same as the summary with no additional context.
**Root Cause:** The `detail` field in `ExplanationFactor` is generated by the engine backend. If it's empty or identical to summary, expanding shows nothing new.
**Files:** `web/src/components/ExplanationFactors.tsx:70-73`, engine factor generation code
**Fix:** Enrich the detail text with actual numbers, comparisons, percentile rankings.

### B19b: Ranked list doesn't show purples and yellows
**Status:** OPEN | **Layer:** BE | **Priority:** P1
**Problem:** The "see all options" table doesn't include purple and yellow (landmark) cards.
**Root Cause:** The engine evaluation only considers cards that are affordable AND in the supply pool. Purple cards have uniqueness constraints and landmarks are always available but expensive. If the engine doesn't evaluate them as options, they won't appear.
**Files:** Engine evaluation logic (multiple files)
**Fix:** Check engine evaluation to see if it includes landmarks and purples in the candidate set.

### B21: Undo button doesn't say what it will undo
**Status:** OPEN | **Layer:** FE | **Priority:** P3
**Problem:** The undo button gives no indication of what action will be undone.
**Root Cause:** The button just says "Undo" with no context. The `history` array contains the last turn record with player name, roll, and purchase.
**Files:** `web/src/components/GameScreen.tsx:158-163`
**Fix:** Add tooltip or inline text showing last turn info. Also consider a turn history sidebar.

### B22: Save button wipes the UI
**Status:** OPEN | **Layer:** FE | **Priority:** P0
**Problem:** Clicking save causes the UI to go blank — the entire page content disappears.
**Root Cause:** The save handler `useSession.ts:67-69` calls `wrap()` which sets loading=true, then returns `result.path`. If the API fails or returns unexpected data, the error could propagate and crash the React tree. Also: the `clearSession()` function sets `session=null` — if save somehow triggers this, the UI would show the setup screen (or blank if there's a rendering issue).
**More investigation needed:** The `SaveLoadMenu` calls `onSave`, which is `session.save`. If save throws, the error is caught by `wrap` and re-thrown. The `handleSave` in `SaveLoadMenu.tsx:25-29` doesn't have a try/catch. An unhandled promise rejection could crash React.
**Files:** `web/src/hooks/useSession.ts:67-69`, `web/src/components/SaveLoadMenu.tsx:25-29`

### B23: Saved games not visible / auto-save not working
**Status:** OPEN | **Layer:** FE+BE | **Priority:** P1
**Problem:** The saved games list shows no games even with autosave on, and clicking "saved games" on the start screen also shows empty.
**Root Cause:** Need to check: (1) where save files are stored, (2) if auto-save is actually implemented beyond the toggle, (3) if the saves list API returns the right data.
**Files:** `src/server/SessionSaveHandler.java`, `src/server/SessionSavesListHandler.java`

### B24: H2H only has one iterations field for two engines
**Status:** OPEN | **Layer:** DESIGN | **Priority:** P3
**Problem:** The H2H setup has a single "iterations/eval" field that applies to both engines.
**User note:** Each engine should be testable with its own iteration count. Also suggests a custom engine configuration screen.
**Files:** `web/src/components/H2hOverview.tsx`

### B25: Engine selection in settings not sorted by performance
**Status:** DESIGN_QUESTION | **Layer:** FE | **Priority:** P3
**Problem:** Engines are grouped by class but not sorted by performance/tier.
**Files:** `web/src/components/SettingsScreen.tsx`

### B26: ETW/Tempo calcs assume all players can roll 2d6
**Status:** CONFIRMED | **Layer:** BE | **Priority:** P1
**Problem:** `CardIncome.playerEvPerRound()` (used by `Calcs.estimatedTurnsToWinForPlayer()` for opponents in tempo calculations) always takes `max(1d6_ev, 2d6_ev)` regardless of whether the player owns Bahnhof. This means:
- Opponents without Bahnhof appear to be progressing toward landmarks faster than they actually are
- Tempo advantage is underestimated (leading player looks less ahead)
- Cards reachable only by 2d6 get inflated ETW scores when the player can't actually roll 2d6
**Root Cause:** Two separate EV functions with different semantics:
- `Calcs.evPerRound(gs, playerIndex, candidate)` — CORRECT, checks `hasProject("bahnhof")`
- `CardIncome.playerEvPerRound(player, numPlayers, oppCoins)` — BROKEN, always assumes max(1d6, 2d6)
The broken one is used in `estimatedTurnsToWinForPlayer()` which feeds into `tempoAdvantage()`.
**Impact:** Affects depth-limited MCTS heuristic evaluation, tempo metrics, and all insights that show ETW.
**Note:** This is the bias the user suspected in MCTS v1 depth 3 — the depth-limited engine uses `WinProbability` heuristic which calls these calcs. If tempo is wrong, the heuristic evaluation at depth cutoff is biased.
**Files:** `src/core/CardIncome.java:342-375`, `src/calcs/Calcs.java:1019-1035`, `src/engine/MctsV1Engine.java:848-871`
**Fix:** Pass Bahnhof ownership into `playerEvPerRound()` and use `bestDiceEV()` pattern, OR refactor `estimatedTurnsToWinForPlayer()` to use the correct `evPerRound()` method.

---

## Dependency Graph

```
B22 (save wipes UI) → blocks nothing but is P0, fix first
B03 (EN names) → blocks B11 (column explanations may need EN too)
B01 (supply counts) → relates to B02 (supply for purples/yellows)
B16 (insights not updating) → blocks accurate B15 (ETW display)
B17 (opponent coins) → independent
B05 (jitter) → should fix before B07 (more coin display)
B04 (engine switch) → independent
B06 (recommendation hover) → independent
B13 (color gradients) → independent
B09 (duplicate Win) → independent
B14 (manual buy sort) → independent
```

## Fix Order (proposed)

### Wave 1 — Critical / P0
1. **B22** — Save wipes UI (P0)
2. **B03** — English card names (P1, likely simple backend fix)
3. **B04** — Engine switch not updating (P1)

### Wave 2 — High Impact
4. **B16** — Insights not updating (P1)
5. **B17** — Opponent buy uses pre-roll coins (P1)
6. **B01** — Supply panel counts (P1, needs investigation)
7. **B23** — Save/load not working (P1)

### Wave 3 — Medium
8. **B05** — Coin preview jitter
9. **B06** — Recommendation hover preview
10. **B07** — All-player coin deltas on user turn
11. **B13** — Color gradient for score/cost/ETW
12. **B09** — Duplicate Win category
13. **B08** — Factor bar/label alignment
14. **B14** — Manual buy sort order
15. **B02** — Purple/yellow supply display
16. **B15** — ETW sort + explanations

### Wave 4 — Cosmetic / Design
17. **B12** — "See all options" label clarification
18. **B18** — Round counter
19. **B19** — New Game button styling
20. **B20** — Factor detail content enrichment
21. **B19b** — Purples/yellows in ranked list
22. **B21** — Undo context + turn history
23. **B11** — Column legend
24. **B24** — H2H per-engine iterations
25. **B25** — Engine sorting

---

## Fix Log

| Date | Bug | Fix Attempted | Result | Notes |
|------|-----|---------------|--------|-------|
| 2026-04-05 | B22 | Fixed backend: saves list returns array directly, save returns {path} | FIXED | Also added try/catch in SaveLoadMenu |
| 2026-04-05 | B03 | Fixed backend: is_grossprojekt + dice_activation field names; synced locale on startup | FIXED | Was desync between machi-locale and machi-settings keys |
| 2026-04-05 | B19 (partial) | Renamed Save button to "Save / Load" | FIXED | Both DE and EN |
| 2026-04-05 | NEW | Fixed autosave toggle CSS dot position | FIXED | Used left positioning instead of translate-x |
| 2026-04-05 | NEW | Saved games count shows 0 before dropdown is opened on SetupScreen | NOTED | Low priority, loading timing issue |
| 2026-04-05 | B02 | Redesigned supply panel: purples show per-player ownership badges; landmarks show per-player ownership badges; regular cards still show remaining/cost | FIXED | Separated into 3 sections: Market Supply, Special Buildings, Landmarks |
| 2026-04-05 | NEW | Purple cards can be bought when already owned (manual buy + opponent buy) | FIXED | Added ownedIds prop filtering to PurchaseArea and OpponentTurnEntry |
| 2026-04-05 | B04 (partial) | Clear rankedOptions on engine switch to remove stale fallback text | FIXED | Preserves perRollDeltas for manual buy during loading |
| 2026-04-05 | NEW | Ranked list affordable flag now updates dynamically with dice selection | PENDING-VERIFY | coinsAfterRoll passed to AssistantPanel → liveOptions override |
| 2026-04-05 | NEW | Landmarks now appear in manual buy list (were excluded by is_grossprojekt filter) | PENDING-VERIFY | Only shows unowned landmarks that are affordable |
| 2026-04-05 | B02 (restyle) | Supply panel purple/yellow headers changed to default white; compact single-line layout | FIXED | Player badges between name and cost, no checkmarks, just initial letter with highlight |
| 2026-04-05 | NEW | Ranked list showed unaffordable cards dimmed instead of filtering them out | FIXED | AssistantPanel filters to affordable-only before passing to RankedList; removed Affordable column |
| 2026-04-05 | NEW | Ranked list dynamically updates card set per dice selection | FIXED | liveOptions recomputes affordable flag from coinsAfterRoll, then filters |
