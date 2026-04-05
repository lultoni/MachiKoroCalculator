/** Engine evaluation hook — calls evaluate once, caches perRoll results. */

import { useState, useCallback, useRef } from 'react';
import type { EvaluateResponse, EvaluateRequest, GameStateJson } from '../api/types';
import * as api from '../api/client';

export interface UseEngineReturn {
  result: EvaluateResponse | null;
  loading: boolean;
  error: string | null;
  evaluate: (state: GameStateJson, playerIndex: number, engineId?: string, preRollState?: GameStateJson) => Promise<void>;
  precompute: (state: GameStateJson, playerIndex: number, engineId?: string) => void;
  clear: () => void;
}

export function useEngine(): UseEngineReturn {
  const [result, setResult] = useState<EvaluateResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const evaluate = useCallback(async (
    state: GameStateJson,
    playerIndex: number,
    engineId?: string,
    preRollState?: GameStateJson,
  ) => {
    // Cancel any in-flight request
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    setLoading(true);
    setError(null);
    // Clear engine-specific rankings but keep perRollDeltas for manual buy
    setResult(prev => prev ? { ...prev, rankedOptions: [], metricRanges: undefined } : null);
    try {
      const req: EvaluateRequest = { state, playerIndex, engineId, preRollState };
      const res = await api.evaluate(req);
      if (!ctrl.signal.aborted) {
        setResult(res);
      }
    } catch (e) {
      if (!ctrl.signal.aborted) {
        const msg = e instanceof Error ? e.message : String(e);
        setError(msg);
      }
    } finally {
      if (!ctrl.signal.aborted) {
        setLoading(false);
      }
    }
  }, []);

  const clear = useCallback(() => {
    abortRef.current?.abort();
    setResult(null);
    setError(null);
    setLoading(false);
  }, []);

  const precompute = useCallback((
    state: GameStateJson,
    playerIndex: number,
    engineId?: string,
  ) => {
    // Fire and forget — no state updates needed
    api.precompute({ state, playerIndex, engineId }).catch(() => {});
  }, []);

  return { result, loading, error, evaluate, precompute, clear };
}
