# System Overview — Machi Koro Calculator

A non-technical map of what this project is, what it does, and where value comes from.
For technical details, see ARCHITECTURE.md. For vision and design principles, see NORTH-STAR.md.

---

## 1. The Product

A local desktop tool that helps you play Machi Koro better.

You sit down to play a physical game of Machi Koro with friends. You open this tool in a browser. Each turn, you enter what happened (dice roll, purchases). The tool tracks everything and tells you: **"Buy this card, and here's why."**

It's an advisor, not an autopilot. You always decide what to buy. The tool just makes sure you have the best possible information before you choose.

---

## 2. What You Can Do Today

### During a game (the core loop)
- **Track your game** — Enter rolls, purchases, and special actions (Bürohaus swaps, Funkturm rerolls) for all players. The tool maintains the complete game state.
- **Get purchase recommendations** — On your turn, the Kauf Assistent shows what to buy, ranked by win probability. Each option shows a score, metrics, and explanation factors you can expand for detail.
- **Choose your depth** — Fast (~50ms), Balanced (~350ms), or Deep (~3s). Deeper = more simulation = more accurate.
- **See coin flow** — A three-column display (Now → Roll → Buy) shows how your coins change through each turn phase, updating live as you hover over cards.
- **Track opponents efficiently** — When it's not your turn, you just enter their roll and purchase. The tool uses this time to pre-compute your next recommendation.
- **Save/load games** — Persist to files, resume later. Undo last turn if you made a mistake.

### Engine R&D (for improving the advisor)
- **Head-to-Head battles** — Pit any two engines against each other in 50-200 simulated games. See who wins, by how much, and why.
- **Game replay** — Step through any H2H game turn by turn. See each engine's reasoning, coin deltas, and purchases.
- **Glicko-2 ratings** — A leaderboard ranking all engines by skill, with confidence intervals. Like chess Elo, but for Machi Koro engines.
- **Auto-Battle** — Continuously runs matches, always picking the pairing with the most uncertainty. Self-balancing: runs until all engines have confident ratings.
- **Custom engines** — Build your own engine configuration through the UI. Tweak parameters, save it, test it against others.
- **Sweep optimization** — Automated parameter tuning via Bayesian optimization (TPE). Feed it a parameter space, it finds the best combination by running hundreds of H2H matches.

### Settings and polish
- **10 engine classes, 35 configurations** — From instant heuristic formulas to deep MCTS tree search.
- **German and English** — All cards, UI, and explanations in both languages.

---

## 3. How the Parts Connect

```
┌─────────────────────────────────────────────────────────┐
│  What the USER sees                                     │
│                                                         │
│  Web UI (React)                                         │
│  ├── Game screen: track game, get recommendations       │
│  └── H2H screen: battles, ratings, replay, sweep        │
│                                                         │
│         ↕ HTTP (localhost:8080)                         │
│                                                         │
│  Java Server (26 endpoints)                             │
│  ├── Session management (create/load/save/undo games)   │
│  ├── Engine evaluation (run engine, return ranked list) │
│  └── H2H orchestration (run matches, store results)     │
├─────────────────────────────────────────────────────────┤
│  What does the THINKING                                 │
│                                                         │
│  Interface layer                                        │
│  ├── Engine registry (which engines exist, configs)     │
│  ├── Orchestrator (route request → right engine)        │
│  └── Pre-computation cache                              │
│                                                         │
│  Simulation Engines (10 classes)                        │
│  ├── MCTS family (6 variants with different rollouts)   │
│  ├── Expectimax (deterministic minimax)                 │
│  ├── FlatMC (pure Monte Carlo sampling)                 │
│  ├── HeuristicEV (instant formula, no search)           │
│  └── Creator (tunable hybrid, sweep-optimized)          │
├─────────────────────────────────────────────────────────┤
│  What EVERYTHING builds on                              │
│                                                         │
│  Math library (calcs)                                   │
│  ├── EV, ROI, variance, synergy, risk metrics           │
│  ├── Win probability estimator (fast heuristic)         │
│  ├── Monte Carlo simulator (play out full games)        │
│  ├── Game state sampler (run games, analyze snapshots)  │
│  └── Luck analyzer (per-roll luck measurement)          │
│                                                         │
│  Core game rules                                        │
│  ├── Game state, players, cards, coins                  │
│  ├── Income resolution (which cards pay how much)       │
│  ├── 19 base-game cards with all rules                  │
│  └── Win condition, turn order, supply tracking         │
│                                                         │
│  H2H infrastructure                                     │
│  ├── Match runner (two engines play N games)            │
│  ├── Tournament runner (round-robin, ratings)           │
│  ├── Glicko-2 rating calculator                         │
│  ├── Auto-battle (uncertainty-prioritized pairing)      │
│  └── Sweep optimizer (Bayesian parameter tuning)        │
└─────────────────────────────────────────────────────────┘
```

### Component groups

| Group | Components | Who benefits |
|-------|-----------|-------------|
| **User-facing** | Web UI, game tracking, recommendations, save/load | The player using the tool during a real game |
| **Engine R&D** | H2H battles, replay, ratings, auto-battle, sweep, custom engines | The developer making engines better |
| **Foundation** | Core rules, math library, simulator, engines | Everything above depends on this |
| **Analysis** | Luck analyzer, win probability, game state sampler | Currently used by tests; future potential for game review or (semi-) automated engine improvement |

---

## 4. Value Map

### Core value: "What should I buy?"
**Components:** Game tracking + Engine evaluation + UI recommendation panel
**Maturity:** High. This works end-to-end today. You can track a real game and get accurate recommendations.
**Depends on:** Core rules + Math library + at least one engine

### Quality assurance: "Is the engine actually good?"
**Components:** H2H match runner + Glicko-2 ratings + Auto-battle
**Maturity:** High. The tournament system has run 60+ matches, the leaderboard is populated, game replay works.
**Depends on:** Engines + Core rules
**User need it serves:** Confidence that the recommendations are trustworthy. If the engine beats every other engine, its advice is probably good.

### Engine R&D: "Make the engine better"
**Components:** Sweep optimizer + Custom engine builder + H2H testing
**Maturity:** High. TPE-based sweep has tuned the Creator engine to 88% WR against benchmarks. Custom engines can be created through the UI.
**Depends on:** H2H infrastructure + Engines
**User need it serves:** Indirectly — better engines = better recommendations.

### Analysis foundation: "Understand the game"
**Components:** Luck analyzer + Win probability heuristic + GameStateSampler
**Maturity:** Medium. The infrastructure works and is tested, but it's not yet connected to any user-facing feature. Currently only used by automated tests.
**Depends on:** Core rules + Math library
**Potential:** Post-game review ("was I lucky or good?"), engine diagnostics, coaching feedback.

---

## 5. What's Missing

These are the gaps between where we are and where the vision (NORTH-STAR.md, INSIGHTS-SESSION.md) points:

### Gap 1: Playing against the engine
**Today:** You play a physical game with human opponents and use the tool as a sidekick advisor.
**Vision:** You can also play directly against the AI in the tool — the engine makes decisions for opponent(s), you make your own.
**Why it matters:** It's the path to learning. You can practice anytime, experiment with strategies, and see how a strong opponent responds. No physical board needed, no friends required.
**What's needed:** Engine auto-play for opponent turns (dice choice, Funkturm, Bürohaus, purchase), plus an immersive board-game-like UI.
**Status:** TODO #14 (backend), #15 (UI). No code yet.

### Gap 2: Explaining "why" in plain language
**Today:** The assistant shows ranked options with scores, metric breakdowns, and expandable factor bullets. It's accurate but reads like a spreadsheet.
**Vision:** The assistant explains like a teacher: "I'd go with Käsefabrik here. It synergizes with your two Bauernhöfe — that's +6 every time you hit a 7. The opponent has 3 landmarks, so we need to accelerate, not play safe."
**Why it matters:** Numbers tell you what. Language tells you why. The "why" is what teaches you to play better without the tool.
**What's needed:** A NarrativeExplainer class that takes engine results + game state and produces conversational prose. The engine data is already there — it just needs a translator.
**Status:** TODO #10. Design direction clear (see INSIGHTS-SESSION.md). No code yet.

### Gap 3: Post-game review and learning
**Today:** H2H game replay exists, but only for engine-vs-engine games. There's no review for your own games.
**Vision:** After a game (or during), review each decision: "Was this a good buy? What would have been better? How lucky/unlucky was I?"
**Why it matters:** This is how you improve. Chess players review games to find mistakes. Machi Koro players should be able to do the same.
**What's needed:** (Per-decision analysis (skill loss or similar)), luck breakdown per roll, narrative annotation. The analysis infrastructure (GameStateSampler, LuckAnalyzer) exists — it needs to be connected to real games and presented in the UI.
**Status:** Analysis foundation built (TODO #1 done). UI integration and user-game analysis not started.

### How the gaps relate
These three gaps form a natural progression:
1. **Play against AI** → gives you games to analyze
2. **Narrative explanation** → helps you understand each recommendation during play
3. **Post-game review** → helps you learn from what happened

All three need a strong engine foundation (which we have) and benefit from the analysis tools (which we've started building).

---

## 6. Open Questions

### Should we build per-decision skill loss (TODO #2)?
**Assessment:** Not as a standalone feature. The skill loss formula (`WR(best) - WR(chosen)`) is only as reliable as the oracle computing WR. Our Monte Carlo oracle (greedy rollouts) is probably weaker than our best MCTS engines — it would be a beginner judging a grandmaster. Useful for evaluating human play or weak policies, unreliable for evaluating strong engines.
**Recommendation:** Defer. If/when we build post-game review for human games, skill loss makes sense as a component (humans play worse than the oracle). For engine evaluation, Elo is more practical.

### What's the highest-impact next feature?
Depends on the goal:
- **"Make the tool more useful during real games"** → Narrative explanation (#10). Immediate improvement to the core loop without requiring new infrastructure.
- **"Enable a new way to use the tool"** → Player-vs-AI (#14). Opens up practice play, which is a fundamentally new use case.
- **"Make the engine stronger"** → Sweep against stronger opponents (#9). The Creator engine is tuned against weak opponents; it needs calibration against strong ones.

### Is the analysis foundation (luck, sampler, win probability) pulling its weight?
Not yet — it's tested but not connected to anything the user sees. It has clear potential (post-game review, coaching triggers, engine diagnostics) but needs a user-facing outlet to deliver value. Building more analysis tools without connecting them to features risks accumulating infrastructure that nobody uses.

---

## 7. Summary

**What works well today:**
- Core game tracking and recommendations (the original purpose)
- Engine ecosystem with 10 engine classes and comprehensive testing
- H2H infrastructure with ratings, replay, and automated tuning
- Solid mathematical and simulation foundation

**What's immature or missing:**
- No way to play against the AI (advisor-only)
- Explanations are numerical, not conversational
- Analysis tools exist but aren't user-facing yet
- Post-game learning/review doesn't exist for human games

**The core mission hasn't changed:**
> "What should I buy right now, and why?"

Everything built so far serves this question. The next step is making the answer more accessible (narrative explanation), expanding how you can use the tool (player-vs-AI), and helping you learn from your games (post-game review).
