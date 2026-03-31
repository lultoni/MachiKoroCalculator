/** Session state hook — wraps all session API calls and holds current state. */

import { useState, useCallback } from 'react';
import type { SessionJson, CreateSessionRequest, ApplyTurnRequest, BürohausRequest, FromSnapshotRequest } from '../api/types';
import * as api from '../api/client';

export interface UseSessionReturn {
  session: SessionJson | null;
  loading: boolean;
  error: string | null;
  create: (req: CreateSessionRequest) => Promise<void>;
  refresh: () => Promise<void>;
  applyTurn: (req: ApplyTurnRequest) => Promise<void>;
  applyBürohaus: (req: BürohausRequest) => Promise<void>;
  undo: () => Promise<void>;
  save: (filename?: string) => Promise<string>;
  load: (filename: string) => Promise<void>;
  fromSnapshot: (req: FromSnapshotRequest) => Promise<void>;
  clearSession: () => void;
}

export function useSession(): UseSessionReturn {
  const [session, setSession] = useState<SessionJson | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const wrap = useCallback(async <T>(fn: () => Promise<T>): Promise<T> => {
    setLoading(true);
    setError(null);
    try {
      const result = await fn();
      return result;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  const create = useCallback(async (req: CreateSessionRequest) => {
    const s = await wrap(() => api.createSession(req));
    setSession(s);
  }, [wrap]);

  const refresh = useCallback(async () => {
    const s = await wrap(() => api.getSessionState());
    setSession(s);
  }, [wrap]);

  const applyTurnAction = useCallback(async (req: ApplyTurnRequest) => {
    const s = await wrap(() => api.applyTurn(req));
    setSession(s);
  }, [wrap]);

  const applyBürohaus = useCallback(async (req: BürohausRequest) => {
    const s = await wrap(() => api.applyBürohaus(req));
    setSession(s);
  }, [wrap]);

  const undo = useCallback(async () => {
    const s = await wrap(() => api.undoTurn());
    setSession(s);
  }, [wrap]);

  const save = useCallback(async (filename?: string): Promise<string> => {
    const result = await wrap(() => api.saveSession(filename));
    return result.path;
  }, [wrap]);

  const load = useCallback(async (filename: string) => {
    const s = await wrap(() => api.loadSession(filename));
    setSession(s);
  }, [wrap]);

  const fromSnapshot = useCallback(async (req: FromSnapshotRequest) => {
    const s = await wrap(() => api.fromSnapshot(req));
    setSession(s);
  }, [wrap]);

  const clearSession = useCallback(() => {
    setSession(null);
    setError(null);
  }, []);

  return {
    session, loading, error,
    create, refresh,
    applyTurn: applyTurnAction,
    applyBürohaus, undo,
    save, load, fromSnapshot,
    clearSession,
  };
}
