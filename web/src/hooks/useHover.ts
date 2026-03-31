/** Shared hover state for CoinFlow Buy preview. */

import { useState, useCallback, useMemo } from 'react';

export interface HoverCard {
  projectId: string;
  cost: number;
}

export interface UseHoverReturn {
  hovered: HoverCard | null;
  onHover: (card: HoverCard | null) => void;
}

export function useHover(): UseHoverReturn {
  const [hovered, setHovered] = useState<HoverCard | null>(null);

  const onHover = useCallback((card: HoverCard | null) => {
    setHovered(card);
  }, []);

  return useMemo(() => ({ hovered, onHover }), [hovered, onHover]);
}
