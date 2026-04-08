# Session Insights — Vision, Research & Design References

Reference document for project direction and research findings. TODOs extracted into TODO.md.

## User's Core Vision: The Teaching Assistant

The user wants the recommendation engine to explain **WHY** like a teacher or father figure — not just show numbers and charts. The explanation should flow as natural language, conversational, with numbers/charts interspersed but subordinate to the narrative.

### Roleplay Example (User-Provided)

> "Ok so if we look at this game situation ... I would go with the Käsefabrik. Even though the Bäckerei would give us a solid +1 every time we roll, the Käsefabrik synergizes with our 2 Bauernhöfe — giving us +6 whenever we roll a 7. That's a high-variance play, but since we already have Bahnhof (dice choice), we can choose to roll 2 dice more often, which gives us a ~17% chance at 7.
>
> Now, looking at the opponent — they have 3 landmarks already. They're close to winning. The Käsefabrik gives us an explosive turn potential that the Bäckerei can't match. We need to accelerate, not play safe.
>
> Risk-wise: yes, if we don't hit 7 for 3 turns, we fall behind. But the alternative (Bäckerei) only nets us +1/turn — not enough to keep pace. Sometimes you have to bet on your position."

### Target Characteristics
- **Flowing natural language**, not bullet points
- **Explains the "why"** — synergy, opponent threat, tempo reasoning
- **Acknowledges trade-offs** honestly (risk vs reward)
- **Uses concrete numbers** but embedded in sentences
- **Conversational tone** — "we", "our", "let's"
- **Teacher/father figure persona** — guiding, not just computing

### UX Reference: Skat App
User mentioned a Skat (German card game) app that provides explanations of recommended plays. This is the UX gold standard.

### Architecture Note
`summarySentence` field exists in `EngineResult.Option` but is always null. `structuredFactors` exist on CreatorEngine but are math-terse ("income: 1.2345 (weight 2.50 × raw 0.4938)"). The data is there — a `NarrativeExplainer` class in `iface/` translates it to prose. "It is the engine's job to crunch numbers, not yap whimsical teacher stuff." — User

## Research: Luck Analysis in Games (April 2026)

### The Backgammon Model (GNU Backgammon / eXtreme Gammon) — DIRECT FIT

**Per-Roll Luck:**
```
Luck_this_roll = WinRate_after(actual_roll) - E[WinRate_after(all_rolls)]
```
- Before each roll, enumerate all outcomes (6 for 1d6, 11+doubles for 2d6)
- For each outcome, compute win rate after income resolution
- Luck = how much better/worse the actual roll was vs the average
- GNU Backgammon thresholds: Very Lucky > +0.6, Lucky > +0.3, Neutral, Unlucky < -0.3, Very Unlucky < -0.6 (equity units)

**Per-Decision Skill Loss (chess centipawn-loss analog):**
```
Skill_loss = WinRate(best_option) - WinRate(chosen_option)
```

**Game-Level Aggregation:**
- `Total_Luck = sum(per_roll_luck)` per player
- `Luck-Adjusted Result = Actual Result - Own_Luck + Opponent_Luck`
- Strips out dice fortune, isolates decision quality

### Poker: All-In EV
- `EV_share = pot * win_probability` at all-in moment
- `Luck = Actual - EV_share`
- Over many hands, EV-adjusted line converges to "true skill"

### Chess: Lichess/Chess.com Accuracy
- Convert engine eval → win% via sigmoid before measuring error
- Key lesson: raw eval scores misleading — same error matters differently at different game stages. Win probability normalizes this.
- Per-move accuracy formula: `Accuracy% = 103.1668 * exp(-0.04354 * (winBefore - winAfter)) - 3.1669`
- Move classification: brilliant / great / good / inaccuracy / mistake / blunder

### Machi Koro Adaptation

**What maps cleanly:**
1. Dice rolls are the ONLY random element → per-roll luck is well-defined
2. MCTS already outputs win rates → per-decision skill loss is nearly free
3. `Calcs` already computes income distributions → can enumerate all roll outcomes
4. Doubles handling in ChanceNode (invariant #4) → luck calc must respect this too

**Design decisions:**
- Use win rate (0-1), not raw scores (HeuristicEv excluded or needs calibration via `scoreIsWinRate`)
- Fast engine (low-iter MCTS) for per-roll eval is fine — bias cancels since we compare same engine vs itself
- Per-roll luck + per-decision skill loss + game-level aggregation = complete picture

**UX reference:** Lichess game review (move-by-move chart with luck/skill annotations)

## Design Clarifications (from Q&A)

**Sweep opponent pool:** Use stronger opponents (heuristic-ev, creator-balanced-default, possibly self-play). Keep 1 weak engine as sanity check. Some engines are >80% beatable regardless of params — useless for optimization.

**Luck analyzer scope:** Live game UI + post-game replay. Quick computation, no perf concern. Not clear if engines themselves would benefit from luck awareness.

**Luck-adjusted WR for sweep:** Cautious, data-driven approach. Even good WR runs could be lucky. Standardize → automate, but validate first. "Quality and Results first, Automatability second."

**Player-vs-AI:** Both AI auto-play backend AND good UI matter equally. Notes box is an accessory, not centerpiece — tagged per-turn, exportable. Could be used to ask AI "explain why the engine did X" for specific turns.

**Engine comparison idea:** Give multiple engines the exact same game → compare decisions → analyze which were better. Needs standardized analysis tools (luck analyzer, card-value tracker).

**Scaling roadmap:** (1) Perfect 2P → (2) 3/4P adjustments → (3) Expansions.
