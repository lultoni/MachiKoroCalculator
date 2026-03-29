# MachiKoroCalculator

A decision-support tool for the base game of Machi Koro. Given the current game state (players, coins, owned projects), it calculates the mathematically optimal project to purchase using expected value and probability analysis.

## Features

- Turn-by-turn game tracking with full undo history
- Snapshot mode: enter or edit the game state at any point; continue turn-by-turn from a snapshot
- Expected value calculation per project per game state
- Considers 1d6 vs. 2d6 choice (Bahnhof), Einkaufszentrum bonuses, Freizeitpark double-roll, Funkturm re-roll, and Bürohaus card-swap EV heuristic
- Landmarks (Großprojekte) are included as purchasable options alongside regular establishments
- Freizeitpark doubles tracking: checkbox shown when the active player owns both Bahnhof and Freizeitpark; grants a bonus second turn (no chaining)
- Ranks all affordable projects by EV/round, ROI over 10 turns (discounted), and risk (P=0 income); sortable columns
- Optional win-probability delta column: analytical (softmax) or Monte Carlo (toggle button)
- **Deep Analysis mode**: configurable Monte Carlo game simulations (100–10 000) per candidate; independent toggle from win-probability display; runs off the EDT via SwingWorker
- Three-column Swing GUI: "Current Turn Tracker" | "Card Details" | ranked table of all affordable cards

## Documentation

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Agent/developer guidance: architecture, coding conventions, workflow |
| `ARCHITECTURE.md` | Mathematical formulas, card rule conventions, design rationales |
| `CHANGELOG.md` | Implementation history: what was built, why, and what decisions were made |
| `PLAN.md` | Active backlog: known limitations and planned improvements |

## Requirements

- Java 17+
- `gson-2.11.0.jar` (bundled)

## Build & Run

```bash
# Compile
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Run
java -cp "out:src:gson-2.11.0.jar" logic.Main

# Tests
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

## Project Structure

```
src/
  logic/                    # Entry point only (Main.java)
  logic/probability/        # Probability layer — math engine + data model
    GameState.java          # Mutable game state (Player[] + unbuilt pool)
    GameStateBuilder.java   # Fluent builder for constructing GameState from user inputs
    GameSession.java        # Turn-by-turn tracker with undo, Freizeitpark bonus turns, snapshot
    GameSessionPersistence.java  # JSON save/load (isolated from GameSession)
    TurnRecord.java         # Immutable record of one turn (roll, purchase, isDoubles flag)
    ProbabilityCalc.java    # Pure-static math engine (EV, ROI, variance, rankings, MC)
    BürohausLogic.java      # Bürohaus swap helpers (extracted from ProbabilityCalc)
    GameSimulator.java      # Stateless Monte Carlo game simulator (greedy rollout policy)
    ProjectLoader.java      # JSON loader with static cache
    RankEntry.java          # Result POJO for ranked recommendations
    RankingOptions.java     # Options (horizon, discount factor, win-prob flag, MC sims)
  gui/newui/                # Swing UI
    SetupWindow.java        # New game setup (player count + names)
    MainWindow.java         # Main three-column game window
    SnapshotDialog.java     # Mid-game snapshot editor
    BoundedSpinner.java     # JSpinner subclass that disables +/- at model boundaries
  resources/jsons/          # projects.json — all 19 base-game cards
  Tests/                    # RuntimeTester — 208 unit tests + benchmarks
```

## UI Overview

**Left panel — Current Turn Tracker**
Roll spinner with dynamic range (1–6 without Bahnhof, 1–12 with). Arrow buttons disable at the boundary (no over-scroll). A "Doubles?" checkbox appears when the active player owns both Bahnhof and Freizeitpark; checking it grants a bonus turn to the same player after Confirm. The buy dropdown shows only cards the player can afford after the roll (post-roll coins). Full turn history below, color-coded by player with doubles badge and landmark marker.

**Center panel — Card Details**
Shows the top-recommended card with its EV/round, ROI, risk metric, optional win-probability delta, and a per-roll outcome preview (which player gains or loses coins). Coin label shows "N → M (after roll)" when the roll changes the active player's balance. Current baseline win probability always visible.

**Right panel — All Affordable Cards**
Full ranked table (sortable by any column). Color-coded values: green = strong positive, red = strong negative. Landmarks (GP) are included alongside regular establishments. Deep Analysis toggle enables configurable MC simulation count.

## Cards (Base Game)

All 19 cards from the Machi Koro base game are supported. Project data lives in `src/resources/jsons/projects.json`.

| Color  | Triggers on        | Examples                          |
|--------|--------------------|-----------------------------------|
| Blau   | Any player's turn  | Weizenfeld, Bauernhof, Bergwerk   |
| Grün   | Own turn only      | Bäckerei, Molkerei, Möbelfabrik   |
| Rot    | Opponents' turns   | Café, Familienrestaurant          |
| Lila   | Own turn, unique   | Stadion, Fernsehsender, Bürohaus  |
| Gelb   | Landmarks (GP)     | Bahnhof, Einkaufszentrum, …       |
