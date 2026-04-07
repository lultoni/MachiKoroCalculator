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
| 27 | Creator Engine: Adaptive opponent modeling | Track actual opponent purchase patterns across turns to adjust rollout behavior and threat assessment. Requires stateful session tracking (interface layer, not engine-level). |
| 28 | Creator Engine: Automated H2H weight sweep | Script/tool that runs H2H tournaments varying Creator Engine parameter vectors (31 knobs), records win rates, and converges on optimal weights via grid search or Bayesian optimization. |
| 29 | Creator Engine: CreatorRollout v2 | Full CreatorScorer heuristic in rollouts (once performance is validated). Add a "rollout-mode" fast-path in CreatorScorer that skips expensive metrics. Profile first to identify bottleneck dimensions. |
| 35 | Creator Engine soll, wenn es ein Bürohaus besitzt immer eine Low Value Cheap Karte besitzen, sodass es bei einem potentiellen Tausch gut benefittet. | Hier muss aber darauf geachtet werden, dass diese karte für den gegner nicht gut wäre und man selber von der getaschten karte profitiert. |
| 37 | H2H: Add stop button with live progress feedback | Currently running matches can't be stopped from the UI. Add a cancel button that stops the match mid-run and shows partial results (games completed so far). |
| 38 | Expectimax/MCTS tiebreaker when all options score 100%/0% | When all buy options score 1.0 (certain win) or 0.0 (certain loss), there's no differentiation. Add a tiebreaker based on tempo (turns-to-win estimate, cost efficiency) so the engine picks the fastest winning move / slowest losing move instead of arbitrary ordering. Observed in expectimax-d1-composite buying random cards instead of landmarks at 100%. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
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
