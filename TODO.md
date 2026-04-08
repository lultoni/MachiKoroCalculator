# TODO.md

Aufgabenliste. Schreib Aufgaben hier rein, sag Claude "arbeite TODO.md ab".

## Anweiseung an Claude

Priorisiere in deiner Abarbeitung die wichtigsten Features. Erkenne Abhänigkeiten und lasse dies auch in deine Priorisierung mit einspielen.

## Open

### Gameplay & Core

| # | Task | Notes |
|---|------|-------|
| 5 | Expansion card support | Out of scope until core is perfected. |
| 26 | Add in 3 Player and 4 Player Testing Scenarios in H2H Testing | Are Elo-Calc adjustments needed for this? I would want separate leaderboards maybe and then an overall leaderboard. |
| 39 | From the ground up reimagine how we can make the game core work so it is as efficient as possible | So maybe this is something like using bitmaps or whatnot where every game position is just a single binary number which we can use to manipulate and check via bitwise operations to improve performance. |

### Engines

| # | Task | Notes |
|---|------|-------|
| 27 | Creator Engine: Adaptive opponent modeling | Track actual opponent purchase patterns across turns to adjust rollout behavior and threat assessment. Requires stateful session tracking (interface layer, not engine-level). **Assessment (7.46):** Low priority — Machi Koro is fundamentally solitaire-optimization; the only opponent interactions are red/purple cards and supply scarcity, both already handled. The engine's threat gravity well already models opponent proximity to winning. Stateful session tracking would break the clean stateless-per-evaluation architecture for marginal gains. Shelved — parameter tuning (#28) will discover better threat-response weights automatically. |
| 40 | Creator Engine: Per-player-count tuned parameter sets | Run separate sweep runs for 2p, 3p, 4p games (once #26 adds multi-player H2H). Each player count has different dynamics: 2p favors aggressive red-card play, 3p/4p dilutes red income but increases blue/green value. Store tuned vectors as separate engine registry entries (`creator-tuned-2p`, `creator-tuned-3p`, `creator-tuned-4p`). CreatorEngine could auto-select based on `state.getPlayers().length`. |
| 45 | Make all Engines capable of working with time budget instead of iteration budget | This is for optimizing the engines for the real life games where we have differing times for opponents turns that we can use instead of just being "idle about it". It also in a way unifies engine entries, as we don't need duplicate engine entries just for fast/balanced/deep, as now we just give each label a set time, add a field in the settings that allows the user to switch between the three and a fourth, normal game only setting of "Use All Time" or something. The H2H testing UI could then be changed to give both engines a unified "time to think" (also then needs to be adjusted for inside the sweep testing). For engines that don't use iterations or depth they just get time until they are done — this then has to be set in the entries as well, what they require in this spectrum. I still would want to retain the ability to use iterations. Brainstorm with me about the effects and if this makes sense and what all would be changed and how this would work in practice. |

### Sweep & Optimization

| # | Task | Notes |
|---|------|-------|
| 42 | Sweep progressive refinement | Two-phase: Phase 1 = wide LHS exploration (100 trials), Phase 2 = local search around top-5 regions (CMA-ES or coordinate descent). TPE currently handles both, but explicit local refinement may extract more. |

### Infrastructure

| # | Task | Notes |
|---|------|-------|
| 44 | GPU-accelerated calculations | Run large-scale match simulations on GPU (RTX 4070). |
| 47 | Extract engine parameter definitions into shared JSON resource | Single source of truth for param schemas (min/max/default/type/description per engine class). Currently duplicated in: (1) `web/src/components/engineParamSchema.ts` — hardcoded TS param defs for Engine Builder UI, (2) `src/h2h/SweepMain.java` lines 114–154 — hardcoded `PARAMS` list with min/max/default for Creator sweep, (3) individual engine `.java` files — implicit `config.extra.getOrDefault(...)` calls define which params exist. **Target:** Create `src/resources/jsons/engine-params.json` with structure like `{"mcts-v1": [{"key":"explorationConstant","type":"number","min":0.1,"max":5.0,"default":"1.4142","description":"UCT exploration constant"},...]}`. **Affected code:** (a) `EngineRegistry.java` or new `EngineParamRegistry.java` — load and serve the JSON, (b) new `GET /api/engine-params` endpoint or embed in `GET /api/engines` response, (c) `SweepMain.java` — read param ranges from JSON instead of hardcoding `PARAMS`, (d) `engineParamSchema.ts` — delete hardcoded defs, fetch from API instead, (e) `H2hEngineBuilder.tsx` — load schema from API on mount. This also enables future features like auto-generating sweep configs from the UI. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
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
