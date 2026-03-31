# ARCHITECTURE.md — MachiKoroCalculator Technical Reference

This document contains the mathematical foundations, card rule conventions, and design rationales for the game rules engine and shared calculations. For the high-level architecture and vision, see `NORTH-STAR.md`.

---

## 1. System Architecture

### 1.1 Layer Overview

```
UI (Web SPA) → Interface → Simulation Engines → Standard Calcs → Core
```

See NORTH-STAR.md Section 6.1 for the full specification. This document covers the **Core** and **Standard Calcs** layers in technical detail.

### 1.2 Core Layer Responsibilities

The Core layer owns everything determined by game rules or card effects:

- Game state representation (`GameState`, `Player`, `Project`, `TurnRecord`)
- Dice mechanics (1d6/2d6 sum, doubles detection)
- Income resolution for all card types in correct order (Red → Blue & Green → Purple)
- Income clamping (can't pay more than you have)
- Counter-clockwise resolution for multiple red claims
- Card supply tracking (6 copies per non-landmark; 1 purple per player)
- Starting cards: each player begins with 1 Weizenfeld + 1 Bäckerei (not deducted from the 6-copy supply)
- Card purchase validation (enough coins, card available, purple uniqueness)
- Mechanical landmark effects: Freizeitpark doubles → bonus turn, Einkaufszentrum +1 coin per green/red store card
- Turn order progression
- Win condition (4 landmarks built)
- Bürohaus swap execution (the mechanics, not the choice)

**Strategic choices are engine territory** (see NORTH-STAR.md Section 6.5):
- Bahnhof: 1d6 or 2d6?
- What to buy (or save)?
- Bürohaus: which cards to swap (or skip)?
- Funkturm: re-roll or keep?

### 1.3 Standard Calcs Layer Responsibilities

Reusable, version-agnostic math that any engine can call:

- Dice probability distributions (`P1`, `P2`)
- Per-card income calculation (`get_I`)
- Expected value computation (weighted roll EV, per-round EV)
- ROI formulas (geometric-series discounted)
- Variance and risk metrics
- Probability of no income
- Synergy scoring helpers

---

## 2. Mathematical Formulas

### 2.1 Dice Probabilities

**1d6 (without Bahnhof):**
```
P(roll = k | 1d6) = 1/6   for k in {1..6}, else 0
```

**2d6 (with Bahnhof):**
```
P(roll = k | 2d6) = (6 - |k - 7|) / 36   for k in {2..12}, else 0
```

Precomputed as `double[] P1` and `double[] P2` constants indexed 0–12 (entries 0 and 1 of P2 are 0).

### 2.2 Blue Card EV per Round (N players)

Blue cards activate on every player's turn:
```
EV_round(blue card) = payout(roll) x P(activation_roll) x N
```

### 2.3 Discounted ROI over T Turns

```
ROI = EV_round x gamma x (1 - gamma^T) / (1 - gamma) - cost

where:
  gamma    = discountFactor (default 0.95)
  T        = horizonTurns   (default 10)
  EV_round = expected coins per full round
  cost     = card purchase price
```

### 2.4 Variance of Per-Turn Net Gain

```
Var = Sum_r P(r) x gain(r)^2 - EV^2
```

High variance = "swingy" card (Stadion, Bergwerk). Low variance = consistent income (Cafe, Bäckerei).

### 2.5 Geometric Sum Helper

```
geometricSum(T, gamma) = gamma x (1 - gamma^T) / (1 - gamma)
```

With L'Hopital guard (returns T when gamma is approximately 1).

---

## 3. Card Game Rules & `get_I` Conventions

### 3.1 `get_I` Perspective Convention

`get_I(r, p_id, oop, eb, f_c, a_c, p_c, c, co)` returns the coin delta for the queried player. The `oop` (own-turn perspective) flag disambiguates card colours:

| Colour | `oop = true` (own turn) | `oop = false` (opponent's turn) |
|--------|------------------------|---------------------------------|
| blau   | receives income        | receives income (fires both turns) |
| gruen  | receives income        | 0 (green only fires on own turn) |
| lila   | receives income        | 0 (purple only fires on own turn) |
| rot    | 0 (owner doesn't pay self) | **negative** (queried player is the roller, pays the owner) |

### 3.2 Income Processing Order

Official rules: **Rot -> Blau & Gruen -> Violett**.

`computeNetGainForRoll` implements this:
1. **Red** — opponents' red card payments deducted from roller's coins before any income
2. **Blue** — active player receives bank income from blue cards
3. **Green** — active player receives bank income from green cards
4. **Purple** — active player's purple effects fire last (Stadion, Fernsehsender steal; Bürohaus handled separately)

This ordering matters for the inability-to-pay rule: a roller with 0 coins pays nothing to red card owners, even if they would receive blue/green income on the same roll.

### 3.3 Counter-Clockwise Red Card Payment Order

When multiple red card owners trigger on the same roll, they are paid counter-clockwise from the active player:

```
for step in 1..(n-1):
    opponentIdx = (playerIndex - step + n) % n
```

Earlier claimants are paid in full; later claimants receive whatever remains.

### 3.4 `computeAllDeltasForRoll` — Single Source of Truth

`computeAllDeltasForRoll(state, activePlayer, roll)` returns an `int[]` of per-player coin deltas for a single roll, applying correct order and counter-clockwise priority. Used by `GameSession.applyTurn` and any simulation code.

### 3.5 Red Card Payment (Cafe, Familienrestaurant)

`get_I` from the roller's perspective for red cards:
- Returns a **negative** integer (amount the roller loses)
- Clamped to `-min(base_cost, current_coins)` for inability-to-pay
- Einkaufszentrum (`eb = true`) adds +1 to the amount the owner collects

### 3.6 Stadion (Lila, Roll 6)

Takes **2 coins from each opponent** (not just the richest). Total uncapped.
```
gain = Sum_opponents min(2, opponent_coins)
```

### 3.7 Fernsehsender (Lila, Roll 6)

Takes **up to 5 coins from the single richest opponent** (one target only).
```
gain = min(5, max(opponent_coins))
```

### 3.8 Bürohaus (Lila, Roll 6) — Special Handling

`get_I` returns 0 for bürohaus because card-swapping is non-monetary. The swap mechanics are implemented in `BürohausLogic.executeSwap`: remove the active player's chosen non-landmark card and replace it with a chosen non-landmark from any opponent. The **choice** of which cards to swap (or whether to swap at all) is engine territory.

### 3.9 Category Multipliers

| Card | Category multiplier |
|------|-------------------|
| Molkerei (roll 7) | 3 coins x animal card count (`a_c`) |
| Möbelfabrik (roll 8) | 3 coins x production card count (`p_c`) |
| Markthalle (rolls 11–12) | 2 coins x food card count (`f_c`) |

`f_c`, `a_c`, `p_c` are passed pre-computed by `PlayerStats.of(player)` to avoid recomputing per card in hot loops.

---

## 4. Data Model Design Rationales

### 4.1 Why `Project` is Immutable

`Project` objects are loaded once from JSON and reused everywhere. Making them immutable with `id`-based `equals`/`hashCode` means they can be safely shared across threads and copies without defensive copying. `Player.copy()` and `GameState.copy()` only need to copy the lists, not the objects.

### 4.2 Why `Player.copy()` is Shallow-Safe

`Player.copy()` creates a new `ArrayList` but keeps the same `Project` references. Safe only because `Project` is immutable.

### 4.3 Why `GameState` Uses Deep Copy

`GameState.copy()` calls `Player.copy()` for each player, producing independent player objects. Needed because simulations mutate `Player.coins` and `Player.owned_projects`, and multiple hypothetical states may be evaluated in parallel.

### 4.4 Why `ProjectLoader` Caches

`ProjectLoader` reads `projects.json` once at class load and stores a `Map<String, Project>`. All subsequent calls are pure map lookups, avoiding file I/O in hot paths.

### 4.5 Card Supply

Non-landmark establishments have 6 copies in the base game. Starting cards (Weizenfeld, Bäckerei) given to players are separate from the 6-copy supply pool — they do not reduce the available copies for purchase. `GameState.unbuilt_projects` stores one entry per non-landmark card type that still has copies available; a type is removed when total copies owned across all players reaches 6.

---

## 5. Engine Interface Contract

Defined in NORTH-STAR.md Section 6.2. Key points for implementers:

```java
interface SimulationEngine {
    String id();
    String description();
    EngineResult evaluate(GameState state, int playerIndex, EngineConfig config);
}
```

- `EngineResult` must contain: ranked purchase options with scores, explanation data (factor list with weights), and metadata (confidence, computation time, iterations).
- Engines share everything they compute — the UI decides what to display.
- Engines may call Standard Calcs and Core freely, but must not mutate the passed-in `GameState` (use copies).

---

## 6. MCTS Design

See NORTH-STAR.md Section 7 for the full specification. Key technical details:

### 6.1 Tree Node Types

- **Chance nodes**: dice outcomes, weighted by `P1` or `P2` probabilities
- **Decision nodes**: purchase choices (including save), one per player per turn
- All players modeled as decision-makers with the MCTS rollout policy

### 6.2 Rollout Approaches

- **v1 (full game)**: simulate until someone wins. Simple, accurate, slower.
- **v2 (depth-limited)**: stop after N turns, estimate winner via heuristic on position. Faster, quality depends on heuristic. To be validated head-to-head against v1.

### 6.3 Safety Valve

Maximum turn limit for rollouts. Games rarely exceed 60–70 turns with reasonable play — the limit must account for unlucky edge cases while preventing infinite loops.
