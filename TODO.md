# TODO.md

Aufgabenliste. Schreib Aufgaben hier rein, sag Claude "arbeite TODO.md ab".

## Anweiseung an Claude

Priorisiere in deiner Abarbeitung die wichtigsten Features. Erkenne Abhänigkeiten und lasse dies auch in deine Priorisierung mit einspielen.

## Open

| # | Task | Notes |
|---|------|-------|
| 5 | Expansion card support. | Out of scope until core is perfected. |
| 9 | Custom engine builder screen. | UI to compose engine configs (base class + params), save to registry with duplicate detection. |
| 10 | Remove/Update Legacy Features/Code | This is because I want the code to be as minimal and clean as possible not be stuck on old/wrong ideas |
| 11 | Discuss 5 heuristic choices in ARCHITECTURE.md Section 7.2 | Especially static landmark weights (#3) and binary endgame bonus (#2). Decide which to fix, which to keep as accepted approximations. |
| 12 | Standardise card display across all UI components | Audit every place a card name is shown — ensure consistent use of CardTooltip, category PNG icons, and locale-aware names. Eliminate any remaining bare text card references. |
| 13 | Per-player coin deltas next to player names on dice selection | When a die face is selected (own or opponent turn), show +x (green) / -x (red) coin changes next to each player's coin count in the left panel. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
| 2 | Refine UI based on real gameplay usage (B24 + B25). |
| 7 | Project-Categorie-Icons in UI |
| 3 | Game-over decision review |
| 1 | Fix MCTS ChanceNode doubles bug |
| 6 | Die h2h-results.json pushen |
| 4 | Fix UI bugs B26-B28 (save counter, round label, settings groups). |
| 8 | Säubere UI-BUGS.md |
