# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## Status

Phases 1–6 complete. The app is a fully functional web-based Machi Koro purchase advisor with MCTS engines, structured explanations, and head-to-head engine testing.

**What works today:**
- All 19 base-game cards with correct income rules
- Turn-by-turn game tracking with undo and session persistence
- 9 engine classes with 32 configurations (6 MCTS variants, Flat Monte Carlo, Heuristic EV, Expectimax)
- 11 advanced statistical metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy, IG, ETW, tempo, urgency, roll correlation)
- Weighted structured explanations with expandable detail per purchase option (9 factor categories)
- Passive-turn insights panel with ETW bars, tempo, supply warnings, narrative guidance
- Background pre-computation during opponent turns for instant results
- Head-to-head engine testing: full games where all decisions (dice, Funkturm, Bürohaus, purchase) come from real MCTS tree search
- H2H match runner with parallel game execution, mid-match seat swapping, CLI runner, round-robin tournament with leaderboard + matrix, REST API, and visual replay UI
- Web SPA (React 19 + TypeScript + Vite 8 + Tailwind CSS 4) with full DE/EN localization
- 21 REST API endpoints (game state, session management, engine evaluation, insights, pre-computation, H2H testing, Glicko-2 ratings)
- 470+ test assertions across 30 test sections

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

# Tournament with ALL 32 engines (warning: may take hours)
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30
```

Note: `src` must be on the runtime classpath for resource loading.

## Architecture

```
UI (Web SPA) → Interface (orchestration) → Simulation Engines → Standard Calcs → Core (game rules)
```

- **Core** — game rules only: state, cards, dice, income, turn order, win condition
- **Standard Calcs** — reusable math: EV, ROI, probability, variance, 11 advanced risk/tempo metrics
- **Simulation Engines** — pluggable strategy: 9 engine classes with 32 configurations
- **Interface** — engine registry (JSON), request routing, result formatting
- **UI** — React 19 SPA (17 components, 8 hooks) with Java HTTP API backend (21 endpoints)

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

All 32 engines across all tiers. 496 matchups. Only for when you really want the complete picture:
```bash
java -cp "out:src:gson-2.11.0.jar" h2h.TournamentMain --unleashed --games 30
```

### Options Reference

| Flag | Default | Description |
|------|---------|-------------|
| `--tier <fast\|balanced\|deep>` | `fast` | Select engines by performance tier |
| `--engines <id1,id2,...>` | — | Select specific engines by ID |
| `--unleashed` | — | All 32 engines |
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

## Cards (Base Game)

All 19 cards defined in `src/resources/jsons/projects.json`.

| Color | Triggers | Examples |
|-------|----------|----------|
| Blue  | Every turn (all players) | Weizenfeld, Bauernhof, Bergwerk |
| Green | Own turn only | Bäckerei, Molkerei, Möbelfabrik |
| Red   | Others' turns | Cafe, Familienrestaurant |
| Purple | Own turn, unique | Stadion, Fernsehsender, Bürohaus |
| Yellow | Landmarks (GP) | Bahnhof, Einkaufszentrum, Freizeitpark, Funkturm |
