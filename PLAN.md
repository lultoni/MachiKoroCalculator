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
| 2.1 | Implement MCTS tree structure: chance nodes (dice) + decision nodes (buy/save) for all players (NORTH-STAR.md Section 7.1). | pending |
| 2.2 | Implement UCT selection + expansion + backpropagation (Section 7.3). | pending |
| 2.3 | Implement full-game rollout policy (simulate until someone wins, Section 7.2). | pending |
| 2.4 | Wire MCTS engine through the Interface layer: `evaluate(GameState, playerIndex, config) -> EngineResult`. | pending |
| 2.5 | Implement iteration budget modes: Fast (~500), Balanced (~5,000), Deep (~50,000) as engine configs in the registry. | pending |
| 2.6 | Validate MCTS results against existing analytical rankings for sanity. | pending |

---

## Phase 3: Web UI

Replace the Swing UI with a web SPA talking to the Java HTTP API.

| Task | Description | Status |
|------|-------------|--------|
| 3.1 | Design API contract: endpoints for game state CRUD, engine evaluation, turn tracking, session persistence. | pending |
| 3.2 | Set up SPA project (framework TBD: React or Svelte). | pending |
| 3.3 | Implement Turn Indicator component (Section 3.1). | pending |
| 3.4 | Implement Dice Interface component (Section 3.2). | pending |
| 3.5 | Implement Coin Flow Display with live preview (Section 3.3): Now/Roll/Buy columns, color-coding, hover-linked project updates. | pending |
| 3.6 | Implement Purchase Decision Area with dual paths: manual tracking + assistant recommendation (Section 3.4). | pending |
| 3.7 | Implement opponent turn tracking: minimal quick-entry (roll + buy), passive insights panel (Section 4). | pending |
| 3.8 | Implement settings screen: engine selection, mode toggle (Fast/Balanced/Deep), language, autosave. | pending |
| 3.9 | Implement session persistence UI: save/load in submenu, past games list, autosave (Section 9.1). | pending |
| 3.10 | Localization: wire DE/EN through the web UI. | pending |

---

## Phase 4: Kauf Assistent

Build the purchase assistant with transparent, structured explanations.

| Task | Description | Status |
|------|-------------|--------|
| 4.1 | Define explanation data model: factor list with weights, expandable detail, summary sentence (Section 5.2). | pending |
| 4.2 | Implement explanation generation from `EngineResult` data. Factors ordered by impact weight. | pending |
| 4.3 | Build expandable bullet-point UI component with dropdown details. | pending |
| 4.4 | Implement full ranked list view with sortable columns for comparison. | pending |
| 4.5 | Implement passive-turn insights: position analysis, opponent predictions, dashboard (Section 4). | pending |
| 4.6 | Implement pre-computation: start engine analysis during opponent turns. | pending |

---

## Phase 5: Head-to-Head Testing

Build the engine comparison and validation framework.

| Task | Description | Status |
|------|-------------|--------|
| 5.1 | Implement match runner: N games between two engine registry entries, parallel execution (Section 8.2). | pending |
| 5.2 | Implement result storage in `h2h-results.json`: match metadata, aggregate stats, per-game logs (Section 8.6). | pending |
| 5.3 | Build testing UI: high-level overview (win rates, avg game length) + detailed game replay with step-through (Section 8.4). | pending |
| 5.4 | Establish baseline: MCTS v1 (all modes) vs. itself as reference. | pending |

---

## Phase 6: Iteration & Future Work

| Task | Description | Status |
|------|-------------|--------|
| 6.1 | Build improved engine versions; test head-to-head against baseline. | pending |
| 6.2 | Implement depth-limited rollout with heuristic evaluation as alternative to full-game rollouts (Section 7.2). Test head-to-head. | pending |
| 6.3 | Card scraping: automated script to collect all cards (all expansions) from Machi Koro wiki for reference data (Section 6.6). | pending |
| 6.4 | Refine UI based on real gameplay usage. | pending |
| 6.5 | Expansion card support (out of scope until core is perfected). | pending |
| 6.6 | Opponent archetypes for more realistic simulation (Landmark-Rusher, Income-Maximizer, Blocker). | pending |

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
