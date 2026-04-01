/** Fetches session insights during opponent turns, caching until player changes. */

import { useState, useEffect, useRef } from 'react';
import type { InsightsResponse } from '../api/types';
import { getInsights } from '../api/client';

export function useInsights(playerIndex: number, isUserTurn: boolean) {
  const [data, setData] = useState<InsightsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const lastPlayerRef = useRef<number>(-1);

  useEffect(() => {
    // Only fetch on opponent turns; invalidate cache when player changes
    if (isUserTurn) return;
    if (lastPlayerRef.current === playerIndex && data != null) return;

    lastPlayerRef.current = playerIndex;
    setLoading(true);

    getInsights(playerIndex)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [playerIndex, isUserTurn]);

  return { data, loading };
}
