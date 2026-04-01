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
  SaveEntry,
  H2hStartRequest,
  H2hStartResponse,
  H2hStatusResponse,
  H2hMatchSummary,
  H2hMatchResult,
  H2hGameLog,
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

export const h2hResults = () =>
  json<H2hMatchSummary[]>('/api/h2h/results');

export const h2hResult = (matchId: string) =>
  json<H2hMatchResult>(`/api/h2h/results/${matchId}`);

export const h2hGameLog = (matchId: string, gameIndex: number) =>
  json<H2hGameLog>(`/api/h2h/results/${matchId}/game/${gameIndex}`);
