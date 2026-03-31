# ARCHIVE.md — Purged Code Concepts

This file indexes concepts and components removed during the restructure. The actual code is preserved in git history. Each entry includes a brief description, the rationale for removal, and a commit reference where the code last existed.

**Last pre-restructure commit:** `345b425`

---

## Strategy / Ranking Layer

### `RolloutTree` (Expectimax Rollout Tree)
**What it did:** Stufe-1 Expectimax tree search. `evaluate(gs, pi, depth, topK)` expanded purchase options to depth `d`, with opponent simulation via `boltzmannBuy(T=0.7)`. Handled Bahnhof (1d6 vs 2d6), Freizeitpark (doubles bonus), Funkturm (re-roll). Used `portfolioDeltaEV` for candidate pruning and `computeBaselineWinProb` for leaf evaluation.
**Why removed:** Replaced by proper MCTS with UCT selection/expansion/backpropagation, modeling all players as decision-makers in a unified tree.
**Last existed in:** `345b425` (`src/logic/probability/RolloutTree.java`)

### `WinProbabilityCalc` (Analytical Softmax Win Probability)
**What it did:** Stufe-2 leaf evaluation. `computeScores` calculated `portfolioEvPerRound x remainingTurns + LANDMARK_WEIGHTS + coinAdvantage + endgameProximityBonus`, then softmax for win probability. Calibrated LANDMARK_WEIGHTS (Bahnhof=24, EKZ=36, FZP=24, FT=48).
**Why removed:** Absorbed into Standard Calcs layer as utility functions. The softmax evaluation concept may be reused as a heuristic in depth-limited MCTS rollouts.
**Ideas worth preserving:** The coin-equivalent landmark weight calibration method (EV/round x ~12 turns) and the endgame proximity bonus (x2.5 when 3 landmarks + enough coins for 4th) produced good results.
**Last existed in:** `345b425` (`src/logic/probability/WinProbabilityCalc.java`)

### `adaptiveMCRefinement` (Adaptive MC Budget)
**What it did:** Stufe-3 MC validation. Top-5 candidates analytically prefiltered, then budget-split: equal when spread <= 0.02, chasers-only when leader > 0.05 ahead. 2500 sims per validated candidate. Overwrote Stufe-2 estimates.
**Why removed:** Replaced by MCTS iteration budgets. The adaptive budget concept (concentrating compute on close races) may inform MCTS exploration policies.
**Last existed in:** `345b425` (`src/logic/probability/ProbabilityCalc.java`, private method)

### `rankPurchasableProjects` / `rankAllProjects`
**What it did:** Sorted all affordable/all cards by ROI. Combined `unbuilt_projects` with unowned landmarks. Computed synergy notes, two-turn lookahead notes, bürohaus swap notes. Called `adaptiveMCRefinement` for top-k validation.
**Why removed:** Ranking is now produced by the simulation engine via the `SimulationEngine.evaluate()` interface.
**Ideas worth preserving:** The note annotation system (synergy + two-turn lookahead + bürohaus) provided valuable explanations. Should inform the new engine explanation output.
**Last existed in:** `345b425` (`src/logic/probability/ProbabilityCalc.java`)

### `RankEntry` / `RankingOptions`
**What they did:** Result POJO and configuration for the ranking system. `RankEntry` held EV, ROI, variance, probNoIncome, winProbDelta, portfolioDeltaEV, notes. `RankingOptions` held horizon, discount factor, MC config.
**Why removed:** Replaced by `EngineResult` and `EngineConfig` in the new engine interface.
**Last existed in:** `345b425` (`src/logic/probability/RankEntry.java`, `RankingOptions.java`)

### `GameSimulator` (Greedy Rollout Policy)
**What it did:** Stateless MC simulator. Greedy policy: landmarks first (cheapest), then highest `contextualEvPerRound/cost` establishment. Boltzmann temperature option. Supply tracking. Freizeitpark doubles handling.
**Why removed:** Replaced by MCTS rollout policy within simulation engines. The greedy heuristic and Boltzmann sampling may be reused in MCTS rollout phases.
**Ideas worth preserving:** The Bahnhof-gate heuristic (don't buy Bahnhof without high-range cards) and the `contextualCardEvPerRound` inline evaluation (12 `get_I` calls, allocation-free) were good performance patterns.
**Last existed in:** `345b425` (`src/logic/probability/GameSimulator.java`)

---

## UI Layer

### `AssistantConfig` / Phase Weights
**What it did:** Centralized strategy profile weights for 8 profiles across 3 game phases (early/mid/late). Interpolated weights based on continuous phase strengths. Position modifiers (catch-up, pull-ahead, coin advantage, diversity).
**Why removed:** Engine-computed explanations replace the rule-based assistant profiles.
**Ideas worth preserving:** The continuous phase blending (earlyStr/midStr/lateStr) and the position-modifier concept (adjusting weights based on game situation) could inform engine explanation generation.
**Last existed in:** `345b425` (`src/gui/newui/AssistantConfig.java`)

### `PhaseFitter` / `LabelingWindow`
**What they did:** OLS regression to calibrate phase detection thresholds from labeled game snapshots. `LabelingWindow` provided the labeling UI; `PhaseFitter` ran the regression.
**Why removed:** Phase detection is no longer needed as a separate system — MCTS evaluates positions directly.
**Last existed in:** `345b425` (`src/gui/newui/PhaseFitter.java`, `src/gui/newui/LabelingWindow.java`)

### `SnapshotGenerator`
**What it did:** Generated random game states by simulating games to a random turn count. Used for testing and labeling.
**Why removed:** Can be rebuilt for the head-to-head testing framework if needed.
**Last existed in:** `345b425` (`src/logic/probability/SnapshotGenerator.java`)

### Entire Swing UI (`gui.newui/*`)
**What it did:** Java Swing-based 3-column UI: left = turn tracker, center = card details, right = 5-tab ranking (Affordable/Not Affordable/All/Assistant/Rollout). Rich features: dice face panels, coin delta grid, income matrix, contextual tooltips, rank-aware coloring, category icons.
**Why removed:** Replaced by web SPA for cross-platform consistency, design flexibility, and cleaner interaction patterns. The core UI concept is simplified to 4 components (Turn Indicator, Dice, Coin Flow, Purchase Decision).
**Ideas worth preserving:** The live coin preview (hover-to-update), rank-relative cell coloring, and contextual metric tooltips were well-received UX patterns.
**Last existed in:** `345b425` (`src/gui/newui/`)

---

*This file is updated whenever code is purged during the restructure.*
