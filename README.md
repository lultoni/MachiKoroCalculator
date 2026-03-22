# MachiKoroCalculator

A decision-support tool for the base game of Machi Koro. Given the current game state (players, coins, owned projects), it calculates the mathematically optimal project to purchase using expected value and probability analysis.

## Features

- Turn-by-turn game tracking with full undo history
- Snapshot mode: enter or edit the game state at any point; continue turn-by-turn from a snapshot
- Expected value calculation per project per game state
- Considers 1d6 vs. 2d6 choice (Bahnhof), Einkaufszentrum bonuses, Freizeitpark double-roll, Funkturm re-roll
- Ranks all affordable projects by EV/round, ROI over 10 turns (discounted), and risk (P=0 income)
- Optional win-probability delta column (toggle button)
- Three-column Swing GUI: turn input | top recommendation | full ranked table

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
    GameSession.java        # Turn-by-turn tracker with undo + snapshot conversion
    TurnRecord.java         # Immutable record of one turn (roll + purchase)
    ProbabilityCalc.java    # Pure-static math engine (EV, ROI, variance, rankings)
    ProjectLoader.java      # JSON loader with static cache
    RankEntry.java          # Result POJO for ranked recommendations
    RankingOptions.java     # Options (horizon, discount factor, win-prob flag)
  gui/newui/                # Swing UI
    SetupWindow.java        # New game setup (player count + names)
    MainWindow.java         # Main three-column game window
    SnapshotDialog.java     # Mid-game snapshot editor
  resources/jsons/          # projects.json — all 19 base-game cards
  Tests/                    # RuntimeTester — 108 unit tests + benchmarks
```

## Cards (Base Game)

All 19 cards from the Machi Koro base game are supported. Project data lives in `src/resources/jsons/projects.json`.

| Color  | Triggers on        | Examples                          |
|--------|--------------------|-----------------------------------|
| Blau   | Any player's turn  | Weizenfeld, Bauernhof, Bergwerk   |
| Grün   | Own turn only      | Bäckerei, Molkerei, Möbelfabrik   |
| Rot    | Opponents' turns   | Café, Familienrestaurant          |
| Lila   | Own turn, unique   | Stadion, Fernsehsender, Bürohaus  |
| Gelb   | Landmarks (GP)     | Bahnhof, Einkaufszentrum, …       |

