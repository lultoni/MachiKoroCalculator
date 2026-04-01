# MachiKoroCalculator

A local desktop decision support tool for the board game Machi Koro (base game). Tracks the full game state, analyzes positions using pluggable simulation engines, and recommends the optimal purchase with a transparent explanation of *why*.

> **"What should I buy right now, and why?"**

## Status

Phases 1–5 complete. The app is a fully functional web-based Machi Koro purchase advisor with MCTS engines and structured explanations.

**What works today:**
- All 19 base-game cards with correct income rules
- Turn-by-turn game tracking with undo and session persistence
- 6 MCTS engine variants with 33 configurations (v1, greedy rollout, Boltzmann, greedy tree, depth-limited, adaptive budget)
- 11 advanced statistical metrics (Sharpe, Sortino, Kelly, VaR/CVaR, HHI, entropy, IG, ETW, tempo, urgency, roll correlation)
- Weighted structured explanations with expandable detail per purchase option (9 factor categories)
- Passive-turn insights panel with ETW bars, tempo, supply warnings, narrative guidance
- Background pre-computation during opponent turns for instant results
- Web SPA (React 19 + TypeScript + Vite 8 + Tailwind CSS 4) with full DE/EN localization
- 15 REST API endpoints (game state, session management, engine evaluation, insights, pre-computation)
- 370+ test assertions across 28 test sections

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

# Run (legacy Swing UI — deprecated, kept for reference)
java -cp "out:src:gson-2.11.0.jar" logic.Main
```

Note: `src` must be on the runtime classpath for resource loading.

## Architecture

```
UI (Web SPA) → Interface (orchestration) → Simulation Engines → Standard Calcs → Core (game rules)
```

- **Core** — game rules only: state, cards, dice, income, turn order, win condition
- **Standard Calcs** — reusable math: EV, ROI, probability, variance, 11 advanced risk/tempo metrics
- **Simulation Engines** — pluggable strategy: 6 MCTS variants with 33 configurations
- **Interface** — engine registry (JSON), request routing, result formatting
- **UI** — React 19 SPA (14 components, 7 hooks) with Java HTTP API backend (15 endpoints)

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
