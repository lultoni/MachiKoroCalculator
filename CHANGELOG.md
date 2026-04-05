# CHANGELOG.md — MachiKoroCalculator

Implementation history: what was built, why, and which design decisions were made.

---

## Phase 7: Iteration

### 7.19 — Legacy Code Removal & Dead Code Cleanup

Deleted 31 legacy files: `gui/` (13 files, unused Swing UI) and `logic/` (18 files, superseded by Core/Calcs layers). Ported 2 classes still needed:
- `GameSimulator` → `calcs.GameSimulator` (MC game simulation, Boltzmann/greedy buy policy)
- `RankingOptions` → `calcs.RankingOptions` (ranking parameter POJO)

Migrated all 80+ test references from `logic.probability.*` to `core.*`/`calcs.*`. Fixed 2 bugs uncovered during migration:
- `GameSimulator.purchase()` and `GameSession.applyTurn()` used `getOwned_projects().add()` which bypasses `Player`'s landmark bitfield cache — games never detected a winner (all MC simulations timed out). Fixed to use `Player.addProject()`.
- Starter card supply test used wrong copy count (7 instead of 8 for 2-player game) because it was implicitly relying on the legacy builder which didn't subtract starters.

Removed 4 unused methods from active code: `CardIncome.singleCardEvPerRound()`, `Calcs.values_per_r_per_p()`/`getProjectColorIndex()`, `GameStateBuilder.removeProject()`, `GameSession.toSnapshot()`.

### 7.17 — Documentation Deep Clean

Cross-document accuracy audit. Condensed CHANGELOG.md from 1101 to 395 lines. Rewrote CLAUDE.md to be concise and prescriptive. Added protective Javadoc to 6 critical code paths. Fixed ARCHITECTURE.md section numbering and NORTH-STAR.md engine list.

### 7.15 — Expectimax Engine

New deterministic minimax engine (`ExpectimaxEngine`) that exhaustively evaluates the game tree to a configurable depth using exact dice probabilities at chance nodes and minimax at decision nodes. No random rollouts — all evaluation is deterministic.

**Algorithm:** Recursive expectimax with alpha-beta pruning at all decision nodes (MAX for perspective player, MIN for opponents). Turn sequence: DiceChoice → ChanceNode → FunkturmNode → BürohausNode → BuyDecision → next player.

**Correct doubles handling:** 2d6 with Freizeitpark creates 15 chance node branches — odd rolls (5 branches, never doubles), rolls 2 and 12 (always doubles), even rolls 4/6/8/10 (2 branches each: doubles vs non-doubles with exact split probabilities). This is mathematically correct, unlike the existing MCTS ChanceNode which treats all even sums as 100% doubles.

**Leaf evaluation:** Two variants configurable via `leafEval` config key:
- `"winprob"` — `WinProbability.computeBaselineWinProb()`, clamped to [0,1]
- `"composite"` — position score differential through sigmoid normalization

**Performance benchmarks (2-player initial state):**
- depth-1: 8–60ms (fast tier)
- depth-2: 1.3–1.5s (balanced tier)
- depth-3: ~17 min (impractical — no deep tier)

**Registry:** 4 entries — `expectimax-d1-winprob`, `expectimax-d1-composite` (fast), `expectimax-d2-winprob`, `expectimax-d2-composite` (balanced).

**Bug fix during implementation:** WinProbability softmax with endgame bonus could return values slightly above 1.0 due to floating-point accumulation. Clamped all expectimax scores to [0,1] to ensure instant-win (score=1.0) always ranks highest.

Engine compliance: 218 tests pass (all tiers including obvious Funkturm win).

### 7.14 — Fix Supply Count: Starter Cards Separate from Market

Starting cards (Weizenfeld, Bäckerei) are given to each player at game start **outside** the 6-copy market supply pool, per official Machi Koro rules. Previously, the codebase incorrectly counted these starter copies against the supply, showing 4 remaining instead of 6 in a 2-player game.

**Root cause:** `SupplyTracker.fromGameState()` subtracted all owned copies from 6, including starters. The same error existed in `GameSession.applyTurn()` (pool removal threshold), `GameStateBuilder.build()` (unbuilt pool construction), `MatchRunner.updateSupply()` (H2H games), and the frontend supply sidebar.

**Fix:** Added `GameState.starterCopies(cardId, numPlayers)` — returns `numPlayers` for weizenfeld/bäckerei, 0 for all other cards. All supply calculations now subtract only purchased copies (`totalOwned - starterCopies`) from the 6-copy market pool. Updated 6 source files, corrected supply tracker tests, and fixed incorrect documentation in ARCHITECTURE.md and SupplyTracker Javadoc.

This affects engine simulations (MCTS, Flat MC, Heuristic EV) — they now correctly see more Weizenfeld/Bäckerei available for purchase, which may shift early-game recommendations.

### 7.13 — Web UI Polish (Round 2)

Second round of UI improvements from gameplay testing:

1. **Column tooltips:** All 12 ranked-list columns now have `title` tooltips explaining what each metric means (Win Rate, EV/Turn, ETW, Tempo, etc.).
2. **Color gradients fixed:** Score column maps to `winRate` range via new `rangeKey` field. Cost and ETW columns now have `colorGradient: true, invertColor: true` for correct color scaling.
3. **Settings engine detail panel:** Selected engine now shows a detail card with description, tier badge, iteration count, and any extra config parameters.
4. **CoinFlowDisplay hover jitter:** Fixed layout shift when hovering purchase options by reserving a fixed `h-4` div for the project name (renders `\u00A0` when empty).
5. **Engine re-evaluation on switch:** Added `settings.engineId` to the `useEffect` dependency array that triggers `engine.evaluate()`, so changing engines in settings immediately re-evaluates.
6. **H2H per-engine iteration display:** Replaced shared iteration input with per-engine info (tier, iterations, description) and an optional override field. Effective iteration count defaults to max of both engines' registry configs.
7. **Engine API enrichment:** `/api/engines` now returns `tier`, `description`, and full `config` (iterations, extra params) per engine. Frontend `EngineRegistryEntry` type updated to match.

Skipped with explanation:
- H2H per-game progress bar (would require per-game status tracking, hurting performance for minimal UX gain)
- Engine overview/leaderboard in H2H (larger feature, better as standalone phase)
- Roll correlation identical for blue/green at start (expected — same starting cards produce same distributions)

### 7.12 — Web UI Bug Fixes

Nine UI bugs found during real gameplay testing, fixed in one batch:

1. **Settings modal overflow:** `max-w-md` was too narrow for 28 engine buttons. Changed to `max-w-2xl` with `max-h-[85vh] overflow-y-auto`.
2. **CoinFlowDisplay not updating on dice click:** `evaluate()` was called without `preRollState`, so the backend never computed `perRollDeltas`. Fixed by passing `s.state` as `preRollState`.
3. **"_wait_" showing as raw ID:** `projects.byId("_wait_")` returns undefined, falling through to raw ID. Added explicit `_wait_` handling with localized "Sparen"/"Save" text.
4. **Multiple _wait_ duplicates:** Backend could return duplicate save entries; sorting triggered React key collisions. Fixed with frontend deduplication and index-based keys.
5. **Engine metadata not visible:** `engineId`, `iterationsUsed`, `computeTimeMs` were in the API response but not displayed. Added to AssistantPanel under the win rate.
6. **Cost factor bars inverted:** Weight formula `cost/coins` made expensive cards show big bars. Inverted to `1 - cost/coins` (cheap = big bar = good). Display-only; does not affect engine ranking.
7. **Weizenfeld/Bäckerei missing from ranked list:** `inferPurchasedCard` used `List.contains()` which matched by Project.equals (ID comparison). Buying a second copy of a card already owned was invisible. Fixed with occurrence-counting comparison.
8. **Buy button sending "_wait_" as project ID:** Backend rejected `"_wait_"` as unknown project. Fixed by mapping `_wait_` to `null` in `handleBuy`.
9. **No coin flow during opponent turns:** Added live coin delta display per player when opponent dice are selected, using the `/api/roll` preview endpoint.

**Note:** The claim in 7.12 that "4 remaining is correct" was wrong — see 7.14 for the fix.

### 7.8–7.11 — Engine Compliance Suite + New Engine Types

**Engine Compliance Test Suite (7.8):** Generic `runEngineComplianceTests()` method in RuntimeTester with 3 tiers of assertions:
- **Tier 1 (Universal):** non-null result, non-empty options, `_wait_` save sentinel, sorted scores, affordable flag correctness, iterationsUsed≥0, computeTimeMs≥0, obvious Funkturm win, registry presence.
- **Tier 2 (Metrics):** 14 mandatory metric keys (winRate, confidence, visitCount, etc.), confidence ∈ [0,1] or NaN.
- **Tier 3 (Performance):** 500-iter eval < 10,000ms, registry entries exist.

New "Engine Compliance" test section auto-discovers all engines from the registry and runs the full suite — 273 assertions across 8 engine classes.

**Flat Monte Carlo Engine (7.9):** `FlatMcEngine` — the simplest possible search engine. For each purchase option, runs N complete random-rollout games (reusing `MctsRollout.simulate`) and ranks by observed win rate. No tree structure, no UCT. Budget allocation: 20% survey phase (evenly distributed), 80% focus phase (top-5 candidates). Registry: `flat-mc-fast` (500), `flat-mc-balanced` (5000), `flat-mc-deep` (20000).

**Heuristic EV Engine (7.10):** `HeuristicEvEngine` — zero-search, pure formula-based ranking. Composite score: `w_ev × evPerRound + w_roi × roiOverHorizon + w_landmark × landmarkBonus + w_tempo × tempoAdvantage + w_delta × portfolioDeltaEV + w_win × winProbDelta`. Instant decisions (<5ms). Registry: `heuristic-ev-default`.

**Static TurnPlan (7.11):** `TurnPlan.staticPlan()` factory creates pre-populated decision plans for non-MCTS engines. `navigateRoll()`/`navigateReroll()` are no-ops — all decisions set upfront. Enables flat-mc and heuristic-ev to participate in H2H matches.

Engine registry now has 28 entries across 8 engine classes. Total: 6 MCTS variants (24 entries) + 1 Flat MC (3 entries) + 1 Heuristic EV (1 entry).

### 7.6 — MCTS Engine Performance Optimization

Six implementation-level optimizations to MCTS rollout hot paths, without changing strategic behavior (same UCT, same rollout policies, same scoring):

1. **Cached unmodifiable children** (`MctsNode`): `getChildren()` caches the `Collections.unmodifiableList` wrapper instead of creating one per call.
2. **Shared `EMPTY_INT_ARRAY`** (`CardIncome`): All `new int[0]` replaced with a shared constant.
3. **Landmark bitfield** (`Player`): O(1) `hasProject()` for landmarks via `int landmarkFlags` with bits for bahnhof/einkaufszentrum/freizeitpark/funkturm. `hasWon()` becomes `landmarkCount >= 4`. Saves ~15,000 string comparisons per rollout.
4. **Pre-allocated deltas array** (`RollResolver`): New `void computeAllDeltasForRoll(state, player, roll, int[] deltas)` overload. All four rollouts allocate once before the main loop and reuse across 200+ turns.
5. **Allocation-free uniform rollout** (`MctsRollout`): Purchase and Bürohaus selection use count-then-index pattern instead of building `ArrayList<Object[]>` per turn.
6. **RolloutEvCache**: Precomputes per-card EV scores once per 20 turns instead of calling `Calcs.evPerRound()` for every card on every turn. Greedy/Boltzmann rollouts now do ~10 full EV computations per rollout instead of ~2000.

**Performance results (500 iterations, mid-game state):**

| Variant | Before | After | Speedup |
|---------|--------|-------|---------|
| v1 (uniform) | ~60ms | ~53ms | 1.1× |
| A (greedy rollout) | ~3,600ms | ~500ms | **7.2×** |
| B (Boltzmann) | ~3,600ms | ~545ms | **6.6×** |
| C (greedy tree) | ~60ms | ~50ms | 1.2× |
| D (depth-limited) | ~10ms | ~9ms | 1.1× |
| E (adaptive) | ~60ms | ~51ms | 1.2× |

---

## Phase 6: Head-to-Head Engine Testing

### 6.0 — Performance Optimizations

Three optimizations to speed up H2H simulation without changing strategic behavior:

- **Mutation-and-restore in `Calcs.evPerRound()`**: Replaced `GameState.copy()` with temporary add/remove of candidate card. ~12% speedup for Variants A/B.
- **MutableSupplyTracker**: In-place `int[]`-backed tracker for rollouts, eliminating HashMap allocation per purchase. Methods return void, use `purchase()`/`undoPurchase()`.
- **skipEnrichment flag**: When `EngineConfig.extra("skipEnrichment", "true")`, `buildResult()` skips structured factor computation and Calcs metrics — only win rates + card IDs returned. Sufficient for H2H decision extraction.

### 6.1 — Full-Turn MCTS Evaluation

`TurnPlan` — progressive decision extraction from full-turn MCTS trees. Tree rooted at `DiceChoiceNode` (if Bahnhof) or `ChanceNode`, covering all 4 decision types: dice count, Funkturm reroll, Bürohaus swap, purchase.

The match runner navigates the tree step by step as actual dice events unfold: `navigateRoll(roll)` → extract Funkturm decision → if reroll, `navigateReroll(newRoll)` → extract Bürohaus swap → extract purchase. Defensive fallback to save when MCTS exploration doesn't reach a branch.

All 6 engine variants override `evaluateFullTurn()` and `buildFullTurnTree()`. `MctsTree` gains `fullTurnRoot` field parallel to `root`.

### 6.2 — Match Runner + Result Storage

New `h2h` package:
- **MatchRunner**: Parallel game execution via ForkJoinPool. Full game loop using `evaluateFullTurn()` for all decisions. Freizeitpark bonus turns, supply tracking, Bürohaus swap execution. Turn limit (200) with softmax fallback for winner (no draws).
- **MatchConfig**: Engine IDs per seat, game count, iteration budget, max turns.
- **GameLog/TurnLog/MatchResult**: Structured per-turn logging (dice, income, purchase, win rate, eval time, coins, Bürohaus swaps).
- **H2hResultStore**: Append-only JSON persistence to `h2h-results.json`.

### 6.3 — CLI Runner

`h2h.H2hMain` with flags: `--engineA`, `--engineB`, `--games`, `--iterations`, `--maxTurns`, `--verbose`. Progress to stdout, results saved to JSON. Lists available engines with `--help`.

### 6.4 — API Endpoints

`H2hHandler` with 5 endpoints:
- `POST /api/h2h/start` — start match in background, return match ID (202 Accepted)
- `GET /api/h2h/status/{matchId}` — polling for progress (gamesCompleted / gameCount)
- `GET /api/h2h/results` — all completed matches (summary, no game logs)
- `GET /api/h2h/results/{matchId}` — full result with game logs
- `GET /api/h2h/results/{matchId}/game/{gameIndex}` — single game log

Matches run in a single-threaded background executor. Atomic game-completion tracking for progress polling.

### 6.5 — Testing Web UI

Three new React components:
- **H2hOverview**: Engine A/B dropdowns, games/iterations inputs, progress bar with polling, results table sorted newest-first. Accessible from setup screen.
- **H2hMatchDetail**: Aggregate stats cards (win rate, avg turns, avg eval), visual win rate bar, scrollable game list with winner/turns/landmarks/coins.
- **H2hGameReplay**: Turn-by-turn step-through (⏮◀▶⏭ navigation), per-turn detail: dice roll, income deltas, purchase + win rate, running coin totals, Bürohaus/Funkturm annotations. Final state comparison.

`useH2h` hook manages API integration with 1-second polling during active matches. Full DE/EN localization (35 new i18n keys).

### 6.6 — Round-Robin Tournament

- **Per-engine EngineConfig fix**: `MatchConfig.toEngineConfig()` was creating a shared config that dropped engine-specific extras (`rolloutTemperature`, `maxRolloutDepth`). Now each seat gets its own `EngineConfig` built from registry entry + match overrides via `MatchConfig.buildEvalConfig()`.
- **Mid-match seat swapping**: After half the games, P1/P2 swap seats for first-player fairness. Win attribution maps back to original indices. Toggleable via `--no-swap`.
- **Engine registry tier field**: `"tier": "fast"|"balanced"|"deep"` on each of the 24 registry entries. `EngineRegistry.getByTier()` for tournament engine selection.
- **TournamentRunner**: Generates N×(N-1)/2 unordered pairs, delegates to `MatchRunner`, aggregates into leaderboard (sorted by win rate) + H2H win-rate matrix.
- **TournamentMain CLI**: `--tier`, `--engines`, `--unleashed` (all 24), `--games`, `--no-swap`, `--estimate`, `--verbose`, `--help`. Runtime estimation based on measured per-engine-class performance baselines (adjusted for CPU parallelism).
- **Ctrl+C partial results**: JVM shutdown hook captures completed matchups on interrupt and prints the same full summary (leaderboard, H2H matrix, matchup details, notable stats).
- **Detailed tournament output**: Four-section results display — leaderboard with W/L/Win%, abbreviated H2H matrix, per-matchup detail table, notable stats (most dominant, closest, shortest/longest games, totals).
- **Tournament guide in README.md**: Preset examples (quick test, speed demons, fast tier, balanced/deep, unleashed), options reference, result interpretation, tips.
- 34 new tests in "Tournament Infrastructure" section.

---

## Phase 5: Kauf Assistent

### 5.1 — Structured Explanation Factor Data Model

New `ExplanationFactor` inner class in `EngineResult` with `category`, `weight` (0–1), `summary`, and `detail` fields. `Option` extended with `structuredFactors` (sorted by weight desc) and `summarySentence`. Backward-compatible constructor preserved for engines not yet producing structured data. 24 unit tests.

### 5.2 — Weighted Explanation Generation

Two-pass option enrichment in `MctsV1Engine.buildResult()`:
1. **Pass 1**: Build all options with metrics and raw factors (existing flow).
2. **Pass 2**: Compute cross-option means/ranges per metric, generate weighted `ExplanationFactor` entries per category.

9 factor categories: `winRate`, `income`, `synergy`, `risk`, `tempo`, `landmark`, `cost`, `coverage`, `winRate` (probability delta). Weight = `|value - mean| / range` — metrics that differentiate an option from the average get higher weight. Summary sentences generated automatically. Flat `explanationFactors` derived from structured data for backward compatibility.

`EvaluateHandler` serializes `structuredFactors` and `summarySentence` per option. 290 integration tests verify weights in [0,1], sort order, and factor completeness.

### 5.3 — Expandable Explanation Factor UI

New `ExplanationFactors.tsx` component renders structured factors with:
- Color-coded category badges (9 distinct colors)
- Weight indicator bars (proportional fill)
- Click-to-expand detail sections
- Fallback to flat string factors when structured data absent

`AssistantPanel` updated: uses `summarySentence` for top recommendation subtitle. i18n keys for all 9 categories in DE and EN.

### 5.4 — Ranked List Enhancement

4 new columns: `portfolioDeltaEV`, `winProbDelta`, `turnsToWin`, `tempoAdvantage`. Engine-adaptive filter automatically hides columns not present in engine response.

Row-expand: click any row to show that option's structured factors inline below the table via `ExplanationFactors` component.

### 5.5 — Passive-Turn Insights Panel

Backend: `SessionInsightsHandler` generates `narrative` array with typed insight entries:
- `position` — tempo advantage vs nearest opponent
- `supply` — critically low card supply warnings
- `strategy` — position-based purchase guidance
- `landmark` — proximity to win condition

Frontend: `useInsights` hook fetches on opponent turns with player-change cache invalidation. `InsightsPanel` component shows:
- ETW horizontal bars per player
- Tempo advantage + portfolio EV summary
- Supply warnings with card names
- Narrative insight cards with type-specific styling

Integrated below `OpponentTurnEntry` in `GameScreen`.

### 5.6 — Background Pre-computation

`PrecomputeCache`: single-entry thread-safe cache with daemon `ExecutorService`. Key = `(structuralHash, playerIndex, engineId)`. New request cancels in-flight computation.

`PrecomputeHandler`: `POST /api/evaluate/precompute` accepts the same request body as evaluate, returns 202 Accepted immediately.

`EvaluateHandler`: checks cache before running evaluation; adds `"cached": true/false` to response.

`GameState.structuralHash()`: deterministic hash of player coins and sorted owned card IDs.

Frontend: `useEngine.precompute()` fire-and-forget method. `GameScreen` triggers precompute after opponent turn confirmation.

---

## Phase 4: Web UI

### 4.1 — Backend Session API + Evaluate Enhancement

13 new server handlers for full session lifecycle management:
- `SessionManager` — singleton holder for active `GameSession`
- `SessionCreateHandler`, `SessionStateHandler`, `SessionTurnHandler`, `SessionBürohausHandler`, `SessionUndoHandler` — core game flow
- `SessionSaveHandler`, `SessionLoadHandler`, `SessionSavesListHandler` — persistence
- `SessionFromSnapshotHandler` — mid-game state import
- `SessionInsightsHandler` — position analysis (ETW, tempo, portfolio EV, supply warnings)
- `StaticFileHandler` — serves `web/dist/` static files
- `SessionSerializer` — canonical JSON format for session state

`EvaluateHandler` enhanced with `metricRanges` (min/max per metric for color gradients) and `perRollDeltas` (coin deltas per possible roll total for instant dice switching).

BürohausNode dedup bugfix: deduplicate own/opponent card types before building swap branches (shared by all 6 engines).

54 new tests in RuntimeTester "Session API Tests" section.

### 4.2–4.10 — React Web SPA

**Tech stack:** React 18 + TypeScript + Vite + Tailwind CSS v4.

**27 source files** organized into:
- `api/` — typed fetch wrappers + TypeScript interfaces matching Java JSON contracts
- `hooks/` — `useSession`, `useEngine`, `useRollPreview`, `useSettings`, `useProjects`, `useHover`
- `utils/` — `metricColor` (red→yellow→green gradient), `columns` (adjustable ranked list column config)
- `i18n/` — DE/EN locale strings via React Context + localStorage
- `components/` — 11 components:
  - `SetupScreen` — new game + saved games list + from-snapshot
  - `GameScreen` — 3-column dashboard with dice/coins/purchase center
  - `TurnIndicator` — active player badge + turn count + bonus turn
  - `DiceInterface` — clickable die faces with instant roll selection
  - `CoinFlowDisplay` — Now/Roll/Buy columns with live color-coded preview
  - `PurchaseArea` + `AssistantPanel` + `RankedList` — engine recommendation + manual buy + sortable metrics table
  - `OpponentTurnEntry` — simplified dice + purchase tracking for opponents
  - `BürohausModal` — card swap UI with engine-ranked recommendations
  - `SettingsScreen` — engine, language, autosave, user player
  - `SaveLoadMenu` — save/load game files

**Key design decisions:**
- Engine-adaptive UI: RankedList shows only columns whose metric keys exist in the engine response
- Color-coded metrics: each value mapped to red→yellow→green gradient using metricRanges min/max
- Instant dice switching: evaluate called once, perRollDeltas cached, frontend indexes by roll total
- Bürohaus triggers on roll total = 6 (any dice combination)

---

## Phase 3: Engine Variants + Calcs Extensions

### 3.0 — 11 Advanced Calcs Metrics (commit cb3a1d9)

Added closed-form statistical metrics to `calcs.Calcs` for richer risk and tempo analysis:

| Method | Description |
|---|---|
| `sharpeRatio(gs, p, card, riskFreeRate)` | (EV−r) / σ — reward per unit income volatility |
| `sortinoRatio(gs, p, card, target)` | (EV−target) / semiσ — penalises only downside deviation |
| `kellyFraction(gs, p, card)` | Optimal purchase fraction from discrete Kelly criterion; clamped to [0,1] |
| `valueAtRisk(gs, p, card, alpha)` | Income at alpha-quantile of the roll distribution (VaR) |
| `conditionalValueAtRisk(gs, p, card, alpha)` | Expected income in the worst alpha fraction (CVaR ≤ VaR) |
| `hhiConcentration(gs, p, card)` | Σ(income_share_r)² — feast-or-famine concentration; 1=all on one roll |
| `incomeEntropy(gs, p, card)` | −Σ P(r)·w(r)·log₂(w(r)) — roll coverage spread in bits |
| `informationGain(gs, p, card)` | |H_before − H_after| — entropy change from adding card to portfolio |
| `estimatedTurnsToWin(gs, p, card)` | deficit / evPerRound — estimated rounds to afford remaining landmarks |
| `tempoAdvantage(gs, p, card)` | ETW_best_opponent − ETW_player — turns ahead/behind nearest opponent |
| `purchaseUrgency(gs, p, card, supply)` | portfolioDeltaEV × scarcity × opponentDemand |
| `rollCorrelation(gs, p, card)` | Cov(card, portfolio) / (σ_card × σ_portfolio) — coverage gap vs. redundancy |

ETW and tempo use `ProjectLoader.getAllProjects()` to compute remaining landmark cost (landmarks are not in `getUnbuilt_projects()`; they're tracked in player `owned_projects`).

14 TDD tests added to `RuntimeTester`.

### 3.A — Variant A: Greedy Rollout Engine

`engine.MctsGreedyRolloutEngine` — Variant A of the MCTS engine. Tree phase is unchanged (full UCT via `MctsTree`). Only the rollout policy changes.

**Architecture changes:**
- `MctsTree` now accepts an optional `RolloutFn` parameter (functional interface in `engine.mcts.RolloutFn`). Default is `MctsRollout::simulate`. This makes all future rollout variants trivial to implement.
- `MctsV1Engine` changed from `final` to open (`class`), exposing a `protected buildTree(...)` factory method for subclass override.
- `MctsGreedyRolloutEngine extends MctsV1Engine`, overriding only `buildTree` to inject `GreedyRollout::simulate`.

**Greedy rollout policy** (`engine.mcts.GreedyRollout`):
- Dice count: 2d6 iff Bahnhof owned AND player has at least one 7–12 activation card.
- Funkturm: keep if current-roll income ≥ expected reroll EV; else reroll.
- Bürohaus: execute `BürohausLogic.executeSwap()` (greedy best swap).
- Purchase: landmark priority (cheapest unowned affordable); else argmax over `evPerRound × geometricSum(5, 0.95) − cost`; else save.

Registry entries: `mcts-v1-greedy-rollout-fast` (500 iter), `-balanced` (5000), `-deep` (50000).

6 TDD tests + smoke run verify the greedy engine is functionally equivalent to v1 (same contract: non-null, non-empty, save sentinel, descending scores, obvious-win landmark, registry presence).

### 3.B — Variant B: Boltzmann Rollout Engine

`engine.MctsBoltzmannRolloutEngine` — Boltzmann (softmax) purchase sampling in the rollout phase. Temperature `T` read from `config.getExtra("rolloutTemperature", "0.7")`. Passed to `buildTree()` via `ThreadLocal<Double>`.

**Rollout policy** (`engine.mcts.BoltzmannRollout`): `P_i ∝ exp(roi_i / T)`. Landmark purchase is deterministic (cheapest affordable first). Non-landmark cards are sampled proportionally to ROI. Dice/Funkturm/Bürohaus decisions same as Variant A (greedy).

Registry entries: 9 total — 3 temperatures (T=0.3, T=0.7, T=2.0) × 3 modes (fast 500, balanced 5000, deep 50000).

5 TDD tests green.

### 3.C — Variant C: Greedy Tree Engine

`engine.MctsGreedyTreeEngine` — UCT everywhere except `BuyDecisionNode` uses argmax (highest win rate) instead of UCB1. All other nodes (DiceChoiceNode, ChanceNode, FunkturmNode, BürohausNode) keep UCT. Rollout = uniform random (same as v1).

Implemented via `greedyBuySelection` boolean flag in `MctsTree`. When true, `select()` dispatches to `selectGreedyChild()` instead of `selectBestChild()` for `BuyDecisionNode` instances.

Registry entries: `mcts-v1-greedy-tree-fast/balanced/deep`.

4 TDD tests green.

### 3.D — Variant D: Depth-Limited Rollout Engine

`engine.MctsDepthLimitedEngine` — rollouts stop after `extra.maxRolloutDepth` turns (default 10) and evaluate the resulting state using `WinProbability.computeBaselineWinProb` instead of simulating to game completion. Tree phase unchanged (full UCT).

`engine.mcts.DepthLimitedRollout.withMaxDepth(n)` returns a `RolloutFn`. Shared uniform-random helpers in `MctsRollout` were made package-visible (`applyBürohausRandomPackage`, `applyPurchaseRandomPackage`, `playBonusTurnPackage`) so `DepthLimitedRollout` can reuse them without duplication.

Registry entries: `mcts-v1-depth3`, `mcts-v1-depth7`, `mcts-v1-depth10`.

8 TDD tests green.

### 3.E — Variant E: Adaptive Budget Engine

`engine.MctsAdaptiveEngine` — concentrates the iteration budget on close races rather than distributing uniformly across all candidates.

**Algorithm:**
1. Survey phase: run `iterations / 5` on the full tree.
2. Identify top-2 root children by win rate.
3. Compute margin = `wr1 − wr2`, then allocate remaining budget:
   - margin ≤ `closeMargin` (default 0.03): split evenly — both candidates need more data.
   - margin > `splitThreshold` (default 0.06): 70% to second place — confirm the leader is genuinely better.
   - otherwise: 60% to leader, 40% to second place.
4. Run focused iterations via `MctsTree.runIterationsFromNode(child, count)` (new method). Backpropagation still updates all ancestors, so root win rates remain consistent.

`MctsV1Engine.evaluate()` was refactored to extract `protected buildResult(state, playerIndex, tree, iterationsUsed, computeTimeMs)`, letting subclasses run their own iteration schedule and then delegate result formatting.

Registry entries: `mcts-v1-adaptive-fast` (500 iter), `-balanced` (5000), `-deep` (50000).

8 TDD tests green.

`RuntimeTester` now supports `--section "name"` and `--test name` CLI flags for running a subset of tests. Section matching is case-insensitive substring (`--section "Variant D"` matches `"Variant D: Depth-Limited Rollout Engine Tests"`). Multiple `--section` flags can be combined. When a filter is active, benchmarks are skipped. The results line reports skipped section count.

Example: `java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Variant D"` runs only the 8 Variant D tests in ~2 seconds instead of waiting ~4 minutes for the full suite.

---

## Pre-Restructure History (Phases 1-2, Early Work)

Phase 2 established the MCTS foundation: BuyDecisionNode, ChanceNode, DiceChoiceNode, FunkturmNode, BurohausNode, MctsRollout, MctsTree, MctsV1Engine. SupplyTracker and full UCT selection. 20 TDD tests.

Phase 1 separated the monolith into the 5-layer architecture: Core (game rules), Calcs (math), Engine interface + registry, Interface (orchestration), Server (HTTP API). 261 tests passing.

Pre-restructure code (Swing UI, RolloutTree, WinProbabilityCalc, GameSimulator, etc.) is indexed in ARCHIVE.md with commit references. See git history for full implementation details.
