# Session Insights — Vision & Design References

Reference document for project direction and design principles. Technical details in ARCHITECTURE.md, tasks in TODO.md.

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

## Design Clarifications (from Q&A)

**Sweep opponent pool:** Use stronger opponents (heuristic-ev, creator-balanced-default, possibly self-play). Keep 1 weak engine as sanity check.

**Player-vs-AI:** Both AI auto-play backend AND good UI matter equally. Notes box is an accessory, not centerpiece — tagged per-turn, exportable. Could be used to ask AI "explain why the engine did X" for specific turns.

**Engine comparison idea:** Give multiple engines the exact same game → compare decisions → analyze which were better. Needs standardized analysis tools (luck analyzer, card-value tracker).

**Scaling roadmap:** (1) Perfect 2P → (2) 3/4P adjustments → (3) Expansions.
