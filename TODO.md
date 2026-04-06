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
| 13 | Per-player coin deltas next to player names on dice selection | When a die face is selected (own or opponent turn), show +x (green) / -x (red) coin changes next to each player's coin count in the left panel. |
| 14 | Check if Engines work correctly | When I ran a H2H Match of 100 games between mcts-v1-fast and mcts-v1-depth3 they both in the end had above 100 coins each but did not buy any landmarks (time timed out at 200 turns) - this should not happen inside engines? why did both engines not see themselves winning by just buying their remaining landmarks? (Game#3 of mcts-v1-fast vs mcts-v1-depth3 on 2026-04-06, Time: 110.8s) |
| 15 | Glicko-2 ratings view in H2H UI | Show computed Glicko-2 ratings (from /api/h2h/ratings) in a dedicated panel or tab in the H2H overview screen. |
| 16 | H2H match overview layout rework | Rearrange columns: [avg eval engine 1][win rate engine 1][avg turns][win rate engine 2][avg eval engine 2]. |
| 17 | H2H UI: more config options | Expose maxTurnsPerGame, seatSwap, and per-engine configOverrides in the H2H UI (CLI parity). |
| 18 | H2H engine param tooltips/dropdowns | For engine config text fields, add tooltips or dropdowns showing allowed/expected values. |
| 19 | H2H game replay redesign with player hands | Show per-player card inventory (like left panel in game screen) while stepping through H2H game replay turns. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
| 10 | Remove/Update Legacy Features/Code: dead runAdaptiveFocusedPhase(), misleading rolloutPolicy config. |
| 12 | Standardise card display across all UI components (CardTooltip, icons, color, locale). |
| 2 | Refine UI based on real gameplay usage (B24 + B25). |
| 7 | Project-Categorie-Icons in UI |
| 3 | Game-over decision review |
| 1 | Fix MCTS ChanceNode doubles bug |
| 6 | Die h2h-results.json pushen |
| 4 | Fix UI bugs B26-B28 (save counter, round label, settings groups). |
| 8 | Säubere UI-BUGS.md |
