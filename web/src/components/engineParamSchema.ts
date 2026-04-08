/**
 * Hard-coded parameter definitions per engine class.
 *
 * Each engine class has a list of configurable parameters with type info,
 * min/max ranges, defaults, and descriptions. Used by the Engine Builder UI
 * to render appropriate input controls and validate user input.
 *
 * Ranges for Creator params are sourced from SweepMain.PARAMS (h2h/SweepMain.java).
 */

export interface ParamDef {
  key: string;
  description: string;
  type: 'number' | 'select';
  min?: number;
  max?: number;
  step?: number;
  default?: string;
  options?: string[];
  category?: string;
}

/** Standard params shared by all simulation-based engines. */
const STANDARD_PARAMS: ParamDef[] = [
  { key: 'iterations', description: 'Simulation iterations (0 = use time budget)', type: 'number', min: 0, max: 100000, step: 100, default: '500', category: 'Standard' },
  { key: 'timeBudgetMs', description: 'Time budget in ms (0 = no limit)', type: 'number', min: 0, max: 60000, step: 100, default: '0', category: 'Standard' },
  { key: 'riskToleranceWeight', description: 'Risk tolerance weight [0.0-1.0]', type: 'number', min: 0, max: 1.0, step: 0.05, default: '0', category: 'Standard' },
];

/** MCTS-specific extra params (shared by all MCTS variants). */
const MCTS_EXTRA: ParamDef[] = [
  { key: 'explorationConstant', description: 'UCT exploration constant (sqrt(2) = 1.414)', type: 'number', min: 0.1, max: 5.0, step: 0.1, default: '1.4142', category: 'MCTS' },
];

/** Full param schema per engine class. */
export const ENGINE_PARAMS: Record<string, ParamDef[]> = {
  'mcts-v1': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
  ],

  'mcts-v1-greedy-rollout': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
  ],

  'mcts-v1-boltzmann-rollout': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
    { key: 'rolloutTemperature', description: 'Boltzmann rollout temperature', type: 'number', min: 0.01, max: 10.0, step: 0.1, default: '0.7', category: 'Rollout' },
  ],

  'mcts-v1-greedy-tree': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
  ],

  'mcts-v1-depth-limited': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
    { key: 'maxRolloutDepth', description: 'Maximum rollout depth in turns', type: 'number', min: 1, max: 50, step: 1, default: '10', category: 'Depth' },
  ],

  'mcts-v1-adaptive': [
    ...STANDARD_PARAMS,
    ...MCTS_EXTRA,
  ],

  'flat-mc': [
    ...STANDARD_PARAMS,
  ],

  'heuristic-ev': [
    // HeuristicEv has no configurable params — weights are hardcoded.
    // iterations/timeBudget are irrelevant but included for consistency.
    ...STANDARD_PARAMS,
  ],

  'expectimax': [
    ...STANDARD_PARAMS,
    { key: 'maxDepthRounds', description: 'Search depth in full rounds', type: 'number', min: 1, max: 4, step: 1, default: '2', category: 'Search' },
    { key: 'leafEval', description: 'Leaf node evaluation function', type: 'select', default: 'winprob', options: ['winprob', 'composite'], category: 'Search' },
  ],

  'creator': [
    ...STANDARD_PARAMS,
    // Rollout policy
    { key: 'rolloutPolicy', description: 'Rollout policy for FlatMC simulations', type: 'select', default: 'creator', options: ['creator', 'greedy', 'uniform', 'boltzmann'], category: 'Rollout' },
    { key: 'rolloutTemperature', description: 'Boltzmann temperature (only for boltzmann rollout)', type: 'number', min: 0.01, max: 10.0, step: 0.1, default: '0.7', category: 'Rollout' },
    // Base weights — ranges from SweepMain.PARAMS
    { key: 'wIncome', description: 'Income dimension weight', type: 'number', min: 0.0, max: 8.0, step: 0.1, default: '2.5', category: 'Base weights' },
    { key: 'wRisk', description: 'Risk/variance dimension weight', type: 'number', min: 0.0, max: 6.0, step: 0.1, default: '2.0', category: 'Base weights' },
    { key: 'wCoverage', description: 'Dice coverage dimension weight', type: 'number', min: 0.0, max: 6.0, step: 0.1, default: '1.5', category: 'Base weights' },
    { key: 'wTempo', description: 'Tempo/speed dimension weight', type: 'number', min: 0.0, max: 6.0, step: 0.1, default: '2.0', category: 'Base weights' },
    { key: 'wWinProb', description: 'Win probability dimension weight', type: 'number', min: 0.0, max: 10.0, step: 0.1, default: '3.0', category: 'Base weights' },
    { key: 'wLandmark', description: 'Landmark progression weight', type: 'number', min: 0.0, max: 8.0, step: 0.1, default: '2.0', category: 'Base weights' },
    { key: 'wUrgency', description: 'Urgency (react to threats) weight', type: 'number', min: 0.0, max: 6.0, step: 0.1, default: '1.0', category: 'Base weights' },
    { key: 'wRoi', description: 'Return on investment weight', type: 'number', min: 0.0, max: 6.0, step: 0.1, default: '1.5', category: 'Base weights' },
    // Situation assessment
    { key: 'sitLandmark', description: 'Situation: landmark proximity factor', type: 'number', min: 0.0, max: 1.0, step: 0.05, default: '0.30', category: 'Situation' },
    { key: 'sitIncome', description: 'Situation: income sufficiency factor', type: 'number', min: 0.0, max: 1.0, step: 0.05, default: '0.30', category: 'Situation' },
    { key: 'sitCoins', description: 'Situation: coin reserves factor', type: 'number', min: 0.0, max: 1.0, step: 0.05, default: '0.15', category: 'Situation' },
    { key: 'sitTempo', description: 'Situation: tempo/speed factor', type: 'number', min: 0.0, max: 1.0, step: 0.05, default: '0.25', category: 'Situation' },
    // Targets
    { key: 'targetEvPerRound', description: 'Target EV per round (income goal)', type: 'number', min: 1.0, max: 15.0, step: 0.5, default: '4.0', category: 'Thresholds' },
    { key: 'maxETW', description: 'Max estimated turns to win', type: 'number', min: 10.0, max: 100.0, step: 5, default: '50.0', category: 'Thresholds' },
    // Sigmoid & gravity wells
    { key: 'sigmoidK', description: 'Sigmoid steepness for score normalization', type: 'number', min: 0.5, max: 20.0, step: 0.5, default: '6.0', category: 'Sigmoid & gravity' },
    { key: 'sprintHorizon', description: 'Sprint gravity well horizon (turns)', type: 'number', min: 2.0, max: 25.0, step: 0.5, default: '6.0', category: 'Sigmoid & gravity' },
    { key: 'sprintSharpness', description: 'Sprint gravity well sharpness', type: 'number', min: 0.1, max: 5.0, step: 0.1, default: '1.0', category: 'Sigmoid & gravity' },
    { key: 'threatHorizon', description: 'Threat gravity well horizon (turns)', type: 'number', min: 2.0, max: 25.0, step: 0.5, default: '8.0', category: 'Sigmoid & gravity' },
    { key: 'threatSharpness', description: 'Threat gravity well sharpness', type: 'number', min: 0.1, max: 5.0, step: 0.1, default: '1.0', category: 'Sigmoid & gravity' },
    // Bürohaus
    { key: 'wBurohausSwap', description: 'Bürohaus swap bait bonus weight', type: 'number', min: 0.0, max: 8.0, step: 0.1, default: '1.5', category: 'Bürohaus' },
  ],
};

/** All known engine class IDs in display order. */
export const ENGINE_CLASS_IDS = Object.keys(ENGINE_PARAMS);

/** Group params by category for display. Returns [categoryName, params[]] pairs. */
export function groupByCategory(params: ParamDef[]): [string, ParamDef[]][] {
  const map = new Map<string, ParamDef[]>();
  for (const p of params) {
    const cat = p.category ?? 'Other';
    const list = map.get(cat);
    if (list) list.push(p);
    else map.set(cat, [p]);
  }
  return Array.from(map.entries());
}
