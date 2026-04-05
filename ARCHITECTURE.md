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
- Starting cards: each player begins with 1 Weizenfeld + 1 Bäckerei (these are separate from the 6-copy market supply)
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

Non-landmark establishments have 6 copies in the base game market. Starting cards (Weizenfeld, Bäckerei) given to players at game start are **separate** from the 6-copy market pool — they do not reduce the purchasable supply. In a 2-player game, all 6 market copies of Weizenfeld and Bäckerei remain available. `GameState.starterCopies()` returns the number of starter copies per card type; `SupplyTracker.fromGameState()` and `GameStateBuilder.build()` subtract only purchased copies (total owned minus starters) when computing remaining supply.

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

Six engine variants are implemented:

- **v1 (full game)**: Simulate until someone wins. Uniform-random rollout policy. Simple, accurate, slower.
- **Variant A (greedy rollout)**: Informed rollout policy — landmark priority, then argmax over EV×geometricSum−cost. Tree phase unchanged (full UCT).
- **Variant B (Boltzmann rollout)**: Softmax purchase sampling with temperature T in rollouts. Stochastic-but-informed exploration.
- **Variant C (greedy tree)**: Argmax selection at `BuyDecisionNode` instead of UCT. All other nodes keep UCT. Rollout = uniform random.
- **Variant D (depth-limited)**: Stop rollout after N turns, evaluate position via `WinProbability.computeBaselineWinProb`. Faster, quality depends on heuristic.
- **Variant E (adaptive budget)**: Survey phase (iterations/5), then concentrate remaining budget on close races. Focused subtree exploration via `MctsTree.runIterationsFromNode`.

Each variant has fast/balanced/deep configurations. Total: 32 registry entries across 9 engine classes (6 MCTS + FlatMc + HeuristicEv + Expectimax).

### 6.4 Expectimax Engine

Deterministic minimax engine with probability-weighted chance nodes. No random rollouts — exhaustively evaluates the game tree to a configurable depth (in full rounds) using exact dice probabilities.

**Algorithm:** Recursive expectimax with alpha-beta pruning at decision nodes:

1. **DiceChoice** (if Bahnhof): MAX/MIN over {1d6, 2d6}.
2. **ChanceNode**: probability-weighted sum over roll outcomes.
   - 1d6: 6 branches (P = 1/6 each).
   - 2d6 with Freizeitpark: **15 branches** — odd rolls (5 branches, never doubles), roll 2 and 12 (always doubles), even rolls 4/6/8/10 (2 branches each: doubles + non-doubles with correct split probabilities).
   - 2d6 without Freizeitpark: standard 11 branches (2–12).
3. **FunkturmNode** (if owned): MAX/MIN over {keep, reroll}. Reroll uses pre-roll state; Funkturm NOT offered on reroll.
4. **BürohausNode** (if owned, roll=6): MAX/MIN over {skip + all valid swap pairs}. Deduplicated by card ID.
5. **BuyDecision**: MAX/MIN over {save + all affordable cards}. Instant win check for landmarks.
6. **Freizeitpark**: bonus turn on doubles (not chained — `isBonusTurn` flag prevents).

**Depth:** measured in full rounds (all players complete one turn). Depth decrements when perspective player's turn comes around.

**Leaf evaluation** (two variants):
- `"winprob"`: `WinProbability.computeBaselineWinProb()`, clamped to [0,1].
- `"composite"`: position score differential (evPerRound × 12 + landmarks × 15 + coins × 0.5) through sigmoid.

**Config:** `maxDepthRounds` (default 2), `leafEval` ("winprob" or "composite").

**Performance:** depth-1 ≈ 8–60ms, depth-2 ≈ 1.3–1.5s, depth-3 ≈ 17 min (impractical). Registry: 4 entries (d1/d2 × winprob/composite).

---

## 7. Known Engine Issues & Heuristic Choices

### 7.1 Critical Bug: MCTS Doubles Probability (ChanceNode)

**Severity:** High — affects all 6 MCTS engine variants (tree phase only; rollouts are correct).

**Issue:** `ChanceNode` (in `engine/mcts/ChanceNode.java`) treats ALL even 2d6 sums as 100% doubles. For example, roll=8 is classified as always being doubles, but only 1 out of 5 ways to make 8 is actually doubles (4+4). The correct doubles probability for each even sum is 1/(6−|sum−7|).

**Impact:** When a player owns Freizeitpark, the MCTS tree overestimates the probability of bonus turns for even rolls. Roll 8 has P(doubles)=1/5 but MCTS assumes P(doubles)=1. This inflates the value of Freizeitpark-related strategies in the tree phase.

**Note:** The Expectimax engine handles this correctly with 15-branch chance nodes (see Section 6.4). MctsRollout (used in rollouts) also correctly uses `d1 == d2` to detect doubles.

**Status:** Deferred — will be fixed after docs cleanup.

### 7.2 Heuristic Choices (Documented for Discussion)

These are deliberate simplifications in the Calcs and WinProbability layers. Each has a known directional effect on evaluation quality.

1. **Blue card EV assumes opponent optimal dice** (`Calcs.evPerRound`): Takes `max(1d6_ev, 2d6_ev)` for opponent turns regardless of Bahnhof ownership. **Effect:** slightly overstates opponent income, making the perspective player's position appear marginally worse. Conservative bias — may slightly undervalue buying blue cards.

2. **WinProbability endgame bonus is binary** (`WinProbability.java`): 2.5× score multiplier only when player has exactly 3 landmarks AND can afford the 4th right now. No gradient for "almost there" (e.g., 1-2 coins short). **Effect:** may undervalue endgame positions where the player is very close to winning but doesn't quite have enough coins.

3. **Landmark weights are static constants** (`WinProbability.java`): Fixed coin-equivalent values (Bahnhof=24, Einkaufszentrum=36, Freizeitpark=24, Funkturm=48). Don't adapt to card synergies or game phase. **Effect:** softmax heuristic may mis-rank players in unusual board states where landmark value differs from the average case.

4. **Linear turn projection in evPerRound** (`Calcs.java`): Assumes linear coin accumulation over opponent turns. **Effect:** overestimates opponent resource growth in high-variance portfolios. Matters more in late game with concentrated income distributions.

5. **CVaR assumes fixed dice strategy** (`Calcs.java`): Risk metrics use the same dice choice for the tail distribution as for the expected case. **Effect:** may understate downside risk when switching dice count would be better in bad scenarios. Only affects UI risk explanations, not engine ranking.

### 7.3 Safety Valve

Maximum turn limit for rollouts. Games rarely exceed 60–70 turns with reasonable play — the limit must account for unlucky edge cases while preventing infinite loops.

---

## 8. Structured Explanation System

### 8.1 ExplanationFactor Model

Each purchase option carries structured explanation data:
- `ExplanationFactor`: `category` (9 types), `weight` [0,1], `summary` (one-line), `detail` (expandable)
- `Option.structuredFactors`: sorted by weight descending
- `Option.summarySentence`: "Buy {cardName} — {highestWeightFactor.summary}"

### 8.2 Weight Computation (Two-Pass Enrichment)

1. **Pass 1**: Build all options with metrics (existing flow)
2. **Pass 2**: Compute cross-option means and ranges per metric, then for each option generate weighted factors

Weight formula: `|value - mean| / range` normalized to [0,1]. Weight = 0 when range = 0 (metric doesn't discriminate).

9 factor categories: `winRate`, `income`, `synergy`, `risk`, `tempo`, `landmark`, `cost`, `coverage`, `scarcity`.

### 8.3 Pre-computation

`PrecomputeCache`: single-entry thread-safe cache with daemon `ExecutorService`. Key = `(structuralHash, playerIndex, engineId)`. New request cancels in-flight computation. `GameState.structuralHash()` provides a deterministic hash of player coins and sorted owned card IDs.
