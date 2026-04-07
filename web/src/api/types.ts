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
  engineSnapshots?: (EngineSnapshotJson | null)[];
}

// ─── Engine Snapshot (stored per turn for post-game review) ─────────

export interface EngineSnapshotOption {
  projectId: string;
  score: number;
  affordable: boolean;
  summarySentence?: string;
}

export interface EngineSnapshotJson {
  engineId: string;
  iterationsUsed: number;
  computeTimeMs: number;
  options: EngineSnapshotOption[];
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
  description: string;
  tier: string;
  iterations: number;
  timeBudgetMs: number;
  isDefault: boolean;
  extra: Record<string, string>;
  config: {
    iterations: number;
    timeBudgetMs: number;
    riskToleranceWeight: number;
    extra?: Record<string, string>;
  };
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
  engineSnapshot?: EngineSnapshotJson;
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

// ─── H2H (Head-to-Head) ────────────────────────────────────────────────

export interface EngineRating {
  rating: number;
  rd: number;
  volatility: number;
  matchCount: number;
}

export interface RatingsResponse {
  ratings: Record<string, EngineRating>;
}

export interface H2hStartRequest {
  engineA: string;
  engineB: string;
  games: number;
  iterations?: number;
  maxTurns?: number;
  seatSwap?: boolean;
  configA?: Record<string, string>;
  configB?: Record<string, string>;
}

export interface H2hStartResponse {
  matchId: string;
  status: string;
  gameCount: number;
}

export interface H2hStatusResponse {
  matchId: string;
  gamesCompleted: number;
  gameCount: number;
  completed: boolean;
  error?: string;
  resultId?: string;
}

export interface H2hDecisionOption {
  cardId: string;
  score: number;
  chosen: boolean;
}

export interface H2hDecisionDetail {
  options: H2hDecisionOption[];
  iterations: number;
  confidence: number;
}

export interface H2hTurnLog {
  playerIndex: number;
  diceCount: number;
  roll: number;
  isDoubles: boolean;
  coinDeltas: number[];
  purchasedCardId: string | null;
  purchaseWinRate: number;
  coinsAfterPurchase: number;
  bürohausSwap: string | null;
  bürohausActivated?: boolean;
  funkturmRerolled: boolean;
  evaluateTimeMs: number;
  decisionDetail?: H2hDecisionDetail | null;
}

export interface H2hGameLog {
  gameIndex: number;
  winnerIndex: number;
  totalTurns: number;
  timeoutWin: boolean;
  turns: H2hTurnLog[];
  finalCoins: number[];
  landmarkCounts: number[];
}

export interface H2hMatchSummary {
  id: string;
  date: string;
  totalTimeMs: number;
  avgGameLength: number;
  avgEvalTimeMs: number;
  avgEvalTimeMsPerEngine?: number[];
  shortestGameIndex?: number;
  longestGameIndex?: number;
  shortestGameTurns?: number;
  longestGameTurns?: number;
  gameCount: number;
  engines: string[];
  wins: number[];
  winRates: number[];
  ratingDelta?: number[];
}

export interface H2hMatchResult extends H2hMatchSummary {
  gameLogs: H2hGameLog[];
  config: {
    engineIds: string[];
    gameCount: number;
    maxTurnsPerGame: number;
    iterationsPerEval: number;
    seatSwap?: boolean;
  };
}

export interface H2hImportResponse {
  imported: number;
  skipped: number;
}
