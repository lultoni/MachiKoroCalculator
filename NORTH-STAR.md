# North Star — Machi Koro Calculator

This is the single source of truth for what this program is, what it does, and how it should be built.
Every design decision, architectural choice, and UI element must trace back to this document.

---

## 1. What Is This Program?

A **local desktop application** that helps a single Machi Koro player make the best possible purchase decision on every turn by:

1. Tracking the full game state (all players' cards, coins, and actions)
2. Analyzing the current position using a pluggable simulation engine
3. Recommending the optimal purchase with a clear, transparent explanation of *why*
4. Letting the player browse, compare, and ultimately decide for themselves

The program is a **decision support tool**, not an autopilot. The player always has the final say.

---

## 2. The Player's Game Loop

From the perspective of the player using this tool, every turn follows this cycle:

```
1. It's my turn
2. I roll the dice
3. Coins flow (I gain/lose, others gain/lose)
4. I decide what to buy (or save)      ← This is where the tool provides maximum value
5. I buy a project / save
6. My turn ends
7. Between my turns: I passively earn/lose coins from others' rolls (blue/red cards)
```

Steps 1–3 and 5–7 are **tracking** (input). Step 4 is **analysis** (output).

Between my turns, the tool tracks opponent actions (rolls + purchases) to keep the game state accurate. This tracking time is also used productively: the engine pre-computes analysis for my next turn and shows insights about how opponent actions affect my position.

### Win Condition

A player wins by building all 4 Großprojekte (landmarks). Draws are impossible.

### Player Goals During Play

- **Maximize own coins** → enables buying projects faster → approaches win condition
- **Minimize opponent coins** → slows their progress → reduces their win probability
- **Manage risk** → avoid situations where bad rolls stall or reverse progress
- These combine into the single north star metric: **win probability**

---

## 3. Core UI Components

The UI has exactly **four core responsibilities**. Everything else is supportive.
The design principle is: **fast input, clear output, no clutter.**

### 3.1 Turn Indicator

Shows whose turn it is. Provides quick-entry for tracking any player's turn.

- Active player highlighted
- Turn order visible
- Optional turn counter

### 3.2 Dice Interface

Quick, frictionless way to enter the rolled dice result.

- Works for 1d6 and 2d6
- Indicates doubles (relevant for Freizeitpark bonus turn)

### 3.3 Coin Flow Display

Always-visible display showing how coins change through the turn phases.

```
┌──────────┬────────────────┬──────────┐
│   Now    │      Roll      │   Buy    │
│    12    │       15       │    7     │
│          │  (Dice Result) │  (Café)  │
└──────────┴────────────────┴──────────┘
```

- **Now**: coins at start of turn
- **Roll**: coins after dice resolution (all blue/red/green/purple effects applied)
  - (optional) Shows the dice results as a quick reference, updates when the dice interface is updated
- **Buy**: coins after purchasing — **updates live** as the user hovers/selects different purchase options
  - Shows the project name in parentheses below the number
  - Every interactive element in the UI that represents a project updates this column on hover
- Color-Coding for numbers should be used
  - "Now" is the reference
  - If "Roll" or "Buy" are...
    - higher -> green numbers
    - same -> no concrete color difference to "now"-number-color
    - lower -> red numbers
    - both fields are supposed to choose their color based on their value, so both can have seperate colors

### 3.4 Purchase Decision Area

Two distinct but coexisting interaction paths:

#### Path A: Manual Tracking (always available)
A simple project selector + confirm button. Used for:
- Tracking your own purchase when ignoring the assistant
- Tracking opponent purchases (opponents never see the assistant)

#### Path B: Assistant Recommendation (for the active user's turn)
The Kauf Assistent panel with:
- The recommended purchase + a prominent "Buy" button to execute it
- Coin-after-buy info displayed consistently with the tracking path
- Bullet-point explanation of *why* (see Section 5)
- Full ranked list of all options with scores for comparison

The user can freely switch between reading the assistant's analysis and just picking a project manually. Neither path blocks the other.

---

## 4. What Happens When It's NOT My Turn

When an opponent is the active player:

### Tracking (input)
Minimal quick-entry: roll value + what they bought. Two inputs, done.

### Analysis (output, passive)
The tool uses this time productively:

- **Pre-computation**: the engine starts analyzing for the user's upcoming turn
- **Position insights**: how the opponent's action affects the user's standing - the following ones are examples that are to be used as inspiration for the final insight selection:
  - "You are ahead by X because of Y"
  - "Your crucial projects are A, B — opponent's crucial projects are C, D"
  - "Opponent *should* buy X for best effect because Z" (predictive insight, not a command)
- **Dashboard-style overview**: current standings, EV gaps, risk exposure

This transforms "dead time" into useful strategic context.

---

## 5. The Kauf Assistent (Purchase Assistant)

### 5.1 What "Best Decision" Means

The best purchase is the one that **maximizes the player's win probability**.

Win probability implicitly accounts for:
- Expected income (EV) and/or similar metrics
- Risk of stagnation or negative income (variance, P(no income))
- Opponent positions and trajectories
- Synergies with existing portfolio
- Proximity to win condition (landmark progress)

However, the **risk dimension** deserves explicit treatment. Machi Koro is a game of chance — you can be unlucky for many turns in a row, halting progress while opponents surge ahead. The assistant must surface this risk clearly so the player can make an informed trade-off.

The system (or user, via settings) can adjust the **risk tolerance** based on game situation:
- Behind → more risk acceptable for catch-up potential
- Ahead → safer plays to protect the lead
- This weighting can be automatic (based on position analysis) or manual (user preference)

### 5.2 Explanation Format

Inspired by chess engine output: dense, informative, transparent.

**Structure** (top to bottom):
1. **Summary sentence** — the recommendation in one line, with the key reason
   - e.g., "Buy Käsefabrik — strongest synergy with your 2 Bauernhöfe, +1.3 EV/round"
2. **Factor bullets** — ordered by impact weight (highest first), each with:
   - Category label + score/value
   - Expandable dropdown for detailed breakdown and numbers
   - e.g., "Synergy: +1.3 EV/round ▸" → expands to show per-card EV contribution
   - e.g., "Catch-up urgency: HIGH ▸" → expands to show position gap analysis
   - e.g., "Risk: moderate (P(0 income) = 23%) ▸" → expands to show variance details
3. **Full ranked list** — all purchasable options with sortable columns for comparison

The exact factors and their weights are determined by the active simulation engine. The explanation adapts dynamically: if catch-up urgency drove the decision, that factor appears first. If synergy was the differentiator, synergy leads.

### 5.3 Modes

Three modes controlling engine depth, exposed in the main UI:

| Mode     | Behavior                          |
|----------|-----------------------------------|
| Fast     | Low MCTS iteration count          |
| Balanced | Medium MCTS iteration count       |
| Deep     | High MCTS iteration count         |

Exact iteration counts are configurable in settings. Power-user controls (weights, parameters, engine selection) live in a settings screen, not the main UI.

---

## 6. Architecture

### 6.1 System Layers

```
┌─────────────────────────────────────────┐
│                  UI                     │  Web frontend (SPA)
│         (user ↔ system interface)       │  Responsibility: display, input, UX
├─────────────────────────────────────────┤
│              Interface                  │  Java — orchestration layer
│     (routes requests, manages engines)  │  Responsibility: engine registry,
│                                         │  request routing, result formatting
├─────────────────────────────────────────┤
│           Simulation Engines            │  Java — pluggable via interface
│  (MCTS, full tree, future variants...)  │  Responsibility: given a GameState
│                                         │  + params, return ranked suggestions
├─────────────────────────────────────────┤
│            Standard Calcs               │  Java — shared math utilities
│    (EV, ROI, probability, dice math)    │  Responsibility: version-agnostic
│                                         │  calculations any engine can use
├─────────────────────────────────────────┤
│                 Core                    │  Java — game rules engine
│   (GameState, Player, Project, cards,   │  Responsibility: what IS the game
│    dice resolution, turn order, win     │  (rules, state, card effects)
│    condition, card effects)             │  No strategy, no opinions.
└─────────────────────────────────────────┘
```

**Call flow**: `UI → Interface → SimulationEngine → StandardCalcs / Core`

Each layer has a single responsibility and does not reach into layers it shouldn't:
- **Core** = pure game rules. Dice resolution, card income calculation, turn order, bonus turns (Freizeitpark), win condition check. No strategy.
- **Standard Calcs** = reusable math. EV computation, ROI, probability distributions, synergy scores. Stateless, version-agnostic. Any engine can call these.
- **Simulation Engines** = strategy. Given a game state, simulate/analyze and return ranked purchase options with scores and explanations. Each engine is a self-contained strategy implementation.
- **Interface** = orchestration. Maintains the engine registry, routes analysis requests to the selected engine, manages engine parameters/configs. The bridge between UI and computation.
- **UI** = presentation. Web SPA talking to the Java backend via local HTTP API. Handles all display, input, interaction. Decides how to present engine results to the user.

### 6.2 Engine Interface

```java
interface SimulationEngine {
    String id();
    String description();           // human-readable, for settings UI
    EngineResult evaluate(GameState state, int playerIndex, EngineConfig config);
}
```

`EngineResult` contains:
- Ranked list of all purchase options (project + score + per-metric breakdown)
- Explanation data (factor list with weights, expandable detail)
- Metadata: confidence, computation time, iterations used, any engine-specific stats
- The engine shares *everything* it computed — the UI decides what to display

`EngineConfig` is a generic configuration object:
- Each engine type defines its own config schema
- Common fields: iteration count, time budget, risk tolerance weight
- Configs are stored in a JSON registry alongside engine definitions

### 6.3 Engine Registry (Versions)

All engine+config combinations are stored in a **flat JSON registry**:

```json
[
  {
    "id": "mcts-v1-fast",
    "engine": "mcts-v1",
    "description": "MCTS v1 — fast mode (500 iterations)",
    "config": { "iterations": 500, "rolloutPolicy": "greedy" }
  },
  {
    "id": "mcts-v1-deep",
    "engine": "mcts-v1",
    "description": "MCTS v1 — deep mode (50000 iterations)",
    "config": { "iterations": 50000, "rolloutPolicy": "greedy" }
  },
  {
    "id": "full-tree-v1",
    "engine": "full-tree",
    "description": "Full tree search (depth-limited)",
    "config": { "maxDepth": 4, "evaluator": "analytical" }
  }
]
```

- Two dimensions (engine class × parameters) flatten into one list
- The "current best" is the default for normal play
- Power users can select any version from settings
- Head-to-head testing can pit any two entries against each other
- Head-to-head result storage: see Section 8.6

### 6.4 UI ↔ Backend Communication

- **Technology**: Java HTTP server (lightweight, e.g. Javalin or built-in `com.sun.net.httpserver`) serving a REST-ish API
- **Frontend**: Single-page app (SPA) — React 19 + TypeScript + Vite 8 + Tailwind CSS 4
- **Runs locally**: user starts the app, it opens in their browser
- **Not a deployed service**: no server hosting, no phone support — purely local desktop use

### 6.5 What the Core Owns (Game Rules)

Everything determined by rules or card effects:

- Game state representation (players, cards, coins, landmarks, supply)
- Dice rolling mechanics (1d6 / 2d6 sum)
- Income resolution for all card types, in correct order (red → blue & green → purple)
- Income clamping (can't pay more than you have)
- Counter-clockwise resolution order for multiple red claims
- Card supply tracking (6 copies per non-landmark; 1 purple per player)
  - Starting Cards tracking: Every Player starts with 1 Weizenfeld and 1 Bäckerei. These starting copies count against the 6-copy market supply (e.g., in a 2-player game, 4 copies of each remain purchasable).
- Card purchase validation (enough coins, card available, purple uniqueness)
- Landmark effects that are purely mechanical:
  - Freizeitpark: doubles → bonus turn (the *rule*; whether to *aim* for doubles is engine territory)
  - Einkaufszentrum: +1 coin per green/red store-symbol card
- Turn order progression
- Win condition (4 landmarks built)
- Bürohaus card swap execution (the *mechanics* of swapping; which cards to swap (or choosing to not swap) is engine territory)

Everything that involves a **choice** is engine territory:
- Bahnhof: roll 1d6 or 2d6?
- What to buy?
- Bürohaus: which cards to swap or choosing to not swap?
- Funkturm: re-roll or keep?

### 6.6 Card Data & Future Expansion

- All 19 base-game cards defined in `projects.json`
- Architecture must support adding expansion cards by:
  - Adding entries to the JSON data file
  - Adding income logic to the core's card resolver
  - No changes needed in engines or UI
- **Task (future)**: scrape all cards (all expansions) from https://machi-koro.fandom.com/wiki/List_of_cards and store for reference — makes adding expansions easy
- Expansion implementation is explicitly **out of scope** until the core system is perfected

---

## 7. MCTS Implementation

### 7.1 Tree Structure

```
[Chance Node: Dice Roll]          ← probability-weighted
    ├── roll=2 (p=1/36)
    │   └── [Decision Node: Buy]  ← player chooses
    │       ├── buy Bäckerei
    │       │   └── [Chance Node: Next player's dice] ...
    │       ├── buy Café
    │       │   └── ...
    │       └── save
    │           └── ...
    ├── roll=3 (p=2/36)
    │   └── ...
    ...
```

- **Chance nodes**: dice outcomes, weighted by probability
- **Decision nodes**: purchase choices (or save), one per player
- All players are modeled as decision-makers (not just the active player)
- Opponents play "reasonably" — the MCTS rollout policy applies to everyone

### 7.2 Rollout Policy

- **v1: Full game rollouts** — simulate until someone actually wins. Simple and accurate, but slower because every rollout plays out the entire remaining game.
- **Later versions: Stop early + estimate the winner** — instead of playing to completion, stop the simulation after N turns and use a heuristic to guess "who's winning from this position?" (e.g., based on coins, landmarks built, portfolio strength). Faster because rollouts are shorter, but only as good as the heuristic. To be tested head-to-head against full rollouts to see which approach actually produces better recommendations.
- Maximum turn limit as safety valve (games rarely exceed 60–70 turns if both players act decently smart and are not obscenly unlucky - this has to be accorded for when setting the limit)

### 7.3 Selection / Expansion / Backpropagation

Standard MCTS with UCT (Upper Confidence Bound for Trees):
- Selection: traverse tree using UCB1 to balance exploration/exploitation
- Expansion: add one new node per iteration
- Simulation: full game rollout from the new node
- Backpropagation: update win counts up the tree

### 7.4 Iteration Budgets (Modes)

| Mode     | Iterations | Use Case                     |
|----------|------------|------------------------------|
| Fast     | ~500       | Quick recommendation         |
| Balanced | ~5,000     | Good balance of speed/quality|
| Deep     | ~50,000    | Best possible analysis       |

Exact numbers are configurable and will be tuned based on performance testing.

---

## 8. Head-to-Head Testing System

### 8.1 Purpose

Ensure that new engine versions are actually better than old ones. A new version must **beat all previous versions** to become the new default.

### 8.2 Match Format

- **100 games** per match (configurable)
- Win = more than 50% of games won
- Both engines go all-out (full iteration budgets, no shortcuts)
- Game length typically 20–60 turns

### 8.3 Expected Runtime

- Acceptable: minutes to ~15–30 minutes per match running in the background
- Engines should not pull punches — the test must be thorough and complete

### 8.4 Results & UI

**High-level overview** for bulk matches:
- Win rates, average game length, average coins at turn N, EV trajectories

**Detailed game replay** for individual games:
- Turn-by-turn log of every action (roll, buy, coin deltas)
- Engine reasoning/numbers stored per decision point
- Visual step-through replay (see board state at each turn)
- This serves as **inspiration** for designing better engine versions

### 8.5 What "A Version" Means

A version = an engine registry entry (engine class + parameters). This means:
- MCTS-v1-fast vs. MCTS-v1-deep is a valid comparison
- MCTS-v1 vs. full-tree-v1 is a valid comparison
- Any two entries from the registry can be pitted against each other

### 8.6 Result Storage

All head-to-head results are stored in a **separate JSON file** (`h2h-results.json`), not in the engine registry. The registry is a clean config defining what engines exist; test results are runtime data that grows over time.

The results file contains:
- **Match metadata**: version A id, version B id, date, game count
- **Aggregate stats**: win rates, average game length, average coins at key turns
- **Per-game logs**: turn-by-turn actions + engine reasoning per decision point (for replay)
- **Current "best" version id**: the engine that has beaten all others, referenced by the registry as the default for normal play

---

## 9. Persistence & Settings

### 9.1 Game Session Persistence

- Save/load game state + turn history (existing `.mkoro` format or improved)
  - They have a default save location, which should be considerate of the OS (so it works on all plattforms)
- Lives in a submenu / menu bar — not a core UI element
  - List Selection for past played games (name of file + relevant metadata)
- Essential for resuming interrupted games
- Autosave Setting can be turned on (off by default) so that after every action tracked in the UI a "Save-Action" is triggered
  - The file names follow a coherent and consistent naming schema when this schema is turned on (for example player names, date, time, etc.)
  - If a manual save is entered with this setting on the player should be asked if the autosave should be overwritten until the end of that game or if the save should be a seperate "jump right back in" point

### 9.2 Settings

Power-user controls in a dedicated settings screen:
- Engine selection (dropdown from registry)
- Mode selection (Fast / Balanced / Deep)
- Per-engine parameter overrides
- Language (DE / EN — existing localization stays, not a priority to expand)
- Engine descriptions visible for informed selection
- Autosave On/Off

Settings are persistent throughtout sessions so they do not have to be configured each and every time.

### 9.3 Localization

German and English support remains. Not a priority for expansion, but existing translations are maintained. Clarity and quick understanding in both languages is important.

---

## 10. What Gets Purged

The following components from the current codebase are **replaced** by the new architecture:

| Current Component | Reason | Replacement |
|---|---|---|
| `RolloutTree` (Expectimax) | Replaced by unified MCTS tree search | SimulationEngine implementations |
| `WinProbabilityCalc` (softmax analytical) | Absorbed into Standard Calcs / engine leaf evaluation | Standard Calcs layer |
| `adaptiveMCRefinement` | Replaced by proper MCTS iteration budgets | Engine config |
| `rankPurchasableProjects` | Replaced by engine-driven ranking | Interface + Engine |
| `RankingOptions` | Replaced by EngineConfig | Engine registry |
| `RankEntry` | Replaced by EngineResult | Engine interface |
| `GameSimulator` (greedy policy) | Replaced by MCTS rollout policy | SimulationEngine |
| `AssistantConfig` / phase weights | Replaced by engine-computed explanations | Engine output |
| `PhaseFitter` / `LabelingWindow` | No longer needed (was for calibrating phase weights) | Cut |
| `SnapshotGenerator` | Can be rebuilt if needed for testing | Defer |
| Entire Swing UI (`gui.newui/*`) | Replaced by web frontend | New SPA |
| `MainWindow` (5 ranking tabs, assistant tab, etc.) | Radical simplification to 4 core components | New UI |

**Preserved** (these are game rules, not strategy):
- `Project`, `Player`, `GameState`, `GameStateBuilder`, `TurnRecord` — core data model
- `GameSession`, `GameSessionPersistence` — game tracking + persistence
- `ProjectLoader` — card data loading
- `CardIncome.get_I` — per-card income calculation (all 19 cards)
- `CardIncome.P1` / `P2` — dice probability arrays (make sure that functionality for getting a "pasch" is still being correctly handled with dice throws)
- `computeAllDeltasForRoll` — full roll resolution for all players
- `BürohausLogic.executeSwap` — swap execution mechanics (not swap *selection*)
- `Strings` — localization registry (adapted for web)
- `projects.json` — card data

### Purge Archive

All purged concepts are documented in `ARCHIVE.md` with:
- 2–3 sentence description of what it did and why
- Commit hash reference where the code last existed
- Any ideas worth preserving for future engine versions

Git history preserves the actual code — the archive is a readable index.

---

## 11. Implementation Roadmap

The phased implementation plan lives in `PLAN.md`. It is the active backlog derived from this document. This separation keeps the North Star focused on *what* and *why*, while the plan tracks *when* and *how*.

---

## 12. Non-Goals (Explicitly Out of Scope)

- Phone app or deployable web service
- Expansion cards (until core is perfected)
- AI opponent / autopilot mode
- Multiplayer networking
- The "Komme, was wolle" variant (randomized supply)

---

## Summary

This program exists to answer one question as fast and accurately as possible:

> **"What should I buy right now, and why?"**

Everything in the architecture — the clean Core, the pluggable engines, the MCTS tree, the streamlined UI — serves this single purpose. If a feature doesn't help answer this question better, it doesn't belong.
