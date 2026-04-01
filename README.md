# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## Status

Phases 1–5 complete. The app is a fully functional web-based Machi Koro purchase advisor with MCTS engines and structured explanations.

**What works today:**
- All 19 base-game cards with correct income rules
- Turn-by-turn game tracking with undo and session persistence
- 6 MCTS engine variants (v1, greedy rollout, Boltzmann, greedy tree, depth-limited, adaptive budget)
- 11 advanced statistical metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy, IG, ETW, tempo, urgency, roll correlation)
- Weighted structured explanations with expandable detail per purchase option
- Passive-turn insights panel with ETW bars, tempo, supply warnings, narrative guidance
- Background pre-computation during opponent turns for instant results
- Web SPA (React 18 + TypeScript + Vite + Tailwind CSS v4) with full DE/EN localization

**What's next:**
- Phase 6: Head-to-head engine testing framework
- Phase 7: Performance optimization, game-over review, expansion support

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

# Run web server (localhost:8080)
java -cp "out:src:gson-2.11.0.jar" server.ServerMain

# Tests (run specific section)
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester --section "Phase 5 Explanation"

# Run (legacy Swing UI)
java -cp "out:src:gson-2.11.0.jar" logic.Main
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
