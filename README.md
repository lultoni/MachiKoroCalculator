# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## What it does

- **Purchase advisor** — enter your game state (cards, coins, landmarks) and the engine tells you the best buy with a full explanation
- **All 19 base-game cards** with correct income rules, turn order, and all special card interactions (Bürohaus, Funkturm, Freizeitpark, etc.)
- **Turn-by-turn tracking** — undo, session persistence, passive-turn insights when it's not your turn
- **10 simulation engines** — from fast heuristics to MCTS tree search and the Creator Engine (parameter-tuned via Bayesian optimization)
- **Head-to-head engine testing** — run engines against each other with auto-battle, game replay, Glicko-2 ratings, and a visual leaderboard

---

## Quick Start

**Requirements:** Java 17+, Node.js 18+ (only needed for frontend development)

```bash
# 1. Compile
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# 2. Start the server (serves both API and the web app)
java -cp "out:src:gson-2.11.0.jar" server.ServerMain

# 3. Open http://localhost:8080 in your browser
```

That's it. The web app is pre-built and bundled — no npm needed to run.

---

## Head-to-Head Engine Testing

The H2H system lets you pit engines against each other and track their relative strength over time via Glicko-2 ratings. Everything is available through the **H2H tab** in the web app.

### Auto Battle (recommended)

Auto Battle continuously runs matches between engines, always picking the pairing with the highest combined rating uncertainty (Glicko-2 RD). This is the fastest way to build up reliable ratings:

1. Go to the H2H tab → **Auto Battle**
2. Select which engines to include and how many games per match
3. Click **Start** — it runs indefinitely until you click **Stop**
4. Results are saved automatically; Glicko-2 ratings update after each match

### Single Match (CLI)

Run a specific matchup from the command line:

```bash
java -cp "out:src:gson-2.11.0.jar" h2h.H2hMain \
  --engineA mcts-v1-fast --engineB creator-fast \
  --games 100
```

| Flag | Default | Description |
|------|---------|-------------|
| `--engineA <id>` | — | First engine |
| `--engineB <id>` | — | Second engine |
| `--games <n>` | 50 | Games to play (seat-swapped at midpoint) |
| `--iterations <n>` | 0 | Override iterations (0 = use registry default) |
| `--maxTurns <n>` | 200 | Turn limit per game |
| `--verbose` | — | Print each game result |

Results are saved to `data/h2h-summaries.json` and appear in the web UI automatically on next load.

---

## Creator Engine Parameter Sweep

The Creator Engine uses 20 tunable scoring parameters. The sweep tool uses TPE (Tree-structured Parzen Estimator / Bayesian optimization) to find parameter vectors that maximize win rate against a chosen opponent.

Results are saved to `data/sweep-results.json` **after every completed trial**, so you can stop at any time with Ctrl+C and never lose work.

### Running the sweep

```bash
# Compile first
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Quick smoke test — 5 trials, 20 games each (~1 minute)
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 5 --games 20

# Run indefinitely — stop with Ctrl+C when satisfied
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --infinite --games 50

# Fixed run — 100 trials, 50 games each (~30–60 min)
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 100 --games 50

# Resume all prior trials and continue to 200 total
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 200 --resume

# Against a stronger opponent for tougher calibration
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain \
  --opponent mcts-v1-fast --trials 50 --games 100 --seed 42
```

### Options reference

| Flag | Default | Description |
|------|---------|-------------|
| `--trials <n>` | 100 | Total evaluation trials |
| `--infinite` | — | Run until Ctrl+C (overrides `--trials`) |
| `--games <n>` | 50 | Games per trial |
| `--creator <id>` | `creator-fast` | Creator engine registry ID |
| `--opponent <id>` | `heuristic-ev-default` | Opponent engine ID |
| `--iterations <n>` | 0 | Override iterations (0 = registry default) |
| `--startup <n>` | 20 | Random (LHS) trials before TPE kicks in |
| `--gamma <f>` | 0.25 | TPE good/bad split quantile |
| `--seed <n>` | random | Random seed for reproducibility |
| `--resume` | — | Load all prior trials from `sweep-results.json` as context |
| `--no-default` | — | Skip evaluating default params as trial 0 |
| `--verbose` | — | Per-game results, param deltas, match details |
| `--help` | — | Show this help with full parameter space listing |

### How it works

1. **Trial 0** evaluates the current default parameter vector as a baseline
2. **Startup phase** (trials 1–20 by default) uses Latin Hypercube Sampling for uniform coverage of the 20-dimensional space
3. **TPE phase** fits a 1D Gaussian KDE per parameter on the good/bad observation split and samples from the region with the highest expected improvement
4. At the end (or on Ctrl+C), the top-10 vectors are printed with a ready-to-use `engines.json` config snippet for the best one

---

## Syncing Results Between Devices

Both H2H match results and sweep results can be accumulated across multiple machines and merged into a single dataset.

### H2H results

Match summaries are stored in `data/h2h-summaries.json`. Game logs (full per-turn replay data) are stored separately in `data/h2h-gamelogs/`.

**To sync:**

1. On each machine, export via the **Export** button in the H2H results table — this downloads `h2h-summaries.json`
2. On your main machine, click **Import** and select the exported files — duplicates are skipped by match ID, ratings recompute automatically
3. To persist as the new baseline for all future clones:
   ```bash
   cp data/h2h-summaries.json src/resources/h2h-baseline/h2h-summaries.json
   # then commit and push
   ```
4. Other machines: delete `data/h2h-summaries.json` and restart — they load the new baseline automatically

Notes:
- **Summaries only** are synced (~14 KB for 60 matches). Full game replay logs stay local; ratings work without them.
- **Import is idempotent** — safe to import the same file multiple times.
- **Glicko-2 is deterministic** — replaying the same match history always produces the same ratings, regardless of which machine computed them.

### Sweep results

Sweep trials are stored in `data/sweep-results.json`.

**To sync:**

Copy `data/sweep-results.json` from each machine and merge them manually — or just replace the file with the one from the machine that has run the most trials. Since `--resume` loads all trials from the file, the merged file gives TPE the full observation history to work from.

To persist sweep results alongside H2H data:
```bash
# Commit data/sweep-results.json to the repo (it's in .gitignore by default — add it if you want)
git add data/sweep-results.json
git commit -m "Add sweep results"
```

Or simply copy the file between machines over SSH / shared storage before running `--resume`.

---

## Development

### Frontend development (hot reload)

```bash
cd web && npm install && npm run dev   # Vite dev server on localhost:5173
cd web && npm run build                # Production build → web/dist/ (served by Java)
```

### Running tests

```bash
# Always use --section — never run the full suite (takes minutes)
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Phase 5 Explanation"
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Income"
```

---

## Architecture

```
UI (React 19 SPA) ──HTTP──▶ Interface ──▶ Engines ──▶ Calcs ──▶ Core
```

| Layer | Responsibility |
|-------|---------------|
| **Core** | Game rules only: state, cards, dice, income, turn order, win condition |
| **Calcs** | Reusable math: EV, ROI, probability, variance, 11 advanced metrics (Sharpe, Kelly, CVaR, etc.) |
| **Engines** | Pluggable strategy: 10 engine classes, 35 registry configurations |
| **Interface** | Engine registry (JSON), request routing, result formatting |
| **UI** | React 19 SPA (17 components, 8 hooks, DE/EN) + Java HTTP API (22 endpoints) |

**Engines:** MctsV1 + 5 variants (A–E), FlatMcEngine, HeuristicEvEngine, ExpectimaxEngine, CreatorEngine

See `NORTH-STAR.md` for the complete specification and `ARCHITECTURE.md` for formulas and data model details.

---

## Documentation

| File | Purpose |
|------|---------|
| `NORTH-STAR.md` | Vision, architecture, UI spec — the single source of truth |
| `TODO.md` | Open work items |
| `CLAUDE.md` | Developer guidance: conventions, workflow, critical invariants |
| `ARCHITECTURE.md` | Technical reference: formulas, card rules, data model |
| `CHANGELOG.md` | Implementation history |
| `ARCHIVE.md` | Index of purged code with commit references |

---

## Cards (Base Game)

All 19 cards defined in `src/resources/jsons/projects.json`.

| Color | Triggers | Examples |
|-------|----------|---------|
| Blue | Every turn (all players) | Weizenfeld, Bauernhof, Bergwerk |
| Green | Own turn only | Bäckerei, Molkerei, Möbelfabrik |
| Red | Others' turns | Cafe, Familienrestaurant |
| Purple | Own turn, unique | Stadion, Fernsehsender, Bürohaus |
| Yellow | Landmarks | Bahnhof, Einkaufszentrum, Freizeitpark, Funkturm |
