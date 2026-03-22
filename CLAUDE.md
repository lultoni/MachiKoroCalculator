# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Implementation Plan

See `PLAN.md` for the active backlog of known limitations and planned improvements.
See `CHANGELOG.md` for the history of what was built and why.
See `ARCHITECTURE.md` for mathematical formulas, card rule conventions, and design rationales.

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
java -cp "out:src:gson-2.11.0.jar" logic.Main
```

**Run tests / runtime tester:**
```bash
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

Note: `src` must be on the runtime classpath so `ClassLoader.getResourceAsStream` can locate `resources/jsons/projects.json`.

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

**`ARCHITECTURE.md`** — Update if:
- A formula changes or a new one is added
- A card rule convention changes (get_I perspective, red card payment, special cards)
- A new data model design decision is made

**`CHANGELOG.md`** — Update when:
- A meaningful feature or fix is shipped (add an entry under a new heading)

**`PLAN.md`** — Update if:
- A known issue is fixed (remove it from the backlog)
- A new limitation is discovered (add it)
- A future feature is approved for implementation (move it to a task)

**Javadoc in source files** — Update if:
- A method's parameters, return value, or behavior changes
- A new public/package-private method is added (add a Javadoc block)
- A TODO comment is resolved (remove or update the comment)

**`projects.json`** — Update if:
- A new card is added or an existing card's data is corrected
- A new field is introduced to the project schema

Do not batch documentation updates — apply them as part of the same change that modifies the code.

## Architecture

### Code layers

The codebase is now a single active layer with no legacy code.

**Probability layer (`src/logic/probability/`)** — math engine and data model:
- `probability.Project` — immutable POJO (id, category, color, cost, dice_activation, is_grossprojekt). Has `equals`/`hashCode` on `id` and `toString`. Id field is injected from the JSON key by `ProjectLoader`.
- `probability.Player` — name, coins, `ArrayList<Project> owned_projects`. Constructor validates `coins >= 0`. Has `copy()` (shallow-copies the list — safe because `Project` is immutable).
- `probability.GameState` — holds `Player[]` + `ArrayList<Project> unbuilt_projects`. Constructor validates 2–4 players, no nulls. `copy()` uses `Player.copy()` + `new ArrayList<>()`. `GameState.initial(numPlayers)` builds the standard starting state (each player: Weizenfeld + Bäckerei, 3 coins; 17 cards in unbuilt pool).
- `probability.ProjectLoader` — static cache (`Map<String, Project>`) built once at class load from classpath. `getProject(id)` returns `Optional<Project>`. `getAllProjects()` returns a new `ArrayList` of all 19 projects. **`src/` must be on the runtime classpath** for resource loading to work.
- `probability.GameStateBuilder` — fluent builder for constructing a `GameState` from user inputs (setPlayerName, setCoins, addProject, removeProject, build). Used by the UI and snapshot dialog.
- `probability.TurnRecord` — immutable record of one turn (playerIndex, roll, bought project or null).
- `probability.GameSession` — wraps a mutable `GameState` with a full `ArrayList<TurnRecord>` history. Methods: `applyTurn`, `undoLastTurn`, `toSnapshot` (→ builder), `fromSnapshot` (builder → new session), `nextPlayerIndex`. Bidirectional turn-by-turn ↔ snapshot conversion.
- `probability.ProbabilityCalc` — pure-static math engine (see below).
- `probability.RankEntry` — result POJO for rankings.
- `probability.RankingOptions` — options for `rankPurchasableProjects` (horizonTurns, discountFactor, mcSimulations, includeWinProbDelta).

**UI layer (`src/gui/newui/`)** — launched by `logic.Main.main()`:
- `gui.newui.SetupWindow` — game setup screen (player count, names, Start button). Builds the initial `GameSession` from a `GameStateBuilder` and opens `MainWindow`.
- `gui.newui.MainWindow` — three-column window: left = turn input (roll spinner, buy dropdown, Confirm Turn, Undo, Snapshot button, history log); center = top recommendation (card name, EV/round, ROI, risk, optional win-prob delta); right = full ranking table (sorted by ROI, color-coded by card type). Win-probability delta is on-demand (toggle button, not shown by default).
- `gui.newui.SnapshotDialog` — modal dialog for editing the full game state mid-session (tabs per player with coin spinner + checkbox grid of all 19 cards). On apply, calls `MainWindow.replaceSession()` to re-root the session at the snapshot.

**Entry point:** `logic.Main.main()` calls `SwingUtilities.invokeLater(SetupWindow::new)`.

### ProbabilityCalc — all methods implemented (Phase 2 complete)

- `get_P1(r)` / `get_P2(r)` — 1d6 / 2d6 probabilities (pre-computed arrays).
- `get_I(r, p_id, oop, eb, f_c, a_c, p_c, c, co)` — coin income/cost for a single project on a given roll. All 19 base-game cards implemented. `bürohaus` returns 0 here; its swap EV is handled separately in `immediateEV` via `bürohausSwapEV()`.
- `computeNetGainForRoll` / `computeOpponentTurnGainForRoll` — per-roll coin delta for active player and passive player respectively.
- `bürohausSwapEV(GameState, int)` — private helper that approximates the coin-equivalent EV of a bürohaus card-swap: `max(0, bestOppCardEV − worstOwnCardEV)` using `singleCardEvPerRound`.
- `bürohausSwapNote(GameState, int)` — package-visible helper that returns a human-readable swap recommendation string (e.g. "Swap your Weizenfeld for P1's Bergwerk"), or `null` if no beneficial swap. Used to populate `RankEntry.notes` in `rankPurchasableProjects`.
- `bestSecondRollEV` — EV of best re-roll after Freizeitpark doubles.
- `immediateEV` — own-turn EV including Bahnhof/Freizeitpark/Funkturm.
- `evPerRound` — full-round EV (own turn + N−1 opponent turns, blue and red cards).
- `roiOverHorizon` — geometric-series discounted ROI + variance + probNoIncome, returns `RankEntry`.
- `estimateWinProbDelta` — analytical softmax win-probability delta; also accepts MC path when `mcSimulations > 0`.
- `rankPurchasableProjects` — sorted list of all affordable cards by ROI; computes MC baseline once and reuses it across all candidates.
- `public static mcWinRate(GameState, int, int)` — runs N parallel Monte Carlo simulations via `IntStream.parallel()` + `ThreadLocalRandom`; returns win rate in [0, 1].
- Package-visible bridges: `computeNetGainForRollPublic`, `computeOpponentTurnGainForRollPublic` — used by `GameSession.applyTurn` and `GameSimulator`.

### GameSimulator (Phase 5)

`probability.GameSimulator` — stateless Monte Carlo game simulator. All methods are static; callers supply a per-thread `Random`.

- `simulate(GameState, Random) → int` — runs one full game from the given state using a greedy rollout policy. Returns winner index (0-based) or -1 on timeout (MAX_TURNS = 200).
- **Greedy policy:** (1) buy cheapest unbuilt landmark if affordable; (2) else buy highest `STATIC_EV_PER_COST` establishment; (3) else save.
- `STATIC_EV_PER_COST` — precomputed `evPerRound/cost` table built once at class load from a 4-player reference state; avoids calling ProbabilityCalc in the simulation hot loop.
- Supply: 6 copies per non-landmark card; tracked as `Map<String,Integer>`. Players cannot buy exhausted cards.
- Freizeitpark doubles → second roll applied immediately. Bahnhof → always uses 2d6 (heuristic).
- `public static boolean hasWon(Player)` — returns true if player owns ≥ 4 Großprojekte.

**Thread safety:** pass `ThreadLocalRandom.current()` and `state.copy()` per simulation. `rankPurchasableProjects` uses `IntStream.range(0, numSims).parallel()` for embarrassingly parallel execution.

### Project data

All 19 base-game cards are defined in `src/resources/jsons/projects.json` with fields: `category`, `is_grossprojekt`, `cost`, `dice_activation`, `color`, `description`.

**Colors:** `blau` (blue, triggers all turns), `rot` (red, triggers on others' turns), `grün` (green, own turn only), `lila` (purple, own turn only, unique), `gelb` (Großprojekte / landmarks).

**Categories used for synergy:** `food` (Markthalle multiplier), `animal` (Molkerei multiplier), `production` (Möbelfabrik multiplier).

**Key Großprojekte (gelb):** Bahnhof (enables 2d6 choice), Einkaufszentrum (boosts green/store cards), Freizeitpark (second roll on doubles), Funkturm (re-roll once on dislike).

### `get_I` note

`get_I` uses German string IDs (e.g. `"weizenfeld"`, `"café"`, `"möbelfabrik"`). These must match exactly the `id` keys in `projects.json`. `bürohaus` returns 0 in `get_I` because its card-swap effect is non-monetary; the EV contribution is computed separately in `immediateEV` via `bürohausSwapEV()` and added as `P(roll=6) × swapEV`.

