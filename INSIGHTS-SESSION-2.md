# Session Insights — Architecture Decisions (April 2026)

Strategic decisions from architecture review session. Technical details in ARCHITECTURE.md, tasks in TODO.md.

## Skill Loss Assessment

Per-decision skill loss (TODO #2) was analyzed and **deferred**. Key finding:

**The "beginner judging a grandmaster" problem:** Skill loss uses an oracle (MC greedy rollouts) to judge engine decisions. But our MC oracle is probably *weaker* than our best MCTS engines. It would reliably evaluate human play or weak policies, but give false "bad move" signals for strong engines that see deeper.

**Prerequisite for skill loss:** A significantly stronger oracle. Either:
- Much deeper MC simulations (impractical with current speed)
- A heuristic oracle that's fast enough to run thousands of evaluations (needs WinProbability improvement first)

**When it makes sense:** As a component inside post-game review for *human* games (humans play worse than any oracle). Not as a standalone engine evaluation tool — that's what Elo is for.

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
