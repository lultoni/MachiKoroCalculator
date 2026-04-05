/** Fetches session insights during opponent turns, refreshing each turn. */

import { useState, useEffect } from 'react';
import type { InsightsResponse } from '../api/types';
import { getInsights } from '../api/client';

export function useInsights(playerIndex: number, isUserTurn: boolean, turnCount?: number) {
  const [data, setData] = useState<InsightsResponse | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // Only fetch on opponent turns
    if (isUserTurn) return;

    setLoading(true);

    getInsights(playerIndex)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [playerIndex, isUserTurn, turnCount]);

  return { data, loading };
}
