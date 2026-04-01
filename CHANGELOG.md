# CHANGELOG.md — MachiKoroCalculator

Implementation history: what was built, why, and which design decisions were made.

---

## Phase 7: Iteration

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



**The engine is now live.** `POST /api/evaluate` returns ranked purchase options from a full UCT tree search.

### Core bug fix: Bürohaus purple-card swap scope

`core.BürohausLogic.findCandidates()` was allowing purple (lila) cards to be swapped. The official rule says "any non-landmark establishment," but purple cards are unique per player — swapping one would give a second copy of a unique card, violating the uniqueness constraint. Both `core/BürohausLogic` and the legacy `logic.probability/BürohausLogic` now exclude purple cards from both the own-card and opponent-card candidate lists.

### SupplyTracker

`engine.mcts.SupplyTracker` — immutable value object tracking how many market copies of each non-landmark card remain. Built from a `GameState` at evaluation start by counting owned copies across all players and subtracting from `GameState.SUPPLY_PER_CARD`. Exposes `getCount(id)`, `canPurchase(id)`, and `withPurchase(id)` (returns a new instance — the original is unchanged). Travels alongside each `GameState.copy()` through the MCTS tree.

### MCTS Tree Node Types (6 classes in `engine.mcts/`)

All 6 node types extend the abstract `MctsNode` base (UCB1 scoring, backpropagation, terminal detection):

- **`DiceChoiceNode`** — Bahnhof 1d6/2d6 decision. Present at start of active player's turn iff they own Bahnhof on that branch. Two children: ChanceNode(1d6) and ChanceNode(2d6).
- **`ChanceNode`** — dice outcome branching. 1d6: 6 children (rolls 1–6). 2d6: 11 children (rolls 2–12). Each child has roll income applied. Detects doubles (even 2d6 sums) for Freizeitpark bonus turn insertion. Hooks up Funkturm, Bürohaus, and bonus-turn nodes per branch based on what the active player owns on that specific path.
- **`FunkturmNode`** — keep or reroll decision (UCT). Keep branch → pre-built afterKeepNode (re-parented). Reroll branch → new ChanceNode from pre-roll state. Both explored via UCT, not greedy.
- **`BürohausNode`** — all valid (ownCard × oppCard) swap pairs + no-swap, all via UCT. Valid pairs: non-landmark, non-purple on both sides. On roll 6 only.
- **`BuyDecisionNode`** — all affordable non-landmark cards (supply > 0, coins ≥ cost) + affordable landmarks + save sentinel, all via UCT. After purchase, transitions to next player's DiceChoiceNode or ChanceNode. Handles win-condition detection (terminal node if purchase wins the game).

**Landmark tracking per branch:** which special nodes appear depends on the `GameState` stored in that node (reflecting purchases along that specific path), not the root state.

### MctsRollout

`engine.mcts.MctsRollout` — uniform-random full-game simulation from a leaf node. All decisions are fully random: 50/50 dice count (Bahnhof), uniform roll, 50/50 Funkturm keep/reroll, uniform selection over all valid Bürohaus swap pairs + no-swap, uniform purchase over affordable cards + save. Freizeitpark doubles trigger one bonus turn per main turn (no chaining). Falls back to `WinProbability.computeBaselineWinProb` after 200 turns.

### MctsTree

`engine.mcts.MctsTree` — UCT iteration loop: select (walk via UCB1 to a node with unvisited children or unexpanded leaf) → expand → pick first unvisited child → rollout → backpropagate. Score perspective is always the root `playerIndex` throughout the tree (1.0 win, 0.0 loss, fractional softmax on timeout).

### MctsV1Engine

`engine.MctsV1Engine` — full `SimulationEngine` implementation:
- Builds `SupplyTracker`, constructs `MctsTree`, runs iterations or time budget
- Infers which card was purchased per root child by state diff (owned list comparison)
- Sorts options by win rate descending; tie-breaking: non-save options above save on equal score (buying the winning card is preferred over saving when both are equivalent)
- Populates all 14 required metric keys: `winRate`, `confidence`, `visitCount`, `immediateEV`, `evPerRound`, `roiOverHorizon`, `winProbDelta`, `portfolioDeltaEV`, `variance`, `probNoIncomeOwnTurn`, `probNoIncomeRound`, `cost`, `turnsToWin`, `tempoAdvantage`
- Explanation factors ordered by impact: win rate, immediate EV, EV/round, ROI, win-prob delta, portfolio delta, variance, risk probabilities, cost, color, activation rolls, landmark annotations, Einkaufszentrum synergy, Bürohaus swap note, ETW, tempo

### ServerMain

`server.ServerMain` — entry point: registers `MctsV1Engine` with `EngineOrchestrator`, starts `ApiServer` on port 8080. `POST /api/evaluate` endpoint is now live.

### Tests

20 new TDD tests added to `RuntimeTester` (written before implementation):
- 3 Bürohaus swap scope tests (purple exclusion for own cards, opponent cards, valid non-purple swaps)
- 3 SupplyTracker tests (initial state, decrement, exhaustion)
- 14 MctsV1Engine contract tests (non-null result, non-empty options, save sentinel, score ordering, affordable flags, all metric keys, time budget, obvious-win landmark recommendation, Bürohaus/Funkturm/Freizeitpark branch expansion, deep > fast iterations, confidence range, visit count sum)



After reaching feature-completeness on the original monolithic design (Stufe 1/2/3 hybrid system, Swing UI with 5 ranking tabs, assistant, rollout), the project underwent a fundamental re-evaluation of its goals and architecture.

**The problem:** The codebase had accumulated complexity — multiple overlapping analysis approaches (analytical EV, Monte Carlo, Expectimax rollout), a cluttered Swing UI with too many competing features, and no clean separation between game rules and strategy. The core question ("What should I buy right now, and why?") was being lost in the noise.

**The decision:** Complete restructure around a clear North Star vision document (`NORTH-STAR.md`) that defines:
- **5-layer architecture**: Core (game rules) / Standard Calcs (shared math) / Simulation Engines (pluggable strategy) / Interface (orchestration) / UI (web SPA)
- **MCTS as the primary strategy engine** with proper tree search (chance nodes + decision nodes for all players), replacing the previous hybrid of analytical ranking + Expectimax + adaptive MC
- **Web SPA frontend** replacing Swing, with exactly 4 core UI components (Turn Indicator, Dice, Coin Flow, Purchase Decision)
- **Pluggable engine interface** with a JSON registry, enabling head-to-head testing of different strategy versions
- **Transparent Kauf Assistent** with structured explanations (summary + weighted factor bullets with expandable details)

**What survives the purge:** Core data model (`Project`, `Player`, `GameState`, etc.), game rules (`get_I`, `computeAllDeltasForRoll`, income order), `ProjectLoader`, `GameSession` + persistence, `Strings` localization, `projects.json`.

**What gets replaced:** All strategy/ranking code (`RolloutTree`, `WinProbabilityCalc`, `adaptiveMCRefinement`, `rankPurchasableProjects`, `GameSimulator`), all Swing UI code (`gui.newui/*`), assistant config and phase calibration systems.

See `NORTH-STAR.md` for the full specification. See `PLAN.md` for the phased implementation backlog. See `ARCHIVE.md` for an index of purged concepts with commit references.

---

## Phase 1 Task 1.7: HTTP API Server (commit 283d798)

**`server/` package** — local HTTP API using the built-in JDK `com.sun.net.httpserver.HttpServer`, zero new dependencies.

Endpoints:
- `GET  /api/health`   — liveness check (`{"status":"ok"}`)
- `GET  /api/projects` — all 19 base-game cards as JSON array
- `GET  /api/engines`  — all registered engine configurations from `engines.json`
- `POST /api/roll`     — apply a dice roll to a game state; returns `coinDeltas` + `stateAfter`
- `POST /api/evaluate` — run engine evaluation; returns ranked purchase options (returns HTTP 503 with descriptive message until MCTS is implemented in Phase 2)

Supporting infrastructure:
- `ApiUtils` — shared JSON/HTTP helpers: `sendJson()`, `sendError()`, `handleCors()`, `parseBody()`; sets `Access-Control-Allow-Origin: *` on all responses
- `GameStateSerializer` — `toJson(GameState)` / `fromJson(JsonObject)` using `GameStateBuilder`; validates all project IDs

Test suite extended to 261 passing tests (was 247); 5 new server integration tests start a live server on port 18080 and hit each endpoint.

---

## Phase 1 Tasks 1.5–1.6 + 1.8: Interface Layer + Engine Registry + Test Suite (commit 3ede567)

**`iface/` package** — orchestration layer between UI and engines:
- `EngineRegistryEntry` — immutable record: `id`, `engineClass`, `description`, `isDefault`, `config` (built from registry JSON)
- `EngineRegistry` — loads `engines.json` from classpath; singleton cache; `getAll()`, `getDefault()`, `findById()`, `reload()`
- `EngineOrchestrator` — routes `evaluate()` calls to registered `SimulationEngine` instances; `register()`, `evaluate(state, playerIndex, entry)`, `evaluateDefault()`

**`engines.json`** — flat engine registry with 3 entries: `mcts-v1-fast` (500 iter), `mcts-v1-balanced` (5000 iter, default), `mcts-v1-deep` (50000 iter). `engineClass = "mcts-v1"` matches the future `SimulationEngine.id()` value.

**Test suite adaptation** (`RuntimeTester`):
- Added 11 Calcs layer tests (P1/P2 sums, get_I, immediateEV, evPerRound, roiOverHorizon, baselineWinProb, winProbDelta, portfolioDeltaEV, geometricSum, optimalDiceCount)
- Added 4 Engine Registry tests (loads entries, has default, balanced is default, findById)
- Fixed 2 explicit `logic.probability.GameSession/TurnRecord` references → `core.*`
- Results: **247 passed, 0 failed** (was 224)

---

## Phase 1 Tasks 1.1–1.4: Layer Separation Foundation (commit 1cb66d3)

Extracted the 5-layer architecture skeleton from the monolithic `logic.probability` package.

**`util/Strings`** — cross-cutting locale registry (DE/EN), stripped of all Swing-specific code from the original `gui.newui.Strings`.

**`core/` package** — pure game rules, no strategy:
- `Project`, `Player`, `GameState` (adds `SUPPLY_PER_CARD` constant + `hasWon()` static method, previously buried in `GameSimulator`)
- `GameStateBuilder`, `TurnRecord`, `ProjectLoader`, `GameSession`, `GameSessionPersistence`
- `CardIncome` — all 19 cards, P1/P2 dice probabilities, income calculation. Made public so `calcs/` can access it directly.
- `RollResolver` — new class; extracts `computeAllDeltasForRoll` from `ProbabilityCalc` into its own authoritative Core class (Red → Blue/Green → Purple, counter-clockwise)
- `BürohausLogic` — made public for cross-layer access

**`calcs/` package** — version-agnostic math, callable by any engine:
- `Calcs` — public API: `get_I`, `computeAllDeltasForRoll`, `get_P1/P2`, `geometricSum`, `immediateEV`, `evPerRound`, `roiOverHorizon`, `portfolioEvPerRound`, `portfolioDeltaEV`, `optimalDiceCount`, `computeBaselineWinProb`, `estimateWinProbDelta`, `values_per_r_per_p`
- `RankEntry` — result POJO for `roiOverHorizon` (replaces `logic.probability.RankEntry`)
- `WinProbability` — analytical softmax scorer (ports analytical-only parts from `WinProbabilityCalc`; `mcWinRate` intentionally excluded — superseded by MCTS engine)

**`engine/` package** — engine contract:
- `SimulationEngine` interface — `id()`, `description()`, `evaluate(GameState, playerIndex, EngineConfig) -> EngineResult`
- `EngineConfig` — generic config container (iterations, timeBudgetMs, riskToleranceWeight, extra key-value map)
- `EngineResult` — ranked options list with score + explanation factors + metrics + confidence + timing metadata

All 224 existing tests pass. The old `logic.probability` code continues to compile unchanged.

---

## Pre-Restructure History

Everything below documents the original implementation that led to the restructure decision.

---

## UI-Redesign: visuelle Hierarchie, Rank-Coloring, Kategorie-Icons, Insight-Zusammenfassung

Vollständiges UI-Redesign von `MainWindow` und `Strings.java` mit Fokus auf schnell extrahierbare Insights für neue und erfahrene Spieler gleichzeitig.

### Rank-relatives Zellen-Coloring in der Rankingtabelle

`RankAwareNumericCellRenderer` ersetzt `NumericCellRenderer` für alle Metrik-Spalten (EV, ROI, P0, Varianz, Win-Prob-Δ, Portfolio-Δ). Ranking-Logik: alle Werte der Spalte zur Renderzeit gesammelt, sortiert, rankPct berechnet, dann `MetricColorScheme.rankedBackgroundFor(rankPct)` — Top-15% dunkelgrün, 15–40% hellgrün, 65–85% gelb, 85–100% orange. `RankAwareNumericCellRendererWithDim` Variante für "Alle"-Tab dimmt unerschwingliche Zeilen zusätzlich grau/kursiv.

### Kategorie-Icons überall

`CATEGORY_ICON_HTML` static Map (neu): encodiert alle 8 Kategorie-Icons (16×16 PNG) als Base64-Data-URIs für HTML-`<img>`-Einbettung in `JLabel`-HTML-Text. `iconHtml(category, text)` Helper gibt `<img>` + `&nbsp;` + Text zurück. Verwendet in:
- **Karten-Beschreibungen** (`enrichDescriptionWithIcons`): ersetzt „Tier-Gebäude", „Lebensmittelgebäude", „Rohstoff-Gebäude", „Café- und Geschäftsgebäude" (DE + EN) durch Icon + Text.
- **Kauf-Dropdown** (`IconComboRenderer`): 14×14 Icon vor jedem Eintrag.
- **Rankingtabelle** (`CardNameRenderer`): Icon + Kartenfarben-Hintergrund pro Zeile.
- **Rollout-Tabelle**: inline Renderer mit Icon + Kartenfarbe für Kartenname-Spalte.

### Kontextuelle Metrik-Tooltips + Insight-Zusammenfassung

Alle 6 Metrik-Tooltips (`evTooltipContextual`, `roiTooltipContextual`, `p0TooltipContextual`, `varianceTooltipContextual`, `winProbTooltipContextual`, `portfolioDeltaTooltipContextual`) in `Strings.java` zeigen jetzt: konkreten aktuellen Wert, Rang (#X von N), was gut/schlecht bedeutet mit quantifizierten Schwellwerten, und Wirkung unterschiedlicher Zahlen. `metricInsightSummary` erzeugt 1–2-Satz HTML-Zusammenfassung mit ★ für #1-Rang und farbcodierten Win-Prob-Deltas.

Alle Metrik-Buttons im Center-Panel verwenden `applyRankedMetricColor` (rank-relativ) statt absoluter Schwellwerte.

### Header-Bar + CTA-Button + Delta-Grid

**Header-Bar** oben im Fenster: schmale Leiste (F0F0F0, 1px unten Border) zeigt Spielernamen, Zug, Münzen, Win-Prob%. `refreshHeaderBar` via `refreshAll()`. **Confirm-Button** als primäre CTA gestaltet: grüner Hintergrund (#2E7D32), weiß, Bold 14pt, volle Breite, 40px Höhe. **Delta-Grid-Panel** zwischen Würfelstrips und Roll-Preview: zeigt pro Spieler Name (in Spielerfarbe) + Münzdelta (+N¢ grün, -N¢ rot) für den aktuellen Würfelwurf.

### Reiche Tooltips für Analysis-Einstellungen

`deepAnalysisTooltipRich()`, `mcSimTooltipRich()`, `mcTempTooltipRich()`, `rolloutDepthTooltipRich()`, `rolloutTopKTooltipRich()` — erklären N=100–10000 mit ms/Fehler-Schätzungen, T=0/0.7/1.5, Tiefe 1/2/3 mit Kosten, K=2/5/8 mit Pruning-Erklärung.

---

## Dreistufiges Hybrid-System: Stufe 1 (RolloutTree) + Stufe 2 (verbesserter Leaf-Evaluator) + Stufe 3 (Adaptives MC-Budget)

Vollständige Implementierung des in PLAN.md § "Dreistufiges Hybrid-System" beschriebenen Architektur-Ziels.

### Stufe 2 — Leaf-Evaluator-Verbesserungen in `WinProbabilityCalc`

**Problem:** `computeBaselineWinProb` lieferte zu ungenaue Endspielwerte. Drei konkrete Schwächen: (1) Landmark-Gewichte (max 4.0) wurden vom `evPerRound × remainingTurns`-Term dominiert (~20–80) und hatten keinen messbaren Einfluss auf den Softmax; (2) kein Münzvorteil-Signal — ein Spieler mit 20 Münzen mehr als alle anderen sah für den Evaluator genauso aus wie ein Spieler ohne Münzvorsprung; (3) Endspiel-Blindheit: ein Spieler mit 3 Landmarks und genug Münzen für die letzte erhielt keine Bonusgewichtung.

**`LANDMARK_WEIGHTS` Neukalibrierung:** Werte von (max 4.0) auf münzäquivalente Einheiten skaliert. Formel: Landmark-Gewicht = ΔEV/Runde × ~12 Runden. Neue Werte: Bahnhof=24, Einkaufszentrum=36, Freizeitpark=24, Funkturm=48, Default=20. Jetzt in derselben Größenordnung wie die EV-Komponente.

**`coinAdvantage`-Term:** `(coins_p − avgCoins) / COIN_ADVANTAGE_SCALE (5.0)` direkt zu `score(p)` addiert. Bei typischen Münzabständen von ~10 ergibt das ±2 pro Münzeinheit, ~±10 gesamt — ca. 10–20% der EV-Komponente. Stärkerer Münzvorteil-Signal für den Softmax.

**`endgameProximityBonus`:** Wenn `landmarkCount == 3` und `player.coins >= cheapestMissingLandmarkCost(player)`, wird `score *= 2.5` angewandt. Verhindert dass ein unmittelbar gewinnender Spieler vom Evaluator mit einem Spieler 5+ Züge vom Sieg verwechselt wird. Neuer privater Helper `cheapestMissingLandmarkCost(Player)`.

### Stufe 1 — `RolloutTree.java` (neue Klasse)

**Neue Klasse** `logic.probability.RolloutTree` (~250 Zeilen). Implementiert Expectimax-Suchbaum für Tiefe d.

**`RolloutResult` record:** `(Project bestAction, double expectedWinProb, Map<Project, Double> allValues)`. `bestAction` ist `RankEntry.WAIT_SENTINEL` wenn Sparen optimal ist.

**`evaluate(gs, pi, depth, topK)`:** Endspiel-Extension (+1 Tiefe wenn ≤8 Münzen vom Sieg), Kandidaten via `portfolioDeltaEV` gefiltert (Top-k), immer `WAIT_SENTINEL` als Spar-Option inkludiert. Tiefe-1: `simulateOpponentTurns` (stochastisch, einzelner Sample-Roll pro Gegner) + `computeBaselineWinProb`. Tiefe>1: `expandOwnTurnChanceNode` (probabilistisch gewichtet über alle Würfelergebnisse).

**Sonderfälle:** Bahnhof (1d6 vs 2d6 je nach Kartenbesitz), Freizeitpark (Pasch 6/36 → rekursiver Bonus-Zug), Funkturm (Re-Roll-Entscheidung wenn g(r) < EV-Baseline).

**Gegner-Simulation:** `boltzmannBuy(T=0.7)` aus `GameSimulator` — einzelner stochastischer Sample pro Gegner-Zug, nicht probability-weighted. Akzeptierte Näherung A5 (stochastisch statt exakt für Performance).

**Konvergenz:** `allConverged(ε=0.01)` — informationell, kein frühzeitiger Abbruch im Baum.

**UI-Integration:** 5. Tab "Rollout" in `MainWindow`. Tiefe-Spinner (1–3), Top-K-Spinner (2–8), Run-Button mit SwingWorker-Hintergrundausführung. Ergebnis-Tabelle sortiert nach Win-Prob mit Grün/Gelb-Farbkodierung. Strings in `Strings.java` (DE/EN).

### Stufe 3 — Adaptives MC-Budget in `ProbabilityCalc.rankPurchasableProjects`

**Problem:** Vorher wurden alle Kandidaten einzeln mit MC validiert (langsam) oder gar nicht (ungenau). Neues System: Top-k analytisch vorfiltern, dann Budget adaptiv verteilen.

**`adaptiveMCRefinement(results, gs, playerIndex, opts, mcBaseline)`:** Privater Helper in `ProbabilityCalc`. Nimmt Top-k = `min(MC_TOP_K=5, results.size())`. Berechnet analytische Win-Prob-Deltas für alle Top-k. Wenn `spread ≤ 0.02` (alle eng beieinander) oder kein Kandidat dominiert (Vorsprung ≤ 0.05): alle Top-k mit je 2.500 MC-Sims validieren. Wenn ein Kandidat klar dominiert: nur die Verfolger validieren (Anführer spart Budget). MC-Ergebnisse überschreiben die Stufe-2-Schätzungen in den entsprechenden `RankEntry`-Objekten.

**Neue Konstanten:** `MC_TOP_K=5`, `MC_EQUAL_BUDGET_EPSILON=0.02`, `MC_DOMINANT_LEAD_THRESHOLD=0.05`, `MC_SIMS_PER_CANDIDATE_EQUAL=2500`.

**Tests:** 224 PASS, 0 FAIL.

---

## C5 — buildRollGainCache + computeOwnTurnEV: Hot-Path-DRY-Refactoring

**Problem:** `computeNetGainForRoll(state, playerIndex, r, false)` wurde pro `immediateEV`- oder `evPerRound`-Aufruf bis zu ~84 mal aufgerufen (6 für 1d6 + 36 für 2d6 + 36 für `funkturmEV` + 6 für 1d6-Funkturm), obwohl es für jeden der 12 distinkten Roll-Werte stets dasselbe Ergebnis liefert. Außerdem war die Bahnhof/Freizeitpark/Funkturm-Entscheidungslogik identisch in `immediateEV` und `evPerRound` dupliziert.

**`buildRollGainCache(state, playerIndex)`** — neue private Hilfsmethode. Befüllt `double[13]` einmal (Index 0 ungenutzt, 1–12 = Ergebnis pro Roll). Da der `isDoubles`-Parameter in `computeNetGainForRoll` intern nicht ausgewertet wird (reserved hook), ist der Cache für alle Roll-Kontexte korrekt.

**`computeOwnTurnEV(state, pi, cache, hasBahnhof, hasFreizeitpark, hasFunkturm)`** — neue private Hilfsmethode, extrahiert die gemeinsame Bahnhof/FZP/FT-Logik. Aufgerufen von `immediateEV` und `evPerRound`. Eliminiert ~50 Zeilen Code-Duplikat.

**`computeVariance1d6(double[])`** / **`computeVariance2d6(double[])`** — neue cache-basierte Überladungen. Veraltete `(GameState, int)`-Überladungen rufen `buildRollGainCache` einmalig auf und delegieren.

**`bestSecondRollEV`** — nutzt jetzt ebenfalls `buildRollGainCache` statt direkter Lambda-Closure.

**`optimalDiceCount`** — ebenfalls auf Cache umgestellt.

**Tests:** 224 PASS, 0 FAIL. Keine Verhaltensänderung.

---



`RankEntry.portfolioDeltaEV` = `playerEvPerRound(portfolio + card) − playerEvPerRound(portfolio)`. Erfasst Cross-Karten-Synergien die `evPerRound` (per-Karte-isoliert) verpasst: Bauernhof→Molkerei, Food→Markthalle, Bahnhof→alle 7–12-Karten.

**`ProbabilityCalc.portfolioDeltaEV(gs, pi, candidate)`** — add/remove Pattern auf dem Spieler-Owned-Array (kein `GameState.copy()`, allokationsfrei). Wird von `roiOverHorizon` befüllt und an jede `RankEntry` weitergegeben.

**UI:** Neue Spalte "ΔEV/Rd" in der Rangliste-Tabelle + neue Metrik-Zeile im Card-Details-Panel. `MetricColorScheme.PORTFOLIO_DELTA(0.30, 0.08, false)` für Farbkodierung. `TABLE_ORDER` hat jetzt 6 Einträge.

**Rankingtabelle:** Spalte 7 = Portfolio-ΔEV/round. Sortierbar. Farbkodiert via `NumericCellRenderer`.

**Akzeptierte Näherung A3** (per-Karte-Max für Bahnhof) bleibt in `contextualCardEvPerRound`, aber ist für das Ranking selbst nicht mehr relevant — `portfolioDeltaEV` nutzt `playerEvPerRound` direkt.

---

## M7 — Boltzmann-Exploration Toggle für MC-Simulator

Der MC-Simulator verwendete bisher eine rein deterministische Greedy-Policy: alle simulierten Spieler kaufen immer die Karte mit dem höchsten ROI-Score. Das führte zu systematisch verzerrten Win-Raten — Spieler die von der optimalen Strategie abweichen, wurden als schlechter dargestellt als sie tatsächlich sind.

**`GameSimulator.simulate(state, rng, temperature)`** — neue Überladung mit Boltzmann-Temperatur T. Bei T=0 wird die bestehende `greedyBuy()`-Methode aufgerufen (identisches Verhalten). Bei T>0 ruft `boltzmannBuy()` auf: Scores aller erschwingl. Karten werden via Softmax in eine Wahrscheinlichkeitsverteilung umgewandelt, aus der stochastisch gesampelt wird.

**Formel:** `P(buy X) ∝ exp((score(X) − max_score) / T)` mit max-Subtraktion für numerische Stabilität.

**Landmark-Priorität** (Bahnhof-Gate + Kosten-Reihenfolge) bleibt in beiden Modi deterministisch — nur die Establishments werden stochastisch gewählt.

**`RankingOptions.mcExplorationTemp`** (default 0.0) — neues Feld, wird durch `rankPurchasableProjects`, `rankAllProjects` und `mcWinRate` durchgereicht.

**UI:** T-Spinner (Bereich 0.0–5.0, Schritt 0.1) neben dem N-Spinner in der Button-Bar. Empfohlener Wert: T=0.7.

**Tests:** 228 PASS, 0 FAIL.

---

## Bahnhof-Synergie-Fixes: M6 (Lookahead), M8 (Simulator-Gate)

### Problem
Simulierte Spieler (MC/Labeling) kauften Bahnhof in `GameSimulator.greedyBuy` zu früh — ohne Karten mit Aktivierung ≥ 7 bringt 2d6 keinen EV-Vorteil, der Kauf ist wertlos. Außerdem zeigte `computeTwoTurnNote` keine Bahnhof-Synergien: „Bergwerk kaufen → dann lohnt sich Bahnhof" war nie als Note sichtbar.

### M8 — Bahnhof-Gate in `GameSimulator.greedyBuy`
Vor Bahnhof-Kauf wird jetzt `hasHighRangeCard(player)` geprüft — gibt `true` zurück wenn der Spieler mindestens eine Nicht-Landmark mit Aktivierung ≥ 7 besitzt. Ohne solche Karte wird der Bahnhof-Kauf übersprungen (nächste Landmark in der Prioritätsreihenfolge wird probiert, oder Establishment-Phase tritt ein). Die gleiche Logik war bereits in `rollDice` implementiert; jetzt konsistent für den Kauf.

### M6 — Bahnhof-Synergie in `computeTwoTurnNote`
Der generelle Landmark-Skip (`continue` für alle `is_grossprojekt`) wurde durch eine gezielte Bahnhof-Behandlung ersetzt:
- Wenn `cardB=Bahnhof` und Spieler hat ihn noch nicht: berechne `contextualCardEvPerRound(cardA, statsWithAB)` vs `contextualCardEvPerRound(cardA, statsAfterA)`. Die Differenz ist der Synergy-Gewinn den Bahnhof für Karte A bringt. ROI(Bahnhof für A) = synergyGain × geometricSum − cost(Bahnhof). Konservative Untergrenze — berücksichtigt nur A's Synergy, nicht andere Karten. Wenn selbst das ROI > 0.5 ergibt, erscheint die Note.
- Wenn `cardA=Bahnhof`: `statsAfterA.hasBahnhof=true` → `contextualCardEvPerRound(Bergwerk, statsAfterA)` berechnet automatisch 2d6-EV für Bergwerk. Die beste 7–12 Karte erscheint dann korrekt als Follow-up.

**Tests:** 228 PASS, 0 FAIL.

---



**`computeTwoTurnNote(gs, pi, cardA, candidates, horizon, discount)`** — new package-private static method in `ProbabilityCalc`. For each affordable candidate card A, evaluates all remaining candidates as a potential follow-up purchase B. Uses `CardIncome.contextualCardEvPerRound(B, statsAfterA, n, oppCoins)` — no `GameState.copy()` needed, only `PlayerStats` are constructed. Selects B with the highest estimated `roiOverHorizon` in the post-A portfolio state. Returns a note like "Danach: Bergwerk (ROI +4.2)" when the follow-up ROI exceeds 0.5 (threshold to avoid noise). Landmarks excluded from candidates (their interaction is too complex for this level).

Wired into both `rankPurchasableProjects` and `rankAllProjects` (affordable cards only) alongside the existing synergy note. Notes are concatenated with `"  |  "` separator. `Strings.twoTurnNote(name, roi)` added.

Performance: ranking benchmark still passes at < 5ms avg (O(n²) per ranking call, but n ≤ 19 cards so ≤ 361 `contextualCardEvPerRound` calls with no allocations beyond `PlayerStats`).

**Tests:** 228 PASS, 0 FAIL (MC sum test occasionally flaky by design).

---

## UI-Polishing: Bug-Fixes, Runner-Ups, Win-Prob always-on, Income Matrix

**Bug-Fixes:**
- Roll-change no longer resets buy selection unless the previously selected card is no longer affordable after the new roll (preserves selection correctly).
- Roll-change now always re-ranks analytically (was blocked when MC mode was active).
- Phase label in context profile now shows a continuous blend ("Früh 30% · Mitte 70%") instead of a single label.
- EKZ GP hint now computes actual EV gain via `portfolioEvPerRound` diff; no longer shows "+0.00¢/Runde".
- Wait sentinel ("≡ Sparen") now appears in the affordable tab (it's a valid this-turn choice).
- Removed all emoji from UI strings — replaced with `[GP]`, `[+]`, `[!]`, `[W]`/`[D]` prefixes for cross-platform safety.

**Runner-Ups per assistant profile:** Each profile row in the Game Assistant now shows the 2nd and 3rd place cards in a right-aligned column ("2. Bergwerk  3. Wald"), so the uniqueness of the top recommendation is immediately visible. `runnerUpNames(metric, lowerIsBetter, winnerId, max)` helper; `addAssistantRow` overload with `BorderLayout` right column.

**Win-prob always on / MC on by default:** The win-probability delta toggle button is removed — win prob is always shown in the card detail panel. Deep Analysis (MC) is enabled by default. `showWinProb` field removed; `rankOpts.includeWinProbDelta = true` and `rankOpts.mcSimulations = mcSimCount` set in constructor.

**Extended `GamePhaseContext`:** Eleven new fields added — `catchUpStrength`, `pullAheadStrength`, `evGapVsLeader`, `coinAdvantage`, `portfolioDiversity`, `turnsToOwnWin`, `minTurnsToOppWin`, `ekzEvGain`, plus synergy gap detection (`synergyGapExists`, `synergyGapCard`, `synergyGapGain`). `addContextProfile` uses position modifiers (catch-up boosts GPRush/Aggro/Cheap; pull-ahead boosts ROI/Safe/LowVar), coin advantage, and diversity gap on top of the phase interpolation.

**Income Matrix (collapsible):** New toggle button in the left panel between roll preview and buy dropdown. Shows a grid of coin deltas for all players (rows = roll values 1–12 or 1–6, columns = players), color-coded green/red. Hidden by default; lazily refreshed on show and on every roll change. `refreshIncomeMatrix()` method; `incomeMatrixPanel` + `incomeMatrixToggleBtn` fields.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N4d–N4f: Continuous Phase Weights + LabelingWindow UX + PhaseFitter

**N4d — Kontinuierliche Phasen-Gewichte:** `computePhaseContext` berechnet jetzt drei kontinuierliche Stärken `earlyStrength`, `midStrength`, `lateStrength` ∈ [0,1] (Summe = 1). Spät-Stärke: linearer Ramp über GP-Anzahl → `LATE_GP_THRESHOLD`. Früh-Stärke: Mittelwert aus EV-Schwäche (`1 - avgEv/threshold`) und EKZ-Erreichbarkeit (0/1). Mid = Restant. `addContextProfile` interpoliert Gewichte als `w[i] = earlyStr × WEIGHTS_EARLY[i] + midStr × WEIGHTS_MID[i] + lateStr × WEIGHTS_LATE[i]` — kein hartes Snap mehr auf eine Phase. `phaseLabel` wird nur noch für die Anzeige und als Tiebreaker gesetzt (höchste Stärke).

**N4e — LabelingWindow UX:** Einzelner Phase-Slider (0=Früh, 50=Mitte, 100=Spät) ersetzt drei unabhängige Slider. Live-Label "Früh 80% · Mitte 20% · Spät 0%" unter Slider. Auto-Save nach jedem "Nächster Snapshot"-Klick in `phase_labels.json`. Labels werden beim Öffnen des Fensters wiederhergestellt.

**N4e — Detaillierte Label-Exports:** JSON enthält jetzt: `gp_count`, `gps` (Liste der GP-IDs in Kaufreihenfolge), `non_gp_cards`, `cards` (Liste von `{id, count}`), `features` Block (`avg_gps`, `max_gps`, `avg_cards`, `avg_coins`) — direkt für `PhaseFitter` verwendbar ohne Re-Berechnung.

**N4e — SnapshotCard UX:** GP-Leiste mit benannten Slots und Tooltips (GP-Name + Kosten + gebaut/nicht gebaut). Karten-Liste zeigt Projektnamen mit ×N-Multiplikator. Münzen-Zeile mit Text-Label statt Emoji (vermeidet Rendering-Probleme). Karten-Liste erhält `CENTER`-Layout-Slot — füllt restlichen Platz.

**N4f — PhaseFitter:** Neue Klasse `gui.newui.PhaseFitter`. OLS-Regression (Normalengleichungen, Gauß'sche Elimination mit Partial Pivoting) auf `phase_labels.json`. Features: `[1, avg_gps, max_gps, avg_cards, avg_coins]`. Deriviert `LATE_GP_THRESHOLD` aus max_gps-Wert wo Late-Score = 0.5. R²-Bericht im Kalibrier-Dialog. "Kalibrieren…"-Button in `LabelingWindow` triggert Fit + Update via Reflection (Fallback: zeigt Ergebnis ohne Anwendung auf Java 17+).

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N4a–N4c: SnapshotCard + SnapshotGenerator + LabelingWindow

**N4a — `SnapshotCard.java`:** Neues kompaktes Player-Panel in `gui.newui`. Zeigt: Spielername, Münzen (Clickable / Spinner in edit mode), GP-Fortschrittsleiste (0–4, farbkodiert: grün=führend, gelb=mittel, rot=hinten), farbige Karten-Chips (Blau/Grün/Rot/Lila/Gelb als aggregierte Chips mit ×N-Zähler), EV/Runde via `portfolioEvPerRound`. In Edit-Mode (Doppelklick oder `setEditable(true)`): `BoundedSpinner` für Münzen, pro-Farbe-Spinner/Checkbox für alle Karten — gleiche Validierungslogik wie `SnapshotDialog`. API: `setPlayer(Player)`, `getEditedPlayer() → Player`, `setEditable(boolean)`, `addChangeListener(...)`. `.mkoro`-kompatibel: `getEditedPlayer()` liefert direkt einen `Player` für `GameStateBuilder`.

**N4b — `SnapshotGenerator.java`:** Neue public Klasse in `logic.probability`. `generate(numPlayers, minTurn, maxTurn)`: Simuliert ein frisches Spiel (greedy via `GameSimulator.applyRoll` + `greedyBuy`) bis zu einem Zufallszug im Bereich und gibt den `GameState`-Deep-Copy zurück. `generateFromFile(Path)`: Lädt `.mkoro` via `GameSession.load()` und gibt den Endzustand zurück. `applyRoll`, `greedyBuy`, `buildSupply` in `GameSimulator` auf package-private geändert.

**N4c — `LabelingWindow.java`:** Neues JFrame in `gui.newui`. Layout: Oben — Spieleranzahl, Züge-Bereich, "Generieren"-Button, "Aus Datei laden"-Button. Mitte — Side-by-Side `SnapshotCard`s + drei unabhängige Slider (keine Tick-Nummern, nur Endpoint-Labels: "Frühphase ←→ Nicht Frühphase" etc.) für Early/Mid/Late. Unten — "Nächster Snapshot" (speichert aktuelles Label, generiert nächsten), "Labels exportieren" (schreibt `phase_labels.json` via Gson). Label-Format: `[{players:[{name,coins,gps,cards}], labels:{early,mid,late}}]`. Erreichbar via neues "Werkzeuge"-Menü in `MainWindow`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N2+N3: Bahnhof-Würfelwahl im Assistenten + wirtschaftsbasierte Phasenerkennung

**N2 — `optimalDiceCount(gs, pi)`:** Neuer public wrapper in `ProbabilityCalc`. Vergleicht `weightedRollEV(1d6)` vs `weightedRollEV(2d6)` mit aktuellem Portfolio (kein Kandidat). Gibt 1 oder 2 zurück. Im Assistenten: wenn Bahnhof besessen → neuer Hint "🎲 1W6 optimal — Portfolio aktiviert hauptsächlich auf 1–6" (oder 2W6). Strings: `assistantDiceHint1d6()` / `assistantDiceHint2d6()`.

**N3 — `AssistantConfig.java`:** Neue package-private Klasse in `gui.newui`. Zentralisiert alle Schwellwerte und Gewichtsarrays — kein Magic-Number-Streuer mehr in `rebuildAssistantPanel`. Konstanten: `EARLY_AVG_EV_THRESHOLD`, `EARLY_SAVE_ROUNDS`, `EKZ_COST`, `LATE_GP_THRESHOLD`, Pressure-Modifier-Werte, drei Gewichtsarrays (EARLY/MID/LATE). Methode `weightsForPhase(String)` gibt mutable Clone zurück.

**N3 — Wirtschaftsbasierte Phasenerkennung** ersetzt einfachen GP-Zähler-Check:
- **Frühphase**: `avgPortfolioEV < 1.2` UND EKZ nicht innerhalb 2 Runden erreichbar (`coins + 2×ownEv < 10`)
- **Endspiel**: `max(eigene GPs, maxOppGPs) >= 3`
- **Mittelspiel**: alles andere

**N3 — Rückstand-Modifier**: `minTurnsToWin` des gefährlichsten Gegners berechnet als `(22 - oppCoins) / oppEv` (Worst-Case 4. GP = Funkturm 22 Münzen). Notfall (≤3 Züge): GP-Rush +0.5, Aggro +0.3. Druck (≤6 Züge): GP-Rush +0.2, Aggro +0.1. Modifier via `AssistantConfig`-Konstanten.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## Synergy-Lookahead im Ranking + P(0)-Metrik auf vollständige Runde umgestellt

**Synergy-Lookahead:** `ProbabilityCalc.computeSynergyNote(gs, pi, card, candidates, n)` — neue package-private Methode. Berechnet für jede Karte im Ranking die beste Folgekarte (Partner), die ihren Wert am meisten steigern würde. Methode:

1. Erstellt `PlayerStats` als ob der Spieler `card` bereits besitzt (via `buildStatsWithCard`)
2. Für jede Nicht-Landmark-Karte S im Pool: erstellt `PlayerStats` mit card + S (`buildStatsWithCards`) und berechnet `contextualCardEvPerRound(card, statsWithS)` − Baseline
3. Für grün/store-Karten (Bäckerei, Mini-Markt): testet zusätzlich Einkaufszentrum via `buildStatsWithEkz`
4. Gibt `Strings.synergyNote(partnerName, gain)` zurück wenn Gewinn ≥ 0.05¢/Runde

Ergebnis: `entry.notes` im Ranking-Eintrag enthält z.B. "Gut mit: Bauernhof (+0.30¢/Runde)". Hilfsmethoden `applyToStats`, `buildStatsWithCard`, `buildStatsWithCards`, `buildStatsWithEkz` im selben `ProbabilityCalc`. Keine `GameState.copy()`-Aufrufe nötig → allokationsfrei.

Wird in `rankPurchasableProjects` und `rankAllProjects` aufgerufen. Bürohaus-Hinweis und Synergy-Note werden mit `"  |  "` kombiniert wenn beide vorhanden.

**P(0)-Metrik auf Rundenbasis:** `probNoIncomeRound` ersetzt `probNoIncomeOwnTurn` in Rankingtabelle, Kartendetail-Panel und `computeMetricRankPct`. Berechnet `P(0 Münzen über komplette Runde) = P(0 eigener Zug) × Π P(0 je Gegner-Zug)`. Dies ist konsistent mit dem "Sicherheitsstrategie"-Profil im Game Assistant, das bereits `probNoIncomeRound` verwendete. Beschreibungstexte in `Strings.legendP0Desc()` und `Strings.colTipP0()` aktualisiert.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## MC-Policy: ROI-basiertes Scoring in GameSimulator

**Problem:** `greedyBuy` benutzte `contextualEvPerRound / cost` als Kaufentscheidung. Das ignoriert die zeitliche Diskontierung — ein teurer 5-Münzen-Return-Karte sah gleich aus wie 5 billige 1-Münzen-Karten.

**Lösung:** Neues Scoring: `roi = contextualEvPerRound × ROI_GEOMETRIC_SUM − cost`, wobei `ROI_GEOMETRIC_SUM = γ × (1 − γ^T) / (1 − γ)` mit γ = 0.95, T = 10 (= 7.72, vorberechnet als statische Konstante). Das entspricht der ROI-Formel des analytischen Rankings. Die Simulation spielt nun dieselbe Strategie, die der Spieler im Ranking sieht → realistischere Win-Raten.

Performance: 40ms für 1000 Sims (unverändert), da `contextualCardEvPerRound` allokationsfrei bleibt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## U3: Trigger-Modus-Anzeige in Kartendetails

`TriggerModePanel` — neues inneres `JPanel` in `MainWindow`, in die `nameRow` nach dem Farb-Tag eingefügt. Zeichnet programmatisch mit `Graphics2D`:
- **Blau** — 3 blaue Kreise: Karte triggert bei jedem Spieler-Zug
- **Grün** — 1 grüner Kreis: nur eigener Zug
- **Rot** — 1 roter Kreis mit Diagonalstrich: nur Gegner-Züge
- **Lila** — 1 lila Kreis + Diamant: eigener Zug, einmalig pro Runde
- **Gelb** — kein Indikator (Großprojekte werden gebaut, nicht getriggert)

`populateCenter` setzt `topCardTrigger.setCardColor(p.getColor())`; `clearCenter` setzt `null`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M5: "Warten/Sparen" als synthetischer RankEntry im "Alle"-Tab

**Problem:** Das Ranking zeigte nie die Option, Münzen für eine bessere Karte zu sparen.

**Lösung:**
- `RankEntry.WAIT_SENTINEL` — statisches Sentinel-`Project`-Objekt mit `id="_wait_"`. `RankEntry.isWaitEntry()` erkennt es.
- `addWaitEntryIfUseful(results, gs, playerIndex, opts)` — neue private Methode in `ProbabilityCalc.rankAllProjects`. Findet die beste nicht-erschwingliche Karte, berechnet `turnsToSave = coinsNeeded / currentEvPerRound`, und ROI: `ROI(warten) = ROI(beste_nächste) − turnsToSave × currentEvPerRound`. Nur eingefügt wenn unerschwingliche Karten vorhanden.
- `Strings.waitLabel()` — "≡ Sparen" / "≡ Save". `Strings.waitEntryNotes(card, turns)` — "Spare auf: [Karte] (~X.X Züge)".
- **UI**: `fillRankTableModel` überspringt den Sentinel in Erschwinglich/Nicht-erschwinglich-Tab; im "Alle"-Tab erscheint er als "≡ Sparen"-Zeile (unaffordable, kursiv/grau per DimRenderer). Kost-Spalte zeigt `NaN` (leer). Row-click zeigt Name + Notes im Center-Panel.
- **Assistent**: GP-Rush-Filter ergänzt mit `!e.isWaitEntry()` Guard.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M4: Per-Landmark-Gewichte in WinProbabilityCalc

**Problem:** `LANDMARK_WEIGHT = 2.0` war für alle 4 Großprojekte identisch, obwohl ihre EV-Beiträge stark variieren.

**Kalibrierung** (mid-game Portfolio, 15 verbleibende Züge):
| Landmark | evPerRound-Delta | Neues Gewicht |
|---|---|---|
| Bahnhof (4¢) | +0.5/Runde | 1.5 |
| Einkaufszentrum (10¢) | +1.0/Runde | 3.0 |
| Freizeitpark (16¢) | +0.3/Runde | 1.5 |
| Funkturm (22¢) | +1.1/Runde | 4.0 (M2-Fix) |

**Lösung:** `LANDMARK_WEIGHTS` Map in `WinProbabilityCalc`; `LANDMARK_WEIGHT_DEFAULT = 2.0` als Fallback für zukünftige Expansion-Landmarks. `computeScores` nutzt `LANDMARK_WEIGHTS.getOrDefault(id, DEFAULT)`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M3: Dynamischer REMAINING_TURNS_ESTIMATE in WinProbabilityCalc

**Problem:** `REMAINING_TURNS_ESTIMATE = 12.0` war eine statische Konstante. Im Frühspiel (viele Züge übrig) wurde der EV-Term unterschätzt, im Endspiel (Gegner hat 3 GPs) dramatisch überschätzt.

**Lösung:**
- `RankingOptions.turnsElapsed` — neues optionales Feld (Default 0 = Fallback auf statischen Wert).
- `WinProbabilityCalc.computeScores(GameState, int turnsElapsed)` — dynamische Schätzung:
  `remainingTurns = max(3, 25 − turnsElapsed / n)`, wobei `TOTAL_EXPECTED_TURNS = 25`.
- `WinProbabilityCalc.estimateWinProbDelta` — nimmt jetzt `turnsElapsed`-Overload.
- `ProbabilityCalc.rankAllProjects` / `rankPurchasableProjects` — leiten `opts.turnsElapsed` an `estimateWinProbDelta` weiter.
- `MainWindow.refreshAll()` und `refreshAfterRollChange()` setzen `rankOpts.turnsElapsed = session.getEffectiveTurnCount()` vor jedem Ranking-Aufruf.
- Rückwärtskompatibilität: `turnsElapsed = 0` → REMAINING_TURNS_FALLBACK = 12.0 wie zuvor.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M2: Funkturm-EV in immediateEV und evPerRound

**Problem:** `hasFunkturm` wurde bisher nur im Freizeitpark-Doppelwurf-Pfad (`bestSecondRollEV`) genutzt. Ein Spieler mit Funkturm aber ohne Freizeitpark bekam null Funkturm-Nutzen im EV-Modell.

**Lösung:** Neue private Methode `funkturmEV(boolean use2d6, IntToDoubleFunction payoutFn)`:
```
E[Funkturm] = E_baseline + Σ_{r : g(r) < E_baseline} P(r) × (E_baseline − g(r))
```
Der Spieler re-rollt optimal — nur wenn der erste Wurf unter dem Erwartungswert liegt. Das ergibt einen EV, der strikt höher als `E_baseline` und niedriger als ein erzwungenes Neu-Würfeln ist.

- **`immediateEV`**: wenn `hasFunkturm`, verwendet `funkturmEV(false, ...)` statt `weightedRollEV(false, ...)` für 1d6; bei Bahnhof zusätzlich `funkturmEV(true, ...)` für 2d6 (ohne Doubles-Freizeitpark-Bonus, da Funkturm dieselbe Würfelanzahl erzwingt).
- **`evPerRound`**: gleiche Logik im Eigenzug-Block.
- **`bestSecondRollEV`**: unverändert (Freizeitpark-Pfad; Funkturm+Freizeitpark erzwingt `forcedDice=2` für den zweiten Wurf wie bisher).

Sanity-Check: Weizenfeld+Bäckerei, 2 Spieler — `immediateEV` steigt von 0.667 auf 1.000 mit Funkturm allein; `evPerRound` von 1.000 auf 1.333.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Batch: Rang-Kontext, relative Farben, Tie-Handling, Spiellage-Assistent

### Rang-Kontext im Kartendetail
- **`topCardRank`-Label** — neue Zeile unterhalb des Metrik-Grids: "#X / Y erschwinglich · #Z / N gesamt". Zeigt wo die gewählte Karte im gesamten Ranking steht (nach ROI sortiert), sowohl unter den erschwingli­chen Karten als auch absolut.

### Relative Farben
- **`MetricColorScheme.rankedBackgroundFor(double rankPct)`** — neue Methode; nimmt Rang-Prozentsatz (0.0 = bester, 1.0 = schlechtester) statt absolutem Wert. Neue Farben `YELLOW_LIGHT` (0xFFF4CC) und `ORANGE_LIGHT` (0xFFE0B0) für mittleres/unteres Drittel.
- **`applyRankedMetricColor`** — alle 5 Metrik-Labels im Kartendetail nutzen jetzt rang-relative Farben: Platz 1 der jeweiligen Metrik = dunkelgrün, letzter Platz = orange. Tabellenspalten bleiben unverändert (absolute Schwellen).
- **`computeMetricRankPct`** — Hilfsmethode sortiert `lastRanking` nach der jeweiligen Metrik und gibt normierte Rang-Position zurück; beachtet `inverted`-Flag für P0/Varianz.

### Tie-Handling im Assistenten
- **`resolveWithTiebreaker`** — neue Methode; findet alle Einträge innerhalb `1e-6` des Bestwertes, wendet 3-stufigen Tiebreaker an (ROI → EV/Runde → Kosten), gibt `TieResult` (winner, tiebreakerNote, otherNames) zurück.
- **`TieResult`** record — `winner`, `tiebreakerNote` (warum dieser gewann), `otherNames` (übrige Gleichstands-Karten).
- **`buildTieSuffix`** — HTML-Suffix nach Erklärung: kursiv grau, zeigt Tiebreaker-Grund und "Auch: X, Y, ...".
- Alle 8 Einzel-Profile nutzen `resolveWithTiebreaker` statt einfachem `.max()/.min()`.

### Spiellage-Analyse (9. Profil, oben im Assistenten)
- **`GamePhaseContext`** record — Spielphase (Früh/Mittel/Endspiel), GP-Zähler, GP-Synergy-Flags (bahnhofSuggested, ekzSuggested, fpSuggested, ftSuggested + bahnhofEvGain).
- **`computePhaseContext`** — berechnet Phase aus `effectiveTurnCount` und Landmark-Besitz; prüft GP-Synergien via `portfolioEvPerRound`-Vergleich.
- **`addContextProfile`** — gewichtete Gesamt-Empfehlung: pro Phase eigene Gewichte [ROI/EV/Safe/LowVar/Cheap/WinProb/Aggro/GPRush]; Gegner-Druck-Modifikator (+0.3 auf Aggro+GPRush wenn Gegner ≥3 GPs); normRank-Scoring bestimmt finale Empfehlung; zeigt Faktoren mit Gewicht ≥ 0.5 und GP-Hinweise.
- **Rendering** — blauer Hintergrund-Block (0xF0F4FF), TitledBorder; Phasen-Header, Empfehlung in fett, Faktorliste, GP-Hinweise in blau.
- **`GameSession.getEffectiveTurnCount()`** — neuer public Getter.
- **`ProbabilityCalc.portfolioEvPerRound(GameState, int)`** — neuer public Wrapper für `CardIncome.playerEvPerRound`.
- **`Strings`** — neue Strings: `rankLabel`, `assistantTiebreakerNote`, `assistantAlso`, `assistantContextTitle/Phase/Recommend/Factor/GPHint`, `assistantPhaseEarly/Mid/Late`, `assistantContextNoAffordable`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N1: Game Assistant — 4th Tab mit 8 Strategieprofilen

Deterministischer, regelbasierter Spielassistent als vierter Tab im rechten Panel.

- **4. Tab "Assistent"** — `JScrollPane` über `assistantPanel` (BoxLayout Y); `rebuildAssistantPanel()` wird am Ende von `rebuildTable()` aufgerufen und bei `showGameOver()` geleert.
- **8 Strategieprofile** — jedes Profil wählt den besten erschwingli­chen Eintrag aus `lastRanking` nach eigenem Kriterium:
  - **Bestes Investment** — höchster `roiOverHorizon`
  - **Maximaler Ertrag** — höchstes `evPerRound`
  - **Sicherheitsstrategie** — niedrigstes `probNoIncomeRound` (P0)
  - **Niedrige Varianz** — niedrigste `variance`
  - **Sparsam** — niedrigster Kartenpreis
  - **Gewinnwahrscheinlichkeit** — höchstes `winProbDelta` (zeigt Hinweis wenn nicht berechnet)
  - **Aggressiv** — höchstes `evPerRound` unter `rot`/`lila`-Karten
  - **GP Rush** — günstigstes ungebautes Großprojekt (erschwinglich oder nicht)
- **Rendering** — jede Zeile: fetter Profilname + HTML-Label mit Karte und 1-2 Sätzen Begründung; durch graue Trennlinie getrennt.
- **i18n** — `Strings.tabAssistant()`, `assistantProfileROI/EV/...()`, `assistantExplainROI/EV/...()`, `assistantNoAffordable()`, `assistantNoWinProb()` in DE und EN.
- **`RankingOptions.DEFAULT_HORIZON = 10`** — neue Klassenkonstante für externe Referenz aus UI-Code.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N0: Bürohaus-Tausch im UI — Dialog, Verlauf, Undo, Persistence

Der Bürohaus-Tausch (lila, Roll=6) war bisher nur in der Monte-Carlo-Simulation automatisch implementiert. In der echten Spielsession passierte nichts. Jetzt:

- **Swap-Dialog** — nach `session.applyTurn()` in `MainWindow.onConfirmTurn`: wenn Roll=6 und der aktive Spieler Bürohaus besitzt, wird `ProbabilityCalc.bürohausSwapNote` aufgerufen. Falls ein lohnender Tausch existiert, erscheint `JOptionPane.showConfirmDialog` mit Empfehlung und EV-Gewinn. Spieler kann "Ja" oder "Nein" wählen.
- **`GameSession.applyBürohausSwap(pi)`** — neue öffentliche Methode: ruft `BürohausLogic.executeSwap` auf dem State auf und patcht den letzten `TurnRecord` mit den Feldern `swappedAway`/`swappedIn`.
- **`TurnRecord` erweitert** — zwei neue optionale Felder (`swappedAway`, `swappedIn`, beide `Project` oder null) und ein 7-arg-Konstruktor. Alle kürzeren Konstruktoren delegieren mit `null`-Defaults. Vollständig rückwärtskompatibel.
- **Undo-Korrektheit** — `undoLastTurn()` replayed die History; bei Turns mit `swappedAway != null` wird `BürohausLogic.executeSwap` nach `applyTurn` erneut aufgerufen, um den Swap-State wiederherzustellen.
- **Persistence** — `GameSessionPersistence` serialisiert `swappedAway`/`swappedIn` als optionale Felder (nur wenn nicht null). Beim Laden wird `executeSwap` nach `applyTurn` für Turns mit Swap-Daten aufgerufen. Alte Saves ohne diese Felder laden korrekt.
- **Zugverlauf** — `TurnEntryPanel` zeigt eine neue Zeile "↔ [abgegebene Karte] → [erhaltene Karte]" kursiv in grau, wenn ein Tausch stattfand.
- **i18n** — `Strings.bürohausSwapTitle()` und `bürohausSwapPrompt()` in DE/EN.
- **`BürohausLogic`** — `findCandidates` und `SwapCandidates` von `private` auf package-private gesetzt, damit `GameSession` darauf zugreifen kann. `ProbabilityCalc.bürohausSwapNote` und `bürohausSwapEV` auf `public` gesetzt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M1: Math-Audit — drei Näherungen durch exakte Berechnungen ersetzt

- **A1 → Schritt-bewusste Münzprojektion in `evPerRound`** — Statt eines einzelnen Vorwärtsprojektions-Schritts für alle Spieler wird jetzt pro Gegner-Position das akkumulierte Blau-Einkommen des aktiven Spielers berechnet (`step × bluePerOppTurn`). Ergebnis: Rote-Karten-Klammerung ist für frühe vs. späte Gegner im Rundenzyklus korrekt.
- **A2 → Kontextbewusste Bürohaus-Swap-Bewertung in `BürohausLogic`** — `findCandidates` nutzt jetzt `contextualCardEvPerRound` statt `singleCardEvPerRound`. Sowohl die eigene schlechteste Karte als auch die Karte des Gegners werden im **echten Kontext des aktiven Spielers** bewertet (reale Einkaufszentrum-Flag, food/animal/production-Anzahl). Synergien (Markthalle mit vielen Food-Karten, Molkerei mit Bauernhöfen) werden korrekt berücksichtigt.
- **A3 → Inline-Kontextevaluation im GameSimulator** — `STATIC_EV_PER_COST`-Tabelle entfernt. `greedyBuy` berechnet jetzt pro Kaufkandidat `contextualCardEvPerRound(card, playerStats, n, oppCoins)` inline (~12 `get_I`-Aufrufe pro Karte, allokationsfrei). Die Spielsimulation berücksichtigt jetzt korrekt, dass ein Spieler mit 3 Food-Karten Markthalle viel höher bewertet als ein Spieler ohne.
- **`CardIncome.contextualCardEvPerRound`** — neue package-private Methode; 2d6-Pass + 1d6-Pass (max), skaliert nach Kartenfarbe (Blau ×N, Rot ×(N-1)). Wird von `BürohausLogic` und `GameSimulator` geteilt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## Rechtes Panel: drei Tabs (Erschwinglich / Nicht erschwinglich / Alle)

- **`ProbabilityCalc.rankAllProjects`** — neue öffentliche Methode, die alle Kandidaten (erschwinglich und nicht) berechnet und per `RankEntry.affordable`-Flag markiert. Win-Prob-Delta wird nur für erschwingliche Karten berechnet.
- **`RankEntry.affordable`** — neues Boolean-Feld (Standard: `true`); von `rankAllProjects` gesetzt.
- **`JTabbedPane` im rechten Panel** — drei JTable-Instanzen (`rankTable`, `rankTableUnaffordable`, `rankTableAll`), jede mit eigenem `DefaultTableModel`. Tab-Klick wählt automatisch ersten erschwingli­chen Eintrag im Kartendetails-Panel.
- **Dim-Renderer für "Alle"-Tab** — `CardNameRendererWithDim` und `NumericCellRendererWithDim`: nicht-erschwingliche Zeilen werden kursiv und grau gerendert.
- **`selectFirstAffordable()`** — Helper wählt ersten erschwingli­chen Eintrag und aktualisiert Kartendetails; ersetzt mehrfach duplizierte inline-Logik.
- **`refreshAll` / `refreshAfterRollChange`** nutzen jetzt `rankAllProjects` statt `rankPurchasableProjects`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## Bugs + Code-Qualität: Würfelzahlen > 6, Panel-Breite, Lokalisierung, Metrik-Färbung

- **`DiceFacePanel` Fallback** — `paintComponent` rendert die Würfelborder/-schatten normal, zeichnet bei Werten > 6 aber eine zentrierte Zahl statt Dots (Apfelplantage=10, Bergwerk=9, Markthalle=11/12)
- **Minimale Panel-Breite** — `JFrame.setMinimumSize(1020, 600)`, rechtes Panel `setMinimumSize(430, 0)` — Reload-Button und MC-Status-Text nicht mehr abgeschnitten
- **`Strings.coinsUnit()`** — `refreshRollPreview()` nutzt `Strings.coinsUnit()` statt hartkodiertem `"coins"` — korrekt lokalisiert in DE und EN
- **`MetricColorScheme`** — neues package-private Enum: 6 Konstanten (COST, EV, ROI, P0, VARIANCE, WIN_PROB_DELTA) mit Schwellwerten, `inverted`-Flag für P0/Varianz (kleiner = besser). `backgroundFor()` / `foregroundFor()` liefern Farbtöne. `NumericCellRenderer` nimmt Scheme-Instanz; jede Tabellenspalte hat eigenen Renderer. Neuer `applyMetricColor()`-Helper in `MainWindow` für Kartendetails-Panel.
- **Language Deep Clean** — `Strings`: `rightPanelTitle` → "Verfügbare Karten" / "Available Cards", `leftPanelTitle` → "Aktueller Zug-Tracker", `gameOverDesc` nutzt `grossProjekt()`, `colTipEV` DE auf EN-Detailniveau gebracht
- **Dead Code entfernt** — `UIUtils.java` (unbenutztes `capitalize()`) und `DICE.png` (Orphan-Ressource) gelöscht

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## DE/EN Lokalisation

Vollständige Deutsch/Englisch-Lokalisation.

- **`Strings.java`** — zentrale String-Registry mit `Locale`-Enum (DE/EN), `setLocale()`, privatem `s(de, en)`-Dispatcher und statischen Accessor-Methoden für jeden UI-String
- **`projects.json` + `Project`** — alle 19 Karten bekamen `name_en` + `description_en` (offizielle englische Namen); `Project`-Konstruktor auf 9 Args erweitert; `getLocalizedName()` / `getLocalizedDescription()` locale-abhängig; `ProjectLoader` von Gson-Auto-Mapping auf manuelles Field-Parsing umgestellt
- **GUI-Verdrahtung** — alle UI-Strings in `SetupWindow`, `MainWindow`, `SnapshotDialog`, `TurnEntryPanel` über `Strings.*`; Kartennamen/-beschreibungen über `Project.getLocalizedName/Description()`
- **Sprachwechsel** — `SetupWindow`: DE/EN-Radiobuttons, `rebuildUI()` in-place; `MainWindow`: `JMenuBar` mit Language-Menü (`JRadioButtonMenuItem`), `buildUI()` + `refreshAll()` in-place
- **`projectFromLabel`-Fix** — vorheriges `toLowerCase()` funktionierte nur auf Deutsch ("Weizenfeld" → "weizenfeld"). Ersetzt durch Reverse-Lookup via `getLocalizedName()` — korrekt in beiden Locales. `CardNameRenderer` nutzt denselben Ansatz.

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## Würfel-UI-Überarbeitung: programmatische Würfelgesichter, Selector-Strips, neu gestalteter Verlauf

- **`DiceFacePanel`** — zeichnet Würfelgesicht (Wert 1–6) mit Dots per `Graphics2D` inkl. Antialiasing und Schlagschatten. Drei Modi: reine Anzeige, selektiert, selektierbar-aber-nicht-selektiert. `DICE.png` wird nicht mehr verwendet.
- **`DiceSelectorPanel`** — horizontaler Strip von 6 `DiceFacePanel`s, Einfachauswahl. Optional (zweiter Strip): Klick auf selektierten Würfel deselektiert ihn (→ 1W6 trotz Bahnhof möglich)
- **Wurfeingabe** — Spinner ersetzt durch zwei `DiceSelectorPanel`s; erster immer sichtbar, zweiter nur bei Bahnhof. `getCurrentRoll()` summiert beide Strips.
- **`TurnEntryPanel`** — ersetzt HTML-`JLabel`-Verlauf. Zeigt: Spielername (farbig), gerendertes Würfelgesicht, DOUBLES-Badge, Münzdeltas pro Spieler (grün/rot), Kaufinfo. Kein "→ saved" mehr bei leerem Kauf.
- **Aktivierungswürfel in Kartendetails** — `topCardCostRow` (JPanel) statt `topCardCost` (JLabel); `buildActivationDice()` hängt einen `DiceFacePanel` pro Aktivierungswert an. GPs zeigen " · Großprojekt" kursiv.
- **Metrik-Legende** — ausklappbares Panel unterhalb des Metrik-Grids mit Kurzbeschreibungen für EV/round, ROI, P(0), Var, Win Δ.

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## Münz-Icon, Win-Prob-Interaktion, Game-Over-Fix

- **Deep Analysis × Win Prob** — `onToggleDeepAnalysis` setzt `rankOpts.mcSimulations > 0` nur wenn **beide** Flags aktiv sind; `onToggleWinProb` setzt korrekten MC-Count beim Einblenden, 0 beim Ausblenden
- **Münzanzeige** — `COIN.png`-Icon (18×18) + Zahl; `coinsAfterLabel` zeigt Post-Roll-Delta (+N grün / −N rot), immer sichtbar (kein Layout-Shift)
- **`showGameOver`** — nutzt jetzt `setWinProbRowVisible()` statt direktem `setVisible(false)` am Label

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Bugs + erweiterter Zugverlauf

- **Win-Prob-Row immer sichtbar** — `populateCenter` ruft `setWinProbRowVisible(showWinProb)` nach dem Setzen der Werte auf
- **Sortierung nach Table-Rebuild verloren** — `rebuildTable` speichert und stellt `sorter.getSortKeys()` wieder her; Column-Indizes geclampet für den Win-Δ-Spaltenfall
- **Deep Analysis zeigte Win-Prob-Spalte auto** — `onToggleDeepAnalysis` setzt `showWinProb` nicht mehr; "Show Win Prob Δ" ist alleiniges Gate
- **Linkes Panel Resize** — `BorderLayout`: Controls in `NORTH` (fixiert), History-`JScrollPane` in `CENTER` (füllt freien Platz)
- **`TurnRecord.coinDeltas`** — `int[]`-Feld (5-Arg-Konstruktor; kürzere Konstruktoren: `null`); `GameSession.applyTurn` berechnet und speichert Deltas; JSON-Serialisierung rückwärtskompatibel

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Polish-Batch: BoundedSpinner, Freizeitpark, GP-Ranking, MC-Controls

- **`BoundedSpinner`** — deaktiviert +/−-Buttons an Modellgrenzen; alle Spinner in `MainWindow` und `SnapshotDialog` nutzen ihn
- **Freizeitpark-Doppelwürfe** — `TurnRecord.isDoubles`; `GameSession.bonusTurnPending` + `effectiveTurnCount`; "Doubles?"-Checkbox ein/ausgeblendet je nach Kartenbesitz
- **GPs im Ranking + Kaufdropdown** — `rankPurchasableProjects` kombiniert `unbuilt_projects` mit nicht-besessenen GPs aus `ProjectLoader`
- **Sortierbare Tabelle** — `TableRowSorter` mit typ-bewussten Spalten; `NumericCellRenderer` färbt > 0.5 grün, < −0.5 rot
- **Deep Analysis** — `BoundedSpinner` (100–10 000), "⟳"-Reload-Button unabhängig vom Win-Prob-Toggle; MC per `SwingWorker` off-EDT
- **SnapshotDialog Startkarten** — Weizenfeld/Bäckerei-Spinner max=7 (1 Startkopie + 6 Markt); andere blau/grün/rot max=6
- **Panels umbenannt** — "Current Turn" → "Current Turn Tracker", "Best Purchase" → "Card Details"

**Tests:** 208 bestanden, 0 fehlgeschlagen.

---

## Kaufliste und Ranking auf Post-Roll-Münzen umgestellt

`MainWindow.postRollState()` kopiert den Spielstand und wendet `computeAllDeltasForRoll` an. Kaufdropdown, Ranking und Baseline-Win-Prob verwenden diesen Post-Roll-Zustand. Münzlabel zeigt "N → M (after roll)". `refreshAfterRollChange()` aktualisiert Liste + Vorschau live beim Würfelwechsel.

**Tests:** 165 bestanden, 0 fehlgeschlagen.

---

## File-Split: BürohausLogic + GameSessionPersistence

- **`BürohausLogic`** — `swapEV`, `swapNote`, `executeSwap` aus `ProbabilityCalc` extrahiert; gemeinsamer `findCandidates()`-Helper eliminiert duplizierte Scan-Schleifen. Public-API unverändert.
- **`GameSessionPersistence`** — 140 Zeilen JSON-Serialisierung + 11 Gson-Imports aus `GameSession` extrahiert. `GameSession.save/load` sind dünne Wrapper. `GameSession` hat jetzt nur noch 3 Standard-Imports.

**Tests:** 165 bestanden, 0 fehlgeschlagen.

---

## Frühphasen (kompakt)

### Supply-Modell-Fix + SnapshotDialog Multi-Copy + Würfelbereich + Roll-Preview

- Supply: Karte bleibt kaufbar bis alle 6 Kopien vergeben sind (statt nach erster Kopie); `GameStateBuilder.build()` und `GameState.initial()` korrigiert; `undoLastTurn()` replayed korrekt
- `SnapshotDialog`: blau/grün/rot als `JSpinner(0–6)` statt Checkbox; `cardControls Component[][]` statt `projectChecks JCheckBox[][]`
- Würfelbereich: `updateRollInput(Player)` setzt Range 1–6 / 1–12 + Default je nach Bahnhof-Besitz, bei jedem Turn-Wechsel
- Roll-Preview: `refreshRollPreview()` zeigt Münzdeltas pro Spieler sofort beim Würfelwechsel

### Baseline-Win-Prob-Anzeige

`ProbabilityCalc.computeBaselineWinProb()` (public); `refreshAll()` zeigt "Current win prob: X.X%" im Center-Panel via `baselineWinProbLabel`.

### Game-Over-Erkennung

`GameSession.isFinished()` / `getWinnerIndex()`; `onConfirmTurn` ruft `showGameOver()` nach dem 4. GP.

### Regeltreue: Einkommensreihenfolge + Gegenuhrzeigersinn-Zahlung

`computeNetGainForRoll`: Rot → Blau/Grün → Lila. Gegner gegen den Uhrzeigersinn iteriert: `(playerIndex - step + n) % n`. `computeAllDeltasForRoll` als Single Source of Truth; `GameSession.applyTurn` und `GameSimulator.applyRoll` nutzen es.

### Bürohaus Tausch-Logik

`bürohausSwapEV` = `max(0, bestOppCardEV − worstOwnCardEV)`; in `immediateEV` bei `P(roll=6)` eingebaut. `bürohausSwapNote` liefert "Tausche X gegen Ps Y" für Kartendetails. `executeBürohausSwap` mutiert `GameState` für MC-Simulation.

### Monte Carlo Deep Mode (Phase 5)

`GameSimulator`: stateless, greedy Policy (Landmarks zuerst, dann höchstes `evPerRound/cost`), Supply-Tracking (`Map<String,Integer>`), `MAX_TURNS=200`. `mcWinRate()` via `IntStream.parallel()` + `ThreadLocalRandom`. MC-Baseline einmal in `rankPurchasableProjects` berechnet, für alle Kandidaten wiederverwendet.

### Core Math Engine (Phase 2)

P1/P2-Tabellen precomputed. `get_I` für alle 19 Karten implementiert. Formeln: `evPerRound`, `roiOverHorizon` (geometrische Reihe, γ=0.95, T=10), Varianz, Softmax-Win-Prob, `rankPurchasableProjects`.

### Datenmodell (Phase 1)

`Project` unveränderlich (id-basiertes equals/hashCode). `Player.copy()` shallow-safe. `GameState.copy()` tief. `ProjectLoader` static cache. `GameStateBuilder` fluent.

---

## Designentscheidungen

| Frage | Entscheidung |
|-------|-------------|
| Bürohaus-EV | Heuristik: `max(0, bestOppCardEV − worstOwnCardEV)`, in `immediateEV` bei `P(roll=6)` |
| UI-Modell | Turn-by-turn mit Snapshot-Edit-Möglichkeit |
| Diskontfaktor | 0.95 pro Zug (konfigurierbar via `RankingOptions`) |
| MC-Standard | Off (analytisch); per Deep-Analysis-Toggle, Standard 1000 Sims |
| Supply-Modell | `unbuilt_projects` in `GameState` für Ranking; `Map<String,Integer>` in `GameSimulator` |
| Stadion-Regel | 2 Münzen von **jedem** Gegner (kein Gesamtlimit) |
| Fernsehsender-Regel | Bis zu 5 Münzen vom **einzelnen reichsten** Gegner |
