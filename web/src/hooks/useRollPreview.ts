/** Roll preview hook — reads from cached perRollDeltas for instant dice switching. */

import { useMemo } from 'react';
import type { EvaluateResponse } from '../api/types';

export interface RollPreview {
  coinDeltas: number[] | null;
  rollTotal: number;
}

/**
 * Given the cached evaluate response and a selected dice total,
 * returns the coin deltas for that roll — zero network latency.
 */
export function useRollPreview(
  evalResult: EvaluateResponse | null,
  rollTotal: number,
  diceCount: 1 | 2,
): RollPreview {
  return useMemo(() => {
    if (!evalResult?.perRollDeltas) {
      return { coinDeltas: null, rollTotal };
    }
    // For 1d6, key is just the number; for 2d6, key is "2d6_<total>"
    const key = diceCount === 1 ? String(rollTotal) : `2d6_${rollTotal}`;
    const deltas = evalResult.perRollDeltas[key] ?? null;
    return { coinDeltas: deltas, rollTotal };
  }, [evalResult, rollTotal, diceCount]);
}
