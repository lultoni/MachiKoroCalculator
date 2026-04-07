# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## Status

Phases 1–6 complete. The app is a fully functional web-based Machi Koro purchase advisor with MCTS engines, structured explanations, and head-to-head engine testing.

**What works today:**
- All 19 base-game cards with correct income rules
- Turn-by-turn game tracking with undo and session persistence
- 10 engine classes with 35 configurations (6 MCTS variants, Flat Monte Carlo, Heuristic EV, Expectimax, Creator Engine)
- 11 advanced statistical metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy, IG, ETW, tempo, urgency, roll correlation)
- Weighted structured explanations with expandable detail per purchase option (9 factor categories)
- Passive-turn insights panel with ETW bars, tempo, supply warnings, narrative guidance
- Background pre-computation during opponent turns for instant results
- Head-to-head engine testing: full games where all decisions (dice, Funkturm, Bürohaus, purchase) come from real MCTS tree search
- H2H match runner with parallel game execution, mid-match seat swapping, CLI runner, round-robin tournament with leaderboard + matrix, auto battle mode, REST API, and visual game replay UI
- Automated Creator Engine parameter sweep via TPE (Bayesian optimization)
- Web SPA (React 19 + TypeScript + Vite 8 + Tailwind CSS 4) with full DE/EN localization
- 22 REST API endpoints (game state, session management, engine evaluation, insights, pre-computation, H2H testing, Glicko-2 ratings)
- 480+ test assertions across 30 test sections

**What's next:**
- UI refinement based on real gameplay
- Expansion card support (deferred until core is perfected)

## Documentation

| File | Purpose |
|------|---------|
| `NORTH-STAR.md` | Single source of truth: vision, architecture, UI spec, engine design |
| `TODO.md` | Task list — open work items |
| `CLAUDE.md` | Developer guidance: architecture, conventions, workflow |
| `ARCHITECTURE.md` | Technical reference: formulas, card rules, data model |
| `CHANGELOG.md` | Implementation history |
| `ARCHIVE.md` | Index of purged code concepts with commit references |

## Build & Run

Java 17+, `gson-2.11.0.jar` (bundled in repo root). Node.js 18+ for web frontend.

```bash
# Compile Java backend
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Run web server (localhost:8080, serves API + built SPA)
java -cp "out:src:gson-2.11.0.jar" server.ServerMain

# Tests (run specific section)
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Phase 5 Explanation"

# Web frontend development
cd web && npm install && npm run dev   # Vite dev server (hot reload)
cd web && npm run build                # Production build → web/dist/

# Head-to-head engine testing (CLI)
java -cp "out:src:gson-2.11.0.jar" h2h.H2hMain \
  --engineA mcts-v1-fast --engineB mcts-v1-depth3 \
  --games 100 --iterations 500 --verbose

# Round-robin tournament (all fast-tier engines)
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier fast --games 50

# Tournament with specific engines
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain \
  --engines mcts-v1-fast,mcts-v1-depth3,mcts-v1-greedy-tree-fast --games 20

# Tournament with ALL 35 engines (warning: may take hours)
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30
```

Note: `src` must be on the runtime classpath for resource loading.

## Architecture

```
UI (Web SPA) → Interface (orchestration) → Simulation Engines → Standard Calcs → Core (game rules)
```

- **Core** — game rules only: state, cards, dice, income, turn order, win condition
- **Standard Calcs** — reusable math: EV, ROI, probability, variance, 11 advanced risk/tempo metrics
- **Simulation Engines** — pluggable strategy: 10 engine classes with 35 configurations
- **Interface** — engine registry (JSON), request routing, result formatting
- **UI** — React 19 SPA (17 components, 8 hooks) with Java HTTP API backend (22 endpoints)

See `NORTH-STAR.md` for the complete specification.

## Running Tournaments

The tournament runner pits engines against each other in a round-robin: every engine plays every other engine once, with automatic mid-match seat swapping for fairness (P1/P2 positions flip after half the games).

All commands below assume you've compiled first:
```bash
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")
```

### Quick Test (~30 seconds)

Good for verifying the setup works. Three fast engines, 10 games each:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain \
  --engines mcts-v1-fast,mcts-v1-depth3,mcts-v1-greedy-tree-fast --games 10
```

### Speed Demons (~10 min)

The 6 fast engines that don't use expensive rollout policies (v1, greedy-tree, depth-limited, adaptive). Good for comparing tree-search strategies:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain \
  --engines mcts-v1-fast,mcts-v1-greedy-tree-fast,mcts-v1-depth3,mcts-v1-depth7,mcts-v1-depth10,mcts-v1-adaptive-fast \
  --games 50
```

### Fast Tier — The Standard Run (~hours)

All 10 fast-tier engines including the slow greedy-rollout and Boltzmann variants. This is the go-to tournament for comparing all engine families at their lightest configurations:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier fast --games 50
```

Note: Variants A (greedy-rollout) and B (Boltzmann) are 30–40× slower per evaluation than the other engines. Matchups involving these dominate runtime. Use `--estimate` to preview the expected duration.

### Balanced / Deep Tiers

Same round-robin, but with higher iteration budgets. Useful for testing whether stronger search compensates for simpler strategies:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier balanced --games 30
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --tier deep --games 20
```

### All Engines — The Full Run (~days)

All 35 engines across all tiers. 595 matchups. Only for when you really want the complete picture:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30
```

### Options Reference

| Flag | Default | Description |
|------|---------|-------------|
| `--tier <fast\|balanced\|deep>` | `fast` | Select engines by performance tier |
| `--engines <id1,id2,...>` | — | Select specific engines by ID |
| `--unleashed` | — | All 35 engines |
| `--games <n>` | 50 | Games per matchup (split across seat swap) |
| `--iterations <n>` | 0 | Override MCTS iterations (0 = registry default) |
| `--maxTurns <n>` | 200 | Max turns per game |
| `--no-swap` | — | Disable mid-match seat swapping |
| `--estimate` | — | Print runtime estimate and exit |
| `--verbose` | — | Print every game result |
| `--help` | — | Show usage with engine tier listing |

### Interpreting Results

The tournament prints four sections on completion (or on Ctrl+C for partial results):

1. **Leaderboard** — Engines ranked by overall win rate across all matchups
2. **H2H Matrix** — Win percentage of each row engine vs each column engine
3. **Matchup Details** — Per-pair breakdown: wins, losses, average game length, time
4. **Notable Stats** — Most dominant matchup, closest match, shortest/longest games

### Tips

- Use `--estimate` before long runs to preview expected duration
- Press **Ctrl+C** at any time — the runner prints results from all completed matchups
- Seat swapping eliminates first-player advantage: half the games each engine plays as P1, half as P2
- Games that hit the turn limit (200) use softmax win probability as a tiebreaker (no draws)

## Creator Engine Parameter Sweep

The sweep tool uses TPE (Tree-structured Parzen Estimator) to automatically optimize the Creator Engine's 20 scoring parameters. It runs H2H matches with different parameter vectors and converges on high-win-rate configurations.

All commands below assume you've compiled first:
```bash
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")
```

### Quick Smoke Test (~1 min)

Verify the sweep tool works. 5 trials, 20 games each:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 5 --games 20
```

### Standard Sweep (~30-60 min)

100 trials with 50 games each. The first 20 trials use Latin Hypercube Sampling for uniform coverage, then TPE guides the remaining 80:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 100 --games 50
```

### Against a Specific Opponent

Default opponent is `heuristic-ev-default` (instant, good heuristic play). For tougher calibration:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain \
  --opponent mcts-v1-fast --trials 50 --games 100 --seed 42
```

### Resume a Previous Sweep

Results are saved to `data/sweep-results.json`. Resume with `--resume`:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.SweepMain --trials 200 --resume
```

### Options Reference

| Flag | Default | Description |
|------|---------|-------------|
| `--trials <n>` | 100 | Total evaluation trials |
| `--games <n>` | 50 | Games per trial match |
| `--creator <id>` | `creator-fast` | Creator engine registry ID |
| `--opponent <id>` | `heuristic-ev-default` | Opponent engine ID |
| `--iterations <n>` | 0 | Override iterations (0 = registry default) |
| `--startup <n>` | 20 | Random trials before TPE kicks in |
| `--gamma <f>` | 0.25 | TPE good/bad split quantile |
| `--seed <n>` | random | Random seed for reproducibility |
| `--resume` | — | Continue from existing sweep-results.json |
| `--no-default` | — | Skip evaluating default params as trial 0 |
| `--help` | — | Show usage with parameter space listing |

### Output

The sweep prints progress per trial and a ranked top-10 summary at the end, including a ready-to-use `engines.json` config snippet for the best parameter vector found.

## Multi-Machine H2H Testing

You can run H2H matches on multiple machines and combine the results into a single rating pool. The workflow uses Export/Import in the web UI.

### Setup

1. Clone the repo and build on each machine (see Build & Run above)
2. On first startup, each machine loads the **baseline** from `src/resources/h2h-baseline/h2h-summaries.json` — a bundled snapshot of match results so everyone starts with the same ratings

### Workflow

1. **Run matches** on each machine — use the H2H page (manual matches, auto battle, CLI tournaments). Results are stored locally in `data/h2h-summaries.json`
2. **Export** — on each machine, click the **Export** button in the Results table header. This downloads the local `h2h-summaries.json` file
3. **Import** — on your main machine, click **Import** and select each exported file. The merge deduplicates by match ID: existing matches are skipped, only new ones are added. Ratings recompute automatically from the combined history
4. **Repeat** — import is idempotent. Importing the same file twice adds nothing. You can safely import from the same machine multiple times

### Single Source of Truth

To consolidate everything into one canonical dataset:

1. Pick one machine as the **main** (the one with the most results, or any machine)
2. Import all exported files from the other machines into it
3. Update the baseline snapshot so new clones start with the full history:
   ```bash
   cp data/h2h-summaries.json src/resources/h2h-baseline/h2h-summaries.json
   ```
4. Commit and push — now every clone of the repo starts with the combined ratings
5. On the other machines, delete `data/h2h-summaries.json` and restart the server — they'll load the new baseline automatically

### Notes

- **Summaries only** — Export/Import transfers match summaries (~40 KB for 60 matches), not game logs (~43 MB). Ratings and leaderboards work from summaries alone. Detailed game replays are only available on the machine that ran the match.
- **No conflicts** — match IDs are 8-character UUIDs, so collisions are effectively impossible. Import order doesn't matter.
- **Glicko-2 is deterministic** — replaying the same match history always produces the same ratings, regardless of which machine computed them.

## Cards (Base Game)

All 19 cards defined in `src/resources/jsons/projects.json`.

| Color | Triggers | Examples |
|-------|----------|----------|
| Blue  | Every turn (all players) | Weizenfeld, Bauernhof, Bergwerk |
| Green | Own turn only | Bäckerei, Molkerei, Möbelfabrik |
| Red   | Others' turns | Cafe, Familienrestaurant |
| Purple | Own turn, unique | Stadion, Fernsehsender, Bürohaus |
| Yellow | Landmarks (GP) | Bahnhof, Einkaufszentrum, Freizeitpark, Funkturm |
