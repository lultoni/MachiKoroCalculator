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
| 30 | Creator Engine: Fix risk/coverage dimension baseline inflation | CreatorScorer's `riskTerm` and `coverageTerm` dimensions produce positive values from the **existing portfolio** regardless of the candidate card's merit. A zero-income card (e.g., Möbelfabrik with 0 production cards) gets a positive composite score because `(1-probNoIncome)` and `(1-rollCorr)/2` reflect portfolio quality, not the card's marginal contribution. Fix: compute risk/coverage as **deltas** (after−before adding the card) instead of absolute values, so a card that doesn't change the portfolio's risk/coverage profile scores 0 on those dimensions. This is the root cause of buying useless synergy cards (Möbelfabrik, Käsefabrik) and 7-12 cards (Apfelplantage) without prerequisites. |
| 31 | Creator Engine: Penalize 7-12 cards without Bahnhof in scorer | Cards activating on rolls 7-12 (Apfelplantage, Wald, Bergwerk, Möbelfabrik, Käsefabrik, Obstgarten) provide 0 own-turn income without Bahnhof. The Calcs layer correctly computes 0 EV, but CreatorScorer's risk/coverage dimensions still inflate their composite score (see #30). Independent of #30: add an explicit coverage-based penalty to the `incomeTerm` or a new guard that scales the composite score by the player's ability to actually activate the card (1d6 for rolls 1-6, needs Bahnhof for 7-12). Must NOT prevent the engine from building toward a 2d6 strategy — only penalize buying these cards BEFORE Bahnhof is owned. |
| 32 | Creator Engine: Fix early-game save-then-buy pattern | Engine saves on turn N when an affordable card exists, then buys that same card on turn N+2. Root cause: save scores 0.0 (fixed from old discount bug), but some affordable cards also score near 0 or slightly negative due to risk/coverage baseline inflation (#30). When MC validation runs, the save option is excluded from MC but card scores come back as MC win rates — if those MC rates are very close together (e.g., 0.48 vs 0.49), the top pick can flip between save and buy across turns. Fix depends on #30 (making dimension scores properly delta-based will spread card scores apart). Additionally consider: if the best affordable card has a positive heuristic score, save should be suppressed. |
| 33 | H2H Game Replay: Show engine decision details (the "why") | Save engine evaluation metrics/explanation factors alongside each purchase decision in H2H game logs. Display in match replay UI so users can understand why specific choices were made. Requires: (a) extend GameLog/TurnRecord to store EngineResult metrics map for the chosen option, (b) serialize in game JSON, (c) display in H2hGameReplay component (expandable section per turn showing top-3 factors, composite score breakdown, active gravity well). |
| 34 | H2H Game Replay: Show Bürohaus swap actions | Bürohaus swap decisions are not currently shown in match replay. The swap IS executed during H2H games (via BürohausLogic.executeSwap in rollouts and GameSession), but the result (which card was swapped for which) is not logged. Requires: (a) extend TurnRecord to store swap details (ownCard, opponentCard, opponentIndex), (b) log swap in GameSession.playTurn(), (c) display in H2hGameReplay as a sub-action within the turn. |

## Done

Moved here when completed. Full history in CHANGELOG.md.

| # | Task |
|---|------|
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
