/** Typed fetch wrappers for all backend API endpoints. */

import type {
  SessionJson,
  CreateSessionRequest,
  ApplyTurnRequest,
  BürohausRequest,
  EvaluateRequest,
  EvaluateResponse,
  FromSnapshotRequest,
  InsightsResponse,
  ProjectDef,
  EngineRegistryEntry,
  EngineParamSchema,
  SaveEntry,
  H2hStartRequest,
  H2hStartResponse,
  H2hStatusResponse,
  H2hMatchSummary,
  H2hMatchResult,
  H2hGameLog,
  RatingsResponse,
  H2hImportResponse,
  SweepRun,
} from './types';

const BASE = '';  // same-origin; Vite proxy handles /api → :8080

class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, init);
  const body = await res.json();
  if (!res.ok) throw new ApiError(res.status, body.error ?? res.statusText);
  return body as T;
}

function post<T>(url: string, body: unknown): Promise<T> {
  return json<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

// ─── Health ──────────────────────────────────────────────────────────────

export const health = () => json<{ status: string }>('/api/health');

// ─── Projects ────────────────────────────────────────────────────────────

export const getProjects = () => json<ProjectDef[]>('/api/projects');

// ─── Engines ─────────────────────────────────────────────────────────────

export const getEngines = () => json<EngineRegistryEntry[]>('/api/engines');

export const getEngineParams = () => json<EngineParamSchema>('/api/engine-params');

// ─── Evaluate ────────────────────────────────────────────────────────────

export const evaluate = (req: EvaluateRequest) =>
  post<EvaluateResponse>('/api/evaluate', req);

export const precompute = (req: EvaluateRequest) =>
  post<{ status: string }>('/api/evaluate/precompute', req);

// ─── Session Management ──────────────────────────────────────────────────

export const createSession = (req: CreateSessionRequest) =>
  post<SessionJson>('/api/session/create', req);

export const getSessionState = () =>
  json<SessionJson>('/api/session/state');

export const applyTurn = (req: ApplyTurnRequest) =>
  post<SessionJson>('/api/session/turn', req);

export const applyBürohaus = (req: BürohausRequest) =>
  post<SessionJson>('/api/session/burohaus', req);

export const undoTurn = () =>
  post<SessionJson>('/api/session/undo', {});

export const saveSession = (filename?: string) =>
  post<{ path: string }>('/api/session/save', { filename });

export const loadSession = (filename: string) =>
  post<SessionJson>('/api/session/load', { filename });

export const listSaves = () =>
  json<SaveEntry[]>('/api/session/saves');

export const fromSnapshot = (req: FromSnapshotRequest) =>
  post<SessionJson>('/api/session/from-snapshot', req);

export const getInsights = (playerIndex?: number) =>
  json<InsightsResponse>(`/api/session/insights${playerIndex != null ? `?playerIndex=${playerIndex}` : ''}`);

// ─── Roll Preview (stateless) ────────────────────────────────────────────

export const previewRoll = (state: unknown, playerIndex: number, roll: number) =>
  post<{ coinDeltas: number[]; stateAfter: unknown }>('/api/roll', {
    state,
    playerIndex,
    roll,
  });

// ─── H2H (Head-to-Head) ──────────────────────────────────────────────

export const h2hStart = (req: H2hStartRequest) =>
  post<H2hStartResponse>('/api/h2h/start', req);

export const h2hStatus = (matchId: string) =>
  json<H2hStatusResponse>(`/api/h2h/status/${matchId}`);

export const h2hCancel = (matchId: string) =>
  post<{ matchId: string; status: string; gamesCompleted: number }>(`/api/h2h/cancel/${matchId}`, {});

export const h2hResults = () =>
  json<H2hMatchSummary[]>('/api/h2h/results');

export const h2hResult = (matchId: string) =>
  json<H2hMatchResult>(`/api/h2h/results/${matchId}`);

export const h2hGameLog = (matchId: string, gameIndex: number) =>
  json<H2hGameLog>(`/api/h2h/results/${matchId}/game/${gameIndex}`);

export const h2hRatings = () =>
  json<RatingsResponse>('/api/h2h/ratings');

export async function h2hExport(): Promise<void> {
  const res = await fetch(BASE + '/api/h2h/export');
  if (!res.ok) {
    const body = await res.json();
    throw new ApiError(res.status, body.error ?? res.statusText);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'h2h-summaries.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function h2hImport(fileContent: string): Promise<H2hImportResponse> {
  return json<H2hImportResponse>('/api/h2h/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: fileContent,
  });
}

// ─── H2H Auto Battle ────────────────────────────────────────────────

export interface AutoBattleStartRequest {
  gamesPerMatch?: number;
  maxTurns?: number;
  maxRounds?: number;
  tier?: string;
  timeBudgetMs?: number;
  computeLuck?: boolean;
  luckMcSims?: number;
  luckUseMc?: boolean;
  computeCardIncome?: boolean;
}

export interface AutoBattleStatusResponse {
  running: boolean;
  roundsCompleted?: number;
  maxRounds?: number;
  endless?: boolean;
  gamesPerMatch?: number;
  gamesCompletedInMatch?: number;
  totalGamesPlayed?: number;
  elapsedMs?: number;
  currentMatchup?: string;
  error?: string;
}

export const h2hAutoStart = (req: AutoBattleStartRequest) =>
  post<{ status: string; maxRounds: number }>('/api/h2h/auto/start', req);

export const h2hAutoStop = () =>
  post<{ status: string }>('/api/h2h/auto/stop', {});

export const h2hAutoStatus = () =>
  json<AutoBattleStatusResponse>('/api/h2h/auto/status');

// ─── Custom Engines ──────────────────────────────────────────────

export const saveCustomEngine = (entry: {
  id: string;
  engineClass: string;
  description: string;
  tier: string;
  config: Record<string, string>;
}) => post<EngineRegistryEntry>('/api/engines/custom', entry);

export async function deleteCustomEngine(id: string): Promise<{ status: string; id: string }> {
  return json<{ status: string; id: string }>(`/api/engines/custom/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

// ─── Sweep Results ────────────────────────────────────────────────

export const sweepResults = () =>
  json<SweepRun[]>('/api/h2h/sweep/results');
