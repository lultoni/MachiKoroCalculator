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
- **Standard Calcs** — reusable, version-agnostic math: EV, ROI, probability distributions, variance. Any engine can call these.
- **Simulation Engines** — pluggable strategy implementations (MCTS, future alternatives). Each implements `SimulationEngine` interface and returns ranked purchase options with scores and explanations.
- **Interface** — orchestration layer: engine registry (JSON), request routing, result formatting.
- **UI** — web SPA (React or Svelte) talking to a local Java HTTP API. Handles display, input, interaction.

### Current State (Pre-Restructure)

The codebase is currently a single Java layer with Swing UI. The restructure separates it into the 5 layers above. Code that survives:

**Preserved (game rules + core data model):**
- `Project`, `Player`, `GameState`, `GameStateBuilder`, `TurnRecord` — core data model
- `GameSession`, `GameSessionPersistence` — game tracking + persistence
- `ProjectLoader` — card data loading from JSON
- `CardIncome.get_I`, `P1`/`P2` — per-card income calculation (all 19 cards) + dice probabilities
- `computeAllDeltasForRoll` — full roll resolution for all players
- `BürohausLogic.executeSwap` — swap execution mechanics
- `Strings` — localization registry (adapted for web)
- `projects.json` — card data

**Being replaced (see NORTH-STAR.md Section 10):**
- Strategy/ranking layer (`RolloutTree`, `WinProbabilityCalc`, `adaptiveMCRefinement`, `rankPurchasableProjects`, `GameSimulator`) → replaced by pluggable `SimulationEngine` implementations
- Entire Swing UI (`gui.newui/*`) → replaced by web SPA
- `AssistantConfig`, `PhaseFitter`, `LabelingWindow` → replaced by engine-computed explanations

## Build & Run

Java 17+, `gson-2.11.0.jar` (bundled in repo root).

**Manual compile (from repo root):**
```bash
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")
```

**Run main app (current Swing UI):**
```bash
java -cp "out:src:gson-2.11.0.jar" logic.Main
```

**Run tests:**
```bash
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

Note: `src` must be on the runtime classpath so `ClassLoader.getResourceAsStream` can locate `resources/jsons/projects.json`.

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

## Card Rules Reference

All 19 base-game cards are in `src/resources/jsons/projects.json`.

**Colors:** `blau` (triggers all turns), `rot` (triggers on others' turns), `grün` (own turn only), `lila` (own turn only, unique), `gelb` (Großprojekte/landmarks).

**Categories for synergy:** `food` (Markthalle), `animal` (Molkerei), `production` (Möbelfabrik).

**Großprojekte:** Bahnhof (1d6/2d6 choice — engine decision), Einkaufszentrum (+1 coin per green/red store), Freizeitpark (doubles bonus turn — game rule), Funkturm (re-roll choice — engine decision).

**`get_I` convention:** Uses German string IDs matching `projects.json` keys. `bürohaus` returns 0 (swap is non-monetary; EV handled separately).

**Income order:** Red → Blue & Green → Purple. Counter-clockwise for multiple red claims.
