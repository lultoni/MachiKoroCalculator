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

Each variant has fast/balanced/deep configurations. Total: 35 registry entries across 10 engine classes (6 MCTS in `engine.mcts` + FlatMc in `engine.flat` + HeuristicEv in `engine.heuristic` + Expectimax in `engine.expectimax` + Creator in `engine.creator`).

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

### 6.5 Creator Engine

Custom strategy engine encoding a low-risk, income-first, adaptive philosophy with decisive endgame execution. Uses a **seeded Flat Monte Carlo** architecture: a fast heuristic pre-ranks candidates, then MC rollouts validate and refine with biased allocation.

**Architecture — Two-Phase Seeded FlatMC:**

1. **Phase 1: Heuristic Seeding (~2-5ms)** — `CreatorScorer` scores all candidates using a holistic situation assessment and 8 weighted dimensions.
2. **Phase 2: MC Validation (budget-dependent)** — Biased FlatMC sampling with 50%/30%/20% allocation by heuristic rank. If budget=0, returns heuristic-only result.

**Situation Assessment** (replaces simple landmark-count progress):

```
situation = 0.30 × (landmarks / 4)
          + 0.30 × clamp01(evPerRound / targetEv)
          + 0.15 × clamp01(coins / remainingLandmarkCost)
          + 0.25 × clamp01(1 − ETW / maxETW)
```

All four weights configurable via `EngineConfig.extra` (`sitLandmark`, `sitIncome`, `sitCoins`, `sitTempo`).

**8 Scoring Dimensions** (all configurable base weights + sigmoid multipliers):

| Dimension | Default base | Source |
|-----------|-------------|--------|
| income | 2.5 | evPerRound + portfolioDeltaEV |
| risk | 2.0 | ΔCVaR(10%) + ΔprobNoIncome + correlation diversity |
| coverage | 1.5 | ΔincomeEntropy + Δcoverage density |
| tempo | 2.0 | tempoAdvantage |
| winProb | 3.0 | estimateWinProbDelta |
| landmark | 2.0 | dynamic landmark value (EV-based) |
| urgency | 1.0 | purchaseUrgency (scarcity) |
| roi | 1.5 | roiOverHorizon(horizon=5, γ=0.95) |

Risk and coverage dimensions are **delta-based**: each measures the card's marginal improvement over the current portfolio baseline (computed once via `WAIT_SENTINEL`). A card that doesn't change the portfolio's risk profile or roll coverage scores 0 on those dimensions. This prevents baseline inflation where useless cards (e.g., Möbelfabrik with 0 production cards) scored positive purely from existing portfolio quality.

**7-12 Activation Guard:** Non-landmark cards that only activate on rolls 7-12 have their composite score scaled by an `activationGuard` factor:
- Green (own-turn): `0.0` without own Bahnhof (can't trigger on own turn).
- Blue (all turns): `0.5 × oppFrac2d6` without own Bahnhof (only opponent-turn value remains, scaled by fraction of opponents using 2d6); `1.0` with own Bahnhof.
- Red (opponent turns): `oppFrac2d6` — scales by fraction of opponents likely to use 2d6.
- `oppFrac2d6 = count(opponents with Bahnhof + non-red 7-12 cards) / totalOpponents`.
- Cards with `activationGuard = 0.0` are excluded from MC sampling to prevent inflated win rates from rollouts eventually buying Bahnhof.

Each weight has a situational multiplier: `effectiveWeight = baseWeight × (low + (high − low) × sigmoid(k × (situation − 0.5)))`. Multipliers shift with game situation (e.g., income emphasis decreases as situation rises).

**Gravity Wells:**
- **Instant-win snap**: Hard override — `Double.MAX_VALUE` when `findInstantWinLandmark()` succeeds.
- **Win-sprint ramp**: Gradual with configurable `sprintHorizon` (default 6) and `sprintSharpness` (default 1.0). Boosts tempo/winProb, suppresses income/risk.
- **Threat-response ramp**: Gradual with configurable `threatHorizon` (default 8) and `threatSharpness` (default 1.0). Detects approaching opponents early.

**CreatorRollout v3:** Custom rollout policy for Creator Engine's MC validation phase. Builds on GreedyRollout's proven cheapest-landmark-first, deterministic dice/Funkturm/Bürohaus patterns, and adds two Creator-specific enhancements:
- **Coverage bonus** (`COVERAGE_BONUS=0.15`): Cards that activate on roll values the player doesn't currently cover receive a bonus proportional to `newCoverage × 0.15 × cardEV`. Uses bitmask (`computeCoveredRolls`) to track which rolls produce income, excluding red/landmark cards. Promotes portfolio diversification.
- **Save-toward-landmark** (`SAVE_THRESHOLD_RATIO=0.3`): When the player is within 4 coins of the next unowned landmark and the best card's net value is below 30% of that landmark's cost, the rollout saves instead of buying a marginal card. Prevents wasteful purchases that delay landmark progression.

H2H benchmarks (7.46, 100 games at 5000 iterations): CreatorRollout v3 wins 74% vs MCTS-v1 (greedy: 70%), 67% vs heuristic-ev (greedy: 66%), 61% vs Flat MC (greedy: 61%). Now the default rollout policy for CreatorEngine.

**Bürohaus Swap Bonus:** Post-composite bonus (not a 9th dimension) applied when Bürohaus is relevant:
- **Case A (owns Bürohaus):** Cheap low-EV cards get a bonus as swap bait when they would lower the player's worst-card EV below the current worst. `bonus = P(roll=6) × swapDeltaGain × swapQuality × wBurohausSwap`. `swapQuality` discounts when the bait card is valuable to the opponent. Uses card-alone `contextualCardEvPerRound`, not portfolio EV.
- **Case B (buying Bürohaus):** Bürohaus purchase gets a bonus reflecting the expected swap value that ownership would unlock: `bonus = P(roll=6) × potentialSwapDelta × swapQuality × wBurohausSwap`.
- Swap context (`SwapContext`) is precomputed once per `scoreAll()` call via `BürohausLogic.findCandidates()`.
- Configurable: `wBurohausSwap` (default 1.5).

**31 configurable knobs** via `EngineConfig.extra` for H2H sweep optimization: 4 situation weights, 8 base weights, 1 sigmoid steepness, 4 gravity well parameters, 1 save discount, rollout policy + temperature, plus multiplier endpoints.

**Config:** `iterations` (iteration budget), `timeBudgetMs` (anytime mode), `rolloutPolicy` ("creator" default / "greedy" / "uniform" / "boltzmann"). Default changed from greedy to creator in 7.46 after CreatorRollout v3 (coverage bonus + save-toward-landmark) matched or beat greedy across all opponents. Registry: 3 entries (fast/balanced/deep).

---

## 7. Known Engine Issues & Heuristic Choices

### 7.1 MCTS Doubles Probability (ChanceNode) — FIXED

**Fixed in:** 7.18

`ChanceNode` now splits even 2d6 rolls into doubles/non-doubles branches with exact probabilities, matching the Expectimax engine's 15-branch model. When Freizeitpark is owned and it's not a bonus turn, even rolls 4/6/8/10 produce two children each (doubles at 1/36 and non-doubles at (totalWays−1)/36). Rolls 2 and 12 produce one child each (always doubles). Odd rolls produce one child (never doubles). Total: up to 15 children.

`navigateToRoll(ChanceNode, roll, isDoubles)` uses per-child metadata to find the correct branch during H2H tree navigation.

### 7.2 Heuristic Choices (Documented for Discussion)

These are deliberate simplifications in the Calcs and WinProbability layers. Items 1-5 were reviewed and fixed as part of TODO #11. Additional approximations (A-F) were discovered during the review.

1. **Blue card EV now Bahnhof-aware** (`CardIncome.playerEvPerRound`, `contextualCardEvPerRound`): Previously took `max(1d6_ev, 2d6_ev)` for all players regardless of Bahnhof ownership. Now correctly uses only 1d6 when the player doesn't own Bahnhof. **Fixed in 7.20.**

2. **WinProbability endgame bonus — continuous gradient** (`WinProbability.computeScores`): Replaced binary 2.5× multiplier (3 landmarks + can afford 4th) with a continuous proximity bonus: `score *= 1.0 + landmarkCount × 0.5 × proximity` where `proximity = min(1, coins / cheapestLandmarkCost)`. 0 landmarks → no bonus; 3 landmarks + can afford → 2.5× (backwards-compatible peak). **Fixed in TODO #11.**

3. **Landmark weights are dynamic and synergy-aware** (`WinProbability.computeLandmarkWeight`): Replaced static constants (24/36/24/48) with per-player marginal EV calculations. Bahnhof: `max(0, ev_2d6 − ev_1d6)`, zero if no non-red cards with dice activation ≥ 7. Einkaufszentrum: sum of +1 bonuses across store/café cards with turn-frequency scaling. Freizeitpark: `P(doubles) × secondRollEV`, zero without Bahnhof. Funkturm: expected reroll improvement `Σ P(r) × max(0, baseline − gain(r))`. Each scaled by `remainingTurns`. **Fixed in TODO #11.**

4. **Iterative compound turn projection** (`Calcs.evPerRound`): Replaced linear `stepCoins = baseCoins + step × bluePerOppTurn` with iterative accumulation where each opponent's turn income compounds into the coin base for the next. Eliminates overestimation in high-variance portfolios. **Fixed in TODO #11.**

5. **CVaR/VaR uses optimal dice strategy** (`Calcs.conditionalValueAtRisk`, `Calcs.valueAtRisk`): Risk metrics now use `max(1d6_metric, 2d6_metric)` instead of inheriting the EV-optimal dice choice. Correctly models downside risk when switching dice count is better in tail scenarios. **Fixed in TODO #11.**

**Additional findings (discovered during TODO #11 review):**

- **A. Adaptive coin-advantage scale** (`WinProbability.computeScores`): Replaced static `COIN_ADVANTAGE_SCALE = 50.0` with `max(1.0, avgEvPerRound × 2.0)`. Early game (low EV) → coin lead matters more; late game (high EV) → less so. **Fixed in TODO #11.**

- **B. Landmark-based remaining turns** (`WinProbability.computeScores`): Replaced dead `turnsElapsed` parameter with landmark-progress estimation: `max(3.0, 50.0 × (1 − avgLandmarks / 4))`. Calibrated from empirical H2H data (avg ~60 turns/player). **Fixed in TODO #11.**

- **C. `TOTAL_EXPECTED_TURNS`** calibrated to 50.0 (was 25.0). Based on H2H empirical data showing average 59.7 turns per player; 50 is conservative for human play.

- **D. `GameSimulator.ROI_GEOMETRIC_SUM`** now sourced from `RankingOptions.DEFAULT_DISCOUNT_FACTOR` (was a magic 0.95 literal). **Fixed in TODO #11.**

- **E. `c=99` in `CardIncome.estimateUncappedOwnTurnEV` and `playerEvPerRound`** is correct, not an approximation. Blue/green cards pay from the bank (c irrelevant); red cards use actual opponent coins array. Documented in Javadoc. **Documented in TODO #11.**

- **F. `BürohausLogic` greedy swap policy** is a deliberate design choice. Single-activation greedy EV-max is the correct analytical approximation for Calcs/Core. Multi-turn swap optimization belongs in Engine layer (MCTS tree search). Documented in class-level Javadoc. **Documented in TODO #11.**

### 7.3 Safety Valve

Maximum turn limit for rollouts. Games rarely exceed 60–70 turns with reasonable play — the limit must account for unlucky edge cases while preventing infinite loops.

### 7.4 MCTS Instant-Win Detection (BuyDecisionNode) — FIXED

**Fixed in:** 7.29

**Problem:** In full-turn MCTS trees, the iteration budget is spread across DiceChoice × ChanceNode × BuyDecisionNode branches. A terminal "buy last landmark" child correctly scores 1.0 on every visit, but "save" also accumulates near-1.0 scores because random rollouts eventually buy the landmark. With limited budgets (500-2000 iterations), `bestChild()` (most-visited) may never converge on the winning child.

**Fix:** `BuyDecisionNode.instantWinChildIndex` records any child that creates a terminal winning state during `expand()`. `MctsTree.select()` and `bestChild()` short-circuit to this child unconditionally — an immediate win is always optimal, no exploration needed.

### 7.5 Rollout Instant-Win Detection — FIXED

**Fixed in:** 7.35

**Problem:** MCTS rollout policies (uniform-random, greedy, Boltzmann) did not check for instant-win states. In endgame positions where a simulated player has 3 landmarks and enough coins to buy the 4th, the uniform-random rollout could choose "save" with probability 1/N (where N = number of options), dragging out games and reducing rollout signal quality.

**Fix:** `GameState.findInstantWinLandmark(Player)` — O(1) fast-path when `getLandmarkCount() != 3`. All 3 rollout purchase methods call this before any other logic. DepthLimitedRollout inherits the fix via delegation to MctsRollout.

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
