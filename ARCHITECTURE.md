# ARCHITECTURE.md — MachiKoroCalculator Technical Reference

This document contains the mathematical foundations, card rule conventions, and design rationales
for the probability engine. It is intended as a reference for anyone reading or modifying
`ProbabilityCalc.java`, `GameSimulator.java`, or the data model.

---

## 1. Mathematical Formulas

### 1.1 Dice Probabilities

**1d6 (without Bahnhof):**
```
P(roll = k | 1d6) = 1/6   for k ∈ {1..6}, else 0
```

**2d6 (with Bahnhof):**
```
P(roll = k | 2d6) = (6 − |k − 7|) / 36   for k ∈ {2..12}, else 0
```

Both tables are precomputed as `double[] P1` and `double[] P2` constants at class load in
`ProbabilityCalc.java`, indexed 0–12 (entries 0 and 1 of P2 are 0).

### 1.2 Blue Card EV per Round (N players)

Blue cards activate on *every* player's turn, so the owner receives income N times per round:
```
EV_round(blue card) = payout(roll) × P(activation_roll) × N
```

For a multi-activation card (e.g. weizenfeld activates on roll 1 only with 1d6):
```
EV_round(weizenfeld, N players, 1d6) = 1 × (1/6) × N
```

Implemented in `evPerRound()` by summing own-turn income + N−1 opponent-turn income.

### 1.3 Discounted ROI over T Turns

The discounted return on investment uses a geometric series:
```
ROI = EV_round × γ × (1 − γ^T) / (1 − γ) − cost

where:
  γ          = discountFactor (default 0.95)
  T          = horizonTurns   (default 10)
  EV_round   = expected coins per full round
  cost       = card purchase price
```

This is the **primary sort key** used by `rankPurchasableProjects`.

### 1.4 Win Probability — Analytical Softmax

Used in `estimateWinProbDelta` when `mcSimulations == 0`:
```
score(player p) = playerEvPerRound(p) × REMAINING_TURNS_ESTIMATE
                + Σ_{built landmark} LANDMARK_WEIGHT

P_win(player i) = exp(score_i) / Σ_j exp(score_j)

winProbDelta(candidate) = P_win(state_after_buy, i) − P_win(state_before_buy, i)
```

`playerEvPerRound` uses the player's actual `PlayerStats` (Einkaufszentrum, food/animal/production counts) and real opponent coin counts, so category multipliers (Molkerei, Möbelfabrik, Markthalle) and purple card values (Stadion, Fernsehsender) are scored correctly.

Constants in `WinProbabilityCalc`:
- `REMAINING_TURNS_ESTIMATE` — fixed estimate of turns left in the game
- `LANDMARK_WEIGHT` — bonus added per completed landmark

The softmax is computed with max-subtraction for numerical stability (see `softmaxEntry()`).

### 1.5 Variance of Per-Turn Net Gain

Used as the risk metric in `RankEntry.variance`:
```
Var = Σ_r P(r) × gain(r)² − EV²
```

High variance = "swingy" card (stadion, bergwerk). Low variance = consistent income (café, bäckerei).

---

## 2. Card Game Rules & `get_I` Conventions

### 2.1 `get_I` Perspective Convention

`get_I(r, p_id, oop, eb, f_c, a_c, p_c, c, co)` always returns the coin **delta for the
queried player**. The `oop` (own-turn perspective) flag disambiguates card colours:

| Colour | `oop = true` (own turn) | `oop = false` (opponent's turn) |
|--------|------------------------|---------------------------------|
| blau   | receives income        | receives income (same — fires both turns) |
| grün   | receives income        | 0 (green only fires on own turn) |
| lila   | receives income        | 0 (purple only fires on own turn) |
| rot    | 0 (owner doesn't pay self) | **negative** (queried player is the roller, pays the owner) |

### 2.2 Income Processing Order

The official rules specify: **Rot → Blau & Grün → Violett**.

`computeNetGainForRoll` implements this order:
1. **Red** — opponents' red card payments are deducted from the roller's coins *before any income is received*. Roller's coins at the start of red resolution = `activeCoins` (pre-roll).
2. **Blue** — active player receives bank income from their own blue cards.
3. **Green** — active player receives bank income from their own green cards.
4. **Purple** — active player's purple effects fire last (Stadion, Fernsehsender steal; Bürohaus handled separately in `immediateEV`).

This ordering matters for the **inability-to-pay** rule: a roller with 0 coins pays nothing to red card owners, even if they would receive blue or green income on the same roll.

### 2.3 Counter-Clockwise Red Card Payment Order

When multiple red card owners trigger on the same roll, they are paid in **counter-clockwise order** from the active player. Earlier claimants are paid in full; later claimants receive whatever remains.

`computeNetGainForRoll` iterates opponents as:
```
for step in 1..(n-1):
    opponentIdx = (playerIndex - step + n) % n
```

This is enforced consistently in:
- `computeNetGainForRoll` (EV model)
- `computeAllDeltasForRoll` (live game / simulation)

### 2.4 `computeAllDeltasForRoll` — Single Source of Truth for Roll Resolution

`computeAllDeltasForRoll(state, activePlayer, roll)` returns an `int[]` of per-player coin deltas for a single roll, applying the correct order and counter-clockwise priority. Both `GameSession.applyTurn` and `GameSimulator.applyRoll` use this method.

The two older bridge methods (`computeNetGainForRollPublic`, `computeOpponentTurnGainForRollPublic`) are retained as `@Deprecated` for backward compatibility but are no longer used in the live game path.

### 2.4b `evPerRound` — Projected Coin Correction

`computeNetGainForRoll` uses the players' current coin counts for red card clamping. This creates a **static-snapshot bias**: a player with 0 coins appears unable to pay red cards in the EV model, even though they will accumulate blue/green income before the roll that triggers the red card.

`evPerRound` corrects for this by projecting each player's coins forward before evaluation:
```
projectedCoins(player) = currentCoins + round(estimateUncappedOwnTurnEV(player))
```

`estimateUncappedOwnTurnEV` sums the player's own-turn blue+green income using `c=99` (no clamp), giving the income they can expect regardless of current wallet. The rounding converts the fractional EV to the nearest integer coin count.

`immediateEV` is **not** affected — it correctly uses actual current coins for the turn happening right now (the player may genuinely have 0 coins on their current turn).

### 2.5 Red Card Payment (Café, Familienrestaurant)

`get_I` is called from the *roller's* perspective for red cards:
- Returns a **negative** integer (the amount the roller loses)
- Clamped to `−min(base_cost, current_coins)` to enforce inability-to-pay
- Einkaufszentrum (`eb = true`) adds +1 to the amount the owner collects (roller pays +1)

The owner's gain equals the absolute value of the roller's loss. In `computeAllDeltasForRoll`, the owner's delta is set to `-loss` directly, ensuring the gain exactly matches what the roller pays (no double-counting).

### 2.6 Stadion (Lila, Roll 6)

Takes **2 coins from each opponent** (not just the richest). Total is uncapped.
```
gain = Σ_opponents min(2, opponent_coins)
```

### 2.7 Fernsehsender (Lila, Roll 6)

Takes **up to 5 coins from the single richest opponent** (one target only).
```
gain = min(5, max(opponent_coins))
```

### 2.8 Bürohaus (Lila, Roll 6) — Special Handling

`get_I` returns `0` for bürohaus because card-swapping is non-monetary.

The EV approximation is computed separately in `ProbabilityCalc.bürohausSwapEV()` and added
to the output of `immediateEV()`:
```
bürohausSwapEV = max(0, singleCardEvPerRound(bestOppNonLandmark)
                       − singleCardEvPerRound(worstOwnNonLandmark))

Contribution to immediateEV = P(roll = 6 | dice_mode) × bürohausSwapEV
```

**Limitation:** This assumes the player always makes the optimal swap. In reality the swap is
optional and only favourable when `bestOppEV > worstOwnEV`.

### 2.9 Category Multipliers

| Card | Category multiplier |
|------|-------------------|
| Molkerei (roll 7) | 3 coins × animal card count (`a_c`) |
| Möbelfabrik (roll 8) | 3 coins × production card count (`p_c`) |
| Markthalle (rolls 11–12) | 2 coins × food card count (`f_c`) |

`f_c`, `a_c`, `p_c` are passed pre-computed by `PlayerStats.of(player)` to avoid recomputing
per card in the hot loop.

---

## 3. Data Model Design Rationales

### 3.1 Why `Project` is Immutable

`Project` objects are loaded once from JSON and reused everywhere: in player owned lists,
unbuilt pools, `GameState` copies, and simulation states. Making them immutable and using
`id`-based `equals`/`hashCode` means they can be safely shared across threads and copies
without any defensive copying.

Consequence: `Player.copy()` and `GameState.copy()` only need to copy the *lists*, not the
`Project` objects themselves.

### 3.2 Why `Player.copy()` is Shallow-Safe

`Player.copy()` creates a new `ArrayList` but keeps the same `Project` references. This is
safe *only* because `Project` is immutable. If `Project` were ever made mutable, this would
need to change to a deep copy.

### 3.3 Why `GameState` Uses Deep Copy

`GameState.copy()` calls `Player.copy()` for each player, producing independent player objects
with independent owned-project lists. This is needed because:
- Simulations mutate `Player.coins` and `Player.owned_projects`
- Multiple hypothetical states (one per candidate card) are evaluated in parallel
- A deep copy ensures mutations in one evaluation don't affect others

### 3.4 Why `ProjectLoader` Caches

`ProjectLoader` reads `projects.json` once at class load and stores a `Map<String, Project>`.
All subsequent calls to `getProject()` and `getAllProjects()` are pure map lookups. This
matters because `get_I` calls (inside ranking hot loops) may call `ProjectLoader` indirectly,
and file I/O on every call would be ~1000× slower.

---

## 4. GameSimulator Design

### 4.1 Greedy Rollout Policy

Each simulated player follows this buy priority each turn:

1. **Landmark first:** if the player can afford the cheapest unbuilt landmark (Bahnhof → Einkaufszentrum → Freizeitpark → Funkturm in cost order), buy it.
2. **Best establishment:** else buy the establishment with the highest `STATIC_EV_PER_COST` score that the player can afford and is still in supply.
3. **Save:** if nothing is affordable, skip.

The landmark order is always cheapest-first because landmark abilities stack and it is never
correct to skip a cheaper landmark to buy a more expensive one.

### 4.2 Static EV/Cost Table

`GameSimulator.STATIC_EV_PER_COST` is precomputed once at class load using a 4-player neutral
reference state (each player: Weizenfeld + Bäckerei, 5 coins, no landmarks). This avoids calling
`ProbabilityCalc.evPerRound` inside the simulation hot loop, which would be ~100× more expensive.

**Trade-off:** The table uses a fixed reference state, so synergy effects (e.g. a player with
3 food cards gets more from Markthalle) are not reflected in the buy decisions during simulation.
This is an acceptable approximation for win-rate estimation.

### 4.3 Card Supply Tracking

```
initialSupply(card) = 6 − (copies already owned by players at simulation start)
```

Non-landmark establishments have 6 copies in the base game. The simulator tracks
`Map<String, Integer>` supply and prevents purchases when supply reaches 0. Landmarks have
unlimited supply (one per player, not shared).

### 4.4 Known Approximations in the Simulator

| Approximation | Impact | Location |
|---------------|--------|----------|
| Bahnhof always picks 2d6 | Overestimates income in early game when 1d6 might be better | `GameSimulator.rollDice()` |
| Static EV/cost table ignores synergy | Suboptimal buy decisions for synergy-heavy builds | `GameSimulator.STATIC_EV_PER_COST` |
| Bürohaus not executed in simulation | Bürohaus owners never swap; slightly underestimates their win rate | `GameSimulator.greedyBuy()` |
| `MAX_TURNS = 200` cap | Rare timeout returns -1 (excluded from win rate denominator) | `GameSimulator.simulate()` |

---

## 5. Known Limitations & Backlog

See `PLAN.md` (the active backlog) for the full list of known issues and planned improvements.
