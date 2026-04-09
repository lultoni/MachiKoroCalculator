# TODO.md

Aufgabenliste. Schreib Aufgaben hier rein, sag Claude "arbeite TODO.md ab".

## Anweisung an Claude

Priorisiere in deiner Abarbeitung die wichtigsten Features. Erkenne Abhängigkeiten und lasse dies auch in deine Priorisierung mit einspielen. User Tasks sind brainstorm/discussion Tasks — hier soll Claude unterstützen, nicht eigenständig implementieren.

## Open

### Analysis & Insights

| # | Task | Type | Notes |
|---|------|------|-------|
| 2 | Per-decision skill loss | Dev | Chess centipawn-loss analog: `Skill_loss = WinRate(best_option) - WinRate(chosen_option)`. At each buy decision, compare engine's top choice vs actual choice. Perfect play = 0. Depends on #1 (shared eval infrastructure). |
| 3 | Game-level luck aggregation | Dev | `Total_Luck = sum(per_roll_luck)` per player. `Luck-Adjusted Result = Actual - Own_Luck + Opponent_Luck`. Calibrate thresholds for Machi Koro (maybe +/- 5% WR per roll = lucky/unlucky). Depends on #1. |
| 4 | Luck display in game replay UI | Dev | Show per-roll luck value next to each roll result in H2H game replay. Color-code (green = lucky, red = unlucky). Show game-level luck summary at top. Depends on #1, #3. |
| 5 | Luck display in live game UI | Dev | Show luck info in main game session (AssistantPanel or similar). Lower priority than replay. Depends on #1. |
| 6 | Luck-adjusted WR for sweep optimization | Dev | Feed luck-adjusted results into SweepMain's WR calculation. Goal: optimize engine params against "true skill" rather than raw outcomes. Cautious approach — validate that it produces stronger engines before adopting. Even good WR runs could be lucky. Depends on #1, #3. |
| 7 | Card-value-throughout-game analyzer | Dev | Track how the value/contribution of each owned card changes over the course of a game. "Was this card worth buying?" Useful for post-game analysis and engine evaluation. Concept from user — scope TBD. |
| 8 | Engine decision comparison tool | User | Replay the same game with different engines and compare their decisions. Identify where strategies diverge and whether differences led to better/worse outcomes. Needs standardized analysis tools (#1, #2, #7). |
| 23 | WinProbability heuristic improvement | Dev | Reduce MAE from ~0.25 to <0.10. Run real-scenario testing to identify high-error situations (compare heuristic vs deep MC). Catalog failure cases as regression tests. Iterate on the formula using those test cases. Enables cheap real-time luck computation and faster analysis everywhere the heuristic is used. |
| 24 | Luck-adjusted ratings everywhere | Dev | Switch LuckAnalyzer to use WinProbability heuristic (instant instead of MC). Wire luck into H2H match runner (raw + luck-adjusted WR per match). Update Glicko-2 to rate on luck-adjusted outcomes. Show luck-adjusted WR wherever raw WR is displayed. Depends on #23 for reliable heuristic. |

### Engine Quality

| # | Task | Type | Notes |
|---|------|------|-------|
| 9 | Sweep against stronger opponents | User | Re-run Creator sweep with strong opponent pool: heuristic-ev-balanced, creator-balanced (default params), maybe self-play. Keep 1 weak engine as sanity check. Current tuned engine doesn't dominate these. Goal: find params that beat everyone, not just weak engines. |
| 10 | NarrativeExplainer class | Dev | New class in `iface/` that takes `EngineResult` + `GameState` → natural-language teacher-style explanation. Populates the existing `summarySentence` field (always null today). Conversational tone, explains synergy/risk/tempo/opponent threats as flowing prose. See INSIGHTS-SESSION.md for tone reference and roleplay example. |
| 11 | Machi Koro strategy brainstorm | User | Fundamental thinking: what makes a position good/bad? How to find the best move? Play lots of 2P games, build intuition, document patterns. Foundation for a potential creator-engine-v2. |
| 12 | Creator Engine: Adaptive opponent modeling | Dev (shelved) | Track opponent purchase patterns to adjust rollout/threat assessment. Assessment: low priority — game is fundamentally solitaire-optimization. Threat gravity well already models opponent proximity. Shelved. |
| 13 | Creator Engine: Per-player-count tuned params | Dev | Separate sweep runs for 2p, 3p, 4p. Store as `creator-tuned-2p/3p/4p` registry entries. Auto-select by player count. Blocked by #19. |

### Player vs AI

| # | Task | Type | Notes |
|---|------|------|-------|
| 14 | Player-vs-AI: engine auto-play backend | Dev | Engine automatically plays opponent turns in a game session. Core needs to correctly handle AI making dice choice, Funkturm, Bürohaus, and buy decisions. Existing GameSession + engine evaluate infrastructure is close — needs turn automation loop. |
| 15 | Player-vs-AI: immersive board-game UI | Dev | Redesign the game session screen to look like the physical Machi Koro board. Cards laid out visually, opponent's tableau visible, market in center. Priority alongside #14 — both matter for the experience. |
| 16 | Player-vs-AI: per-turn notes box | Dev | Simple textarea, tagged per turn, persists with game save. Accessory feature, not centerpiece. Export to JSON for later review. Lower priority than #14/#15. |

### Engine Infrastructure

| # | Task | Type | Notes |
|---|------|------|-------|
| 17 | Time budget mode for all engines | User | Brainstorm: engines work with time budget instead of iterations. Unifies fast/balanced/deep into a single entry with configurable time. UI settings switch. H2H + sweep adjustments needed. Retain iteration mode as option. Discuss effects and feasibility first. |
| 18 | Sweep progressive refinement | Dev | Two-phase: Phase 1 = wide LHS exploration (100 trials), Phase 2 = local search around top-5 regions (CMA-ES or coordinate descent). TPE handles both currently, but explicit local refinement may extract more. |

### Core & Scaling

| # | Task | Type | Notes |
|---|------|------|-------|
| 19 | 3/4 player H2H testing | Dev | Multi-player match runner + tournament support. Glicko-2 adjustments for >2 players. Separate per-player-count leaderboards + overall leaderboard. |
| 20 | Bitwise game core — Phase 3 MCTS rollouts | Dev | Phase 1 done: BitState + BitStateTranslator, conversion, income resolution. Phase 2 done: GameSimulator uses BitState internally. Phase 3 done: All MCTS rollouts (BitMctsRollout, BitGreedyRollout, BitBoltzmannRollout, BitRolloutEvCache) use BitState. All engines wired. Phases 4-6 (tree nodes, analysis, interface boundary) remain. |
| 21 | GPU-accelerated match simulations | Dev | Run large-scale simulations on GPU (RTX 4070). Likely depends on #20 (bitwise core). |
| 22 | Expansion card support | Dev | Out of scope until base game is perfected. Scaling roadmap: (1) perfect 2P → (2) 3/4P → (3) expansions. |

## Scaling Roadmap

Confirmed direction: **(1)** Perfect 2P engines + insights → **(2)** 3/4P adjustments, retraining, UI, narrator → **(3)** Expansion support.

## Done

Moved here when completed. Full history in CHANGELOG.md.

| Old # | Task |
|-------|------|
| 1 | Per-roll luck computation (LuckAnalyzer + GameStateSampler) |
| 1 | Fix MCTS ChanceNode doubles bug |
| 2 | Refine UI based on real gameplay usage (B24 + B25). |
| 3 | Game-over decision review |
| 4 | Fix UI bugs B26-B28 (save counter, round label, settings groups). |
| 6 | Die h2h-results.json pushen |
| 7 | Project-Categorie-Icons in UI |
| 8 | Säubere UI-BUGS.md |
| 9 | Custom engine builder screen |
| 10 | Remove/Update Legacy Features/Code: dead runAdaptiveFocusedPhase(), misleading rolloutPolicy config. |
| 11 | Heuristic review: ARCHITECTURE.md Section 7.2 fixes (#2-#5, A-F) |
| 12 | Standardise card display across all UI components (CardTooltip, icons, color, locale). |
| 13 | Per-player coin deltas next to player names on dice selection (own + opponent turn). |
| 14 | Fix MCTS instant-win convergence bug (engines not buying winning landmarks). |
| 15 | Glicko-2 ratings view as separate tab in H2H UI with confidence badges. |
| 16 | H2H match overview layout rework. |
| 17 | H2H UI: expose maxTurnsPerGame, seatSwap, configOverrides (CLI parity). |
| 18 | H2H engine param tooltips/dropdowns for known parameters. |
| 19 | H2H game replay redesign with player hands. |
| 20 | H2H game replay i18n: localize Funkturm/Bürohaus labels. |
| 21 | Creator Engine: custom strategy engine (seeded FlatMC + CreatorScorer + CreatorRollout). |
| 22 | Auto Battle Mode with uncertainty-prioritized pair selection. |
| 23 | Engine performance analysis & optimization. |
| 24 | All Engines get a "can win" check to prevent games from going on too long. |
| 25 | Check which Engines still don't have a rating and make them play the best one. |
| 28 | Creator Engine: Automated H2H weight sweep — TPE-based parameter optimization via SweepMain. |
| 29 | Creator Engine: CreatorRollout v2 — data-driven rollout policy optimization via H2H benchmarking. |
| 30 | Creator Engine: Fix risk/coverage dimension baseline inflation — delta-based scoring. |
| 31 | Creator Engine: Penalize 7-12 cards without Bahnhof + activation guard + opponent 2d6 check. |
| 32 | Creator Engine: Fix early-game save-then-buy pattern — linear normalization replaces softmax. |
| 33 | H2H Game Replay: Show engine decision details + fix non-MCTS affordable purchase bug. |
| 34 | H2H Game Replay: Show Bürohaus swap actions + greedy fallback for non-MCTS engines. |
| 35 | Creator Engine: Bürohaus swap bait bonus — incentivize cheap cards when Bürohaus is owned. |
| 36 | UI: Replace category type text with icons in project tooltips. |
| 37 | H2H: Stop button with live progress feedback — cancel running matches from UI. |
| 38 | All engines: tempo tiebreaker when options score identically (landmarks first, cost DESC). |
| 41 | Creator Engine: Multi-opponent sweep — `--opponents` CLI flag, averaged WR across all opponents per trial. |
| 43 | Sweep visualization in UI — Recharts-based Sweep Results tab with convergence, importance, parallel coords, param ranges. |
| 46 | Adjust SweepMain starting params from sweep analysis — seeded from best old-run results. |
| 47 | Extract engine parameter definitions into shared JSON resource. |
