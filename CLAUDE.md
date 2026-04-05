# CLAUDE.md

Instructions for AI agents working on this codebase. Read this entire file before writing any code.

## Core Principles

These are non-negotiable. Every change must respect them.

1. **Mathematical correctness over convenience.** No approximations unless the user explicitly approves. Every formula must be analytically verifiable.
2. **Layer boundaries are sacred.** The 5-layer architecture exists to prevent coupling. Never violate import direction: Core <- Calcs <- Engines <- Interface <- UI (HTTP only).
3. **Ask before deciding.** If a design choice is ambiguous (algorithm, layer placement, UI layout, engine behavior), stop and ask the user. Do not guess.
4. **Code must compile and tests must pass.** Never commit broken code. Run relevant test sections before committing.
5. **No dead code, no bloat.** No commented-out code, no unused methods, no speculative features. Do only what was asked.
6. **Protect invariants.** Read Javadoc warnings before modifying any file. Many bugs have been fixed here — the comments explain why code works the way it does.

## Project

Local desktop Machi Koro decision support tool. One question: **"What should I buy right now, and why?"**

| Doc | Purpose |
|-----|---------|
| `NORTH-STAR.md` | Source of truth: vision, architecture, UI spec. Change only with user approval. |
| `TODO.md` | User task list. Work items go here. |
| `ARCHITECTURE.md` | Formulas, card rules, data model rationales, known engine issues. |
| `CHANGELOG.md` | What was built and why. |
| `ARCHIVE.md` | Index of purged code with commit hashes. |

## Architecture

```
UI (React 19 SPA) --HTTP--> Interface --> Engines --> Calcs --> Core
```

| Layer | Package | Responsibility |
|-------|---------|----------------|
| Core | `core/` | Game rules: state, cards, dice, income, turn order, win condition. No strategy. |
| Calcs | `calcs/` | Reusable math: EV, ROI, variance, 11 advanced metrics. Stateless. |
| Engines | `engine/` | 9 engine classes, 32 registry configs. Returns ranked options + explanations. |
| Interface | `iface/` | Engine registry (JSON), routing, result formatting. |
| Server | `server/` | Java HTTP API (21 endpoints), session management, pre-computation. |
| H2H | `h2h/` | Engine comparison: match runner, tournament, Glicko-2 ratings, game logging. |
| UI | `web/` | React 19 + TypeScript + Vite 8 + Tailwind CSS 4. 17 components, 8 hooks, DE/EN. |

**Engine classes:** MctsV1 (base) + 5 variants (A-E), FlatMcEngine, HeuristicEvEngine, ExpectimaxEngine.

## Build & Run

```bash
# Compile
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Web server (API + SPA on localhost:8080)
java -cp "out:src:gson-2.11.0.jar" server.ServerMain

# Frontend dev
cd web && npm install && npm run dev

# Tests (ALWAYS use --section, never run full suite)
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Section Name"

# H2H match
java -cp "out:src:gson-2.11.0.jar" h2h.H2hMain --engineA mcts-v1-fast --engineB mcts-v1-depth3 --games 100

# Tournament
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier fast --games 50
```

**`src` must be on the runtime classpath** for `ClassLoader.getResourceAsStream` to find `resources/jsons/projects.json`.

## Testing Rules

- **Never run the full test suite.** It takes minutes. Always use `--section "Name"` for only the sections relevant to your change.
- 470+ assertions across 30 sections. Supports `--section` (substring, case-insensitive) and `--test` filters.
- After changing code, compile and run the relevant section(s). Assume unrelated sections pass.
- If you changed code in multiple areas, run each relevant section separately.

## Coding Standards

**Performance:**
- Closed-form over loops. Precompute dice probabilities as constants.
- Cache sub-computations (`PlayerStats`, `RolloutEvCache`).
- No object allocation in hot paths. Reuse arrays.
- `gs.copy()` only when mutation is needed; pass read-only state otherwise.

**Immutability:** `Project` is immutable (id-based equals). `Player`/`GameState` are mutable for simulation — always copy before hypothetical mutation.

**Naming:** Descriptive names everywhere. Abbreviations (`f_c`, `a_c`, `p_c`) allowed only in `get_I` (dense math function).

**Layer imports:**
- Core: no imports from calcs, engines, or UI.
- Calcs: may import Core only.
- Engines: may import Calcs and Core.
- Interface/Server: may import Engines, Calcs, and Core.
- UI: communicates via HTTP only.

## Critical Invariants

These have caused bugs before. Read the Javadoc before touching these areas.

1. **Starter cards are separate from market supply.** Weizenfeld/Backerei given at game start do NOT reduce the 6-copy market pool. See `GameState.starterCopies()`, `SupplyTracker.fromGameState()`.
2. **Purple cards excluded from Burohaus swap candidates.** Both own and opponent sides. See `BurohausLogic.findCandidates()`.
3. **Income order: Red -> Blue & Green -> Purple.** Counter-clockwise for multiple red claims. See `RollResolver.computeAllDeltasForRoll()`.
4. **MCTS ChanceNode doubles splitting.** Even 2d6 sums are split into doubles/non-doubles branches with exact probabilities when Freizeitpark is relevant. Up to 15 children per ChanceNode. `navigateToRoll` requires `isDoubles` parameter for doubles-relevant nodes. See ARCHITECTURE.md Section 7.1.
5. **Purple card uniqueness.** Max 1 copy per player per purple card type. Enforced in all rollouts, BuyDecisionNode, and GameSession.
6. **Funkturm once per turn.** TurnPlan forces "keep" on FunkturmNode after reroll.
7. **Score convention.** MCTS scores are always from the perspective of the root `playerIndex` (1.0 = win, 0.0 = loss).

## Committing

Commit at logical boundaries. One coherent, self-contained improvement per commit.

- Imperative sentence describing *what* and *why*. No bullet lists.
- Never commit broken code or incomplete stubs.
- Always ask the user before pushing to remote.

## After Every Task

Update all docs affected by your change, in the same commit:

- `TODO.md` — mark tasks done, add discovered tasks.
- `CHANGELOG.md` — if a meaningful feature or fix was shipped.
- `README.md` — if features, build instructions, or structure changed.
- `CLAUDE.md` — if architecture or conventions changed.
- `ARCHITECTURE.md` — if formulas, card rules, or data model changed.
- `ARCHIVE.md` — if code was purged.
- **Javadoc** — if a method's signature, behavior, or contract changed.
- **Verify accuracy** — ensure counts, versions, and descriptions in docs match code.

## Self-Improvement Protocol

After each interaction, reflect on whether your approach was optimal:

- **If the user corrects you**, understand the root cause. Was it a doc gap, a wrong assumption, or ignoring an invariant? Fix the source (doc/comment/Javadoc) so it doesn't recur.
- **If a test fails unexpectedly**, investigate the invariant you violated before trying to fix the symptom.
- **If you're unsure**, ask. The cost of asking is always lower than the cost of a wrong change.
- **Track patterns.** If you notice a recurring issue, add a comment/Javadoc warning at the source.

## Card Rules (Quick Reference)

19 base-game cards in `src/resources/jsons/projects.json`. German IDs.

| Color | Triggers | Key rule |
|-------|----------|----------|
| `blau` | All turns | Bank pays owner |
| `grun` | Own turn | Bank pays owner |
| `rot` | Others' turns | Roller pays owner (clamped to roller's coins) |
| `lila` | Own turn, unique | Special effects (Stadion, Fernsehsender, Burohaus) |
| `gelb` | Landmarks | Bahnhof (dice choice), EKZ (+1 store), FZP (doubles bonus), FT (reroll choice) |

Categories: `food` (Markthalle synergy), `animal` (Molkerei), `production` (Mobelfabrik).
