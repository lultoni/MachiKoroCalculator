import { useState, useCallback, useRef, useEffect } from 'react';
import * as api from '../api/client';
import type { H2hMatchSummary, H2hMatchResult, H2hGameLog, H2hImportResponse } from '../api/types';

export interface H2hState {
  results: H2hMatchSummary[];
  activeMatchId: string | null;
  progress: { completed: number; total: number } | null;
  cancelling: boolean;
  selectedResult: H2hMatchResult | null;
  selectedGame: H2hGameLog | null;
  loading: boolean;
  error: string | null;
}

export function useH2h() {
  const [state, setState] = useState<H2hState>({
    results: [],
    activeMatchId: null,
    progress: null,
    cancelling: false,
    selectedResult: null,
    selectedGame: null,
    loading: false,
    error: null,
  });
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadResults = useCallback(async () => {
    try {
      const results = await api.h2hResults();
      setState(s => ({ ...s, results, error: null }));
    } catch (e: unknown) {
      setState(s => ({ ...s, error: (e as Error).message }));
    }
  }, []);

  const startMatch = useCallback(async (
    engineA: string, engineB: string, games: number,
    configA?: Record<string, string>, configB?: Record<string, string>,
    maxTurns?: number, seatSwap?: boolean,
  ) => {
    setState(s => ({ ...s, loading: true, error: null }));
    try {
      const res = await api.h2hStart({ engineA, engineB, games, configA, configB, maxTurns, seatSwap });
      setState(s => ({
        ...s,
        activeMatchId: res.matchId,
        progress: { completed: 0, total: res.gameCount },
        loading: false,
      }));
      // Start polling
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(async () => {
        try {
          const status = await api.h2hStatus(res.matchId);
          setState(s => ({
            ...s,
            progress: { completed: status.gamesCompleted, total: status.gameCount },
          }));
          if (status.completed) {
            if (pollRef.current) clearInterval(pollRef.current);
            pollRef.current = null;
            setState(s => ({ ...s, activeMatchId: null, progress: null, cancelling: false }));
            await loadResults();
          }
          if (status.error) {
            if (pollRef.current) clearInterval(pollRef.current);
            pollRef.current = null;
            setState(s => ({
              ...s, activeMatchId: null, progress: null,
              error: status.error ?? 'Match failed',
            }));
          }
        } catch { /* ignore poll errors */ }
      }, 1000);
    } catch (e: unknown) {
      setState(s => ({ ...s, loading: false, error: (e as Error).message }));
    }
  }, [loadResults]);

  const selectResult = useCallback(async (matchId: string) => {
    setState(s => ({ ...s, loading: true, error: null, selectedGame: null }));
    try {
      const result = await api.h2hResult(matchId);
      setState(s => ({ ...s, selectedResult: result, loading: false }));
    } catch (e: unknown) {
      setState(s => ({ ...s, loading: false, error: (e as Error).message }));
    }
  }, []);

  const selectGame = useCallback(async (matchId: string, gameIndex: number) => {
    try {
      const game = await api.h2hGameLog(matchId, gameIndex);
      setState(s => ({ ...s, selectedGame: game }));
    } catch (e: unknown) {
      setState(s => ({ ...s, error: (e as Error).message }));
    }
  }, []);

  const clearSelection = useCallback(() => {
    setState(s => ({ ...s, selectedResult: null, selectedGame: null }));
  }, []);

  const clearGame = useCallback(() => {
    setState(s => ({ ...s, selectedGame: null }));
  }, []);

  const importResults = useCallback(async (fileContent: string): Promise<H2hImportResponse | null> => {
    setState(s => ({ ...s, loading: true, error: null }));
    try {
      const result = await api.h2hImport(fileContent);
      await loadResults();
      setState(s => ({ ...s, loading: false }));
      return result;
    } catch (e: unknown) {
      setState(s => ({ ...s, loading: false, error: (e as Error).message }));
      return null;
    }
  }, [loadResults]);

  const cancelMatch = useCallback(async () => {
    const matchId = state.activeMatchId;
    if (!matchId) return;
    setState(s => ({ ...s, cancelling: true }));
    try {
      await api.h2hCancel(matchId);
      // Polling continues — it will pick up the completed+cancelled status
    } catch (e: unknown) {
      setState(s => ({ ...s, error: (e as Error).message, cancelling: false }));
    }
  }, [state.activeMatchId]);

  // Cleanup polling on unmount
  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  return {
    ...state,
    loadResults,
    startMatch,
    cancelMatch,
    selectResult,
    selectGame,
    clearSelection,
    clearGame,
    importResults,
  };
}
