/** TypeScript interfaces matching the Java backend JSON contracts. */

// ─── Game State ──────────────────────────────────────────────────────────

export interface PlayerState {
  name: string;
  coins: number;
  ownedIds: string[];
}

export interface GameStateJson {
  players: PlayerState[];
}

// ─── Turn Record ─────────────────────────────────────────────────────────

export interface TurnRecordJson {
  playerIndex: number;
  roll: number;
  boughtId: string | null;
  isDoubles: boolean;
  diceCount: number;
  coinDeltas: number[] | null;
  swappedAway: string | null;
  swappedIn: string | null;
  swapOppPlayerIndex: number;
}

// ─── Session ─────────────────────────────────────────────────────────────

export interface SessionJson {
  state: GameStateJson;
  nextPlayerIndex: number;
  effectiveTurnCount: number;
  bonusTurnPending: boolean;
  finished: boolean;
  winnerIndex: number;
  history: TurnRecordJson[];
}

// ─── Project (card definition) ───────────────────────────────────────────

export interface ProjectDef {
  id: string;
  name_de: string;
  name_en: string;
  color: string;
  cost: number;
  dice_activation: number[];
  income_base: number;
  is_grossprojekt: boolean;
  category?: string;
  description_de?: string;
  description_en?: string;
}

// ─── Engine ──────────────────────────────────────────────────────────────

export interface EngineRegistryEntry {
  id: string;
  engineClass: string;
  iterations: number;
  timeBudgetMs: number;
  isDefault: boolean;
  extra: Record<string, string>;
}

export interface ExplanationFactor {
  category: string;
  weight: number;
  summary: string;
  detail: string;
}

export interface RankedOption {
  projectId: string;
  score: number;
  affordable: boolean;
  explanationFactors: string[];
  structuredFactors?: ExplanationFactor[];
  summarySentence?: string;
  metrics: Record<string, string> | null;
}

export interface MetricRange {
  min: string;
  max: string;
}

export interface EvaluateResponse {
  engineId: string;
  iterationsUsed: number;
  computeTimeMs: number;
  confidence: number;
  debugInfo?: string;
  cached?: boolean;
  rankedOptions: RankedOption[];
  perRollDeltas?: Record<string, number[]>;
  metricRanges?: Record<string, MetricRange>;
}

// ─── Insights ────────────────────────────────────────────────────────────

export interface PlayerInsight {
  name: string;
  etw: number;
  evPerRound: number;
  variance: number;
  landmarksOwned: number;
}

export interface NarrativeInsight {
  type: string;
  text: string;
}

export interface InsightsResponse {
  playerInsights: PlayerInsight[];
  tempoAdvantage: number;
  portfolioEV: number;
  supplyWarnings: { cardId: string; remaining: number }[];
  narrative?: NarrativeInsight[];
}

// ─── Saves ───────────────────────────────────────────────────────────────

export interface SaveEntry {
  filename: string;
  lastModified: string;
}

// ─── Request Bodies ──────────────────────────────────────────────────────

export interface CreateSessionRequest {
  playerCount: number;
  playerNames?: string[];
}

export interface ApplyTurnRequest {
  roll: number;
  boughtId: string | null;
  isDoubles: boolean;
  diceCount: number;
}

export interface BürohausRequest {
  decline?: boolean;
  ownCardId?: string;
  oppPlayerIndex?: number;
  oppCardId?: string;
}

export interface EvaluateRequest {
  state: GameStateJson;
  playerIndex: number;
  engineId?: string;
  preRollState?: GameStateJson;
}

export interface FromSnapshotRequest {
  players: PlayerState[];
}
