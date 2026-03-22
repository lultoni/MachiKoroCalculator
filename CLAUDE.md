# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Goal

A Machi Koro buy-decision calculator: given any game state (player coins, owned projects, number of players), determine the mathematically optimal purchase using expected value and probability calculations.

## Build & Run

This is an IntelliJ IDEA Java project. There is no build script — compile via IntelliJ or manually with `javac`.

**Manual compile (from repo root):**
```bash
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")
```

**Run main app:**
```bash
java -cp "out:gson-2.11.0.jar" logic.Main
```

**Run runtime tester:**
```bash
java -cp "out:gson-2.11.0.jar" Tests.RuntimeTester
```

Dependency: `gson-2.11.0.jar` (bundled in repo root). No other build tooling.

## Committing Changes

Commit at logical boundaries — not after every single edit, and not only at the very end of a large session. A good commit represents one coherent, self-contained improvement.

**Commit when:**
- A new method or class is fully implemented and working
- A bug is fixed (fix + any related test/doc update in one commit)
- A refactor is complete and the code still compiles/runs correctly
- A set of related small changes together form a meaningful unit (e.g. adding a new card to both `projects.json` and `get_I`)
- Documentation is updated as a follow-up to a prior code commit

**Do not commit:**
- After changing only one line or fixing a typo, unless it fixes a real bug
- Mid-implementation when the code is broken or stubs are unfinished
- Everything at once at the end of a long session — split by logical unit first

**Commit message style:** one concise imperative sentence describing *what* changed and *why* (e.g. `Implement evPerRound for blue card multi-turn EV` or `Fix get_I: add missing bürohaus case`). No bullet lists, no "misc changes".

Always ask the user before pushing to remote.

## Working with the User

**Always ask before making design decisions.** If the intended behavior, algorithm choice, or architecture is unclear, stop and ask rather than guessing. This applies to:
- Which metric to prioritize in rankings (immediateEV vs. ROI vs. win probability)
- How to model opponent behavior in multi-step lookahead
- Any trade-off between calculation speed and accuracy
- Whether to extend the new `probability` layer or remove legacy code

## Coding Practices

**Mathematical correctness first.** The primary goal is fast, correct probability math. Every formula must be analytically verified — no approximations unless explicitly approved.

**Performance:**
- Prefer closed-form expressions over loops where possible (e.g. precompute 2d6 probabilities as a `double[13]` constant, not computed per call).
- Cache repeated sub-computations (e.g. `PlayerStats` per player per `GameState`, not recomputed per project candidate).
- Avoid object allocation in hot paths (ranking all candidates calls `get_I` hundreds of times — keep it allocation-free).
- Deep-copy `GameState` only when the simulation mutates it; pass read-only state directly otherwise.

**Immutability:** `Project` is immutable — keep it that way. `Player` and `GameState` are mutable for simulation; always use `gs.copy()` before mutating in hypothetical scenarios.

**Naming:** Use descriptive names in the new layer. Short abbreviations like `f_c`, `a_c`, `p_c` in `get_I` are acceptable only because it's a dense math function — everywhere else use full names.

**No dead code:** Don't leave commented-out code or unused methods. Remove or implement stubs rather than accumulating TODOs without a plan.

**Single responsibility:** `ProbabilityCalc` is pure static math — no I/O, no UI, no state mutation of the passed-in `GameState` (use copies). Keep data loading in `ProjectLoader`, game rules in `get_I`, and ranking logic in `rankPurchasableProjects`.

## After Every Completed Task

After finishing any task, update all of the following that are affected by the change:

**`README.md`** — Update if:
- New features or calculation methods are added or removed
- The build/run instructions change
- The supported card set changes
- The project structure changes meaningfully

**`CLAUDE.md`** (this file) — Update if:
- The architecture changes (new classes, removed legacy code, new layers)
- New coding conventions or performance patterns are established
- The status of stub methods in `ProbabilityCalc` changes (implemented vs. TODO)
- New design decisions are made that future Claude instances need to know

**Javadoc in source files** — Update if:
- A method's parameters, return value, or behavior changes
- A new public/package-private method is added (add a Javadoc block)
- A TODO comment is resolved (remove or update the comment)

**`projects.json`** — Update if:
- A new card is added or an existing card's data is corrected
- A new field is introduced to the project schema

Do not batch documentation updates — apply them as part of the same change that modifies the code.

## Architecture

### Two parallel code layers (legacy vs. new)

There are **two separate, incompatible implementations** of the game model:

**Legacy layer (`src/logic/`)** — the old system, not yet removed:
- `logic.Game` / `logic.Player` / `logic.Project` / `logic.Category` — project data is hardcoded as a fixed array of 19 entries (index-based, not ID-based). EV is computed via `Player.getEX()` using mutable `diceValuesOwn`/`diceValuesOthers` arrays and `Project.doEffect()` side effects. `Game.getBestProjects()` does a naive score-diff approach without proper probability math.
- `gui.*` — Swing-based UI (BootWindow, GameWindow, etc.) wired to the legacy model.

**New probability layer (`src/logic/probability/`)** — the active development target:
- `probability.Project` — immutable POJO loaded from JSON (id, category, color, cost, dice_activation, is_grossprojekt).
- `probability.Player` — name, coins, `ArrayList<Project> owned_projects`, `hasProject(id)`.
- `probability.GameState` — holds `Player[]` + `ArrayList<Project> unbuilt_projects`, has deep `copy()`.
- `probability.ProjectLoader` — loads a single `Project` from `src/resources/jsons/projects.json` via Gson.
- `probability.ProbabilityCalc` — the core math class (see below).
- `probability.RankEntry` — result POJO for rankings.

### ProbabilityCalc — what's done vs. what's TODO

**Implemented:**
- `get_P1(r)` / `get_P2(r)` — 1d6 / 2d6 probabilities.
- `get_I(r, p_id, oop, eb, f_c, a_c, p_c, c, co)` — coin income/cost for a single project on a given roll. Encodes all base-game card effects as a switch on string ID.
- `values_per_r_per_p(playerProjects, playerCoins)` — builds a (players×4 colors) × 12 roll matrix of total income values.
- `immediateEV(gs, playerIndex, candidate, returnAfterCost)` — expected coins gained in the buyer's *current turn* after simulating a purchase. Handles 1d6 vs. 2d6 choice (Bahnhof), Freizeitpark double-roll EV, Funkturm. Calls `computeNetGainForRoll` (not yet visible in file — expected to exist) and `bestSecondRollEV`.

**Stub methods (TODO — bodies missing):**
- `evPerRound` — EV until all other players have had one turn (blue cards trigger on others' turns).
- `roiOverHorizon` — discounted ROI over N turns, returns `RankEntry`.
- `estimateWinProbDelta` — Expectimax or Monte Carlo win probability delta.
- `rankPurchasableProjects` — ranked list of all affordable projects using the above metrics.

### Project data

All 19 base-game cards are defined in `src/resources/jsons/projects.json` with fields: `category`, `is_grossprojekt`, `cost`, `dice_activation`, `color`, `description`.

**Colors:** `blau` (blue, triggers all turns), `rot` (red, triggers on others' turns), `grün` (green, own turn only), `lila` (purple, own turn only, unique), `gelb` (Großprojekte / landmarks).

**Categories used for synergy:** `food` (Markthalle multiplier), `animal` (Molkerei multiplier), `production` (Möbelfabrik multiplier).

**Key Großprojekte (gelb):** Bahnhof (enables 2d6 choice), Einkaufszentrum (boosts green/store cards), Freizeitpark (second roll on doubles), Funkturm (re-roll once on dislike).

### `get_I` note

`get_I` uses German string IDs (e.g. `"weizenfeld"`, `"café"`, `"möbelfabrik"`). These must match exactly the `id` keys in `projects.json`. The `bürohaus` card exists in the JSON but has no case in `get_I` yet.
