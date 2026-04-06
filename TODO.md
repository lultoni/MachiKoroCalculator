# TODO.md

Aufgabenliste. Schreib Aufgaben hier rein, sag Claude "arbeite TODO.md ab".

## Anweiseung an Claude

Priorisiere in deiner Abarbeitung die wichtigsten Features. Erkenne Abhänigkeiten und lasse dies auch in deine Priorisierung mit einspielen.

## Open

| # | Task | Notes |
|---|------|-------|
| 5 | Expansion card support. | Out of scope until core is perfected. |
| 9 | Custom engine builder screen. | UI to compose engine configs (base class + params), save to registry with duplicate detection. |
| 11 | Discuss 5 heuristic choices in ARCHITECTURE.md Section 7.2 | Especially static landmark weights (#3) and binary endgame bonus (#2). Decide which to fix, which to keep as accepted approximations. |
| 21 | Creator Engine Brainstorm | The Creator of the Calc want to make his own engine that does what he thinks are the best decision. He want to Brainstorm the exact decisions that this engine should do, the way it works, the params it has and so on and so forth. |
| 23 | Engine performance analysis & optimization | Profile all engines to find performance bottlenecks. Some "fast" tier engines are too slow. Deep analysis of each engine class needed. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
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
