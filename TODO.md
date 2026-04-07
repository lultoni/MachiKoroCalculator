# TODO.md

Aufgabenliste. Schreib Aufgaben hier rein, sag Claude "arbeite TODO.md ab".

## Anweiseung an Claude

Priorisiere in deiner Abarbeitung die wichtigsten Features. Erkenne Abhänigkeiten und lasse dies auch in deine Priorisierung mit einspielen.

## Open

| # | Task | Notes |
|---|------|-------|
| 5 | Expansion card support. | Out of scope until core is perfected. |
| 9 | Custom engine builder screen. | UI to compose engine configs (base class + params), save to registry with duplicate detection. |
| 26 | Add in 3 Player and 4 Player Testing Scenarios in H2H Testing | Are Elo-Calc adjustments needed for this? I would want seperate leaderboards maybe and then a overall leaderboard. |
| 27 | Creator Engine: Adaptive opponent modeling | Track actual opponent purchase patterns across turns to adjust rollout behavior and threat assessment. Requires stateful session tracking (interface layer, not engine-level). **Assessment (7.46):** Low priority — Machi Koro is fundamentally solitaire-optimization; the only opponent interactions are red/purple cards and supply scarcity, both already handled. The engine's threat gravity well already models opponent proximity to winning. Stateful session tracking would break the clean stateless-per-evaluation architecture for marginal gains. Shelved — parameter tuning (#28) will discover better threat-response weights automatically. |
| 28 | Creator Engine: Automated H2H weight sweep | ~~Script/tool that runs H2H tournaments varying Creator Engine parameter vectors (31 knobs), records win rates, and converges on optimal weights via grid search or Bayesian optimization.~~ **Done (7.47):** `h2h.SweepMain` — TPE-based parameter optimization over 20 CreatorScorer knobs. See README.md for usage. |
| 39 | From the Ground up reimagine how we can make the game core work so it is as efficient as possible. | So maybe this is something like using bitmaps or whatnot where every game position is just a single binary number which we can use to manipulate and check via bitwise operations to improve performance and shit |
| 40 | Creator Engine: Per-player-count tuned parameter sets | Run separate sweep runs for 2p, 3p, 4p games (once #26 adds multi-player H2H). Each player count has different dynamics: 2p favors aggressive red-card play, 3p/4p dilutes red income but increases blue/green value. Store tuned vectors as separate engine registry entries (`creator-tuned-2p`, `creator-tuned-3p`, `creator-tuned-4p`). CreatorEngine could auto-select based on `state.getPlayers().length`. |
| 41 | Creator Engine: Multi-opponent sweep | Extend SweepMain to optimize against a weighted combination of opponents (e.g., 50% heuristic-ev + 30% mcts-v1 + 20% flat-mc). Prevents overfitting to one opponent's weaknesses. Objective = weighted sum of win rates across opponents. Add `--opponents heuristic-ev-default:0.5,mcts-v1-fast:0.3,flat-mc-fast:0.2` CLI syntax. |
| 42 | Sweep progressive refinement | Two-phase sweep: Phase 1 = wide LHS exploration (100 trials), Phase 2 = local search around top-5 regions with shrinking step sizes (CMA-ES or coordinate descent). Currently TPE handles both phases, but explicit local refinement may extract more performance from promising regions. |
| 43 | Sweep visualization in UI | Add a Sweep Results tab to the H2H page. Visualize: (1) convergence plot (best WR over trials), (2) parameter importance (correlation of each param with WR), (3) parallel coordinates plot of top-10 vectors, (4) param distribution heatmaps for good vs bad trials. Data source: `data/sweep-results.json`. |
| 44 | Make Calculations run on GPU | This is for running large scale tests on my PC with a RTX 4070, which could more easily calculate large sums of matches and such. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
| 28 | Creator Engine: Automated H2H weight sweep — TPE-based parameter optimization via SweepMain. |
| 29 | Creator Engine: CreatorRollout v2 — data-driven rollout policy optimization via H2H benchmarking. |
| 35 | Creator Engine: Bürohaus swap bait bonus — incentivize cheap cards when Bürohaus is owned. |
| 37 | H2H: Stop button with live progress feedback — cancel running matches from UI. |
| 38 | All engines: tempo tiebreaker when options score identically (landmarks first, cost DESC). |
| 32 | Creator Engine: Fix early-game save-then-buy pattern — linear normalization replaces softmax. |
| 30 | Creator Engine: Fix risk/coverage dimension baseline inflation — delta-based scoring. |
| 31 | Creator Engine: Penalize 7-12 cards without Bahnhof + activation guard + opponent 2d6 check. |
| 33 | H2H Game Replay: Show engine decision details + fix non-MCTS affordable purchase bug. |
| 34 | H2H Game Replay: Show Bürohaus swap actions + greedy fallback for non-MCTS engines. |
| 36 | UI: Replace category type text with icons in project tooltips. |
| 35 | H2H Game Replay: Fix landmark name/abbreviation localization. |
| 21 | Creator Engine: custom strategy engine (seeded FlatMC + CreatorScorer + CreatorRollout). |
| 24 | All Engines get a "can win" check to prevent games from going on too long. |
| 25 | Check which Engines still don't have a rating and make them play the best one. |
| 23 | Engine performance analysis & optimization. |
| 17 | H2H UI: expose maxTurnsPerGame, seatSwap, configOverrides (CLI parity). |
| 18 | H2H engine param tooltips/dropdowns for known parameters. |
| 15 | Glicko-2 ratings view as separate tab in H2H UI with confidence badges. |
| 20 | H2H game replay i18n: localize Funkturm/Bürohaus labels. |
| 13 | Per-player coin deltas next to player names on dice selection (own + opponent turn). |
| 14 | Fix MCTS instant-win convergence bug (engines not buying winning landmarks). |
| 10 | Remove/Update Legacy Features/Code: dead runAdaptiveFocusedPhase(), misleading rolloutPolicy config. |
| 12 | Standardise card display across all UI components (CardTooltip, icons, color, locale). |
| 2 | Refine UI based on real gameplay usage (B24 + B25). |
| 7 | Project-Categorie-Icons in UI |
| 3 | Game-over decision review |
| 1 | Fix MCTS ChanceNode doubles bug |
| 6 | Die h2h-results.json pushen |
| 4 | Fix UI bugs B26-B28 (save counter, round label, settings groups). |
| 8 | Säubere UI-BUGS.md |
| 16 | H2H match overview layout rework. |
| 19 | H2H game replay redesign with player hands. |
| 22 | Auto Battle Mode with uncertainty-prioritized pair selection. |
| 11 | Heuristic review: ARCHITECTURE.md Section 7.2 fixes (#2-#5, A-F) |
