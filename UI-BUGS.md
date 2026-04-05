# UI Bug Tracker

## Legend
- **Status**: `OPEN` | `DESIGN_QUESTION`
- **Layer**: `FE` | `BE` | `FE+BE` | `DESIGN`
- **Priority**: `P0` (blocks other fixes) | `P1` (high impact) | `P2` (medium) | `P3` (low/cosmetic)

---

## Open Bugs

(none)

---

## Resolved Bugs

All resolved bugs are documented in `CHANGELOG.md` under version 7.22 (UI Bug Sweep).

| Bug | Summary | Resolution |
|-----|---------|------------|
| B01 | Supply panel wrong counts | CANNOT REPRODUCE — logic verified correct |
| B05 | Coin buy preview jitter | Already fixed (h-4 placeholders) |
| B06 | Recommendation hover preview | Already fixed (mouse handlers present) |
| B07 | No opponent coin flow | Fixed — opponent delta grid added |
| B08 | Factor bar alignment | Fixed — min-w-16 + percentage labels |
| B09 | Duplicate "Win" factor | Fixed — folded into factor #1 |
| B11 | Column legend | Deferred — title tooltips suffice |
| B12 | Misleading "See all" label | Fixed — shows all including unaffordable |
| B13 | Missing color gradients | Fixed — client-side ranges for all metrics |
| B14 | Manual buy unsorted | Fixed — sorted by engine ranking |
| B15 | ETW unclear/wrong sort | Fixed — ascending sort + tooltips |
| B16 | Insights not updating | Already fixed (turnCount dependency) |
| B17 | Opponent buy pre-roll coins | Already fixed (opponentCoinsAfterRoll) |
| B18 | No round counter | Fixed — round counter in TurnIndicator |
| B19 | New Game button styling | Fixed — vertical divider added |
| B19b | Purples/yellows missing | Fixed — heuristic scoring for unaffordable cards |
| B20 | Factor detail enrichment | Deferred — numeric breakdowns already present |
| B21 | Undo no context | Fixed — tooltip with last turn info |
| B23 | Autosave not working | Fixed — useEffect on effectiveTurnCount |
| B24 | H2H single iterations field | Fixed — per-engine inline config editors with all params |
| B25 | Engines unsorted by performance | Fixed — Glicko-2 ratings from H2H results, sorted in Settings |
| B26 | Save counter shows 0 until clicked | Fixed — useEffect fetches saves on mount |
| B27 | Round counter label not visible | Fixed — distinct badge styling for round counter |
| B28 | Settings engine list crowded/unsorted | Fixed — collapsible groups with class/tier/rating grouping modes |
