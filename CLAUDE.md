# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Vision

See `NORTH-STAR.md` for the single source of truth on what this program is, how it should work, and why.
See `PLAN.md` for the phased implementation backlog.
See `CHANGELOG.md` for the history of what was built and why.
See `ARCHITECTURE.md` for mathematical formulas, card rule conventions, and design rationales.

## Project Goal

A local desktop Machi Koro decision support tool. Given any game state, recommend the optimal purchase with a transparent explanation of why — powered by pluggable simulation engines (MCTS-based) and presented through a clean web UI.

The program answers one question: **"What should I buy right now, and why?"**

## Architecture Overview

The codebase follows a 5-layer architecture (see NORTH-STAR.md Section 6.1 for full detail):

```
UI (Web SPA) → Interface (orchestration) → Simulation Engines → Standard Calcs → Core (game rules)
```

- **Core** — pure game rules: `GameState`, `Player`, `Project`, `ProjectLoader`, card income (`get_I`), dice resolution, turn order, win condition. No strategy, no opinions.
- **Standard Calcs** — reusable, version-agnostic math: EV, ROI, probability distributions, variance, plus 11 advanced metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy, IG, ETW, tempo, urgency, roll correlation). Any engine can call these.
- **Simulation Engines** — pluggable strategy implementations (6 MCTS variants with 33 configurations). Each implements `SimulationEngine` interface and returns ranked purchase options with scores, structured explanations, and metrics.
- **Interface** — orchestration layer: engine registry (JSON with 33 entries), request routing, result formatting, pre-computation cache.
- **UI** — web SPA (React 19 + TypeScript + Vite 8 + Tailwind CSS 4) talking to a local Java HTTP API (20 endpoints). 17 components, 8 hooks, DE/EN localization.

### Current State

The restructure is complete (Phases 1–6 done). The 5-layer architecture is fully implemented and operational:

- **Core** layer (`core/` package): `GameState`, `Player`, `Project`, `ProjectLoader`, `CardIncome`, `RollResolver`, `BürohausLogic`, `GameSession`, `GameSessionPersistence`, `TurnRecord`
- **Standard Calcs** layer (`calcs/` package): `Calcs` (all metrics), `WinProbability`, `RankEntry`
- **Engines** layer (`engine/` package): `MctsV1Engine` (base) + 5 variants (A–E), `TurnPlan` (full-turn decision extraction), `mcts/` subpackage with all tree node types, rollout policies, `SupplyTracker`, `MctsTree`
- **Interface** layer (`iface/` package): `EngineOrchestrator`, `EngineRegistry`, `EngineRegistryEntry`
- **H2H** layer (`h2h/` package): `MatchRunner` (parallel game execution, seat swapping), `MatchConfig`, `GameLog`/`TurnLog`/`MatchResult`, `H2hResultStore` (JSON persistence), `H2hMain` (single-match CLI), `TournamentRunner`/`TournamentResult`/`TournamentMain` (round-robin tournament with leaderboard + matrix)
- **Server** layer (`server/` package): `ApiServer` with 20 endpoints, `SessionManager`, `PrecomputeCache`, `H2hHandler`, `EvaluateHandler`, `SessionInsightsHandler`, plus various session handlers
- **UI** layer (`web/` directory): React 19 SPA with full game dashboard, structured explanation UI, insights panel, pre-computation integration

**Legacy code** (`logic/`, `gui/`): The old Swing UI and probability calc code remain in the repo but are unused. The web SPA is the current app.

## Build & Run

Java 17+, `gson-2.11.0.jar` (bundled in repo root). Node.js 18+ for web frontend.

**Manual compile (from repo root):**
```bash
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")
```

**Run web server (serves API + built SPA on localhost:8080):**
```bash
java -cp "out:src:gson-2.11.0.jar" server.ServerMain
```

**Web frontend development:**
```bash
cd web && npm install && npm run dev    # Vite dev server (hot reload)
cd web && npm run build                 # Production build → web/dist/
```

**Run legacy Swing UI (deprecated, kept for reference):**
```bash
java -cp "out:src:gson-2.11.0.jar" logic.Main
```

**Run tests:**
```bash
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

**Run specific test section (preferred):**
```bash
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Section Name"
```

**Testing rule: Never run the full test suite.** Always use `--section` to run only the section(s) relevant to the code you changed. The full suite includes slow MCTS engine tests and benchmarks that take minutes. Assume all unrelated sections pass. If you changed code in multiple areas, run each relevant section separately.

Note: `src` must be on the runtime classpath so `ClassLoader.getResourceAsStream` can locate `resources/jsons/projects.json`.

**Run H2H engine match (CLI):**
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.H2hMain --engineA mcts-v1-fast --engineB mcts-v1-depth3 --games 100 --iterations 500
```

**Run round-robin tournament (CLI):**
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier fast --games 50
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --engines mcts-v1-fast,mcts-v1-depth3 --games 20
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30  # all 24 engines
```

## Committing Changes

Commit at logical boundaries — not after every single edit, and not only at the very end of a large session. A good commit represents one coherent, self-contained improvement.

**Commit when:**
- A new method or class is fully implemented and working
- A bug is fixed (fix + any related test/doc update in one commit)
- A refactor is complete and the code still compiles/runs correctly
- A set of related small changes form a meaningful unit
- Documentation is updated as a follow-up to a prior code commit

**Do not commit:**
- After changing only one line or fixing a typo, unless it fixes a real bug
- Mid-implementation when the code is broken or stubs are unfinished
- Everything at once at the end of a long session — split by logical unit first

**Commit message style:** one concise imperative sentence describing *what* changed and *why*. No bullet lists, no "misc changes".

Always ask the user before pushing to remote.

## Working with the User

**Always ask before making design decisions.** If the intended behavior, algorithm choice, or architecture is unclear, stop and ask rather than guessing. This applies to:
- Which layer a new piece of code belongs in
- How to model opponent behavior in MCTS rollouts
- Any trade-off between calculation speed and accuracy
- Whether to add a new engine version or modify an existing one
- UI layout and interaction design choices

## Coding Practices

**Mathematical correctness first.** Every formula must be analytically verified — no approximations unless explicitly approved.

**Performance:**
- Prefer closed-form expressions over loops where possible (precompute dice probabilities as constants).
- Cache repeated sub-computations (e.g. `PlayerStats` per player per `GameState`).
- Avoid object allocation in hot paths.
- Deep-copy `GameState` only when the simulation mutates it; pass read-only state directly otherwise.

**Immutability:** `Project` is immutable — keep it that way. `Player` and `GameState` are mutable for simulation; always use `gs.copy()` before mutating in hypothetical scenarios.

**Naming:** Use descriptive names. Short abbreviations like `f_c`, `a_c`, `p_c` in `get_I` are acceptable only because it's a dense math function — everywhere else use full names.

**No dead code:** Don't leave commented-out code or unused methods.

**Layer boundaries:** Respect the 5-layer separation. Core must not import from engines or UI. Standard Calcs must not import from engines or UI. Engines may import from Standard Calcs and Core. Interface may import from Engines, Standard Calcs, and Core. UI communicates with Interface via HTTP only.

## After Every Completed Task

After finishing any task, update all of the following that are affected by the change:

**`NORTH-STAR.md`** — Update if:
- A fundamental design decision changes (architecture, UI model, engine contract)
- Only with user approval — this is the source of truth

**`PLAN.md`** — Update if:
- A task is completed (mark it done)
- A new task is discovered (add it to the appropriate phase)
- A task's scope changes

**`README.md`** — Update if:
- New features are added or removed
- The build/run instructions change
- The project structure changes meaningfully

**`CLAUDE.md`** (this file) — Update if:
- The architecture changes (new layers, new patterns)
- New coding conventions are established
- The status of preserved vs. replaced components changes

**`ARCHITECTURE.md`** — Update if:
- A formula changes or a new one is added
- A card rule convention changes
- A new data model design decision is made

**`CHANGELOG.md`** — Update when:
- A meaningful feature or fix is shipped

**`ARCHIVE.md`** — Update when:
- Code is purged (add entry with description + commit hash)

**Javadoc in source files** — Update if:
- A method's parameters, return value, or behavior changes
- A new public/package-private method is added

**`projects.json`** — Update if:
- A new card is added or an existing card's data is corrected

Do not batch documentation updates — apply them as part of the same change that modifies the code.

### Documentation Accuracy Audit

At the end of each phase (or when the user requests it), perform a cross-document accuracy audit:

1. **Read every doc file** — README.md, NORTH-STAR.md, PLAN.md, CLAUDE.md, ARCHITECTURE.md, CHANGELOG.md, ARCHIVE.md
2. **Verify against the codebase** — Check that version numbers, file counts, component lists, API endpoint counts, test counts, tech stack versions, and behavioral descriptions match the actual code
3. **Fix contradictions** — When code and docs disagree, determine the ground truth from tests and source code, then fix the docs
4. **Check task statuses** — Ensure completed work is marked done in PLAN.md
5. **Update MEMORY.md** — Ensure session memory reflects the current state

## Card Rules Reference

All 19 base-game cards are in `src/resources/jsons/projects.json`.

**Colors:** `blau` (triggers all turns), `rot` (triggers on others' turns), `grün` (own turn only), `lila` (own turn only, unique), `gelb` (Großprojekte/landmarks).

**Categories for synergy:** `food` (Markthalle), `animal` (Molkerei), `production` (Möbelfabrik).

**Großprojekte:** Bahnhof (1d6/2d6 choice — engine decision), Einkaufszentrum (+1 coin per green/red store), Freizeitpark (doubles bonus turn — game rule), Funkturm (re-roll choice — engine decision).

**`get_I` convention:** Uses German string IDs matching `projects.json` keys. `bürohaus` returns 0 (swap is non-monetary; EV handled separately).

**Income order:** Red → Blue & Green → Purple. Counter-clockwise for multiple red claims.
