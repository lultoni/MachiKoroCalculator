# PLAN.md — MachiKoroCalculator Restructure Backlog

The design rationale, architecture, and UI specification live in `NORTH-STAR.md`.
This file tracks the phased implementation work to realize that vision.

For history of what was built before the restructure, see `CHANGELOG.md`.
For the purge archive, see `ARCHIVE.md`.

---

## Phase 1: Foundation

Separate the existing codebase into the 5-layer architecture defined in NORTH-STAR.md Section 6.1.

| Task | Description | Status |
|------|-------------|--------|
| 1.1 | Extract Core layer: move `Project`, `Player`, `GameState`, `GameStateBuilder`, `TurnRecord`, `GameSession`, `GameSessionPersistence`, `ProjectLoader` into `core/` package. Core = pure game rules, no strategy. | done |
| 1.2 | Extract `CardIncome.get_I`, `P1`/`P2`, `computeAllDeltasForRoll`, `BürohausLogic.executeSwap` into Core. These are game rules, not strategy. | done |
| 1.3 | Create Standard Calcs layer: extract version-agnostic math utilities (EV computation, ROI formula, probability distributions, `geometricSum`, variance calculations) into `calcs/` package. | done |
| 1.4 | Define `SimulationEngine` interface + `EngineConfig` + `EngineResult` contracts (see NORTH-STAR.md Section 6.2). | done |
| 1.5 | Create Interface (orchestration) layer: engine registry loader (JSON), request routing, result formatting. | done |
| 1.6 | Create engine registry JSON file with placeholder entries. | done |
| 1.7 | Set up Java HTTP API server (lightweight, e.g. Javalin or built-in HttpServer) to expose game state + engine endpoints. | done |
| 1.8 | Adapt existing test suite (`RuntimeTester`) to work with the new layer separation. All 224 tests must pass. | done |

---

## Phase 2: First Engine (MCTS v1)

Implement MCTS with full-game rollouts as the first pluggable engine.

| Task | Description | Status |
|------|-------------|--------|
| 2.1 | Fix `BürohausLogic` purple-card swap scope bug in both `core/` and `logic.probability/`. Add tests (TDD). | done |
| 2.2 | Implement `SupplyTracker`: immutable value object tracking per-card market copy counts, built from `GameState`, cloned alongside tree. | done |
| 2.3 | Implement all 6 MCTS tree node types: `MctsNode`, `DiceChoiceNode`, `ChanceNode`, `FunkturmNode`, `BürohausNode`, `BuyDecisionNode`. | done |
| 2.4 | Implement `MctsRollout`: uniform-random full-game simulation with all special cases (Freizeitpark bonus turn, Bürohaus swap, Funkturm reroll, supply tracking). | done |
| 2.5 | Implement `MctsTree`: UCT selection, expansion, rollout, backpropagation iteration loop. | done |
| 2.6 | Implement `MctsV1Engine`: full `SimulationEngine` — builds tree, runs iterations/time budget, returns ranked options with all 14 metric keys and explanation factors. | done |
| 2.7 | Create `ServerMain`: registers `MctsV1Engine`, starts `ApiServer` on port 8080. `POST /api/evaluate` is now live. | done |
| 2.8 | Validate MCTS results: obvious-win test (3 landmarks, coins = cost of 4th) picks the winning landmark. All 20 TDD tests green. | done |

---

## Phase 3: Engine Variants + Calcs Extensions

Add engine variants A–E tested head-to-head against MCTS v1. Each variant changes exactly one thing from v1; a variant becomes the new default only if it beats all previous variants in H2H testing. Extend Standard Calcs with advanced statistical metrics before implementing variants.

### 3.0 — Calcs Audit & New Metrics (pre-work)

Add the following closed-form metrics to `calcs/Calcs.java`. All are computed over the discrete roll distribution (no simulation); they extend `roiOverHorizon` with richer risk and tempo information.

| Metric | Description |
|---|---|
| Sharpe ratio | `(evPerRound - riskFreeRate) / sqrt(variance)` — reward per unit income volatility |
| Sortino ratio | `(evPerRound - target) / sqrt(semiVariance)` — penalises only downside deviation |
| Kelly fraction | Optimal bet fraction adapted to per-card purchasing decision |
| VaR / CVaR | Worst-case floor income at configurable confidence levels (closed-form over discrete distribution) |
| HHI concentration | `Σ(income_share_r)²` normalised [0,1] — identifies "feast or famine" income concentration |
| Income entropy H | `-Σ P(r)×w(r)×log₂(w(r))` — roll coverage spread |
| Information gain IG | `H(portfolio) − H(portfolio + card)` — how much the card reduces income uncertainty |
| ETW | `max(0, landmarkCostRemaining − coins) / evPerRound` — estimated turns to win |
| Tempo advantage | `ETW_best_opponent − ETW_player` — turns ahead/behind nearest opponent |
| Purchase urgency | `portfolioDeltaEV × (1 − supplyFraction) × opponentDemand` — value × scarcity × competition |
| Roll correlation ρ | `Cov(card, portfolio) / (σ_card × σ_portfolio)` — coverage gap vs. redundancy |

### 3.A — Variant A: Greedy Rollout (`mcts-v1-greedy-rollout`)

**Hypothesis:** Informed rollouts converge faster than uniform-random for the same iteration budget. Tree phase unchanged (full UCT). Only the rollout policy changes.

**Rollout policy:**
- **Purchase:** landmark priority first (bahnhof gate heuristic); else card with highest `contextualCardEvPerRound × geometricSum − cost`; else save
- **Dice count:** 2d6 iff player owns a 7–12 activation card
- **Funkturm:** keep if current-roll income > expected reroll income; else reroll
- **Bürohaus:** execute `BürohausLogic.executeSwap()` (greedy best swap)

Registry entries: `mcts-v1-greedy-rollout-fast`, `-balanced`, `-deep`.

### 3.B — Variant B: Boltzmann Rollout (`mcts-v1-boltzmann-rollout`)

**Hypothesis:** Stochastic-but-informed rollouts offer a better exploration/accuracy trade-off. Replicates the archived `GameSimulator` Boltzmann policy.

**Rollout policy:** Boltzmann sampling from ROI scores with temperature T (`extra.rolloutTemperature`, default `"0.7"`); landmark priority deterministic. Dice/Funkturm/Bürohaus same as Variant A.

Registry entries: one per temperature (`T=0.3`, `T=0.7`, `T=2.0`) × fast/balanced/deep.

### 3.C — Variant C: Greedy Tree (`mcts-v1-greedy-tree`)

**Hypothesis:** UCT exploration overhead not worth it; argmax over ROI suffices for `BuyDecisionNode`. All other nodes (ChanceNode, FunkturmNode, BürohausNode, DiceChoiceNode) keep UCT. Rollout = uniform random (same as v1).

### 3.D — Variant D: Depth-Limited + Heuristic Eval (`mcts-v1-depth-limited`)

**Hypothesis:** Shorter rollouts with a good heuristic outperform full-game rollouts for the same iteration budget.

Rollouts stop after `extra.maxRolloutDepth` turns (default `"10"`). Score at cutoff = `WinProbability.computeBaselineWinProb(state, perspective)`. Tree phase unchanged. Registry entries: `mcts-v1-depth3`, `-depth7`, `-depth10`.

### 3.E — Variant E: Adaptive Budget (`mcts-v1-adaptive`)

**Hypothesis:** Concentrating iterations on close races improves recommendation quality within the same budget.

After initial survey of `config.iterations / 5` iterations, identify top-2 candidates. If within `extra.closeMargin` (default `"0.03"`), split remaining budget evenly. If one leads by > `extra.splitThreshold` (default `"0.06"`), allocate 70% to second place. Tree and rollout unchanged (same as v1).

| Task | Description | Status |
|------|-------------|--------|
| 3.0 | Add 11 advanced Calcs metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy H, IG, ETW, tempo, urgency, roll correlation). | done |
| 3.A | Implement Variant A: greedy rollout policy. H2H vs v1. | done |
| 3.B | Implement Variant B: Boltzmann rollout policy. H2H vs v1 + A. | done |
| 3.C | Implement Variant C: greedy tree selection. H2H vs all prior. | done |
| 3.D | Implement Variant D: depth-limited rollout + heuristic eval. H2H vs all prior. | done |
| 3.E | Implement Variant E: adaptive iteration budget. H2H vs all prior. | done |

---

## Phase 4: Web UI

Replace the Swing UI with a web SPA talking to the Java HTTP API.

| Task | Description | Status |
|------|-------------|--------|
| 4.1 | Design API contract: endpoints for game state CRUD, engine evaluation, turn tracking, session persistence. Session management (create, state, turn, bürohaus, undo, save, load, saves list, from-snapshot, insights), evaluate enhancement (metricRanges, perRollDeltas), static file serving. | done |
| 4.2 | Set up SPA project (React 18 + TypeScript + Vite + Tailwind CSS v4). Types, API client, hooks, utils, i18n. | done |
| 4.3 | Implement Turn Indicator component (Section 3.1). | done |
| 4.4 | Implement Dice Interface component (Section 3.2). | done |
| 4.5 | Implement Coin Flow Display with live preview (Section 3.3): Now/Roll/Buy columns, color-coding, hover-linked project updates. | done |
| 4.6 | Implement Purchase Decision Area with dual paths: manual tracking + assistant recommendation (Section 3.4). Includes RankedList with engine-adaptive columns and color-coded metric gradients. | done |
| 4.7 | Implement opponent turn tracking: minimal quick-entry (roll + buy), Bürohaus modal with engine-ranked swap options. | done |
| 4.8 | Implement settings screen: engine selection, mode toggle, language, autosave, user player. | done |
| 4.9 | Implement session persistence UI: save/load modal, saves list. | done |
| 4.10 | Localization: DE/EN through the web UI via React Context + localStorage. | done |

---

## Phase 5: Kauf Assistent

Build the purchase assistant with transparent, structured explanations.

| Task | Description | Status |
|------|-------------|--------|
| 5.1 | Define explanation data model: `ExplanationFactor` inner class with category, weight, summary, detail. Extended `Option` with `structuredFactors` and `summarySentence`. | done |
| 5.2 | Implement weighted explanation generation: two-pass enrichment in `MctsV1Engine` — cross-option means/ranges compute weights per category (winRate, income, synergy, risk, tempo, landmark, cost, coverage). Serialized in `EvaluateHandler`. | done |
| 5.3 | Build expandable factor UI: `ExplanationFactors` component with color-coded category badges, weight bars, click-to-expand detail. `AssistantPanel` uses summary sentence. i18n for 9 categories. | done |
| 5.4 | Enhance ranked list: added portfolioDeltaEV, winProbDelta, turnsToWin, tempoAdvantage columns. Row-expand shows structured factors inline. | done |
| 5.5 | Implement passive-turn insights: narrative generation (position, supply, strategy, landmark) in `SessionInsightsHandler`. `useInsights` hook + `InsightsPanel` with ETW bars, tempo, supply warnings, narrative cards. | done |
| 5.6 | Implement pre-computation: `PrecomputeCache` (single-entry, daemon thread), `PrecomputeHandler` (202 Accepted), `EvaluateHandler` cache check, `GameState.structuralHash()`. Frontend fires precompute after opponent turn. | done |

---

## Phase 6: Head-to-Head Testing

Build the engine comparison and validation framework. Used both during Phase 3 variant development and for ongoing baseline maintenance.

| Task | Description | Status |
|------|-------------|--------|
| 6.1 | Implement match runner: N games between two engine registry entries, parallel execution (Section 8.2). | done |
| 6.2 | Implement result storage in `h2h-results.json`: match metadata, aggregate stats, per-game logs (Section 8.6). | done |
| 6.3 | Build testing UI: high-level overview (win rates, avg game length) + detailed game replay with step-through (Section 8.4). | done |
| 6.4 | Establish baseline: MCTS v1 (all modes) vs. itself as reference. | done |
| 6.5 | Round-robin tournament runner: every engine vs every other engine with seat-swapping fairness. CLI with tier selection, `--unleashed` for all 24 engines, runtime estimation, leaderboard + H2H matrix output. Fix per-engine EngineConfig bug (registry extras were dropped). Add tier field to engine registry. | done |

---

## Phase 7: Iteration & Future Work

| Task | Description | Status |
|------|-------------|--------|
| 7.1 | Card scraping: automated script to collect all cards (all expansions) from Machi Koro wiki for reference data (Section 6.6). | done |
| 7.2 | Refine UI based on real gameplay usage. | pending |
| 7.3 | Expansion card support (out of scope until core is perfected). | pending |
| 7.4 | Opponent archetypes for more realistic simulation (Landmark-Rusher, Income-Maximizer, Blocker). | pending |
| 7.5 | Selective test runner: extend `RuntimeTester` to accept CLI args for running a named test or a named section (e.g. `--section "Variant D"` or `--test test_mcts_obvious_landmark_buy`). | done |
| 7.6 | Deep performance optimization of slow engine rollout variants. Variant A (Greedy Rollout, ~23s/500 iter) and Variant B (Boltzmann Rollout, ~22s/500 iter) are 30–40× slower than Variant D (~0.2s). Hot paths: `Calcs.evPerRound()` called per-card per-turn in every rollout purchase, `computeExpectedRollIncome()` doing 6–11 `RollResolver.computeAllDeltasForRoll` calls per Funkturm check, `BürohausLogic.findCandidates()` calling `contextualCardEvPerRound()` per card per player. Optimization strategies: (1) cache `PlayerStats` and `evPerRound` per player across rollout turns (only changes on purchase/swap), (2) precompute expected roll income once per rollout state rather than per Funkturm check, (3) use lightweight card-score approximations in rollouts instead of full analytical EV, (4) avoid `state.copy()` at rollout start — use reversible state mutations instead, (5) profile to find the actual dominant cost before optimizing. | done |
| 7.7 | Game-over decision review: compare player choices vs. engine recommendations throughout the game, highlighting where deviations helped or hurt. Requires storing engine `rankedOptions` snapshot in each `TurnRecord`. Show in a post-game analysis screen. | pending |
| 7.8 | Engine compliance test suite: generic `runEngineComplianceTests()` with 3 tiers (Universal, Metrics, Performance). Any new engine must pass all applicable tiers. Auto-discovers engines from registry. | done |
| 7.9 | Flat Monte Carlo engine (`flat-mc`): pure sampling, no tree — survey+focus budget allocation. Lower bound baseline for tree search comparison. | done |
| 7.10 | Heuristic EV engine (`heuristic-ev`): zero-search formula-based ranking from Calcs metrics. Instant decisions (<5ms). Tests whether search adds value. | done |
| 7.11 | Static TurnPlan support: `TurnPlan.staticPlan()` factory for non-MCTS engines to participate in H2H matches with heuristic dice/purchase decisions. | done |
| 7.12 | Web UI bug fixes: settings overflow, CoinFlowDisplay not updating, _wait_ display/duplicates, engine metadata, cost factor inversion, missing starter cards in ranked list, buy button, opponent coin flow. | done |

---

## Completed (Pre-Restructure)

All items from the old codebase are documented in `CHANGELOG.md`. Key milestones:

- All 19 base-game cards implemented in `get_I`
- Full roll resolution with correct income order (red -> blue/green -> purple)
- Analytical EV, ROI, variance, softmax win probability
- Monte Carlo simulation with Boltzmann policy
- Expectimax rollout tree (Stufe 1/2/3)
- Swing UI with turn tracking, card details, ranking, assistant, rollout tabs
- DE/EN localization
- Game session persistence (.mkoro files)
- 224 passing tests

These components serve as the foundation. Game rules and core data model carry forward; strategy and UI layers are rebuilt.
