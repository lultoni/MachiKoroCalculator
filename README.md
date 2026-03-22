# MachiKoroCalculator

A decision-support tool for the base game of Machi Koro. Given the current game state (players, coins, owned projects), it calculates the mathematically optimal project to purchase using expected value and probability analysis.

## Features

- Expected value calculation per project per game state
- Considers 1d6 vs. 2d6 choice (Bahnhof), Einkaufszentrum bonuses, Freizeitpark double-roll, Funkturm re-roll
- Ranks all affordable projects by immediate EV, ROI over N turns, and win probability delta
- Swing-based GUI for live game tracking

## Requirements

- Java 17+
- `gson-2.11.0.jar` (bundled)

## Build & Run

```bash
# Compile
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Run
java -cp "out:gson-2.11.0.jar" logic.Main
```

## Project Structure

```
src/
  logic/                    # Legacy game model (not yet removed)
  logic/probability/        # Active development — math engine
  gui/                      # Swing UI (wired to legacy model)
  resources/jsons/          # projects.json — all 19 base-game cards
  Tests/                    # Runtime performance tests
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
