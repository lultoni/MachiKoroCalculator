# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## Status

The project is undergoing a complete restructure from a monolithic Java/Swing app into a 5-layer architecture with a web frontend. See `NORTH-STAR.md` for the vision and `PLAN.md` for the implementation backlog.

**What works today (pre-restructure):**
- All 19 base-game cards implemented with correct income rules
- Turn-by-turn game tracking with undo and session persistence
- Analytical EV, ROI, variance, win probability calculations
- Monte Carlo simulation with Boltzmann policy
- Expectimax rollout tree (Stufe 1/2/3)
- Full DE/EN localization
- Swing UI (being replaced by web SPA)

**What's being built:**
- 5-layer architecture: Core (game rules) / Standard Calcs / Simulation Engines / Interface / UI
- MCTS-based strategy engine with pluggable versions
- Web SPA frontend with streamlined 4-component UI
- Head-to-head engine testing framework
- Transparent purchase assistant with structured explanations

## Documentation

| File | Purpose |
|------|---------|
| `NORTH-STAR.md` | Single source of truth: vision, architecture, UI spec, engine design |
| `PLAN.md` | Phased implementation backlog |
| `CLAUDE.md` | Developer guidance: architecture, conventions, workflow |
| `ARCHITECTURE.md` | Technical reference: formulas, card rules, data model |
| `CHANGELOG.md` | Implementation history |
| `ARCHIVE.md` | Index of purged code concepts with commit references |

## Build & Run (Current)

Java 17+, `gson-2.11.0.jar` (bundled in repo root).

```bash
# Compile
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Run (Swing UI)
java -cp "out:src:gson-2.11.0.jar" logic.Main

# Tests (224 passing)
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

Note: `src` must be on the runtime classpath for resource loading.

## Architecture (Target)

```
UI (Web SPA) → Interface (orchestration) → Simulation Engines → Standard Calcs → Core (game rules)
```

- **Core** — game rules only: state, cards, dice, income, turn order, win condition
- **Standard Calcs** — reusable math: EV, ROI, probability, variance
- **Simulation Engines** — pluggable strategy: MCTS and future alternatives
- **Interface** — engine registry, request routing, result formatting
- **UI** — web SPA with local Java HTTP API backend

See `NORTH-STAR.md` for the complete specification.

## Cards (Base Game)

All 19 cards defined in `src/resources/jsons/projects.json`.

| Color | Triggers | Examples |
|-------|----------|----------|
| Blue  | Every turn (all players) | Weizenfeld, Bauernhof, Bergwerk |
| Green | Own turn only | Bäckerei, Molkerei, Möbelfabrik |
| Red   | Others' turns | Cafe, Familienrestaurant |
| Purple | Own turn, unique | Stadion, Fernsehsender, Bürohaus |
| Yellow | Landmarks (GP) | Bahnhof, Einkaufszentrum, Freizeitpark, Funkturm |
