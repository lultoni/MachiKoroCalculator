# PLAN.md — MachiKoroCalculator Implementation Roadmap

**North Star:** A fast, mathematically rigorous tool that, given any Machi Koro base-game state, instantly recommends the single best project to buy — with transparent reasoning (EV, ROI, win probability delta) — displayed in a clean, fast-to-update GUI.

---

## Progress Tracker

| Phase | Status | Commit | Notes |
|-------|--------|--------|-------|
| 0 — Deep Clean & Audit | ✅ Done | `48a965e` | Compiles clean, all TODOs → FIXME, legacy tagged |
| 1 — Solid Data Model | ✅ Done | `c0d15dd` | 31/31 tests pass, ProjectLoader cached, GameState.initial() |
| 2 — Core Math Engine | ✅ Done | — | 108/108 tests pass, 0.18 ms/call; EV, ROI, variance, ranking, win prob delta |
| 3 — Game State Config UI | ⬜ Pending | — | Requires design question answered first (3.1) |
| 4 — Remove Legacy Code | ⬜ Pending | — | Blocked on Phase 3 |
| 5 — Monte Carlo Deep Mode | ⬜ Pending | — | Blocked on Phase 2 |
| 6 — Polish & Final Integration | ⬜ Pending | — | Blocked on Phase 5 |

---

## How to Use This Plan

Work through phases top-to-bottom. Each phase has:
- **Goal** — what the codebase looks like when this phase is done
- **Steps** — concrete, ordered tasks
- **Testing checkpoint** — what must pass before moving to the next phase
- **Commit point** — when to commit (logical unit boundary)

If any step introduces a design question not answered here, **stop and ask the user** before proceeding.

---

## ✅ Phase 0 — Deep Clean & Audit (COMPLETE — commit `48a965e`)

**Goal:** Eliminate dead code, compile errors, and architectural confusion so the codebase is a clean, stable foundation.

### 0.1 Fix compilation errors

The codebase currently **does not compile** due to missing method bodies referenced in `ProbabilityCalc.java`:
- `computeNetGainForRoll()` (called at line 291, 297, 303)
- `bestSecondRollEV()` (called at line 307)
- `RankingOptions` class (used in `rankPurchasableProjects` signature)

**Action:** Add empty stubs with `throw new UnsupportedOperationException("not yet implemented")` so the project compiles cleanly. Do not implement logic yet.

### 0.2 Remove dead code from legacy layer

The `src/logic/` package (non-probability) contains the original EV model that has been superseded. It contains known bugs, hardcoded array indices, and incorrect probability math. Mark each class explicitly:
- Add a top-of-file comment: `// LEGACY — to be removed once the probability layer is complete.`
- Do **not** delete yet — the GUI still depends on it. Deletion happens in Phase 4.

### 0.3 Fix the one live bug affecting Main.java

`Main.java` lines 41–43 are dead commented-out code. Remove the block.
The `Main.java` `while (!boot_finished) Thread.onSpinWait()` busy-loop is fine for now — note it as a known smell to fix in Phase 4 when the UI is rebuilt.

### 0.4 Document all known issues inline

Every `TODO`, broken logic, and stub must have a comment of the form:
```java
// FIXME [Phase X]: <description of what needs to happen>
```
This turns the audit into a tracked, phase-linked work list.

**Testing checkpoint 0:**
- Project compiles with zero errors.
- `Tests/RuntimeTester.java` runs without crashing (even if output is wrong).

**Commit point:** `Clean up dead code and add compile stubs for missing probability methods`

---

## ✅ Phase 1 — Solid Data Model (COMPLETE — commit `c0d15dd`)

**Goal:** The `logic.probability` package has a complete, correct, immutable data model that can represent any valid base-game state. No legacy dependency.

### 1.1 Finalize `Project` (probability layer)

`logic.probability.Project` is already correct and immutable. **Additions needed:**
- Add `equals()` and `hashCode()` based on `id` (required for collections and deduplication).
- Add a `toString()` returning the ID for debug output.

### 1.2 Finalize `Player` (probability layer)

`logic.probability.Player` needs:
- Validate in constructor: `coins >= 0`, `owned_projects != null`.
- Add `copy()` method (shallow-copy of owned_projects list with same Project references — Projects are immutable so sharing is safe).

### 1.3 Finalize `GameState`

- Add constructor validation: `players.length >= 2 && players.length <= 4`, no null players.
- The existing `copy()` is already a correct deep copy — add a Javadoc block explaining this.
- Add a factory method `GameState.initial(int numPlayers)` that builds the standard starting state (each player has Weizenfeld + Bäckerei, 3 coins, all other cards in the unbuilt pool).

### 1.4 Fix `ProjectLoader`

Current problem: uses a relative path `src/resources/jsons/projects.json` — will break outside IntelliJ.

**Fix:** Load from classpath using `ProjectLoader.class.getResourceAsStream("/resources/jsons/projects.json")`.

**Also add:**
- `getAllProjects()` returning `ArrayList<Project>` — loads all projects from the JSON. This is needed for building the initial unbuilt pool.
- Cache the loaded project map after first load (static `Map<String, Project>`) to avoid re-reading the file every call. This is important — `ProjectLoader.getProject()` is called in hot loops.

### 1.5 Create `RankingOptions`

The `rankPurchasableProjects` stub references `RankingOptions`. Create this class:
```java
public class RankingOptions {
    public int horizonTurns = 10;       // turns to look ahead for ROI
    public double discountFactor = 0.95; // gamma per turn
    public int mcSimulations = 0;        // 0 = analytical only, >0 enables Monte Carlo
    public boolean includeWinProbDelta = false; // expensive, off by default
}
```

### 1.6 Validate `projects.json` completeness

Cross-check that:
- Every project ID in `get_I()` exists in the JSON.
- `bürohaus` has no `get_I` case — add a `FIXME [Phase 2]` comment.
- All 19 cards are present.

**Testing checkpoint 1:**
- Write a small inline test in `RuntimeTester` (or a new `ModelTest` class) that:
  - Loads all 19 projects via `ProjectLoader.getAllProjects()`, asserts count = 19.
  - Builds a `GameState.initial(4)` and asserts each player has 2 starter cards and 3 coins.
  - Calls `gs.copy()` and asserts the copy is equal but not the same object.

**Commit point:** `Complete probability data model: Project, Player, GameState, ProjectLoader, RankingOptions`

---

## Phase 2 — Core Math Engine

**Goal:** `ProbabilityCalc` correctly computes all four metrics for any game state. This is the heart of the program.

### 2.1 Pre-compute probability tables

Replace the switch-based `get_P1` and `get_P2` with pre-computed constant arrays:
```java
private static final double[] P1 = new double[13]; // P1[r] = 1/6 for r in 1..6
private static final double[] P2 = new double[13]; // P2[r] = (6-|r-7|)/36 for r in 2..12
static {
    for (int r = 1; r <= 6; r++)  P1[r] = 1.0 / 6.0;
    for (int r = 2; r <= 12; r++) P2[r] = (6.0 - Math.abs(r - 7)) / 36.0;
}
```
This eliminates a switch lookup on every probability query in the hot loop.

### 2.2 Fix `get_I` — correctness audit

Go through every case in `get_I` and verify against the official Machi Koro base game rules:

- **`café` (rot, roll 3):** `oop=false` means the current player is NOT the owner — owner receives the payment. The current code has the sign and `oop` condition correct for the perspective of `get_I` being called from the roller's POV. Verify and document this convention clearly.
- **`familienrestaurant` (rot, rolls 9–10):** Same convention. Verify.
- **`stadion` (lila, roll 6):** Takes from each opponent up to 2 coins, max 5 total. The current code takes from richest opponent only — **this is wrong**. Fix to iterate all opponents and sum `min(2, opponent_coins)`, cap at 5.
- **`fernsehsender` (lila, roll 6):** Takes 5 coins from one chosen opponent (richest). Current code sums 2 from each — **this is wrong** per base rules. Fix: take `min(5, richest_opponent_coins)`.
- **`bürohaus` (lila, roll 6):** Not implemented. Add a `FIXME [Phase 2]` until rules are confirmed with user — bürohaus swaps a card with an opponent, which is non-monetary and needs a different return type or convention. **Ask the user how to handle this before implementing.**
- Verify all multiplier cards (molkerei, möbelfabrik, markthalle) correctly use `f_c`, `a_c`, `p_c`.

### 2.3 Implement `computeNetGainForRoll`

This is called by `immediateEV` and computes the net coin change for the active player when a given roll occurs. It must:
1. Sum income from all blue cards (all players' blue cards that activate, owner gets paid by bank).
2. Add green card income if `isOwnTurn=true`.
3. Add purple card income if `isOwnTurn=true`.
4. Subtract red card costs (opponents' red cards that activate on this roll — player loses coins).
5. Enforce inability-to-pay: if cost exceeds `player.coins`, clamp to `player.coins`.

Signature:
```java
private static int computeNetGainForRoll(GameState state, int playerIndex, int roll, boolean isOrderedPair)
```
`isOrderedPair` is used to detect a doubles roll (Freizeitpark trigger) — it is `true` when both dice show the same value.

### 2.4 Implement `bestSecondRollEV`

Called when Freizeitpark triggers (player rolled doubles). Returns the EV of the best possible re-roll:
```java
private static double bestSecondRollEV(GameState state, int playerIndex, int forcedDiceCount)
```
`forcedDiceCount`: -1 = player chooses freely (Bahnhof present), 1 = must roll 1 die, 2 = must roll 2 dice (Funkturm forces same count as previous roll).

Implementation: enumerate all possible re-roll outcomes, weight by probability, return expected net gain. No doubles chaining on second roll.

### 2.5 Implement `evPerRound`

EV until all other players have completed one turn. This accounts for blue cards being triggered on opponents' turns.

Algorithm:
1. Compute EV for the current player's own turn (already done via `immediateEV`).
2. For each opponent turn (N-1 turns), compute blue card EV for the current player triggered by that opponent's roll.
   - Red cards: current player may lose coins on opponents' turns — subtract expected red card losses.
3. Sum all N partial EVs.

```java
public static double evPerRound(GameState gs, int playerIndex, Project candidate)
```

The candidate has already been "bought" (simulate: add to player's owned_projects in a copy of the state).

### 2.6 Implement `roiOverHorizon`

Discounted return on investment over `horizonTurns` turns:
```java
public static RankEntry roiOverHorizon(GameState gs, int playerIndex, Project candidate, int horizonTurns, double discountFactor)
```

Formula:
```
ROI = sum_{t=1}^{horizonTurns} discountFactor^t * evPerRound(state_after_buy) - candidate.getCost()
```

Simplified (evPerRound is constant, ignoring coin changes): this is a geometric series:
```
ROI = evPerRound * discountFactor * (1 - discountFactor^horizonTurns) / (1 - discountFactor) - cost
```

Populate and return a `RankEntry` with `immediateEV`, `evPerRound`, `roiOverHorizon`, `variance` fields.

**Variance** = variance of the per-turn net gain distribution. Computed analytically:
```
Var = sum_r P(r) * gain(r)^2 - EV^2
```
High variance cards (stadion, bergwerk) are risky. Report it in `RankEntry`.

### 2.7 Implement `rankPurchasableProjects`

Enumerate all projects in `gs.unbuilt_projects` that the player can afford, call `roiOverHorizon` on each, sort by ROI descending, return the list.

```java
public static ArrayList<RankEntry> rankPurchasableProjects(GameState gs, int playerIndex, RankingOptions opts)
```

Also: for each candidate, compute `probNoIncomeOwnTurn` = probability that the player earns 0 coins on their own turn after buying this card. This is the risk metric shown in the UI.

### 2.8 Implement `estimateWinProbDelta` (analytical, no MC yet)

Use the softmax score approximation:
```java
double score(Player p) = sum_over_owned_cards(evPerRound(card) * remainingTurnsEstimate)
                        + sum_over_built_landmarks(landmarkWeight)
```

Win probability for player i:
```
P_i = exp(score_i) / sum_j exp(score_j)
```

`winProbDelta(candidate) = P_i(state_after_buy) - P_i(current_state)`

Populate `RankEntry.winProbDelta`.

**Testing checkpoint 2:**
- Unit tests for `get_P1`, `get_P2`: verify probabilities sum to 1.0 for each dice mode.
- Unit test `get_I` for each card: at least one roll that should trigger and one that should not.
- Unit test `immediateEV` for a known minimal game state (e.g. 2 players, player 0 has only Weizenfeld, no landmarks): EV should equal `(1/6) * 1 = 0.167` approximately.
- Unit test `evPerRound` for the same state with N=2 players: should be roughly `2 * 0.167 = 0.333` (blue triggers on both turns).
- Verify `rankPurchasableProjects` returns a non-empty list for a standard starting state.

**Architecture analysis checkpoint:** Before writing Phase 3, verify:
- `ProbabilityCalc` has zero imports from `logic.*` (legacy layer). Pure dependency on `logic.probability.*` only.
- No I/O calls in `ProbabilityCalc`.
- All public methods are stateless (no static mutable fields).

**Runtime analysis checkpoint:** Run `RuntimeTester` with a benchmark for `rankPurchasableProjects` on a 4-player game. Target: < 5ms per full ranking call. If over 5ms, profile and optimize before Phase 3.

**Commit point:** `Implement complete math engine: get_I correctness, all ProbabilityCalc methods, EV/ROI/winProbDelta`

---

## Phase 3 — Game State Configuration UI

**Goal:** The player can quickly input the current game state via a simple form, and the calculator instantly shows the ranked recommendation.

### 3.1 Design the state input model

Ask the user how they want to configure the game state before building UI. The two main options:
- **Turn-by-turn tracking**: Player tracks every action in the app throughout the game. More accurate, more upfront work.
- **Snapshot entry**: Player opens the app mid-game and quickly enters current state. Less accurate (no history), much faster to use.

**Stop and ask the user which model they want before implementing any UI.**

### 3.2 Build `GameStateBuilder`

A simple Java class (not UI) for constructing a `GameState` from user inputs:
```java
GameStateBuilder builder = new GameStateBuilder(numPlayers);
builder.setCoins(playerIndex, amount);
builder.addProject(playerIndex, projectId);
builder.setLandmark(playerIndex, landmarkId, true);
GameState state = builder.build();
```

Validates inputs and throws `IllegalArgumentException` on invalid state (e.g. a project owned by two non-office players).

### 3.3 Build the new Swing UI

Structure (three panels, no tabs for simplicity):

**Left panel — Game State Input:**
- Player count selector (2/3/4, dropdown)
- For each player: coin spinner + checkbox grid for all 19 cards (grouped by color, small icons)
- "My player" indicator (which player the recommendation is for)
- "Calculate" button

**Center panel — Recommendation:**
- Large card showing the top recommendation: card name, icon, cost, EV/round, ROI, win prob delta
- Small note explaining the reasoning in one sentence (e.g. "Highest ROI over 10 turns with your current ranch synergy")

**Right panel — Full Ranking:**
- Scrollable list of all affordable cards sorted by ROI
- Each row: card name, cost, EV/round, ROI, risk (variance / P(zero income))
- Clicking a row highlights it in the center panel

The UI calls `ProbabilityCalc.rankPurchasableProjects()` on every state change (debounced 200ms). It does **not** call legacy `logic.*` code.

### 3.4 Wire up state changes

Each input change triggers:
1. Rebuild `GameState` from current inputs via `GameStateBuilder`.
2. Call `rankPurchasableProjects(gs, myPlayerIndex, defaultOpts)`.
3. Render results in center and right panels.

### 3.5 Delete legacy UI

Remove `gui.boot.BootWindow`, `gui.game.*`. Update `Main.main()` to launch only the new UI.

**Testing checkpoint 3:**
- Manually test a 2-player starting state — recommendations should match hand calculations from Phase 2 tests.
- Test with a player having 0 coins — ranking list should be empty (no affordable cards).
- Test with a player who has all landmarks built — state is invalid, app should show a "player has won" message.

**Runtime analysis checkpoint:** The UI must feel instant. Full ranking call must remain < 5ms. If adding the softmax win-probability delta makes it slower, make `includeWinProbDelta` opt-in (toggle in UI).

**Commit point:** `Add GameStateBuilder and new recommendation UI; remove legacy GUI`

---

## Phase 4 — Remove Legacy Code

**Goal:** The `src/logic/` package (non-probability) is completely removed. Zero legacy code remains.

### 4.1 Verify nothing in the new UI imports legacy classes

Grep for any import of `logic.Game`, `logic.Player`, `logic.Project`, `logic.Category` in the new code. If found, replace with probability-layer equivalents.

### 4.2 Delete legacy files

- `src/logic/Game.java`
- `src/logic/Player.java`
- `src/logic/Project.java`
- `src/logic/Category.java`
- `src/logic/Main.java` (replaced)

### 4.3 Update `Main.java`

Single entry point: initialize the new UI, load all projects from JSON, build initial game state, show the window.

**Testing checkpoint 4:**
- Project compiles with zero warnings after removing legacy code.
- Run the full suite from `RuntimeTester`.
- Re-run the Phase 2 unit tests to confirm nothing broke.

**Commit point:** `Remove legacy logic layer; new probability layer is the only backend`

---

## Phase 5 — Monte Carlo Validation & Deep Mode (Optional)

**Goal:** Implement the MC simulation path in `estimateWinProbDelta` and validate that the analytical model from Phase 2 is within ±5% of MC win rates.

### 5.1 Implement a lean game simulator

A stateless `GameSimulator` class that takes a `GameState` and simulates one full game using a greedy-EV rollout policy:
```java
public class GameSimulator {
    public static int simulate(GameState gs, Random rng); // returns winner index
}
```

Rollout policy: each player buys the highest-ROI card they can afford. If nothing is affordable, save. Simulate until one player has all 4 landmarks.

### 5.2 Implement MC in `estimateWinProbDelta`

```java
// opts.mcSimulations > 0 enables this path
for (Project candidate : candidates) {
    int wins = 0;
    for (int i = 0; i < opts.mcSimulations; i++) {
        GameState sim = stateAfterBuying(gs, playerIndex, candidate).copy();
        if (GameSimulator.simulate(sim, rng) == playerIndex) wins++;
    }
    entry.winProbDelta = (double) wins / opts.mcSimulations - baselineWinRate;
}
```

Run in parallel using `ForkJoinPool` — each candidate's simulations are independent.

### 5.3 Validate analytical vs. MC

Run both models on 10 different representative game states. If analytical win-prob delta disagrees with MC by > 5 percentage points consistently, tune the softmax score function weights.

### 5.4 Expose in UI

Add a "Deep Analysis" toggle in the UI. When enabled: `opts.mcSimulations = 1000`, re-run ranking, show MC win-rate alongside analytical result, show a loading indicator during computation (< 2 seconds target on modern hardware).

**Runtime analysis checkpoint:** 1000 simulations × 10–15 candidates × avg 20-turn games × 4 players = ~800k game-state steps. Profiling target: < 1.5 seconds with parallel execution. If over, reduce to 500 simulations or optimize the game simulator inner loop.

**Commit point:** `Add Monte Carlo deep analysis mode with analytical validation`

---

## Phase 6 — Polish & Final Integration

**Goal:** The app is clean, documented, and correct end-to-end.

### 6.1 Complete all remaining `get_I` cases

- `bürohaus`: Ask user how card swapping should be modeled (its effect is non-monetary). Implement based on answer.
- Audit for any other cards added to `projects.json` but missing in `get_I`.

### 6.2 ~~Complete `projects.json` descriptions~~ (done in Phase 1)

All 19 card descriptions were filled in during Phase 1. `bürohaus` has a FIXME note about its non-monetary effect pending Phase 2 design decision.

### 6.3 Final documentation pass

- All public methods in `logic.probability.*` have complete Javadoc.
- `README.md` updated to reflect the final feature set.
- `CLAUDE.md` updated: mark all stub methods as implemented, update architecture section to reflect removal of legacy layer.

### 6.4 Final runtime analysis

Run `RuntimeTester` extended with benchmarks for:
- `rankPurchasableProjects` (analytical, 4 players, mid-game state)
- `estimateWinProbDelta` (MC, 500 sims, 4 players)
- `ProjectLoader.getAllProjects()` (cached vs. cold)

Record results in a comment in `RuntimeTester.java`.

**Testing checkpoint 6 (final):**
- All unit tests pass.
- Manual playthrough: enter a real mid-game state, verify the recommendation is consistent with known Machi Koro strategy (e.g. in a 4-player game with Bahnhof and two ranches, Cheese Factory should rank highly).
- No `TODO` or `FIXME` comments remain without a linked phase (all remaining known gaps should be in a `BACKLOG.md`).

**Final commit point:** `Phase 6 complete: fully functional calculator with analytical + MC ranking and clean documentation`

---

## Appendix: Key Formulas Reference

**2-dice probabilities:**
```
P(roll=k | 2d6) = (6 - |k - 7|) / 36   for k ∈ {2..12}
```

**Blue card EV per round (N players):**
```
EV_round(blue) = payout * P(activation_roll) * N
```

**Discounted ROI over T turns:**
```
ROI = EV_round * γ * (1 - γ^T) / (1 - γ) - cost     where γ = discountFactor
```

**Win probability softmax:**
```
score(player) = Σ EV_round(owned_card) * T_remaining + Σ landmark_weight(built_landmark)
P_win(player i) = exp(score_i) / Σ_j exp(score_j)
```

**Variance of per-turn net gain:**
```
Var = Σ_r P(r) * gain(r)² - EV²
```

---

## Appendix: Known Design Questions (ask user before implementing)

1. **bürohaus card**: Its effect is card-swapping, not coin-based. How should it be modeled in `get_I` and the EV framework?
2. **UI model**: Turn-by-turn tracking vs. snapshot entry (Phase 3.1)?
3. **Discount factor default**: 0.95 per turn is a reasonable starting point — confirm or adjust?
4. **Monte Carlo in default mode**: Should MC simulations be on by default, or opt-in only?
5. **Multiple copies of non-blue cards**: In the base game, each non-office card has limited supply. The current model tracks `unbuilt_projects` — confirm this is the intended availability model.
