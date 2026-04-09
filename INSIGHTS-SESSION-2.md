# Session Insights — April 2026 Architecture Discussion

## Skill Loss Assessment

Per-decision skill loss (TODO #2) was analyzed and **deferred**. Key finding:

**The "beginner judging a grandmaster" problem:** Skill loss uses an oracle (MC greedy rollouts) to judge engine decisions. But our MC oracle is probably *weaker* than our best MCTS engines. It would reliably evaluate human play or weak policies, but give false "bad move" signals for strong engines that see deeper.

**Prerequisite for skill loss:** A significantly stronger oracle. Either:
- Much deeper MC simulations (impractical with current speed)
- A heuristic oracle that's fast enough to run thousands of evaluations (needs WinProbability improvement first)

**When it makes sense:** As a component inside post-game review for *human* games (humans play worse than any oracle). Not as a standalone engine evaluation tool — that's what Elo is for.

## Luck Computation Cost

Current LuckAnalyzer uses MC simulations (200 games per hypothetical roll outcome) — expensive. But the core operation is just: "after applying income for roll X, what's the win probability?"

**Insight:** Switch to WinProbability heuristic instead of MC for luck computation. Makes it near-instant. Trade-off: ~25% MAE in absolute WR values, but for *relative* luck (roll A vs roll B within the same game), the consistent bias cancels out.

**Prerequisite for high-quality real-time luck:** Improve WinProbability heuristic to <10% MAE (TODO #23).

## WinProbability as Foundation

WinProbability heuristic (currently ~25% MAE) is the key enabler for multiple features:
- **Instant luck computation** (replace MC with heuristic)
- **Luck-adjusted ratings** (if heuristic is accurate enough)
- **Faster Expectimax leaf evaluation** (already used, would benefit from improvement)
- **Real-time position assessment** in UI

Improving it to <10% MAE (TODO #23) is high-leverage infrastructure.

## Luck-Adjusted Ratings Vision

User wants luck-adjustment added to ALL places where win rates appear:
- H2H match results (raw WR + luck-adjusted WR)
- Glicko-2 ratings (rate on luck-adjusted outcomes)
- Sweep optimization (optimize against luck-adjusted WR)
- UI wherever WR is displayed

This is TODO #24, depends on TODO #23.

## Automated Engine Improvement Loop

Current workflow: manually run sweep → manually test → manually promote. Vision:
1. Sweep optimizer finds best parameters → produces new engine config
2. Auto-battle tests it against the field → assigns luck-adjusted rating
3. If it's the new best → becomes the default recommendation engine
4. **Feed auto-battle results back into sweep optimizer** (closed loop)
5. Repeat

Key missing piece: closing the feedback loop between step 2 → step 1.

## Player-vs-AI as Dual-Purpose

Player-vs-AI mode serves two purposes:
1. **Learning for the player** — practice against AI, see how strong opponents play
2. **Testing for the engine** — human play reveals weaknesses that automated testing can't (e.g., "this engine always buys Bahnhof too early")

**Time budget mode (TODO #17):** In a real game, the engine should think until the player says "your turn" — not be locked to an iteration count. Need to track think time + iterations for performance analysis.

## Bitwise Core Rework — Full Design

### Card inventory (from scraped data)

**Base game:** 19 card types
- 4 landmarks (Bahnhof, EKZ, Freizeitpark, Funkturm)
- 12 normal establishments (0-6 copies per player, 3 bits each)
- 3 purple establishments (0-1 per player, 1 bit each)

**With all expansions:** 46 card types
- 7 landmarks (base 4 + Harbor 3: Harbor, Airport, City Hall)
- 33 normal establishments (base 12 + Harbor 10 + Millionaire's Row 11)
- 6 purple establishments (base 3 + Millionaire's Row 3: Int'l Exhibit Hall, Tech Startup, Park)

Source: `scripts/scraped_cards.json` + `scripts/scraped_cards_reference.json` (54 entries, scraped 2026-04-01).

### Encoding: 51 bits per player (base game), fits in one `long`

```
Bits 0-7:   coins (8 bits, 0-255)
Bits 8-11:  landmarks (4 bits: bahnhof|ekz|fzp|funkturm)
Bits 12-47: 12 normal card counts × 3 bits each (0-7)
Bits 48-50: 3 purple cards × 1 bit each
--- 51 bits used, 13 spare in a long ---
```

**Base game:** 51 bits → 1 `long` per player. 2P = 2 longs. 4P = 4 longs.
**With expansions:** 120 bits → 2 `long` per player. 2P = 4 longs. 4P = 8 longs.
**Supply** is derived (6 minus sum of player counts per card type). Not stored.

8 bits for coins (0-255) instead of 7 — covers degenerate games where weak engines accumulate >127 coins.

### Why it matters
- **Copy = copy longs** (not allocate ArrayLists, Player objects, etc.)
- **Category counts = shift+mask+add** (not loop through owned_projects comparing strings)
- **Income resolution = tight loop** with no object lookups
- **Cache-friendly** — entire 2P game state fits in one CPU cache line (128 bits = 16 bytes)

### Expansion-friendly design
All bit positions defined in a single `BitStateTranslator` class. Card indices (0-14 for base, 0-45 for full) map to bit offsets. Expanding from 1 long to 2 longs per player requires updating the translator constants — all operations go through it, nothing hardcoded.

### Operations cheat sheet

| Operation | Object-based | Bitwise |
|-----------|-------------|---------|
| Copy state (2P) | 2× Player copy (ArrayList alloc + copy), 1× unbuilt ArrayList copy | Copy 2 longs |
| Get coins | `player.getCoins()` | `(int)(state & 0xFF)` |
| Set coins | `player.setCoins(n)` | `(state & ~0xFFL) \| n` |
| Has landmark | `(landmarkFlags & bit) != 0` | `((state >> 8) & bit) != 0` |
| Card count | Loop `owned_projects`, compare IDs | `(int)((state >> offset) & 0x7)` |
| Add card | `ArrayList.add()` + landmarkFlags update | `state += (1L << offset)` |
| Has won? | `landmarkCount >= 4` | `((state >> 8) & 0xF) == 0xF` |
| Food count | Loop owned_projects, check category string | `count(weizenfeld) + count(bauernhof) + count(apfelplantage)` = 3 shift+mask+add |
| Supply remaining | Build HashMap, iterate all players | Sum per-card count across player longs, subtract from 6 |
| Bürohaus swap | removeProject + addProject (list ops) | 4 bit ops: decrement A's card, increment B's, vice versa |

### Codebase usage analysis — what touches GameState/Player

**HOT PATH (millions of calls per evaluation — bitwise migration critical):**
- `GameSimulator.simulate()` — copies state, mutates during rollout
- `MctsRollout`, `GreedyRollout`, `BoltzmannRollout` — full-game simulation
- `ChanceNode`, `BuyDecisionNode`, `BürohausNode` — MCTS tree construction
- `RollResolver.computeAllDeltasForRoll()` — income calculation
- `CardIncome.get_I()` — per-card income dispatch
- `MutableSupplyTracker` — supply tracking in rollouts

**WARM PATH (once per evaluation — moderate benefit):**
- All engine classes (MctsV1, Expectimax, FlatMc, HeuristicEv, Creator)
- `MctsTree`, `MctsNode` (tree management)
- `Calcs`, `WinProbability`, `GameStateSampler`, `LuckAnalyzer`
- `TurnPlan` (extracts decisions from tree)

**COLD PATH (human timescale — no migration needed):**
- All server HTTP handlers and serializers
- H2H system (MatchRunner, TournamentRunner, etc.)
- GameSession, GameSessionPersistence
- Tests (RuntimeTester)

### Gradual migration strategy

**Goal:** Migrate as much as possible to bitwise, class by class. Only UI-facing code (server serialization, translator/narrator) stays object-based.

**Phase 1: Foundation**
- New `BitState` class in `core/` — holds `long[]`, provides all bitwise operations
- New `BitStateTranslator` class — maps between bit positions and card IDs/indices, single source of truth for encoding layout
- `BitState.fromGameState(gs)` and `BitState.toGameState()` conversion
- Bitwise income resolution (replaces `RollResolver` for simulations)
- Bitwise category counting (food/animal/production)
- **Equivalence test:** Run N games with both representations, compare every state

**Phase 2: Simulation hot path (biggest win)**
- `GameSimulator.simulate()` uses `BitState` internally
- `GameSimulator.mcWinRate()` uses `BitState`
- Bitwise `greedyBuy` / `boltzmannBuy` (card index instead of Project object)
- Bitwise supply tracking (arithmetic instead of HashMap)

**Phase 3: MCTS rollouts**
- `MctsRollout`, `GreedyRollout`, `BoltzmannRollout` use `BitState`
- This is where most CPU time is spent — state copy happens once per rollout
- `RolloutEvCache` adapts to `BitState`

**Phase 4: MCTS tree nodes**
- `MctsNode` stores `BitState` instead of `GameState`
- `ChanceNode`, `BuyDecisionNode`, `BürohausNode`, `FunkturmNode` operate on `BitState`
- Tree construction becomes pure arithmetic
- `SupplyTracker` (immutable) adapts to `BitState`

**Phase 5: Analysis and remaining engines**
- `WinProbability`, `LuckAnalyzer`, `GameStateSampler` use `BitState`
- `ExpectimaxEngine`, `FlatMcEngine`, `CreatorEngine` internals
- `HeuristicEvEngine` (reads state for metrics)
- `Calcs` layer methods

**Phase 6: Interface boundary**
- `EngineOrchestrator` converts GameState→BitState at entry, BitState→GameState at exit
- `TurnPlan` works with card indices internally, translates to Project for API
- Object-based GameState remains the API contract with server/UI

**Stays object-based forever:**
- Server HTTP handlers + JSON serialization
- GameSession + GameSessionPersistence (save/load files)
- UI communication (React expects JSON with card names, not bit patterns)
- NarrativeExplainer (when built) — needs readable card references

### Language choice
Java is fine. The bottleneck is object allocation + cache misses, not language speed. JIT compiles long arithmetic to native instructions. Rust/C would add maybe 2-3x on top but breaks the entire codebase integration. Bitwise-in-Java first; native via JNI only if still not fast enough.

### Testing approach
Equivalence testing: play N games through both object-based and bitwise paths. After every state mutation (income resolution, purchase, Bürohaus swap), convert bitwise→object and compare against the object-based result. Any discrepancy = bit-level bug. This catches off-by-one errors in shift offsets, sign extension issues, etc.

---

## Priority Assessment

From discussion, the user's priorities are:
1. **Bitwise core brainstorm/research** (done — see above)
2. **WinProbability heuristic improvement** (TODO #23 — high-leverage foundation)
3. **Luck-adjusted ratings** (TODO #24 — needs #23)
4. **Player-vs-AI + time budget** (TODOs #14, #17)
5. **Automated improvement loop** (connects sweep → auto-battle → promote)
6. **Per-decision skill loss** (deferred — needs stronger oracle)

## System Overview Created

`SYSTEM-OVERVIEW.md` now exists as a non-technical product-focused document covering:
- What the user can do today
- System component diagram with groupings (user-facing / engine R&D / foundation / analysis)
- Value map (where value comes from, maturity level)
- Three gaps: player-vs-AI, narrative explanation, post-game review
- Open questions for prioritization
