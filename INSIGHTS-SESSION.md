# Session Insights — Direction & Vision (April 2026)

Temporary file to preserve context across compaction. User explicitly requested this.

## User's Core Vision: The Teaching Assistant

The user wants the recommendation engine to explain **WHY** like a teacher or father figure — not just show numbers and charts. The explanation should flow as natural language, conversational, with numbers/charts interspersed but subordinate to the narrative.

### Roleplay Example (User-Provided)

The user gave a detailed example of how an ideal explanation should read:

> "Ok so if we look at this game situation ... I would go with the Käsefabrik. Even though the Bäckerei would give us a solid +1 every time we roll, the Käsefabrik synergizes with our 2 Bauernhöfe — giving us +6 whenever we roll a 7. That's a high-variance play, but since we already have Bahnhof (dice choice), we can choose to roll 2 dice more often, which gives us a ~17% chance at 7.
>
> Now, looking at the opponent — they have 3 landmarks already. They're close to winning. The Käsefabrik gives us an explosive turn potential that the Bäckerei can't match. We need to accelerate, not play safe.
>
> Risk-wise: yes, if we don't hit 7 for 3 turns, we fall behind. But the alternative (Bäckerei) only nets us +1/turn — not enough to keep pace. Sometimes you have to bet on your position."

### Key Characteristics
- **Flowing natural language**, not bullet points
- **Explains the "why"** — synergy, opponent threat, tempo reasoning
- **Acknowledges trade-offs** honestly (risk vs reward)
- **Uses concrete numbers** but embedded in sentences
- **Conversational tone** — "we", "our", "let's"
- **Teacher/father figure persona** — guiding, not just computing

### Reference: Skat App
User mentioned a Skat (German card game) app that does something similar — provides explanations of recommended plays. This is the UX gold standard for the user.

## TODO #48: Sweep Against Stronger Opponents + Luck Analyzer

**Core Problem:** The tuned Creator Engine gets good win rates against many weaker engines but DOESN'T dominate:
- (a) the normal heuristic-ev
- (b) the creator-balanced with default params

**User's Ultimate Goal:** Create an engine that gets blowout wins against basically every opponent when luck is removed from the equation. The user originally wanted to minimize risk/luck when designing CreatorEngine.

**Luck Analyzer Concept:**
- For each game situation, compute the expected income distribution: e.g. `[+2 at 1/6][+1 at 3/6][+0 at 2/6]` (at x being roll chance - could be substituted for just the roll numbers or leaned into for clear "rolled unexpected rolls continuously")
- Track whether the player rolled their 1st-best, 2nd-best, or 3rd-best outcome each turn
- Aggregate these counts over an entire game → histogram of luck quality per turn
- Do this for BOTH own rolls and opponent rolls
- Use this to separate "lost due to bad luck" from "lost due to bad strategy"
- **Danger acknowledged by user:** feedback loop risk — engine might blame luck when it's actually playing badly. The analyzer needs to be honest, not an excuse machine.
- **Semi-automated feedback loop:** Use game replay insights + luck analysis to identify WHERE things go wrong (strategy vs luck)

**This is marked as a "User Task"** — meaning the user drives the thinking, AI assists. _User comment: if this is (semi-)automatable then that would be the best of both worlds, but that remains to be seen based on the actual implementation of this. Quality and Results first, Automatbility second_

## TODO #49: Brainstorm Machi Koro Fundamentals + Player-vs-AI Mode

**Two sub-goals:**

### A) Fundamental Strategic Thinking
- How to fundamentally think about what is "good" vs "bad" in Machi Koro
- How to analyze a game situation to find the best solution
- This would feed into a creator-engine-v2
- **Method:** Play lots of 2p, 3p, 4p games (ideally against humans)

### B) Player-vs-AI Mode
- UI that looks like the real board game for immersion
- Player plays against the AI engine
- **Notes box** where the player can jot down thoughts, insights during play
- Notes can later be evaluated (possibly with AI assistance) for strategic patterns
- This is the user's research tool for understanding the game deeply

**This is marked as a "User Task"** — brainstorming and inspiration gathering first, then AI helps hone in on the best approaches.

_User comment: this could be used to kind of find weaknesses of engines. so if we give the engines the exact same game to play and their decisions differ from mine that i took it can at the end of the game be analysed if that decision was a good or bad one and if i should have listened to the (at the time nonexistent) ai help or if the ai was saying stupid stuff. This would again need standardised analysis tools like the luck analyser or a "card-value-throughout-game" analyser (or something)._

## Direction Summary

The user is shifting focus from **infrastructure** (which is now mostly complete: sweep UI, engine builder, shared params, auto-battle) toward **quality of play** and **quality of explanation**:

1. **Make the engine actually good** (dominate all opponents, not just weak ones)
2. **Understand WHY through play** (player-vs-AI mode, note-taking, luck analysis) so the creator is able to give better insights and ideas for the further development
3. **Explain engine recommendations like a teacher** (natural language, flowing, honest about trade-offs)

The remaining infrastructure TODOs (#42 sweep refinement, #44 GPU, #45 time budgets) serve these goals but aren't the primary focus anymore.

## Clarifications from Q&A (April 8 2026)

### #48 Clarifications

**Opponent pool for sweep:** Use stronger opponents (heuristic-ev, creator-balanced-default, possibly self-play). Keep maybe 1 weak engine as a sanity check — if the tuned engine "gets confused" against weak opponents, that's a problem (a weird one, but still). Prior sweep pool had heuristic-ev, mcts-fast, mcts-d3, flat-mc but some were too easily beatable (>80% WR regardless of params). Self-play with default params is also hard to beat (both think alike → similar results).

**Luck analyzer scope:** Live + post-game. Performance won't be an issue (quick computation). Show in main game UI and in game replay. Not clear yet if engines themselves would benefit from luck awareness.

**Luck-adjusted WR:** User is eager to try this but wants cautious, data-driven validation. Even good WR runs could have been lucky — avoid bias in both directions. Standardize the approach so it's fact-based, not feel-based. If well-defined enough → automate it.

**Feedback loop:** User wants automation IF quality can be guaranteed. "Quality and Results first, Automatability second." Still wants to be able to inspect how the system decided (bug/error finding). Web research requested: do luck analyzers for board/card games exist? How do open/closed-loop game analysis systems work?

### #49 Clarifications

**Player-vs-AI priority:** Both AI-opponent automation AND good UI matter. Core needs AI to correctly auto-play moves. UI needs to be enjoyable for spending time playing.

**Notes box:** Not a priority feature. Tagged per-turn, but more of an accessory — "hey, these are my thoughts right now." Could be used to later ask AI "explain why the engine did that" for specific turns. Not a centerpiece.

**AI evaluation of notes:** Limited expected value. Main use case: weird engine behaviors → ask AI to explain the reasoning → check if it's a genuine strategy weakness or makes sense. In-game decisions themselves carry more info than written notes.

**Multi-player deferral:** Confirmed. Scaling roadmap: (1) Perfect 2P engines + insights → (2) 3/4P adjustments, retraining, UI, narrator, new strategy insights → (3) Expansion card support.

## TODO #50: NarrativeExplainer Class

**Added as new TODO based on discussion.** Separate class (not engine-level) that takes `EngineResult` + `GameState` and produces natural-language teacher-style explanations.

**Key insight:** The `summarySentence` field already exists in `EngineResult.Option` but is ALWAYS null across all engines. `structuredFactors` exist on CreatorEngine but are math-terse. The data is there — it just needs a prose layer on top.

**Architecture:** New class in `iface/` or `server/` layer (imports engine results, doesn't pollute engine logic). Takes the structured factors + game context and weaves them into conversational text. See roleplay example above for target tone.

**User confirmed:** "it is the engines job to crunch numbers and not yap some whimsical teacher stuff. that job can and should be handed to a different class."

## Research: Luck Analyzers in Games (April 8 2026)

Web research results on existing luck/skill separation systems. User requested this to inform #48.

### The Backgammon Model (GNU Backgammon / eXtreme Gammon) — DIRECT FIT

The gold standard. Exactly what we need, adapted for dice:

**Per-Roll Luck:**
```
Luck_this_roll = WinRate_after(actual_roll) - E[WinRate_after(all_rolls)]
```
- Before each roll, enumerate all outcomes (6 for 1d6, 11+doubles for 2d6)
- For each outcome, compute win rate after income resolution
- Luck = how much better/worse the actual roll was vs the average

**Per-Decision Skill Loss (like chess centipawn loss):**
```
Skill_loss = WinRate(best_option) - WinRate(chosen_option)
```
- At each buy decision, compare chosen vs best
- Perfect play = 0 skill loss everywhere

**Game-Level:**
- `Total_Luck = sum(per_roll_luck)` per player
- `Luck-Adjusted Result = Actual Result - Your Luck + Opponent's Luck`
- Strips out dice fortune, isolates decision quality

**GNU Backgammon thresholds:** Very Lucky > +0.6, Lucky > +0.3, Neutral, Unlucky < -0.3, Very Unlucky < -0.6 (equity units). We'd calibrate for Machi Koro win-rate units (maybe +/- 5% per roll).

### Poker: All-In EV
- `EV_share = pot * win_probability` at the all-in moment
- `Luck = Actual - EV_share`
- Over many hands, EV-adjusted line converges to "true skill"
- Simpler than backgammon (terminal-only), but the concept maps

### Chess: Lichess/Chess.com Accuracy
- Convert engine eval (centipawns) → win% via sigmoid: `Win% = 50 + 50 * (2/(1+exp(-0.00368208*cp)) - 1)`
- Per-move accuracy: `Accuracy% = 103.1668 * exp(-0.04354 * (winBefore - winAfter)) - 3.1669`
- Key lesson: raw eval scores misleading — same error matters differently at different game stages. Win probability normalizes this. Our MCTS already outputs win rates.

### Machi Koro Adaptation Plan

**What maps cleanly:**
1. Dice rolls are the ONLY random element → per-roll luck is well-defined
2. MCTS already outputs win rates → per-decision skill loss is nearly free
3. `Calcs` already computes income distributions → can enumerate all roll outcomes
4. Doubles handling already in ChanceNode (invariant #4) → luck calc must respect this too

**Design decisions:**
- Use win rate (0-1), not raw scores (HeuristicEv excluded or needs calibration)
- Fast engine (low-iter MCTS or HeuristicEv) for per-roll eval is fine — bias cancels since we compare same engine vs itself
- Per-roll luck + per-decision skill loss + game-level aggregation = complete picture

**UX reference:** Lichess game review (move-by-move chart with annotations: brilliant/great/inaccuracy/mistake/blunder)
